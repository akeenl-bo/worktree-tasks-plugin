package dev.akeen.worktreetasks.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.akeen.worktreetasks.jira.JiraConfig
import dev.akeen.worktreetasks.jira.JiraCredentials
import dev.akeen.worktreetasks.service.BrowserBridge

/**
 * Settings UI shown under Settings | Tools | Worktree Tasks.
 */
class WorktreeTasksConfigurable : BoundConfigurable("Worktree Tasks") {

    private val settings = WorktreeTasksSettings.getInstance()

    override fun apply() {
        val port = BrowserBridge.debugPort
        super.apply()
        if (BrowserBridge.debugPort == port) return
        val restart = Messages.showYesNoDialog(
            "The remote debugging port applies after the IDE restarts. Restart now?",
            "Task Browser",
            "Restart",
            "Later",
            null,
        )
        if (restart == Messages.YES) {
            ApplicationManager.getApplication().invokeLater({ ApplicationManager.getApplication().restart() }, ModalityState.nonModal())
        }
    }

    override fun createPanel(): DialogPanel = panel {
        row("Claude executable:") {
            textField()
                .bindText(settings::claudePath)
                .comment("Path to the <code>claude</code> CLI. Defaults to <code>claude</code> on PATH.")
        }
        row("Worktree base directory:") {
            textField()
                .bindText(settings::worktreeBaseDir)
                .comment("Where new worktrees are created. Blank = <code>&lt;repo&gt;-worktrees</code> next to the main checkout.")
        }
        row("Default parent branch:") {
            textField()
                .bindText(settings::defaultBaseBranch)
                .comment("Parent for new tasks, fetched first. Blank = the remote's default branch (<code>origin/HEAD</code>, e.g. <code>origin/master</code>).")
        }
        row {
            checkBox("Run claude automatically when a task window opens")
                .bindSelected(settings::autoRunClaude)
        }
        row("Initial prompt template:") {
            textField()
                .bindText(settings::initialPromptTemplate)
                .comment("Optional prompt sent to claude for a new task. <code>{task}</code> = task name.")
        }
        row("Notification sound:") {
            textField()
                .bindText(settings::notificationSound)
                .comment("Played when a task is done or needs input: a macOS sound such as Pop, Glass, Bottle, Tink, Purr. Blank = silent.")
        }
        group("PR Reviews") {
            row("Review PRs labeled:") {
                textField()
                    .bindText(settings::prReviewLabel)
                    .comment("Teammates' open PRs with this label, plus ones assigned to you or awaiting your review, are pulled down and reviewed automatically. Blank = label ignored.")
            }
            row("Check every (minutes):") {
                intTextField(0..120)
                    .bindIntText(settings::prPollMinutes)
                    .comment("0 turns PR watching off.")
            }
        }
        group("Task Browser") {
            row("URL:") {
                textField()
                    .bindText(settings::browserUrl)
                    .comment("Shown in the Task Browser tool window, and reloaded once a task's dev server answers.")
            }
            row("Remote debugging port:") {
                intTextField(-1..65535)
                    .bindIntText(BrowserBridge::debugPort)
                    .comment("Lets each task's Claude drive its Task Browser over CDP. -1 = off. Use a free port such as 9333 (Chrome's own is 9222). Applies after an IDE restart.")
            }
        }
        group("Jira Board") {
            row("Config file:") {
                textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileDescriptor("json").withTitle("Jira Board Config"))
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
                    .bindText(settings::jiraConfigPath)
                    .comment("Site, project, views, and field defaults, as JSON. Blank = <code>${JiraConfig.DEFAULT_PATH}</code>. Point it into a repo to track it; the board's gear menu opens it.")
            }
            row("Email:") {
                textField().bindText(settings::jiraEmail)
            }
            row("API token:") {
                passwordField()
                    .bindText({ "" }, { typed -> if (typed.isNotBlank()) JiraCredentials.save(typed) })
                    .applyToComponent { emptyText.text = if (settings.jiraTokenSaved) "Saved in Keychain; type a new one to replace it" else "" }
                    .comment("Create one at <a href=\"https://id.atlassian.com/manage-profile/security/api-tokens\">id.atlassian.com</a>. Kept in the macOS Keychain, not in settings.")
            }
            row("Reload every (minutes):") {
                intTextField(0..120)
                    .bindIntText(settings::jiraPollMinutes)
                    .comment("While the Jira Board is visible. 0 = only when you refresh.")
            }
            row("Task prompt:") {
                textArea()
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
                    .bindText(settings::jiraTaskPrompt)
                    .applyToComponent { rows = 2 }
                    .comment("Claude's first prompt for a task started from a ticket. <code>{key}</code>, <code>{summary}</code>, <code>{url}</code>.")
            }
        }
        group("Dev Server") {
            row("Commands (one per line):") {
                textArea()
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
                    .bindText(settings::devServerCommands)
                    .applyToComponent { rows = 4 }
                    .comment("Each runs via a login shell in the worktree dir. Active-only: one task's stack at a time.")
            }
            row("Shell:") {
                textField()
                    .bindText(settings::devServerShell)
                    .comment("Blank = <code>\$SHELL</code>, then <code>/bin/zsh</code>.")
            }
            row("Shell args:") {
                textField()
                    .bindText(settings::devServerShellArgs)
                    .comment("<code>-i -l -c</code> = interactive login shell so rbenv/asdf/mise activate.")
            }
            row("Command prefix:") {
                textField()
                    .bindText(settings::devServerCommandPrefix)
                    .comment("Runs before each command (same shell, after cd). <code>nvm use</code> picks Node from <code>.nvmrc</code>.")
            }
        }
        group("Worktree Setup") {
            row("Setup commands (one per line):") {
                textArea()
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
                    .bindText(settings::setupCommands)
                    .applyToComponent { rows = 2 }
                    .comment("Run once in a new worktree (e.g. <code>yarn install</code>) — node_modules isn't shared.")
            }
            row("Linked files (one per line):") {
                textArea()
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
                    .bindText(settings::linkedFiles)
                    .applyToComponent { rows = 2 }
                    .comment("Symlinked from the main worktree (gitignored secrets like <code>config/master.key</code>, <code>.env</code>).")
            }
        }
    }
}
