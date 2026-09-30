package dev.akeen.worktreetasks.service

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.AppExecutorUtil
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import dev.akeen.worktreetasks.startup.ProjectLauncher
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Reviews teammates' PRs one at a time: checks the PR out into its own worktree (parent = the PR's base,
 * so Task Review shows exactly the PR), then runs `claude -p` there with `review/pr-review-prompt.md`.
 * That session is read-only: it may read code, run read-only git/gh, read Jira, and use the
 * code-review / review-tour skills; it writes nothing and replies with the tour, which is saved here. Nothing is
 * posted anywhere. When it finishes the user is notified and Task Review opens on the PR.
 */
@Service(Service.Level.APP)
class PrReviewRunner : Disposable {

    private val queue = AppExecutorUtil.createBoundedApplicationPoolExecutor("Worktree Tasks PR reviews", 1)

    /** Queue [record] for review; unless [force], a head commit that already has a tour is just marked ready. */
    fun enqueue(record: PrReviewStore.Record, force: Boolean = false) {
        PrReviewStore.getInstance().update(record) { status = PrReviewStatus.QUEUED.name }
        refreshSidebars()
        queue.execute { review(record, force) }
    }

    override fun dispose() {}

    private fun review(record: PrReviewStore.Record, force: Boolean) {
        val store = PrReviewStore.getInstance()
        store.update(record) { status = PrReviewStatus.REVIEWING.name }
        refreshSidebars()
        val worktree = try {
            prepareWorktree(record)
        } catch (t: Throwable) {
            LOG.warn("Couldn't check out PR #${record.number}", t)
            null
        }
        if (worktree == null) return failed(record, null, "couldn't check it out")

        val head = WorktreeGit.revParse(worktree, "HEAD").orEmpty()
        if (!force && head.isNotEmpty() && alreadyReviewed(worktree, head)) {
            store.update(record) { status = PrReviewStatus.READY.name; reviewedSha = head }
            refreshSidebars()
            return
        }

        val exit = try {
            runClaude(record, worktree)
        } catch (t: Throwable) {
            LOG.warn("Review of PR #${record.number} failed to run", t)
            -1
        }
        val tour = if (exit == 0) saveTour(worktree) else null
        if (tour == null) return failed(record, worktree, if (exit != 0) "claude exited $exit" else "its reply wasn't a review tour")

        Files.writeString(worktree.resolve(ReviewTours.REVIEWED_SHA_FILE), head)
        store.update(record) {
            status = PrReviewStatus.READY.name
            reviewedSha = head
        }
        Files.writeString(worktree.resolve(ReviewTours.READY_MARKER), record.number.toString())
        refreshSidebars()
        ApplicationManager.getApplication().invokeLater {
            TaskAlerts.show(
                "PR #${record.number} review ready · ${record.title}",
                worktree,
                NotificationType.INFORMATION,
                listOf(
                    NotificationAction.createSimpleExpiring("Open Review") {
                        (ProjectLauncher.findOpen(worktree) ?: TaskAlerts.activeProject())?.let { ReviewTourService.open(it, worktree) }
                    },
                    NotificationAction.createSimpleExpiring("Open on GitHub") { BrowserUtil.browse(record.url) },
                ),
            )
        }
    }

    /** The PR's worktree at its current head, with its parent recorded; null if it can't be checked out. */
    private fun prepareWorktree(record: PrReviewStore.Record): Path? {
        val main = Path.of(record.mainWorktree)
        WorktreeGit.fetch(main, "origin", record.branch)
        WorktreeGit.fetch(main, "origin", record.base)
        val remoteHead = "origin/${record.branch}"
        val base = WorktreeTasksSettings.getInstance().worktreeBaseDir.ifBlank { WorktreeGit.defaultWorktreeBase(main).toString() }
        val worktree = record.worktree.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
            ?: Path.of(base).resolve("pr-${record.number}-${slug(record.branch)}")

        if (!Files.isDirectory(worktree)) {
            // A local branch of its own, so the teammate's branch name never collides with a checkout here.
            val local = "review/pr-${record.number}"
            val added = if (WorktreeGit.isBranch(main, local)) {
                WorktreeGit.addExisting(main, worktree, local)
            } else {
                WorktreeGit.addTracking(main, worktree, local, remoteHead)
            }
            if (!added.success) return null
        }
        if (!WorktreeGit.fastForward(worktree, remoteHead).success) {
            // Force-pushed PR: the review copy follows it, unless someone edited tracked files there.
            if (WorktreeGit.hasTrackedChanges(worktree) || !WorktreeGit.resetHard(worktree, remoteHead).success) return null
        }

        val branch = WorktreeGit.currentBranch(worktree) ?: return null
        val parentRef = "origin/${record.base}"
        TaskParents.set(main, branch, TaskParent(parentRef, WorktreeGit.mergeBase(worktree, "HEAD", parentRef)))
        TaskNameStore.getInstance().put(worktree.normalize().toString(), "PR #${record.number} · ${record.title}")
        WorktreeProvisioner.seedIdeaConfig(main, worktree)
        PrReviewStore.getInstance().update(record) { this.worktree = worktree.normalize().toString() }
        return worktree
    }

    private fun alreadyReviewed(worktree: Path, head: String): Boolean = try {
        val sha = worktree.resolve(ReviewTours.REVIEWED_SHA_FILE)
        Files.isRegularFile(sha) && Files.readString(sha).trim() == head && ReviewTours.read(worktree) != null
    } catch (_: Throwable) {
        false
    }

    /** The session replies with the tour as JSON (it can't write files); save it where Task Review reads it. */
    private fun saveTour(worktree: Path): ReviewTour? {
        val envelope = runCatching {
            com.google.gson.JsonParser.parseString(Files.readString(worktree.resolve(RESULT_FILE))).asJsonObject
        }.getOrNull() ?: return null
        val reply = envelope.get("result")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val json = ReviewTours.extractJsonObject(reply) ?: return null
        val tour = ReviewTours.parse(json)?.takeIf { it.steps.isNotEmpty() } ?: return null
        Files.writeString(worktree.resolve(ReviewTours.TOUR_FILE), json)
        return tour
    }

    private fun runClaude(record: PrReviewStore.Record, worktree: Path): Int {
        val claudeDir = Files.createDirectories(worktree.resolve(".claude"))
        Files.deleteIfExists(worktree.resolve(ReviewTours.TOUR_FILE))
        Files.deleteIfExists(worktree.resolve(ReviewTours.READY_MARKER))
        Files.deleteIfExists(worktree.resolve(ReviewTours.REVIEWED_SHA_FILE))
        val prompt = prompt(record)
        Files.writeString(claudeDir.resolve("review-prompt.md"), prompt)

        val configured = WorktreeTasksSettings.getInstance().claudePath.ifBlank { "claude" }
        val claude = if (configured.startsWith("/")) configured else LoginShell.which(configured) ?: configured
        val command = listOf(
            claude, "-p", prompt,
            "--permission-mode", "dontAsk",
            "--max-turns", "250",
            "--output-format", "json",
            "--allowedTools",
        ) + ALLOWED_TOOLS
        val process = ProcessBuilder(command)
            .directory(worktree.toFile())
            .redirectOutput(worktree.resolve(RESULT_FILE).toFile())
            .redirectError(worktree.resolve(LOG_FILE).toFile())
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .apply {
                environment().clear()
                environment().putAll(LoginShell.claudeEnvironment())
            }
            .start()
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            return -1
        }
        return process.exitValue()
    }

    private fun prompt(record: PrReviewStore.Record): String {
        val template = PrReviewRunner::class.java.getResourceAsStream("/review/pr-review-prompt.md")
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("missing review prompt")
        val jiraKey = Regex("\\b[A-Z][A-Z0-9]+-\\d+\\b").find("${record.title} ${record.branch}")?.value
        val jira = jiraKey?.let { "The ticket is $it." }
            ?: "No Jira key is in the PR title or branch; say so in the Jira section and skip this step."
        return template
            .replace("{number}", record.number.toString())
            .replace("{title}", record.title)
            .replace("{author}", record.author)
            .replace("{url}", record.url)
            .replace("{base}", "origin/${record.base}")
            .replace("{jira}", jira)
    }

    private fun failed(record: PrReviewStore.Record, worktree: Path?, why: String) {
        PrReviewStore.getInstance().update(record) { status = PrReviewStatus.FAILED.name }
        refreshSidebars()
        val target = worktree ?: Path.of(record.mainWorktree)
        ApplicationManager.getApplication().invokeLater {
            val actions = buildList {
                if (worktree != null) {
                    add(NotificationAction.createSimpleExpiring("Open Log") {
                        val project = ProjectLauncher.findOpen(worktree) ?: TaskAlerts.activeProject() ?: return@createSimpleExpiring
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(worktree.resolve(LOG_FILE))
                            ?.let { OpenFileDescriptor(project, it).navigate(true) }
                    })
                }
                add(NotificationAction.createSimpleExpiring("Retry") { enqueue(record) })
            }
            TaskAlerts.show("PR #${record.number} review failed: $why", target, NotificationType.WARNING, actions)
        }
    }

    /** Remove a closed PR's review worktree and its local branch, unless someone edited tracked files there. */
    fun deleteWorktree(record: PrReviewStore.Record) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val worktree = Path.of(record.worktree)
            val main = Path.of(record.mainWorktree)
            if (Files.isDirectory(worktree) && WorktreeGit.hasTrackedChanges(worktree)) return@executeOnPooledThread
            val branch = WorktreeGit.currentBranch(worktree)
            ProjectLauncher.findOpen(worktree)?.let { project ->
                ApplicationManager.getApplication().invokeAndWait {
                    com.intellij.openapi.project.ProjectManager.getInstance().closeAndDispose(project)
                }
            }
            if (WorktreeGit.remove(main, worktree, force = true).success && branch != null) {
                WorktreeGit.deleteBranch(main, branch, force = true)
            }
            TaskNameStore.getInstance().remove(worktree.normalize().toString())
            refreshSidebars()
        }
    }

    private fun refreshSidebars() = ApplicationManager.getApplication().invokeLater { fireTasksChangedEverywhere() }

    private fun slug(branch: String) = branch.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    companion object {
        const val LOG_FILE = ".claude/review-run.log"
        private const val RESULT_FILE = ".claude/review-result.json"
        private const val TIMEOUT_MINUTES = 45L
        private val LOG = Logger.getInstance(PrReviewRunner::class.java)

        /** Everything the review session may do; anything else is refused (`--permission-mode dontAsk`). */
        private val ALLOWED_TOOLS = listOf(
            "Read", "Grep", "Glob", "Skill", "Agent", "ToolSearch", "ReportFindings",
            "Bash(git diff *)", "Bash(git log *)", "Bash(git show *)", "Bash(git merge-base *)",
            "Bash(git rev-parse *)", "Bash(git status *)", "Bash(git blame *)",
            "Bash(gh pr view *)", "Bash(gh pr diff *)", "Bash(ls *)", "Bash(wc *)",
            "mcp__claude_ai_Atlassian_MCP__getAccessibleAtlassianResources",
            "mcp__claude_ai_Atlassian_MCP__getJiraIssue",
            "mcp__claude_ai_Atlassian_MCP__searchJiraIssuesUsingJql",
        )

        fun getInstance(): PrReviewRunner = ApplicationManager.getApplication().getService(PrReviewRunner::class.java)
    }
}
