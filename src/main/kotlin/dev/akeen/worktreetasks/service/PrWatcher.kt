package dev.akeen.worktreetasks.service

import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.Alarm
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** An open PR as `gh pr list --json` reports it. */
data class GhPr(
    val number: Int,
    val title: String,
    val url: String,
    val branch: String,
    val base: String,
    val headSha: String,
    val author: String,
)

/** What one poll means: PRs never seen, known PRs with a new head commit, and known PRs no longer listed. */
internal data class PollPlan(
    val newPrs: List<GhPr>,
    val moved: List<Pair<PrReviewStore.Record, GhPr>>,
    val missing: List<PrReviewStore.Record>,
)

internal fun planPoll(known: List<PrReviewStore.Record>, found: List<GhPr>): PollPlan {
    val byNumber = known.associateBy { it.number }
    val foundNumbers = found.map { it.number }.toSet()
    return PollPlan(
        newPrs = found.filter { it.number !in byNumber },
        moved = found.mapNotNull { pr -> byNumber[pr.number]?.takeIf { it.latestSha != pr.headSha }?.let { it to pr } },
        missing = known.filter { it.number !in foundNumbers },
    )
}

internal fun parseGhPrs(json: String): List<GhPr> =
    runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull()?.mapNotNull { element ->
        val pr = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        GhPr(
            number = pr.get("number")?.asInt ?: return@mapNotNull null,
            title = pr.get("title")?.asString.orEmpty(),
            url = pr.get("url")?.asString.orEmpty(),
            branch = pr.get("headRefName")?.asString ?: return@mapNotNull null,
            base = pr.get("baseRefName")?.asString ?: return@mapNotNull null,
            headSha = pr.get("headRefOid")?.asString.orEmpty(),
            author = pr.get("author")?.takeIf { it.isJsonObject }?.asJsonObject?.get("login")?.asString.orEmpty(),
        )
    }.orEmpty()

/** "git@github.com:owner/repo.git" or "https://github.com/owner/repo" → "owner/repo". */
internal fun githubRepo(remoteUrl: String): String? =
    Regex("github\\.com[:/]([^/]+/[^/]+?)(?:\\.git)?/?$").find(remoteUrl.trim())?.groupValues?.get(1)

/**
 * Watches GitHub for teammates' open PRs that want this user: labeled [WorktreeTasksSettings.prReviewLabel],
 * with a review requested from them, or assigned to them (drafts wait until ready). Each new one is
 * announced and handed to [PrReviewRunner] to pull down (Claude reviews it only on request); new commits on a
 * pulled PR are only flagged; a merged or closed PR offers to delete its review worktree. Polls every
 * [WorktreeTasksSettings.prPollMinutes].
 */
@Service(Service.Level.APP)
class PrWatcher : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (started.compareAndSet(false, true)) schedule(FIRST_POLL_MS)
    }

    /** Poll now, off the EDT (the sidebar's Check PRs button). */
    fun pollNow() {
        ApplicationManager.getApplication().executeOnPooledThread { pollAll() }
    }

    override fun dispose() {}

    private fun schedule(delayMs: Int) {
        alarm.addRequest({
            if (WorktreeTasksSettings.getInstance().prPollMinutes > 0) pollAll()
            schedule(intervalMs())
        }, delayMs)
    }

    private fun intervalMs(): Int = WorktreeTasksSettings.getInstance().prPollMinutes.coerceIn(1, 120) * 60_000

    @Synchronized
    private fun pollAll() {
        try {
            val mains = ProjectManager.getInstance().openProjects
                .filterNot { it.isDisposed }
                .mapNotNull { TaskService.getInstance(it).repoRoot() }
                .map { WorktreeGit.mainWorktree(it).normalize() }
                .distinct()
            mains.forEach { poll(it) }
        } catch (t: Throwable) {
            LOG.warn("PR poll failed", t)
        }
    }

    private fun poll(main: Path) {
        val repo = WorktreeGit.remoteUrl(main, "origin")?.let { githubRepo(it) } ?: return
        val results = queries().map { query ->
            LoginShell.gh(main, "pr", "list", "-R", repo, "--search", query, "--json", FIELDS, "--limit", "50")
        }
        // gh unavailable or signed out: skip rather than treat every known PR as gone.
        if (results.all { it == null }) return
        val found = results.filterNotNull().flatMap { parseGhPrs(it) }.distinctBy { it.number }

        val store = PrReviewStore.getInstance()
        val plan = planPoll(store.open(repo), found)
        for (pr in plan.newPrs) {
            val record = PrReviewStore.Record().apply {
                this.repo = repo
                number = pr.number
                title = pr.title
                author = pr.author
                url = pr.url
                branch = pr.branch
                base = pr.base
                latestSha = pr.headSha
                mainWorktree = main.toString()
            }
            store.add(record)
            announce(record, main)
            PrReviewRunner.getInstance().enqueue(record)
        }
        for ((record, pr) in plan.moved) store.update(record) { latestSha = pr.headSha; title = pr.title }
        for (record in plan.missing) {
            val state = LoginShell.gh(main, "pr", "view", record.number.toString(), "-R", repo, "--json", "state", "--jq", ".state")
            if (state == "MERGED" || state == "CLOSED") {
                store.update(record) { status = PrReviewStatus.CLOSED.name }
                offerCleanup(record, state.lowercase())
            }
        }
        if (plan.newPrs.isNotEmpty() || plan.moved.isNotEmpty() || plan.missing.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater { fireTasksChangedEverywhere() }
        }
    }

    private fun queries(): List<String> {
        val base = "is:open draft:false -author:@me"
        val label = WorktreeTasksSettings.getInstance().prReviewLabel.trim()
        return listOfNotNull(
            label.takeIf { it.isNotEmpty() }?.let { "$base label:\"${it.replace("\"", "")}\"" },
            "$base review-requested:@me",
            "$base assignee:@me",
        )
    }

    private fun announce(record: PrReviewStore.Record, main: Path) = ApplicationManager.getApplication().invokeLater {
        TaskAlerts.show(
            "New PR #${record.number} · ${record.title} — pulling it down…",
            main,
            NotificationType.INFORMATION,
            listOf(NotificationAction.createSimpleExpiring("Open on GitHub") { BrowserUtil.browse(record.url) }),
        )
    }

    private fun offerCleanup(record: PrReviewStore.Record, how: String) {
        val worktree = record.worktree.takeIf { it.isNotEmpty() }?.let { Path.of(it) } ?: return
        ApplicationManager.getApplication().invokeLater {
            TaskAlerts.show(
                "PR #${record.number} was $how. Delete its review worktree?",
                worktree,
                NotificationType.INFORMATION,
                listOf(NotificationAction.createSimpleExpiring("Delete Worktree") { PrReviewRunner.getInstance().deleteWorktree(record) }),
            )
        }
    }

    companion object {
        private const val FIRST_POLL_MS = 30_000
        private const val FIELDS = "number,title,url,headRefName,baseRefName,headRefOid,author"
        private val LOG = Logger.getInstance(PrWatcher::class.java)

        fun getInstance(): PrWatcher = ApplicationManager.getApplication().getService(PrWatcher::class.java)
    }
}
