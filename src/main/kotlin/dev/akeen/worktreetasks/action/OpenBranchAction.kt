package dev.akeen.worktreetasks.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.service.TaskService
import dev.akeen.worktreetasks.service.WorktreeTaskLauncher
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import dev.akeen.worktreetasks.startup.LaunchMode
import java.nio.file.Path

/**
 * Opens an existing branch — local or remote — in a new worktree. Lists the repository's openable
 * branches (excluding those already checked out in a worktree), then provisions and opens the chosen
 * one the same way [NewTaskAction] does, minus the new-branch creation.
 */
class OpenBranchAction :
    AnAction("Open Branch", "Open an existing local or remote branch in a worktree", AllIcons.Vcs.Branch) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repoRoot = TaskService.getInstance(project).repoRoot() ?: run {
            Messages.showErrorDialog(project, "This project is not a git repository.", "Open Branch")
            return
        }

        // Listing branches shells out to git — do it under a modal progress, off the EDT.
        val refs = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<WorktreeGit.BranchRef>, RuntimeException>(
            { WorktreeGit.branchRefs(repoRoot) },
            "Loading Branches…",
            true,
            project,
        )
        val openable = refs.filterNot { it.isCheckedOut }
        if (openable.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No branches available to open — every branch is already checked out in a worktree.",
                "Open Branch",
            )
            return
        }

        val settings = WorktreeTasksSettings.getInstance()
        val worktreeBase = settings.worktreeBaseDir
            .ifBlank { WorktreeGit.defaultWorktreeBase(repoRoot).toString() }
            .let { Path.of(it) }

        val dialog = OpenBranchDialog(project, openable, worktreeBase)
        if (!dialog.showAndGet()) return

        val branch = dialog.selectedBranch ?: return
        openBranch(project, repoRoot, branch, dialog.taskName, dialog.worktreePath)
    }

    private fun openBranch(
        project: Project,
        repoRoot: Path,
        branch: WorktreeGit.BranchRef,
        name: String,
        worktreePath: Path,
    ) {
        object : Task.Backgroundable(project, "Opening branch '${branch.name}'", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = if (branch.isRemote) {
                    WorktreeGit.addTracking(repoRoot, worktreePath, branch.localName, branch.name)
                } else {
                    WorktreeGit.addExisting(repoRoot, worktreePath, branch.name)
                }
                if (!result.success) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "git worktree add failed:\n\n${result.output}",
                            "Open Branch",
                        )
                    }
                    return
                }
                // Opening existing work: no task prompt, and let Claude continue any prior session.
                WorktreeTaskLauncher.openCreated(
                    project, repoRoot, name, worktreePath,
                    claudeMode = LaunchMode.CONTINUE,
                    initialPrompt = null,
                )
            }
        }.queue()
    }
}
