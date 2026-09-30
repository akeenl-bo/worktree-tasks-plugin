package dev.akeen.worktreetasks.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Settings UI shown under Settings | Tools | Worktree Tasks.
 */
class WorktreeTasksConfigurable : BoundConfigurable("Worktree Tasks") {

    private val settings = WorktreeTasksSettings.getInstance()

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
