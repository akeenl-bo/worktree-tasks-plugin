package dev.akeen.worktreetasks.service

import dev.akeen.worktreetasks.git.WorktreeGit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ParentRebaserTest {

    private lateinit var repo: Path
    private val ideRunner = WorktreeGit.runner

    @Before
    fun setUp() {
        WorktreeGit.runner = WorktreeGit.Runner { dir, args -> exec(dir, args) }
        repo = Files.createTempDirectory("parent-rebaser")
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        commit("base.txt", "base")
    }

    @After
    fun tearDown() {
        WorktreeGit.runner = ideRunner
        repo.toFile().deleteRecursively()
    }

    @Test
    fun `rebases automatically only when behind, clean, and unpushed`() {
        assertEquals(RebaseDecision.UP_TO_DATE, ParentRebaser.decide(0, dirty = true, published = true))
        assertEquals(RebaseDecision.AUTO, ParentRebaser.decide(3, dirty = false, published = false))
        assertEquals(RebaseDecision.ASK_DIRTY, ParentRebaser.decide(3, dirty = true, published = false))
        assertEquals(RebaseDecision.ASK_PUBLISHED, ParentRebaser.decide(3, dirty = false, published = true))
    }

    @Test
    fun `moves only the task's own commits after its parent is amended`() {
        git("checkout", "-q", "-b", "parent")
        commit("parent.txt", "v1")
        val forkPoint = git("rev-parse", "HEAD")
        git("checkout", "-q", "-b", "child")
        commit("child.txt", "child")
        TaskParents.set(repo, "child", TaskParent("parent", forkPoint))

        git("checkout", "-q", "parent")
        Files.writeString(repo.resolve("parent.txt"), "v2")
        Files.createDirectories(repo.resolve("db/migrate"))
        Files.writeString(repo.resolve("db/migrate/001_add_things.rb"), "")
        git("add", "-A")
        git("commit", "-q", "--amend", "-m", "parent amended")
        val amendedTip = git("rev-parse", "HEAD")
        git("checkout", "-q", "child")

        val outcome = ParentRebaser.rebase(repo, "child", TaskParents.get(repo, "child")!!)

        assertEquals(RebaseOutcome.Rebased("parent", listOf("db/migrate/001_add_things.rb")), outcome)
        assertEquals(amendedTip, git("rev-parse", "HEAD~1"))
        assertEquals("v2", Files.readString(repo.resolve("parent.txt")))
        assertEquals(TaskParent("parent", amendedTip), TaskParents.get(repo, "child"))
    }

    private fun commit(file: String, content: String) {
        Files.writeString(repo.resolve(file), content)
        git("add", file)
        git("commit", "-q", "-m", "add $file")
    }

    private fun git(vararg args: String): String {
        val result = exec(repo, args.toList())
        check(result.success) { "git ${args.joinToString(" ")} failed: ${result.output}" }
        return result.output
    }

    private fun exec(dir: Path, args: List<String>): WorktreeGit.CommandResult {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        return WorktreeGit.CommandResult(exitCode == 0, output, exitCode)
    }
}
