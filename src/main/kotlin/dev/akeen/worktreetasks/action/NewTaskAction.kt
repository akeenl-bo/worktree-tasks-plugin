package dev.akeen.worktreetasks.action

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
import dev.akeen.worktreetasks.service.ParentRebaser
import dev.akeen.worktreetasks.service.ParentSync
import dev.akeen.worktreetasks.service.TaskParent
import dev.akeen.worktreetasks.service.TaskParents
import dev.akeen.worktreetasks.service.TaskService
import dev.akeen.worktreetasks.service.WorktreeTaskLauncher
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import dev.akeen.worktreetasks.startup.LaunchMode
import java.nio.file.Path

/**
 * Creates a new worktree task: prompts for details, fetches the chosen parent, creates the worktree
 * on a new branch off it (recording the parent for later rebases), then opens it as its own IDE
 * window with `claude` queued to launch.
 */
class NewTaskAction : AnAction("New Task", "Create a new worktree task", com.intellij.icons.AllIcons.General.Add) {

    private data class Context(val mainWorktree: Path, val defaultBase: String, val baseChoices: List<String>)

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

        val context = ProgressManager.getInstance().runProcessWithProgressSynchronously<Context, RuntimeException>(
            { loadContext(repoRoot) },
            "Loading Branches…",
            true,
            project,
        )
        val worktreeBase = WorktreeTasksSettings.getInstance().worktreeBaseDir
            .ifBlank { WorktreeGit.defaultWorktreeBase(context.mainWorktree).toString() }
            .let { Path.of(it) }

        val dialog = NewTaskDialog(project, context.defaultBase, context.baseChoices, worktreeBase)
        if (!dialog.showAndGet()) return

        createWorktree(project, context.mainWorktree, dialog.taskName, dialog.branchName, dialog.baseBranch, dialog.worktreePath)
    }

    /** Parent choices: the default base first, then branches open in other tasks, then other local branches. */
    private fun loadContext(repoRoot: Path): Context {
        val main = WorktreeGit.mainWorktree(repoRoot)
        val defaultBase = ParentSync.defaultBase(main)
        // The local copy of the default branch (e.g. `master`) is usually stale; offer the remote one only.
        val staleLocalDefault = ParentRebaser.splitRemoteRef(defaultBase, WorktreeGit.remotes(main))?.second
        val choices = (listOf(defaultBase) + WorktreeGit.list(main).mapNotNull { it.branch } + WorktreeGit.localBranches(main))
            .filter { it != staleLocalDefault }
            .distinct()
        return Context(main, defaultBase, choices)
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
                indicator.text = "Fetching $baseBranch…"
                ParentRebaser.fetchParent(repoRoot, baseBranch)
                val result = WorktreeGit.add(repoRoot, worktreePath, branch, baseBranch, noTrack = true)
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
                // An existing branch reused by `add` keeps whatever parent it already had.
                if (WorktreeGit.isBranch(repoRoot, baseBranch) && TaskParents.get(repoRoot, branch) == null) {
                    TaskParents.set(repoRoot, branch, TaskParent(baseBranch, WorktreeGit.revParse(repoRoot, baseBranch)))
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
