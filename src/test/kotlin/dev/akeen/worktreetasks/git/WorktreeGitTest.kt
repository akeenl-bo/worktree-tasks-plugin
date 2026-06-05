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
}
