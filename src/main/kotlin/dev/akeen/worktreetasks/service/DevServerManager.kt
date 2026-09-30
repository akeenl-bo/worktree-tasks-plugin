package dev.akeen.worktreetasks.service

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs the configured dev-server stack for one worktree and tears it down on request.
 *
 * Project-scoped: each worktree window owns and controls its own server independently, so you can
 * start/stop the server for a specific worktree without touching the others (the same way each
 * worktree's Claude agent is independent). Each command runs as a managed process shown in this
 * window's Run tool window, launched through a login shell that `cd`s into the worktree so version
 * managers (rbenv/nvm via the command prefix) and inline env vars (e.g. `ASSETS_COMPILE=true`) work.
 */
@Service(Service.Level.PROJECT)
class DevServerManager(private val project: Project) : Disposable {

    private data class Running(val descriptor: RunContentDescriptor, val handler: ProcessHandler)

    private val running = mutableListOf<Running>()
    private var activePath: Path? = null

    fun activeTaskPath(): Path? = activePath

    fun isActive(path: Path): Boolean = activePath?.let { it == path.normalize() } ?: false

    /** Stop this window's stack (if any) and start [worktreePath]'s stack. */
    @RequiresEdt
    fun activate(taskName: String, worktreePath: Path): Boolean {
        val commands = configuredCommands()
        if (commands.isEmpty()) return false

        // Only one dev server runs at a time so they don't fight over the shared port (e.g. :3000).
        // Stop every other worktree's server first; Stop on a specific task remains independent.
        ProjectManager.getInstance().openProjects.forEach { other ->
            if (other != project && !other.isDisposed) getInstance(other).stop()
        }
        stop()

        for (command in commands) {
            launchInConsole(command, worktreePath, "$taskName · ${shortLabel(command)}")
                ?.let { running += it }
        }
        activePath = worktreePath.normalize()
        fireTasksChangedEverywhere()
        return true
    }

    /** Activate, installing deps first (and waiting) if node_modules is missing. */
    @RequiresEdt
    fun activateWithSetupIfNeeded(taskName: String, worktreePath: Path) {
        val needsSetup = setupCommands().isNotEmpty() &&
            Files.notExists(worktreePath.resolve("node_modules"))
        if (needsSetup) {
            runSetupThen(taskName, worktreePath) { activate(taskName, worktreePath) }
        } else {
            activate(taskName, worktreePath)
        }
    }

    /** One-time setup commands (e.g. `yarn install`) for this worktree, run one after another. */
    @RequiresEdt
    fun runSetup(taskName: String, worktreePath: Path): Boolean {
        val commands = setupCommands()
        if (commands.isEmpty()) return false
        runInOrder(taskName, worktreePath, commands) {}
        return true
    }

    /**
     * Run setup commands one at a time, then invoke [onComplete] on the EDT once the last succeeds.
     * If none are configured, [onComplete] runs immediately. Chains "install deps → start server".
     */
    @RequiresEdt
    fun runSetupThen(taskName: String, worktreePath: Path, onComplete: () -> Unit) {
        runInOrder(taskName, worktreePath, setupCommands(), onComplete)
    }

    /** Start [commands]' first line; each later line starts only after the previous one exits 0. */
    private fun runInOrder(taskName: String, worktreePath: Path, commands: List<String>, onComplete: () -> Unit) {
        val command = commands.firstOrNull() ?: return onComplete()
        launchInConsole(command, worktreePath, "$taskName · setup: ${shortLabel(command)}") { exitCode ->
            if (exitCode != 0) return@launchInConsole
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) runInOrder(taskName, worktreePath, commands.drop(1), onComplete)
            }
        }
    }

    /** Stop the active stack, if any. */
    @RequiresEdt
    fun stop() {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val runContentManager = if (project.isDisposed) null else RunContentManager.getInstanceIfCreated(project)
        running.forEach { r ->
            // destroyProcess() sends SIGINT first, letting puma/webpack shut their workers down.
            r.handler.destroyProcess()
            runContentManager?.removeRunContent(executor, r.descriptor)
        }
        running.clear()
        activePath = null
        if (!project.isDisposed) fireTasksChangedEverywhere()
    }

    override fun dispose() {
        running.forEach { it.handler.destroyProcess() }
        running.clear()
        activePath = null
    }

    fun configuredCommands(): List<String> =
        WorktreeTasksSettings.getInstance().devServerCommands
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun setupCommands(): List<String> =
        WorktreeTasksSettings.getInstance().setupCommands
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Launch [command] in [worktreePath] via the configured shell, shown in this window's Run tab. */
    private fun launchInConsole(
        command: String,
        worktreePath: Path,
        title: String,
        onTerminated: ((exitCode: Int) -> Unit)? = null,
    ): Running? {
        val settings = WorktreeTasksSettings.getInstance()
        val shell = resolveShell(settings.devServerShell)
        val shellArgs = settings.devServerShellArgs.trim()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Start OUTSIDE the worktree and `cd` in, so chpwd-based version switchers (nvm/fnm
        // --use-on-cd, direnv, chruby) fire. Setting the working directory directly would skip the
        // `cd`, leaving the shell on the default Node/Ruby instead of the worktree's .nvmrc/.ruby-version.
        val startDir = (worktreePath.parent ?: worktreePath).toString()
        val cd = "cd ${shellQuote(worktreePath.toString())}"
        val prefix = settings.devServerCommandPrefix.trim()
        val fullCommand = if (prefix.isEmpty()) {
            "$cd && $command"
        } else {
            // Braces (not a subshell) so `nvm use`'s PATH change persists for $command; `|| true`
            // keeps a missing .nvmrc from aborting.
            "$cd && { $prefix || true; } && $command"
        }
        return try {
            val cmd = GeneralCommandLine(shell, *shellArgs.toTypedArray(), fullCommand)
                .withWorkDirectory(startDir)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withCharset(Charsets.UTF_8)
            val handler = KillableColoredProcessHandler(cmd)
            ProcessTerminatedListener.attach(handler, project)
            if (onTerminated != null) {
                handler.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) = onTerminated(event.exitCode)
                })
            }

            val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            console.attachToProcess(handler)

            val descriptor = RunContentDescriptor(console, handler, console.component, title)
            handler.startNotify()
            RunContentManager.getInstance(project).showRunContent(
                DefaultRunExecutor.getRunExecutorInstance(),
                descriptor,
            )
            Running(descriptor, handler)
        } catch (t: Throwable) {
            LOG.warn("Failed to launch command: $command", t)
            null
        }
    }

    private fun resolveShell(configured: String): String =
        configured.ifBlank { System.getenv("SHELL") ?: "/bin/zsh" }

    /** POSIX single-quote escaping for embedding a path in a shell command. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun shortLabel(command: String): String {
        val firstReal = command.split(Regex("\\s+")).firstOrNull { !it.contains('=') } ?: command
        return firstReal.substringAfterLast('/')
    }

    companion object {
        private val LOG = Logger.getInstance(DevServerManager::class.java)

        fun getInstance(project: Project): DevServerManager =
            project.getService(DevServerManager::class.java)
    }
}
