package dev.akeen.worktreetasks.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.service.TaskService
import dev.akeen.worktreetasks.service.WorktreeTaskLauncher
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import dev.akeen.worktreetasks.startup.LaunchMode
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path

/**
 * Creates a new worktree task: prompts for details, creates the worktree on a background thread,
 * then opens it as its own IDE window with `claude` queued to launch.
 */
class NewTaskAction : AnAction("New Task", "Create a new worktree task", com.intellij.icons.AllIcons.General.Add) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repoRoot = TaskService.getInstance(project).repoRoot() ?: run {
            Messages.showErrorDialog(project, "This project is not a git repository.", "New Task")
            return
        }
        val settings = WorktreeTasksSettings.getInstance()

        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        val defaultBase = settings.defaultBaseBranch.ifBlank { repo?.currentBranchName ?: "HEAD" }
        val worktreeBase = settings.worktreeBaseDir
            .ifBlank { WorktreeGit.defaultWorktreeBase(repoRoot).toString() }
            .let { Path.of(it) }

        val dialog = NewTaskDialog(project, defaultBase, worktreeBase)
        if (!dialog.showAndGet()) return

        val name = dialog.taskName
        val branch = dialog.branchName
        val baseBranch = dialog.baseBranch
        val worktreePath = dialog.worktreePath

        createWorktree(project, repoRoot, name, branch, baseBranch, worktreePath)
    }

    private fun createWorktree(
        project: Project,
        repoRoot: Path,
        name: String,
        branch: String,
        baseBranch: String,
        worktreePath: Path,
    ) {
        object : Task.Backgroundable(project, "Creating worktree '$name'", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = WorktreeGit.add(repoRoot, worktreePath, branch, baseBranch)
                if (!result.success) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "git worktree add failed:\n\n${result.output}",
                            "New Task",
                        )
                    }
                    return
                }

                val prompt = WorktreeTasksSettings.getInstance().initialPromptTemplate
                    .takeIf { it.isNotBlank() }
                    ?.replace("{task}", name)
                WorktreeTaskLauncher.openCreated(
                    project, repoRoot, name, worktreePath,
                    claudeMode = LaunchMode.NEW,
                    initialPrompt = prompt,
                )
            }
        }.queue()
    }
}
