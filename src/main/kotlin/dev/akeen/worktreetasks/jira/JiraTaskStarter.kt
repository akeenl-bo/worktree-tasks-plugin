package dev.akeen.worktreetasks.jira

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.akeen.worktreetasks.action.NewTaskAction
import dev.akeen.worktreetasks.action.TaskOption
import dev.akeen.worktreetasks.action.TaskPrefill
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.service.TaskNameStore
import dev.akeen.worktreetasks.service.TaskService
import dev.akeen.worktreetasks.service.WorktreeTaskLauncher
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.nio.file.Files
import java.nio.file.Path

/** A worktree already made for a ticket. */
data class TicketTask(val name: String, val path: Path)

/**
 * Picks a ticket up as a worktree task: opens its task if one exists, otherwise New Task pre-filled
 * from the ticket, with "Assign to me" and "Move to In Progress" as checkboxes that run first.
 */
object JiraTaskStarter {

    /** The repository a view's tickets are worked in: its own checkout, or else this window's. */
    fun repoFor(project: Project, view: JiraView): Path? =
        view.repoPath.trim().takeIf { it.isNotEmpty() }?.let { Path.of(JiraConfig.expandHome(it)) }?.takeIf { Files.isDirectory(it) }
            ?: TaskService.getInstance(project).repoRoot()

    /** Worktrees in [repo] by the ticket key their branch or task name carries. Shells out to git. */
    fun tasksByKey(repo: Path, keys: Collection<String>): Map<String, TicketTask> {
        val names = TaskNameStore.getInstance()
        val worktrees = WorktreeGit.list(repo).filterNot { it.isBare || it.isMain }
        return keys.mapNotNull { key ->
            val match = worktrees.firstOrNull { wt ->
                val name = names.nameFor(wt.path.normalize().toString())
                listOfNotNull(wt.branch, name).any { matchesTicket(key, it) }
            } ?: return@mapNotNull null
            val path = match.path.normalize()
            key to TicketTask(names.nameFor(path.toString()) ?: match.branch ?: path.fileName.toString(), path)
        }.toMap()
    }

    /** Must be called on the EDT; [existing] and [myAccountId] come from the board's last load. */
    fun start(project: Project, view: JiraView, issue: JiraIssue, existing: TicketTask?, client: JiraClient, myAccountId: String?) {
        val repo = repoFor(project, view) ?: run {
            Messages.showErrorDialog(project, "No repository for \"${view.name}\". Set one with Edit View.", "Start Task")
            return
        }
        if (existing != null) {
            WorktreeTaskLauncher.openExisting(project, repo, existing.name, existing.path)
            return
        }
        val settings = WorktreeTasksSettings.getInstance()
        val options = buildList {
            if (myAccountId != null && issue.assigneeAccountId != myAccountId) {
                add(TaskOption("Assign to me", true) { client.assign(issue.key, myAccountId) })
            }
            val startStatus = runCatching { JiraConfig.load().startStatus.trim() }.getOrDefault("")
            if (startStatus.isNotEmpty() && issue.statusCategory == "new") {
                add(TaskOption("Move to $startStatus", true) { client.moveTo(issue.key, startStatus) })
            }
        }.map { option -> TaskOption(option.label, option.default) { option.run(); fireJiraChanged() } }
        val prompt = settings.jiraTaskPrompt
            .replace("{key}", issue.key)
            .replace("{summary}", issue.summary)
            .replace("{url}", client.browseUrl(issue.key))
            .takeIf { it.isNotBlank() }
        NewTaskAction.show(project, repo, TaskPrefill("${issue.key} ${issue.summary}", prompt, options))
    }
}
