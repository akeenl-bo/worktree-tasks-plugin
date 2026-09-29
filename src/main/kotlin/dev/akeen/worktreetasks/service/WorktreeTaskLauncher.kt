package dev.akeen.worktreetasks.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import dev.akeen.worktreetasks.startup.LaunchMode
import dev.akeen.worktreetasks.startup.PendingLaunchRegistry
import dev.akeen.worktreetasks.startup.PendingOpen
import dev.akeen.worktreetasks.startup.ProjectLauncher
import dev.akeen.worktreetasks.startup.SetupPolicy
import java.nio.file.Path

/**
 * Shared tail end of "make a worktree, then bring it up as a task window": record the friendly
 * [name], seed `.idea` config (Ruby SDK / run configs) before the window opens, queue Claude, and
 * open/focus the window. Used by both New Task (new branch) and Open Branch (existing branch).
 *
 * Must run off the EDT — it shells out to git to locate the main worktree.
 */
object WorktreeTaskLauncher {

    /**
     * @param claudeMode how Claude should start in the new window (NEW for a fresh task, etc.).
     * @param initialPrompt prompt to seed Claude with, or null to launch with no prompt.
     */
    fun openCreated(
        project: Project,
        repoRoot: Path,
        name: String,
        worktreePath: Path,
        claudeMode: LaunchMode = LaunchMode.NEW,
        initialPrompt: String? = null,
    ) {
        val settings = WorktreeTasksSettings.getInstance()
        TaskNameStore.getInstance().put(worktreePath.normalize().toString(), name)
        WorktreeProvisioner.seedIdeaConfig(WorktreeGit.mainWorktree(repoRoot), worktreePath)

        // Open the worktree as its own window; WorktreeOpenActivity then links secrets, installs
        // deps, and launches Claude inside that window. Setup and the dev server are explicit
        // button actions, not part of opening.
        PendingLaunchRegistry.getInstance().put(
            worktreePath,
            PendingOpen(
                taskName = name,
                claudeMode = claudeMode,
                setup = SetupPolicy.SKIP,
                activateServer = false,
                initialPrompt = initialPrompt,
            ),
        )
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            project.fireTasksChanged()
            if (settings.autoRunClaude) ProjectLauncher.openOrFocus(worktreePath)
        }
    }
}
