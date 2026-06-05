package dev.akeen.worktreetasks.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.config.GitExecutableManager
import java.nio.file.Path

/**
 * Thin wrapper around the `git worktree` plumbing. We shell out via [GeneralCommandLine] using the
 * git executable configured in the IDE (Git4Idea), rather than relying on git4idea's internal
 * command-handler classes whose API has shifted across platform versions.
 *
 * All calls here are blocking and must be invoked off the EDT (e.g. from a background task).
 */
object WorktreeGit {

    /** A single worktree as reported by `git worktree list --porcelain`. */
    data class WorktreeInfo(
        val path: Path,
        val head: String?,
        /** Short branch name (e.g. "feature/x"), or null when detached/bare. */
        val branch: String?,
        val isBare: Boolean,
        val isDetached: Boolean,
        val isLocked: Boolean,
        /** True for the first entry, which git reports as the main worktree. */
        val isMain: Boolean,
    )

    /** Result of a mutating git command. */
    data class CommandResult(val success: Boolean, val output: String, val exitCode: Int)

    private fun gitPath(project: Project): String =
        GitExecutableManager.getInstance().getPathToGit(project)

    private fun run(project: Project, workDir: Path, vararg args: String): CommandResult {
        val cmd = GeneralCommandLine(gitPath(project))
            .withParameters(*args)
            .withWorkDirectory(workDir.toString())
            .withCharset(Charsets.UTF_8)
        val out = ExecUtil.execAndGetOutput(cmd)
        val combined = buildString {
            append(out.stdout)
            if (out.stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(out.stderr)
            }
        }.trim()
        return CommandResult(out.exitCode == 0, combined, out.exitCode)
    }

    /** List all worktrees for the repository rooted at [repoRoot]. */
    fun list(project: Project, repoRoot: Path): List<WorktreeInfo> {
        val result = run(project, repoRoot, "worktree", "list", "--porcelain")
        if (!result.success) return emptyList()
        return parsePorcelain(result.output)
    }

    /**
     * Create a new worktree at [newPath] on a new branch [branch] based on [baseRef].
     * When [branch] already exists, falls back to checking it out instead of creating it.
     */
    fun add(project: Project, repoRoot: Path, newPath: Path, branch: String, baseRef: String): CommandResult {
        val created = run(
            project, repoRoot,
            "worktree", "add", "-b", branch, newPath.toString(), baseRef,
        )
        val result = if (created.success) {
            created
        } else {
            // Branch may already exist — try to attach the existing branch to a new worktree.
            run(project, repoRoot, "worktree", "add", newPath.toString(), branch)
        }
        if (result.success) refresh(newPath)
        return result
    }

    /** Remove the worktree at [path]. Pass [force] to discard uncommitted changes. */
    fun remove(project: Project, repoRoot: Path, path: Path, force: Boolean): CommandResult {
        val args = buildList {
            add("worktree"); add("remove")
            if (force) add("--force")
            add(path.toString())
        }
        val result = run(project, repoRoot, *args.toTypedArray())
        if (result.success) refresh(path)
        return result
    }

    /** Delete a local branch (used after removing its worktree). */
    fun deleteBranch(project: Project, repoRoot: Path, branch: String, force: Boolean): CommandResult =
        run(project, repoRoot, "branch", if (force) "-D" else "-d", branch)

    /** True if the worktree at [worktreePath] has uncommitted changes. */
    fun isDirty(project: Project, worktreePath: Path): Boolean {
        val result = run(project, worktreePath, "status", "--porcelain")
        return result.success && result.output.isNotBlank()
    }

    /** The repository's current branch name, or null if detached/unknown. */
    fun currentBranch(project: Project, repoRoot: Path): String? {
        val result = run(project, repoRoot, "rev-parse", "--abbrev-ref", "HEAD")
        val name = result.output.trim()
        return if (result.success && name.isNotBlank() && name != "HEAD") name else null
    }

    /**
     * Default base directory for new worktrees: a sibling `<repo-name>-worktrees` folder next to
     * the repo, keeping worktrees outside the repository tree and grouped per project.
     */
    fun defaultWorktreeBase(repoRoot: Path): Path {
        val parent = repoRoot.parent ?: repoRoot
        val name = repoRoot.fileName?.toString() ?: "repo"
        return parent.resolve("$name-worktrees")
    }

    private fun refresh(path: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        path.parent?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
    }

    internal fun parsePorcelain(output: String): List<WorktreeInfo> {
        val blocks = output.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        return blocks.mapIndexed { index, block ->
            var path: Path? = null
            var head: String? = null
            var branch: String? = null
            var bare = false
            var detached = false
            var locked = false
            for (line in block.lineSequence().map { it.trim() }) {
                when {
                    line.startsWith("worktree ") -> path = Path.of(line.removePrefix("worktree ").trim())
                    line.startsWith("HEAD ") -> head = line.removePrefix("HEAD ").trim()
                    line.startsWith("branch ") ->
                        branch = line.removePrefix("branch ").trim().removePrefix("refs/heads/")
                    line == "bare" -> bare = true
                    line == "detached" -> detached = true
                    line == "locked" || line.startsWith("locked ") -> locked = true
                }
            }
            WorktreeInfo(
                path = path ?: return@mapIndexed null,
                head = head,
                branch = branch,
                isBare = bare,
                isDetached = detached,
                isLocked = locked,
                isMain = index == 0,
            )
        }.filterNotNull()
    }
}
