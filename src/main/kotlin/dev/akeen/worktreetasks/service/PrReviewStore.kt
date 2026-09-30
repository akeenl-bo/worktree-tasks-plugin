package dev.akeen.worktreetasks.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Path

/** Where a teammate's PR is in the automatic review pipeline. */
enum class PrReviewStatus { QUEUED, REVIEWING, READY, FAILED, CLOSED }

/** Every PR the watcher has picked up, persisted so restarts don't re-review anything. */
@Service(Service.Level.APP)
@State(name = "WorktreeTasksPrReviews", storages = [Storage("worktreeTasksPrReviews.xml")])
class PrReviewStore : PersistentStateComponent<PrReviewStore.State> {

    class Record {
        var repo: String = ""
        var number: Int = 0
        var title: String = ""
        var author: String = ""
        var url: String = ""
        var branch: String = ""
        var base: String = ""
        /** Head commit the watcher last saw on GitHub. */
        var latestSha: String = ""
        /** Head commit the last finished review looked at. */
        var reviewedSha: String = ""
        var worktree: String = ""
        /** The repo's main worktree, where the review worktree is created from. */
        var mainWorktree: String = ""
        var status: String = PrReviewStatus.QUEUED.name

        val reviewStatus: PrReviewStatus get() = runCatching { PrReviewStatus.valueOf(status) }.getOrDefault(PrReviewStatus.QUEUED)

        /** A finished review is older than what's on GitHub now. */
        val hasNewCommits: Boolean get() = reviewStatus == PrReviewStatus.READY && latestSha.isNotEmpty() && latestSha != reviewedSha
    }

    class State {
        var records: MutableList<Record> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    @Synchronized
    fun find(repo: String, number: Int): Record? = state.records.firstOrNull { it.repo == repo && it.number == number }

    @Synchronized
    fun forWorktree(path: Path): Record? {
        val key = path.normalize().toString()
        return state.records.firstOrNull { it.worktree == key && it.reviewStatus != PrReviewStatus.CLOSED }
    }

    @Synchronized
    fun open(repo: String): List<Record> = state.records.filter { it.repo == repo && it.reviewStatus != PrReviewStatus.CLOSED }

    @Synchronized
    fun add(record: Record) {
        state.records.removeAll { it.repo == record.repo && it.number == record.number }
        state.records += record
    }

    @Synchronized
    fun update(record: Record, change: Record.() -> Unit) = record.change()

    companion object {
        fun getInstance(): PrReviewStore = ApplicationManager.getApplication().getService(PrReviewStore::class.java)
    }
}
