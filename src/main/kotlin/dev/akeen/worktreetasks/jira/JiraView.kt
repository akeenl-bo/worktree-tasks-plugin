package dev.akeen.worktreetasks.jira

import com.intellij.util.messages.Topic

/**
 * One saved view in the Jira Board window: a board (its columns and filter) or a plain JQL search,
 * plus which repository its tickets are worked in and the epic new tickets go under.
 */
class JiraView {
    var name: String = ""
    /** Agile board id; 0 = plain JQL search grouped by status. */
    var boardId: Int = 0
    /** Extra JQL on top of the board's own filter, or the whole query when there's no board. */
    var jql: String = ""
    /** Main checkout that Start Task makes worktrees in (`~/` allowed). Blank = the window's own repository. */
    var repoPath: String = ""
    /** Parent epic for tickets created from this view. */
    var epicKey: String = ""

    fun copy(): JiraView = JiraView().also {
        it.name = name
        it.boardId = boardId
        it.jql = jql
        it.repoPath = repoPath
        it.epicKey = epicKey
    }

    override fun toString() = name

    companion object {
        fun of(name: String, boardId: Int = 0, jql: String = "", repoPath: String = "", epicKey: String = "") = JiraView().also {
            it.name = name
            it.boardId = boardId
            it.jql = jql
            it.repoPath = repoPath
            it.epicKey = epicKey
        }
    }
}

fun interface JiraChangedListener {
    fun jiraChanged()
}

/** App-wide: a ticket was created, assigned, or moved from inside the IDE, so boards should reload. */
val JIRA_CHANGED: Topic<JiraChangedListener> = Topic.create("Worktree Tasks Jira changed", JiraChangedListener::class.java)

fun fireJiraChanged() {
    com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.syncPublisher(JIRA_CHANGED).jiraChanged()
}
