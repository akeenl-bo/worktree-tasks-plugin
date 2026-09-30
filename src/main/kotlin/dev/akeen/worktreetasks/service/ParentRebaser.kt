package dev.akeen.worktreetasks.service

import dev.akeen.worktreetasks.git.WorktreeGit
import java.nio.file.Path

/** What to do about a task that may be behind its parent. */
enum class RebaseDecision {
    UP_TO_DATE,

    /** Clean and never pushed: safe to rebase without asking. */
    AUTO,

    /** Uncommitted changes: rebase only on request, stashing them around it. */
    ASK_DIRTY,

    /** Pushed: rebasing means a force-push afterwards, so only on request. */
    ASK_PUBLISHED,
}

data class ParentState(val parent: TaskParent, val parentTip: String, val behind: Int, val decision: RebaseDecision)

sealed interface RebaseOutcome {
    data class Rebased(val onto: String, val newMigrations: List<String>) : RebaseOutcome

    /** The rebase stopped (conflict, or git refused) and was aborted, leaving the branch as it was. */
    data class Stopped(val output: String) : RebaseOutcome
}

/**
 * Git side of keeping a task on top of its parent. Blocking; call off the EDT. IDE concerns
 * (notifications, confirmations, `gh`) live in [ParentSync].
 */
object ParentRebaser {

    const val MIGRATIONS_DIR = "db/migrate"

    fun decide(behind: Int, dirty: Boolean, published: Boolean): RebaseDecision = when {
        behind == 0 -> RebaseDecision.UP_TO_DATE
        dirty -> RebaseDecision.ASK_DIRTY
        published -> RebaseDecision.ASK_PUBLISHED
        else -> RebaseDecision.AUTO
    }

    /** Fetch [parentRef] when it's a remote ref; a local parent is used as it is. */
    fun fetchParent(workDir: Path, parentRef: String): WorktreeGit.CommandResult? {
        val (remote, branch) = splitRemoteRef(parentRef, WorktreeGit.remotes(workDir)) ?: return null
        return WorktreeGit.fetch(workDir, remote, branch)
    }

    fun state(worktree: Path, branch: String, parent: TaskParent): ParentState? {
        val tip = WorktreeGit.revParse(worktree, parent.ref) ?: return null
        val behind = WorktreeGit.countCommits(worktree, "HEAD", tip) ?: return null
        val decision = decide(behind, WorktreeGit.isDirty(worktree), WorktreeGit.isPublished(worktree, branch))
        return ParentState(parent, tip, behind, decision)
    }

    /**
     * Move [branch]'s own commits (those after the recorded fork point) onto [targetRef]'s tip and
     * record [targetRef] as the parent. [targetRef] differs from the current parent when retargeting.
     */
    fun rebase(
        worktree: Path,
        branch: String,
        parent: TaskParent,
        targetRef: String = parent.ref,
        autostash: Boolean = false,
    ): RebaseOutcome {
        val tip = WorktreeGit.revParse(worktree, targetRef)
            ?: return RebaseOutcome.Stopped("Can't resolve $targetRef")
        val oldBase = parent.forkPoint?.takeIf { WorktreeGit.revParse(worktree, it) != null }
            ?: WorktreeGit.mergeBase(worktree, "HEAD", tip)
            ?: return RebaseOutcome.Stopped("'$branch' shares no history with $targetRef")
        val result = WorktreeGit.rebaseOnto(worktree, tip, oldBase, autostash)
        if (!result.success) {
            WorktreeGit.rebaseAbort(worktree)
            return RebaseOutcome.Stopped(result.output)
        }
        TaskParents.set(worktree, branch, TaskParent(targetRef, tip))
        return RebaseOutcome.Rebased(targetRef, WorktreeGit.addedFiles(worktree, oldBase, tip, MIGRATIONS_DIR))
    }

    /** "origin/feature/x" → ("origin", "feature/x") when "origin" is a remote; null for local refs. */
    internal fun splitRemoteRef(ref: String, remotes: List<String>): Pair<String, String>? =
        remotes.firstOrNull { ref.startsWith("$it/") }?.let { it to ref.removePrefix("$it/") }
}
