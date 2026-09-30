package dev.akeen.worktreetasks.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.config.GitExecutableManager
import java.nio.file.Path

/**
 * Thin wrapper around the `git worktree` plumbing. We shell out via [GeneralCommandLine] using the
 * git executable configured in the IDE (Git4Idea), rather than relying on git4idea's internal
 * command-handler classes whose API has shifted across platform versions.
 *
 * Project-less on purpose: git needs no IDE project, only the executable path. This lets worktree
 * removal run safely even while we close the worktree's own window.
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

    /** Result of a git command: [output] is stdout + stderr trimmed; [stdout] is stdout untouched. */
    data class CommandResult(val success: Boolean, val output: String, val exitCode: Int, val stdout: String = output)

    /** A file that differs between some base commit and the working tree ([status] as in `--name-status`). */
    data class FileChange(val status: Char, val path: String, val oldPath: String? = null)

    /** An existing branch the user can open in a worktree. */
    data class BranchRef(
        /** Checkout target: a local short name ("feature/x") or remote ref ("origin/feature/x"). */
        val name: String,
        val isRemote: Boolean,
        /** Remote name (e.g. "origin") for remote refs; null for local. */
        val remote: String?,
        /** Proposed local branch name — equals [name] for local refs, the remote-stripped name otherwise. */
        val localName: String,
        /** True when this branch is already checked out in some worktree (cannot be opened again). */
        val isCheckedOut: Boolean,
    )

    /** Executes git. Swapped in tests for a plain process runner that needs no IDE. */
    fun interface Runner {
        fun run(workDir: Path, args: List<String>): CommandResult
    }

    internal var runner: Runner = Runner { workDir, args -> runWithIdeGit(workDir, args) }

    private fun gitPath(): String = GitExecutableManager.getInstance().getPathToGit()

    private fun run(workDir: Path, vararg args: String): CommandResult = runner.run(workDir, args.toList())

    private fun runWithIdeGit(workDir: Path, args: List<String>): CommandResult {
        val cmd = GeneralCommandLine(gitPath())
            .withParameters(args)
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
        return CommandResult(out.exitCode == 0, combined, out.exitCode, out.stdout)
    }

    /** List all worktrees for the repository rooted at [repoRoot]. */
    fun list(repoRoot: Path): List<WorktreeInfo> {
        val result = run(repoRoot, "worktree", "list", "--porcelain")
        if (!result.success) return emptyList()
        return parsePorcelain(result.output)
    }

    /** Path of the repository's main worktree (run `git worktree` commands from here). */
    fun mainWorktree(repoRoot: Path): Path =
        list(repoRoot).firstOrNull { it.isMain }?.path ?: repoRoot

    /**
     * Create a new worktree at [newPath] on a new branch [branch] based on [baseRef].
     * When [branch] already exists, falls back to checking it out instead of creating it.
     * [noTrack] stops git from making a remote [baseRef] (e.g. `origin/master`) the new branch's upstream.
     */
    fun add(repoRoot: Path, newPath: Path, branch: String, baseRef: String, noTrack: Boolean = false): CommandResult {
        val createArgs = buildList {
            add("worktree"); add("add")
            if (noTrack) add("--no-track")
            add("-b"); add(branch); add(newPath.toString()); add(baseRef)
        }
        val created = run(repoRoot, *createArgs.toTypedArray())
        val result = if (created.success) {
            created
        } else {
            // Branch may already exist — try to attach the existing branch to a new worktree.
            run(repoRoot, "worktree", "add", newPath.toString(), branch)
        }
        if (result.success) refresh(newPath)
        return result
    }

    /**
     * Open an existing local [branch] in a new worktree at [newPath]. The branch must not already be
     * checked out in another worktree, or git refuses.
     */
    fun addExisting(repoRoot: Path, newPath: Path, branch: String): CommandResult {
        val result = run(repoRoot, "worktree", "add", newPath.toString(), branch)
        if (result.success) refresh(newPath)
        return result
    }

    /**
     * Open a remote branch in a new worktree at [newPath], creating a local branch [localBranch] that
     * tracks [remoteRef] (e.g. "origin/feature/x").
     */
    fun addTracking(repoRoot: Path, newPath: Path, localBranch: String, remoteRef: String): CommandResult {
        val result = run(repoRoot, "worktree", "add", "--track", "-b", localBranch, newPath.toString(), remoteRef)
        if (result.success) refresh(newPath)
        return result
    }

    /** Local branch short names (e.g. "feature/x"). */
    fun localBranches(repoRoot: Path): List<String> =
        forEachRef(repoRoot, "refs/heads")

    /** Remote-tracking branch names (e.g. "origin/feature/x"), excluding symbolic per-remote HEAD refs. */
    fun remoteBranches(repoRoot: Path): List<String> =
        forEachRef(repoRoot, "refs/remotes").filterNot { it.endsWith("/HEAD") }

    private fun forEachRef(repoRoot: Path, pattern: String): List<String> {
        val result = run(repoRoot, "for-each-ref", "--format=%(refname:short)", pattern)
        if (!result.success) return emptyList()
        return result.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** Branch short names currently checked out in any worktree. */
    fun checkedOutBranches(repoRoot: Path): Set<String> =
        list(repoRoot).mapNotNull { it.branch }.toSet()

    /**
     * All openable branches: every local branch plus remote-only branches (those without a local
     * counterpart). Local branches already checked out in a worktree are flagged via
     * [BranchRef.isCheckedOut] rather than dropped, so callers can show them as unavailable.
     */
    fun branchRefs(repoRoot: Path): List<BranchRef> =
        buildBranchRefs(checkedOutBranches(repoRoot), localBranches(repoRoot), remoteBranches(repoRoot))

    internal fun buildBranchRefs(
        checkedOut: Set<String>,
        locals: List<String>,
        remotes: List<String>,
    ): List<BranchRef> {
        val localSet = locals.toSet()
        val localRefs = locals.sorted().map {
            BranchRef(name = it, isRemote = false, remote = null, localName = it, isCheckedOut = it in checkedOut)
        }
        val remoteRefs = remotes.sorted().mapNotNull { full ->
            val slash = full.indexOf('/')
            if (slash <= 0) return@mapNotNull null
            val remote = full.substring(0, slash)
            val short = full.substring(slash + 1)
            // Skip remote branches that already have a local counterpart — the local entry covers them.
            if (short in localSet) return@mapNotNull null
            BranchRef(name = full, isRemote = true, remote = remote, localName = short, isCheckedOut = false)
        }
        return localRefs + remoteRefs
    }

    /** Remove the worktree at [path]. Run from [repoRoot] (the main worktree, not [path]). */
    fun remove(repoRoot: Path, path: Path, force: Boolean): CommandResult {
        val args = buildList {
            add("worktree"); add("remove")
            if (force) add("--force")
            add(path.toString())
        }
        val result = run(repoRoot, *args.toTypedArray())
        if (result.success) refresh(path)
        return result
    }

    /** Delete a local branch (used after removing its worktree). */
    fun deleteBranch(repoRoot: Path, branch: String, force: Boolean): CommandResult =
        run(repoRoot, "branch", if (force) "-D" else "-d", branch)

    /** True if the worktree at [worktreePath] has uncommitted changes. */
    fun isDirty(worktreePath: Path): Boolean {
        val result = run(worktreePath, "status", "--porcelain")
        return result.success && result.output.isNotBlank()
    }

    /** The current branch name at [repoRoot], or null if detached/unknown. */
    fun currentBranch(repoRoot: Path): String? {
        val result = run(repoRoot, "rev-parse", "--abbrev-ref", "HEAD")
        val name = result.output.trim()
        return if (result.success && name.isNotBlank() && name != "HEAD") name else null
    }

    /** Remote names (e.g. "origin"). */
    fun remotes(repoRoot: Path): List<String> {
        val result = run(repoRoot, "remote")
        if (!result.success) return emptyList()
        return result.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** The remote's default branch as a remote ref (e.g. "origin/master"), read from `origin/HEAD`. */
    fun defaultRemoteBranch(repoRoot: Path): String? {
        val result = run(repoRoot, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD")
        return result.output.trim().takeIf { result.success && it.isNotBlank() }
    }

    /** Fetch one [branch] from [remote], updating its remote-tracking ref. */
    fun fetch(repoRoot: Path, remote: String, branch: String): CommandResult =
        run(repoRoot, "fetch", "--quiet", remote, branch)

    /** Commit id [ref] points at, or null when it doesn't resolve. */
    fun revParse(workDir: Path, ref: String): String? {
        val result = run(workDir, "rev-parse", "--verify", "--quiet", "$ref^{commit}")
        return result.output.trim().takeIf { result.success && it.isNotBlank() }
    }

    /** True when [ref] names a local branch or a remote-tracking branch (not a tag, sha, or HEAD). */
    fun isBranch(workDir: Path, ref: String): Boolean =
        run(workDir, "show-ref", "--verify", "--quiet", "refs/heads/$ref").success ||
            run(workDir, "show-ref", "--verify", "--quiet", "refs/remotes/$ref").success

    fun mergeBase(workDir: Path, a: String, b: String): String? {
        val result = run(workDir, "merge-base", a, b)
        return result.output.trim().takeIf { result.success && it.isNotBlank() }
    }

    /** Commits reachable from [to] but not from [from] (`git rev-list --count from..to`). */
    fun countCommits(workDir: Path, from: String, to: String): Int? {
        val result = run(workDir, "rev-list", "--count", "$from..$to")
        return if (result.success) result.output.trim().toIntOrNull() else null
    }

    /** Replay the current branch's commits after [oldBase] onto [newBase]. */
    fun rebaseOnto(worktree: Path, newBase: String, oldBase: String, autostash: Boolean): CommandResult {
        val args = buildList {
            add("rebase")
            if (autostash) add("--autostash")
            add("--onto"); add(newBase); add(oldBase)
        }
        return run(worktree, *args.toTypedArray())
    }

    fun rebaseAbort(worktree: Path): CommandResult = run(worktree, "rebase", "--abort")

    /** Files under [path] that exist at [to] but not at [from]. */
    fun addedFiles(workDir: Path, from: String, to: String, path: String): List<String> {
        val result = run(workDir, "diff", "--name-only", "--diff-filter=A", from, to, "--", path)
        if (!result.success) return emptyList()
        return result.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** True when some remote has a branch named exactly [branch], i.e. it has been pushed. */
    fun isPublished(workDir: Path, branch: String): Boolean =
        remotes(workDir).any { run(workDir, "show-ref", "--verify", "--quiet", "refs/remotes/$it/$branch").success }

    /** `git config --get-regexp` as key → value. Keys come back lowercased except the subsection. */
    fun configGetRegexp(workDir: Path, pattern: String): Map<String, String> {
        val result = run(workDir, "config", "--get-regexp", pattern)
        if (!result.success) return emptyMap()
        return result.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate { line -> line.substringBefore(' ') to line.substringAfter(' ', "") }
    }

    fun configSet(workDir: Path, key: String, value: String): CommandResult =
        run(workDir, "config", key, value)

    /**
     * Committed and uncommitted changes since [base], plus untracked files. IDE metadata (`.idea/`,
     * which worktree provisioning seeds and many repos don't ignore) is left out.
     */
    fun changedFiles(workDir: Path, base: String): List<FileChange> {
        val diff = run(workDir, "-c", "core.quotePath=false", "diff", "--name-status", "-M", base)
        val tracked = if (diff.success) parseNameStatus(diff.output) else emptyList()
        val others = run(workDir, "-c", "core.quotePath=false", "ls-files", "--others", "--exclude-standard")
        val untracked = if (others.success) {
            others.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { FileChange('A', it) }.toList()
        } else {
            emptyList()
        }
        return (tracked + untracked).filterNot { it.path.startsWith(".idea/") }
    }

    /** [path]'s exact content at [rev], or null when it didn't exist there. */
    fun showFile(workDir: Path, rev: String, path: String): String? {
        val result = run(workDir, "show", "$rev:$path")
        return if (result.success) result.stdout else null
    }

    internal fun parseNameStatus(output: String): List<FileChange> =
        output.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            val code = parts.firstOrNull()?.firstOrNull() ?: return@mapNotNull null
            if (code == 'R' || code == 'C') {
                parts.getOrNull(2)?.let { FileChange(code, it, oldPath = parts[1]) }
            } else {
                parts.getOrNull(1)?.let { FileChange(code, it) }
            }
        }.toList()

    /**
     * Default base directory for new worktrees: a sibling `<repo-name>-worktrees` folder next to
     * the repo, keeping worktrees outside the repository tree and grouped per project. Pass the main
     * worktree, or worktrees made from another worktree's window end up nested under it.
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
