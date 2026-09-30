package dev.akeen.worktreetasks.service

import dev.akeen.worktreetasks.git.WorktreeGit
import java.nio.file.Path

/**
 * The branch a task is stacked on ([ref], e.g. `origin/master` or another task's branch) and the
 * commit the task's own commits currently sit on top of ([forkPoint]). Rebasing replays only the
 * commits after [forkPoint], so an amended or force-pushed parent never gets its old commits replayed.
 */
data class TaskParent(val ref: String, val forkPoint: String?)

/**
 * Parent records live in git config (`branch.<name>.worktreeTasksParent` / `...ForkPoint`), which
 * every worktree of the repo shares, so the terminal and Claude can read them too.
 */
object TaskParents {

    private const val PARENT = "worktreetasksparent"
    private const val FORK_POINT = "worktreetasksforkpoint"

    /** Every branch's parent record, keyed by branch name. */
    fun all(repoRoot: Path): Map<String, TaskParent> =
        parse(WorktreeGit.configGetRegexp(repoRoot, "^branch\\..*\\.worktreetasks"))

    fun get(repoRoot: Path, branch: String): TaskParent? = all(repoRoot)[branch]

    fun set(repoRoot: Path, branch: String, parent: TaskParent) {
        WorktreeGit.configSet(repoRoot, "branch.$branch.$PARENT", parent.ref)
        parent.forkPoint?.let { WorktreeGit.configSet(repoRoot, "branch.$branch.$FORK_POINT", it) }
    }

    internal fun parse(entries: Map<String, String>): Map<String, TaskParent> {
        val refs = mutableMapOf<String, String>()
        val forkPoints = mutableMapOf<String, String>()
        for ((key, value) in entries) {
            // Branch names can contain dots, so the variable is whatever follows the last one.
            val branch = key.removePrefix("branch.").substringBeforeLast('.')
            when (key.substringAfterLast('.').lowercase()) {
                PARENT -> refs[branch] = value
                FORK_POINT -> forkPoints[branch] = value
            }
        }
        return refs.mapValues { (branch, ref) -> TaskParent(ref, forkPoints[branch]) }
    }
}
