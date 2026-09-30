package dev.akeen.worktreetasks.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
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
 * Opens an integrated terminal tab in this project's window and runs `claude`. Running `claude` in
 * the IDE terminal is what makes the official Claude Code plugin auto-activate. Each worktree window
 * keeps its own agent alive (project-scoped service), so switching windows never kills another.
 */
@Service(Service.Level.PROJECT)
class ClaudeLauncher(private val project: Project) {

    private var current: TerminalWidget? = null

    /** Start (or restart) the Claude terminal for [worktreePath]. */
    fun launch(
        worktreePath: Path,
        taskName: String,
        mode: LaunchMode,
        initialPrompt: String? = null,
    ) {
        ApplicationManager.getApplication().invokeLater {
            doLaunch(worktreePath, taskName, mode, initialPrompt)
        }
    }

    /**
     * If this window's Claude terminal is still alive, focus it; otherwise launch a new one. Used
     * when switching back to an already-open worktree so the agent isn't killed and restarted.
     */
    fun focusOrLaunch(
        worktreePath: Path,
        taskName: String,
        mode: LaunchMode,
        initialPrompt: String? = null,
    ) {
        ApplicationManager.getApplication().invokeLater {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            val existing = current
            val alive = existing != null &&
                runCatching { terminalManager.getContainer(existing) }.getOrNull() != null
            if (alive) {
                ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.show()
                existing!!.requestFocus()
            } else {
                doLaunch(worktreePath, taskName, mode, initialPrompt)
            }
        }
    }

    /**
     * Type [text] into this window's live Claude session and submit it, then focus the terminal.
     * Returns false when no Claude terminal is running here. Call on the EDT.
     */
    fun sendPrompt(text: String): Boolean {
        val widget = current ?: return false
        val alive = runCatching { TerminalToolWindowManager.getInstance(project).getContainer(widget) }.getOrNull() != null
        if (!alive) return false
        widget.sendCommandToExecute(text)
        ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.show()
        widget.requestFocus()
        return true
    }

    private fun doLaunch(worktreePath: Path, taskName: String, mode: LaunchMode, initialPrompt: String?) {
        val settings = WorktreeTasksSettings.getInstance()
        // --continue fails when no prior conversation exists for this dir, so fall back to NEW.
        val effectiveMode = if (mode == LaunchMode.CONTINUE && !hasClaudeSession(worktreePath)) {
            LaunchMode.NEW
        } else {
            mode
        }
        val command = buildCommand(settings.claudePath, taskName, effectiveMode, initialPrompt)
        try {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            // Replace a previous (dead) Claude terminal we own, if any.
            current?.let { prev ->
                runCatching { terminalManager.getContainer(prev)?.closeAndHide() }
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
        false
    }

    companion object {
        private val LOG = Logger.getInstance(ClaudeLauncher::class.java)

        fun getInstance(project: Project): ClaudeLauncher =
            project.getService(ClaudeLauncher::class.java)
    }
}
