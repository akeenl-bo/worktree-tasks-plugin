package dev.akeen.worktreetasks.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import dev.akeen.worktreetasks.service.DevServerManager
import dev.akeen.worktreetasks.service.WorktreeProvisioner
import dev.akeen.worktreetasks.service.installClaudeStatusHooks
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs when a worktree window opens. If the New Task / Open / Activate action recorded a pending
 * action for this worktree, it provisions the worktree (links secrets, installs deps if needed),
 * launches its Claude agent, and optionally starts its dev server. Windows opened by other means
 * have no pending entry and are left untouched.
 */
class WorktreeOpenActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val base = project.basePath ?: return
        val path = Path.of(base)
        val pending = PendingLaunchRegistry.getInstance().take(path) ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            // Link gitignored secrets/config off the EDT (shells out to git to find the main worktree).
            WorktreeProvisioner.linkSharedFiles(project, path)
            // Install status hooks before Claude starts so it reports working/needs-input/done.
            installClaudeStatusHooks(path)
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
