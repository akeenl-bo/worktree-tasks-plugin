package dev.akeen.worktreetasks.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.service.DevServerManager
import dev.akeen.worktreetasks.service.ParentSync
import dev.akeen.worktreetasks.service.TaskWatcher
import dev.akeen.worktreetasks.service.WorktreeProvisioner
import dev.akeen.worktreetasks.service.installClaudeStatusHooks
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs when a worktree window opens. Any reopened task is first checked against its parent branch
 * ([ParentSync.checkOnOpen]). If the New Task / Open / Activate action recorded a pending action for
 * this worktree, it then provisions the worktree (links secrets, installs deps if needed), launches
 * its Claude agent, and optionally starts its dev server.
 */
class WorktreeOpenActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val base = project.basePath ?: return
        val path = Path.of(base)
        val pending = PendingLaunchRegistry.getInstance().take(path)

        TaskWatcher.getInstance().ensureStarted()

        ApplicationManager.getApplication().executeOnPooledThread {
            // Every task window reports its Claude status, however it was opened.
            if (WorktreeGit.list(path).any { it.path.normalize() == path.normalize() }) installClaudeStatusHooks(path)
            // A brand-new task was just cut from a fresh fetch; anything reopened may be behind its parent.
            if (pending?.claudeMode != LaunchMode.NEW) {
                try {
                    ParentSync.checkOnOpen(project, path)
                } catch (t: Throwable) {
                    Logger.getInstance(WorktreeOpenActivity::class.java).warn("Parent check failed for $path", t)
                }
            }
            if (pending == null) return@executeOnPooledThread

            // Link gitignored secrets/config off the EDT (shells out to git to find the main worktree).
            WorktreeProvisioner.linkSharedFiles(project, path)
            val needsSetup = when (pending.setup) {
                SetupPolicy.SKIP -> false
                SetupPolicy.FORCE -> setupConfigured()
                SetupPolicy.IF_MISSING -> setupConfigured() && Files.notExists(path.resolve("node_modules"))
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                // New project windows use the default layout, so make the sidebar visible here.
                ToolWindowManager.getInstance(project).getToolWindow("Worktree Tasks")?.show()
                ClaudeLauncher.getInstance(project)
                    .launch(path, pending.taskName, pending.claudeMode, pending.initialPrompt)
                val manager = DevServerManager.getInstance(project)
                when {
                    pending.activateServer -> manager.activateWithSetupIfNeeded(pending.taskName, path)
                    needsSetup -> manager.runSetup(pending.taskName, path)
                }
            }
        }
    }

    private fun setupConfigured(): Boolean =
        WorktreeTasksSettings.getInstance().setupCommands.isNotBlank()
}
