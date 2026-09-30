package dev.akeen.worktreetasks.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.akeen.worktreetasks.git.WorktreeGit
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path

/**
 * One agentic "task" — a git worktree.
 */
data class WorktreeTask(
    val name: String,
    val path: Path,
    val branch: String?,
    val head: String?,
    /** The repository's main worktree (cannot be removed as a task). */
    val isMain: Boolean,
    /** True when this task corresponds to the currently open project window. */
    val isCurrent: Boolean,
    val isLocked: Boolean,
    /** Whether the worktree has uncommitted changes. */
    val isDirty: Boolean,
    /** The branch this task is stacked on, if recorded. */
    val parent: String? = null,
    /** Commits on [parent] this task doesn't have yet, as of the last fetch. */
    val behindParent: Int = 0,
    /** True when [parent] is the default base (e.g. origin/master), so the sidebar needn't name it. */
    val parentIsDefault: Boolean = true,
)

/**
 * Project-scoped facade over [WorktreeGit] that turns raw worktrees into [WorktreeTask]s, applying
 * friendly names from [TaskNameStore]. Read operations shell out to git and must run off the EDT.
 */
@Service(Service.Level.PROJECT)
class TaskService(private val project: Project) {

    /**
     * Git repository root for this project (the project may itself be a worktree).
     *
     * Prefers Git4Idea's registered root, but falls back to the project base path so we still work
     * when the repo was just `git init`'d or its VCS root hasn't been registered yet. `git worktree`
     * resolves the toplevel from any directory inside the repo, so the base path is good enough.
     */
    fun repoRoot(): Path? {
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            ?.root?.toNioPathOrNull()
            ?.let { return it }
        return project.basePath?.let { Path.of(it) }
    }

    fun listTasks(): List<WorktreeTask> {
        val root = repoRoot() ?: return emptyList()
        val currentBase = project.basePath?.let { Path.of(it).normalize() }
        val store = TaskNameStore.getInstance()
        val parents = TaskParents.all(root)
        val defaultBase = ParentSync.defaultBase(root)
        return WorktreeGit.list(root)
            .filterNot { it.isBare }
            .map { wt ->
                val normalized = wt.path.normalize()
                val name = store.nameFor(normalized.toString())
                    ?: wt.branch
                    ?: normalized.fileName?.toString()
                    ?: normalized.toString()
                val parent = wt.branch?.let { parents[it] }?.takeUnless { wt.isMain }
                WorktreeTask(
                    name = name,
                    path = normalized,
                    branch = wt.branch,
                    head = wt.head,
                    isMain = wt.isMain,
                    isCurrent = currentBase != null && currentBase == normalized,
                    isLocked = wt.isLocked,
                    isDirty = WorktreeGit.isDirty(normalized),
                    parent = parent?.ref,
                    behindParent = parent?.let { WorktreeGit.countCommits(normalized, "HEAD", it.ref) } ?: 0,
                    parentIsDefault = parent == null || parent.ref == defaultBase,
                )
            }
    }

    companion object {
        fun getInstance(project: Project): TaskService = project.getService(TaskService::class.java)
    }
}

private fun com.intellij.openapi.vfs.VirtualFile.toNioPathOrNull(): Path? =
    try {
        toNioPath()
    } catch (_: UnsupportedOperationException) {
        Path.of(path)
    }
