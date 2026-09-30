package dev.akeen.worktreetasks.jira

import com.intellij.openapi.application.PathManager
import dev.akeen.worktreetasks.service.LoginShell
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Asks a headless Claude to order the Dashboard's recommendations. The session gets no tools at all
 * (no built-ins, no MCP servers, nothing approved), so it can only read the prompt and reply: it
 * can't touch Jira, the repo, or anything else. Blocks for up to a few minutes; run it off the EDT.
 */
object ClaudePicker {
    private const val TIMEOUT_MINUTES = 4L

    fun ask(prompt: String, offered: Set<String>): ClaudePicks {
        val configured = WorktreeTasksSettings.getInstance().claudePath.ifBlank { "claude" }
        val claude = if (configured.startsWith("/")) configured else LoginShell.which(configured) ?: configured
        // A scratch directory, so no project's CLAUDE.md or settings shape the answer.
        val dir = Files.createDirectories(Path.of(PathManager.getTempPath(), "worktree-tasks-dashboard"))
        val reply = dir.resolve("reply.json").toFile()
        val log = dir.resolve("reply.log").toFile()
        val command = listOf(
            claude, "-p", prompt,
            "--tools", "",
            "--strict-mcp-config",
            "--permission-mode", "dontAsk",
            "--no-session-persistence",
            "--output-format", "json",
        )
        val process = ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectOutput(reply)
            .redirectError(log)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .apply {
                environment().clear()
                environment().putAll(LoginShell.claudeEnvironment())
            }
            .start()
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw IllegalStateException("Claude didn't answer within $TIMEOUT_MINUTES minutes.")
        }
        val result = parseJson(reply.readText())?.takeIf { it.isJsonObject }?.asJsonObject
        if (process.exitValue() != 0 || result == null || result.get("is_error")?.asBoolean == true) {
            val why = result?.get("result")?.asString ?: log.readText().lines().firstOrNull { it.isNotBlank() }
            throw IllegalStateException("Claude failed: ${why ?: "exit ${process.exitValue()}"}")
        }
        return parseClaudePicks(result.get("result")?.asString.orEmpty(), offered)
            ?: throw IllegalStateException("Claude's reply wasn't the expected JSON.")
    }
}
