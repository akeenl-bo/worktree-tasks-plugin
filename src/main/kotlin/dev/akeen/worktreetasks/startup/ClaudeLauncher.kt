package dev.akeen.worktreetasks.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.streams.asSequence

/** How to start `claude` for a task. */
enum class LaunchMode {
    /** Brand-new task: `claude -n "<name>" [prompt]`. */
    NEW,

    /** Reopened existing task: `claude --continue`. */
    CONTINUE,
}

/**
 * Opens an integrated terminal tab in the current window, scoped to a worktree, and runs `claude`.
 * Running `claude` in the IDE terminal is what causes the official Claude Code plugin to
 * auto-activate — we never call that plugin directly. Tasks open as tabs in the current window;
 * no new IDE windows are created.
 *
 * Active-only: switching to another task closes the previous Claude terminal before opening the new
 * one, so terminals don't pile up.
 */
@Service(Service.Level.PROJECT)
class ClaudeLauncher(private val project: Project) {

    private var current: TerminalWidget? = null

    fun launch(
        worktreePath: Path,
        taskName: String,
        mode: LaunchMode,
        initialPrompt: String? = null,
    ) {
        val settings = WorktreeTasksSettings.getInstance()
        // --continue fails when no prior conversation exists for this dir, so fall back to a fresh
        // named session in that case.
        val effectiveMode = if (mode == LaunchMode.CONTINUE && !hasClaudeSession(worktreePath)) {
            LaunchMode.NEW
        } else {
            mode
        }
        val command = buildCommand(settings.claudePath, taskName, effectiveMode, initialPrompt)
        ApplicationManager.getApplication().invokeLater {
            try {
                val terminalManager = TerminalToolWindowManager.getInstance(project)
                // Close the previous Claude terminal so switching tasks doesn't stack tabs.
                current?.let { prev ->
                    try {
                        terminalManager.getContainer(prev)?.closeAndHide()
                    } catch (t: Throwable) {
                        LOG.debug("Previous claude terminal already closed", t)
                    }
                }
                current = null

                val widget = terminalManager.createShellWidget(
                    worktreePath.toString(),
                    "claude: $taskName",
                    /* requestFocus = */ true,
                    /* deferSessionStartUntilUiShown = */ false,
                )
                widget.sendCommandToExecute(command)
                current = widget
            } catch (t: Throwable) {
                LOG.warn("Failed to launch claude in terminal for $worktreePath", t)
            }
        }
    }

    private fun buildCommand(
        claudePath: String,
        taskName: String,
        mode: LaunchMode,
        initialPrompt: String?,
    ): String = when (mode) {
        LaunchMode.CONTINUE -> "$claudePath --continue"
        LaunchMode.NEW -> buildString {
            append(claudePath)
            append(" -n ").append(shellQuote(taskName))
            initialPrompt?.takeIf { it.isNotBlank() }?.let { append(' ').append(shellQuote(it)) }
        }
    }

    /** POSIX single-quote escaping (target shells are bash/zsh on macOS/Linux). */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * True if Claude Code has a stored session for [worktreePath]. Claude keys sessions by the
     * absolute working directory with path separators replaced by dashes, under
     * `~/.claude/projects/<dir>/<session-id>.jsonl`.
     */
    private fun hasClaudeSession(worktreePath: Path): Boolean = try {
        val abs = worktreePath.toAbsolutePath().normalize().toString()
        val projectDirName = abs.replace('/', '-')
        val projectDir = Path.of(System.getProperty("user.home"), ".claude", "projects", projectDirName)
        Files.isDirectory(projectDir) &&
            Files.list(projectDir).use { stream ->
                stream.asSequence().any { it.extension == "jsonl" }
            }
    } catch (t: Throwable) {
        LOG.warn("Failed to check for existing claude session at $worktreePath", t)
        false // safest: treat as no session, so we start fresh instead of failing on --continue
    }

    companion object {
        private val LOG = Logger.getInstance(ClaudeLauncher::class.java)

        fun getInstance(project: Project): ClaudeLauncher =
            project.getService(ClaudeLauncher::class.java)
    }
}
