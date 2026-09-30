package dev.akeen.worktreetasks.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level, persisted user settings for the plugin.
 */
@State(
    name = "WorktreeTasksSettings",
    storages = [Storage("worktreeTasks.xml")],
)
class WorktreeTasksSettings : PersistentStateComponent<WorktreeTasksSettings> {

    /** Path to the `claude` executable. Defaults to whatever is on PATH. */
    var claudePath: String = "claude"

    /**
     * Base directory under which new worktrees are created. When blank, defaults at runtime to a
     * `<repo>-worktrees` folder next to the main checkout (see
     * [dev.akeen.worktreetasks.git.WorktreeGit.defaultWorktreeBase]).
     */
    var worktreeBaseDir: String = ""

    /**
     * Default parent branch for new tasks. When blank, uses the remote's default branch from
     * `origin/HEAD` (e.g. `origin/master`); see [dev.akeen.worktreetasks.service.ParentSync.defaultBase].
     */
    var defaultBaseBranch: String = ""

    /** Whether to automatically launch `claude` in a terminal when a task window opens. */
    var autoRunClaude: Boolean = true

    /**
     * Optional initial prompt sent to `claude` when a task is first created. Supports the
     * `{task}` placeholder, replaced with the task name. Blank means start with no prompt.
     */
    var initialPromptTemplate: String = ""

    /**
     * macOS system sound played when a task finishes or needs input (a name from
     * `/System/Library/Sounds`, e.g. Pop, Glass, Bottle). Blank = silent.
     */
    var notificationSound: String = "Pop"

    /** Teammates' PRs with this GitHub label get reviewed automatically (as do ones assigned to you or awaiting your review). */
    var prReviewLabel: String = "Team AI"

    /** How often to check GitHub for PRs to review, in minutes. 0 = off. */
    var prPollMinutes: Int = 5

    /** Page the Task Browser tool window shows: the dev server every task's stack serves on. */
    var browserUrl: String = "http://localhost:3000"

    /**
     * Dev-server commands for a task's stack, one shell command per line (`#` lines are comments).
     * Each runs through a login shell in the worktree directory. Active-only: only the active task's
     * stack runs at a time.
     */
    var devServerCommands: String = "ASSETS_COMPILE=true rails s\n./bin/shakapacker-dev-server\nbundle exec sidekiq"

    /** Shell used to launch dev-server commands. Blank = `$SHELL`, then `/bin/zsh`. */
    var devServerShell: String = ""

    /**
     * Shell flags before the command. Default `-i -l -c` = interactive login shell, so version
     * managers (rbenv/asdf/mise) configured in `.zshrc` activate and the project's Ruby is used.
     */
    var devServerShellArgs: String = "-i -l -c"

    /**
     * Command run (in the same shell, after `cd` into the worktree) before every dev-server/setup
     * command, to activate the project's toolchain. Default `nvm use` reads the worktree's `.nvmrc`
     * and switches Node — needed because nvm has no auto-switch hook. It's wrapped so failure (e.g.
     * no `.nvmrc`) is non-fatal. Blank to disable. Use e.g. `mise install` for other managers.
     */
    var devServerCommandPrefix: String = "nvm use"

    /**
     * One-time setup commands run in a new worktree, one per line. A fresh worktree has no
     * `node_modules` (gitignored, per-directory), so dependencies must be installed before servers
     * can boot. Uses the same shell as dev-server commands.
     */
    var setupCommands: String = "yarn install"

    /**
     * Files to symlink from the main worktree into a new worktree, one relative path per line.
     * These are gitignored secrets/config (e.g. `config/master.key`, `.env`) that git doesn't carry
     * across worktrees but the app needs to boot.
     */
    var linkedFiles: String = "config/master.key\n.env"

    /**
     * JSON file holding the Jira Board's site, project, views, and field defaults (see
     * [dev.akeen.worktreetasks.jira.JiraConfig]). Blank = `~/.config/worktree-tasks/jira.json`.
     */
    var jiraConfigPath: String = ""

    /** Atlassian account email, paired with the API token (kept in the password safe) for basic auth. */
    var jiraEmail: String = ""

    /** Whether an API token has been saved to the password safe (which can't be read on the EDT to check). */
    var jiraTokenSaved: Boolean = false

    /** How often a visible Jira Board reloads, in minutes. 0 = only on demand. */
    var jiraPollMinutes: Int = 5

    /**
     * First prompt for a task started from a ticket. `{key}`, `{summary}` and `{url}` are filled in,
     * so Claude reads the ticket through the Jira MCP the way it does when one is pasted.
     */
    var jiraTaskPrompt: String = "{key}: {summary}\n{url}"

    /** Name of the view the Jira Board last showed. */
    var jiraSelectedView: String = ""

    /** Last value used per create-screen field, by field name, for fields the config file has no default for. */
    var jiraLastFieldValues: MutableMap<String, String> = mutableMapOf()

    /** Issue type the create dialog opens on. */
    var jiraIssueType: String = "Story"

    /** Points to deliver each week on the Jira Board's Dashboard. 0 = use the last four weeks' average. */
    var jiraWeeklyGoal: Int = 0

    override fun getState(): WorktreeTasksSettings = this

    override fun loadState(state: WorktreeTasksSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): WorktreeTasksSettings =
            ApplicationManager.getApplication().getService(WorktreeTasksSettings::class.java)
    }
}
