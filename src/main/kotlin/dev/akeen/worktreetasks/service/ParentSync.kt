package dev.akeen.worktreetasks.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps tasks on top of their parent branch: checks on window open (rebasing automatically when
 * [ParentRebaser.decide] says it's safe), backs the sidebar's Rebase / Change Parent / Retarget
 * actions, and notices when a parent's PR has merged (via `gh`).
 */
object ParentSync {

    private const val NOTIFICATION_GROUP = "Worktree Tasks"

    /** Worktrees whose parent branch's PR has merged, from the last check. */
    private val mergedParents = ConcurrentHashMap<String, Boolean>()

    fun isParentMerged(worktree: Path): Boolean = mergedParents[key(worktree)] == true

    /** Parent for new tasks: the setting, else the remote's default branch (e.g. `origin/master`). */
    fun defaultBase(repoRoot: Path): String =
        WorktreeTasksSettings.getInstance().defaultBaseBranch.trim().ifBlank {
            WorktreeGit.defaultRemoteBranch(repoRoot) ?: WorktreeGit.currentBranch(repoRoot) ?: "HEAD"
        }

    /** Called off the EDT when a task window opens, before its Claude session starts. */
    fun checkOnOpen(project: Project, worktree: Path) {
        val branch = WorktreeGit.currentBranch(worktree) ?: return
        val parent = TaskParents.get(worktree, branch) ?: return
        ParentRebaser.fetchParent(worktree, parent.ref)
        if (refreshMerged(worktree, parent)) {
            notifyParentMerged(project, worktree, branch, parent)
        } else if (ParentRebaser.state(worktree, branch, parent)?.decision == RebaseDecision.AUTO) {
            report(project, worktree, branch, ParentRebaser.rebase(worktree, branch, parent))
        }
        refreshSidebars()
    }

    /** Sidebar "Rebase on Parent": fetch, confirm if the branch is pushed, rebase (stashing if dirty). */
    fun rebaseNow(project: Project, worktree: Path, branch: String) = background(project, "Rebasing '$branch'") {
        val parent = TaskParents.get(worktree, branch) ?: return@background
        ParentRebaser.fetchParent(worktree, parent.ref)
        val state = ParentRebaser.state(worktree, branch, parent) ?: return@background
        when (state.decision) {
            RebaseDecision.UP_TO_DATE -> notify(project, "'$branch' is up to date with ${parent.ref}.", NotificationType.INFORMATION)
            RebaseDecision.ASK_PUBLISHED ->
                if (confirm(project, forcePushWarning(branch, parent.ref))) {
                    report(project, worktree, branch, ParentRebaser.rebase(worktree, branch, parent))
                }
            RebaseDecision.ASK_DIRTY ->
                report(project, worktree, branch, ParentRebaser.rebase(worktree, branch, parent, autostash = true))
            RebaseDecision.AUTO -> report(project, worktree, branch, ParentRebaser.rebase(worktree, branch, parent))
        }
        refreshSidebars()
    }

    /** After the parent's PR merged: move the task onto the default base and offer to retarget its PR. */
    fun retargetToDefault(project: Project, worktree: Path, branch: String) = background(project, "Retargeting '$branch'") {
        val parent = TaskParents.get(worktree, branch) ?: return@background
        val target = defaultBase(worktree)
        ParentRebaser.fetchParent(worktree, target)
        if (WorktreeGit.isPublished(worktree, branch) && !confirm(project, forcePushWarning(branch, target))) {
            return@background
        }
        val outcome = ParentRebaser.rebase(worktree, branch, parent, target, autostash = WorktreeGit.isDirty(worktree))
        report(project, worktree, branch, outcome)
        if (outcome is RebaseOutcome.Rebased) {
            mergedParents.remove(key(worktree))
            val baseName = branchName(worktree, target)
            val pr = openPrNumber(worktree, branch)
            if (pr != null && confirm(project, "Change PR #$pr's base branch to '$baseName'?")) {
                gh(worktree, "pr", "edit", pr.toString(), "--base", baseName)
            }
        }
        refreshSidebars()
    }

    /** Sidebar "Change Parent…": pick another branch to stack on, then offer to rebase onto it. */
    fun changeParent(project: Project, worktree: Path, branch: String) = background(project, "Loading branches") {
        val current = TaskParents.get(worktree, branch)
        val default = defaultBase(worktree)
        val choices = (listOf(default) + WorktreeGit.list(worktree).mapNotNull { it.branch } + WorktreeGit.localBranches(worktree))
            .filter { it != branch }
            .distinct()
        var picked: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            picked = Messages.showEditableChooseDialog(
                "Stack '$branch' on:",
                "Change Parent",
                null,
                choices.toTypedArray(),
                current?.ref ?: default,
                null,
            )?.trim()
        }
        val newRef = picked?.takeIf { it.isNotEmpty() && it != current?.ref } ?: return@background
        if (WorktreeGit.revParse(worktree, newRef) == null) {
            notify(project, "'$newRef' isn't a branch in this repository.", NotificationType.WARNING)
            return@background
        }
        // Keep the old fork point: the task's own commits still start there, whatever they sit on next.
        val forkPoint = current?.forkPoint ?: WorktreeGit.mergeBase(worktree, "HEAD", default)
        val updated = TaskParent(newRef, forkPoint)
        TaskParents.set(worktree, branch, updated)
        mergedParents.remove(key(worktree))
        if (confirm(project, "Rebase '$branch' onto $newRef now?")) {
            ParentRebaser.fetchParent(worktree, newRef)
            val dirty = WorktreeGit.isDirty(worktree)
            if (!WorktreeGit.isPublished(worktree, branch) || confirm(project, forcePushWarning(branch, newRef))) {
                report(project, worktree, branch, ParentRebaser.rebase(worktree, branch, updated, autostash = dirty))
            }
        }
        refreshSidebars()
    }

    private fun refreshMerged(worktree: Path, parent: TaskParent): Boolean {
        val merged = parent.ref != defaultBase(worktree) && prMerged(worktree, branchName(worktree, parent.ref))
        mergedParents[key(worktree)] = merged
        return merged
    }

    private fun notifyParentMerged(project: Project, worktree: Path, branch: String, parent: TaskParent) {
        val target = defaultBase(worktree)
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("'$branch': parent ${parent.ref} has merged.", NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring("Retarget to $target") {
                retargetToDefault(project, worktree, branch)
            })
            .notify(project)
    }

    private fun report(project: Project, worktree: Path, branch: String, outcome: RebaseOutcome) {
        when (outcome) {
            is RebaseOutcome.Rebased -> {
                refreshFiles(worktree)
                if (outcome.newMigrations.isEmpty()) {
                    notify(project, "Rebased '$branch' onto ${outcome.onto}.", NotificationType.INFORMATION)
                } else {
                    notify(
                        project,
                        "Rebased '$branch' onto ${outcome.onto}. It brought in ${outcome.newMigrations.size} " +
                            "migration(s) — run db:migrate:<br>${outcome.newMigrations.joinToString("<br>")}",
                        NotificationType.WARNING,
                    )
                }
            }
            is RebaseOutcome.Stopped -> notify(
                project,
                "Rebase of '$branch' stopped and was undone; the branch is unchanged.<br>${outcome.output.take(400)}",
                NotificationType.WARNING,
            )
        }
    }

    private fun forcePushWarning(branch: String, onto: String) =
        "'$branch' has been pushed. After rebasing it onto $onto you'll need to force-push it.\n\nRebase now?"

    /** Short branch name for `gh`: "origin/feature/x" → "feature/x"; local names pass through. */
    private fun branchName(workDir: Path, ref: String): String =
        ParentRebaser.splitRemoteRef(ref, WorktreeGit.remotes(workDir))?.second ?: ref

    private fun prMerged(workDir: Path, branch: String): Boolean =
        gh(workDir, "pr", "list", "--head", branch, "--state", "merged", "--json", "number", "--jq", "length")
            ?.toIntOrNull()?.let { it > 0 } ?: false

    private fun openPrNumber(workDir: Path, branch: String): Int? =
        gh(workDir, "pr", "list", "--head", branch, "--state", "open", "--json", "number", "--jq", ".[0].number")
            ?.toIntOrNull()

    private fun gh(workDir: Path, vararg args: String): String? = LoginShell.gh(workDir, *args)

    private fun refreshFiles(worktree: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(worktree)?.let {
            VfsUtil.markDirtyAndRefresh(true, true, true, it)
        }
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(content, type)
            .notify(project)
    }

    private fun confirm(project: Project, message: String): Boolean {
        var yes = false
        ApplicationManager.getApplication().invokeAndWait {
            yes = !project.isDisposed &&
                Messages.showYesNoDialog(project, message, "Worktree Tasks", Messages.getQuestionIcon()) == Messages.YES
        }
        return yes
    }

    private fun background(project: Project, title: String, body: () -> Unit) {
        object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) = body()
        }.queue()
    }

    private fun refreshSidebars() = ApplicationManager.getApplication().invokeLater { fireTasksChangedEverywhere() }

    private fun key(path: Path) = path.normalize().toString()
}
