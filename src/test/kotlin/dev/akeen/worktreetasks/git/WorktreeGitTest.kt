package dev.akeen.worktreetasks.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeGitTest {

    @Test
    fun `parses main, branch, detached and bare worktrees`() {
        val output = """
            worktree /Users/me/dev/repo
            HEAD aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            branch refs/heads/master

            worktree /Users/me/dev/repo-worktrees/feature-x
            HEAD bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
            branch refs/heads/feature/x

            worktree /Users/me/dev/repo-worktrees/detached
            HEAD cccccccccccccccccccccccccccccccccccccccc
            detached

            worktree /Users/me/dev/repo-worktrees/locked-one
            HEAD dddddddddddddddddddddddddddddddddddddddd
            branch refs/heads/locked-branch
            locked some reason

            worktree /Users/me/dev/bare-repo
            bare
        """.trimIndent()

        val worktrees = WorktreeGit.parsePorcelain(output)

        assertEquals(5, worktrees.size)

        val main = worktrees[0]
        assertTrue("first entry is main", main.isMain)
        assertEquals("master", main.branch)
        assertFalse(main.isDetached)

        val feature = worktrees[1]
        assertEquals("feature/x", feature.branch)
        assertFalse(feature.isMain)

        val detached = worktrees[2]
        assertTrue(detached.isDetached)
        assertNull(detached.branch)

        val locked = worktrees[3]
        assertTrue(locked.isLocked)
        assertEquals("locked-branch", locked.branch)

        val bare = worktrees[4]
        assertTrue(bare.isBare)
    }

    @Test
    fun `returns empty list for empty output`() {
        assertTrue(WorktreeGit.parsePorcelain("").isEmpty())
        assertTrue(WorktreeGit.parsePorcelain("   \n  ").isEmpty())
    }

    @Test
    fun `builds branch refs flagging checked-out locals and dropping duplicate remotes`() {
        val refs = WorktreeGit.buildBranchRefs(
            checkedOut = setOf("master", "feature/x"),
            locals = listOf("master", "feature/x", "wip"),
            // remoteBranches() already strips */HEAD before this point.
            remotes = listOf("origin/feature/x", "origin/release", "upstream/main"),
        )

        // master, feature/x, wip (sorted) then remote-only release, main (sorted).
        assertEquals(
            listOf("feature/x", "master", "wip", "origin/release", "upstream/main"),
            refs.map { it.name },
        )

        val master = refs.first { it.name == "master" }
        assertTrue(master.isCheckedOut)
        assertFalse(master.isRemote)

        val wip = refs.first { it.name == "wip" }
        assertFalse(wip.isCheckedOut)

        // origin/feature/x is dropped because the local feature/x already covers it.
        assertNull(refs.firstOrNull { it.name == "origin/feature/x" })

        val release = refs.first { it.name == "origin/release" }
        assertTrue(release.isRemote)
        assertEquals("origin", release.remote)
        assertEquals("release", release.localName)
    }
}
