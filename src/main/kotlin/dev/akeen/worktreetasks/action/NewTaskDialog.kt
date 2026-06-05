package dev.akeen.worktreetasks.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Collects the inputs for a new task: a friendly name, the base branch to fork from, and the
 * resulting worktree path (auto-derived from the name but editable).
 */
class NewTaskDialog(
    project: Project,
    defaultBaseBranch: String,
    private val worktreeBase: Path,
) : DialogWrapper(project) {

    private val nameField = JBTextField()
    private val baseBranchField = JBTextField(defaultBaseBranch)
    private val pathField = JBTextField()
    private var pathEditedByUser = false

    val taskName: String get() = nameField.text.trim()
    val baseBranch: String get() = baseBranchField.text.trim()
    val branchName: String get() = slug(taskName)
    val worktreePath: Path get() = Path.of(pathField.text.trim())

    init {
        title = "New Worktree Task"
        nameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = sync()
            override fun removeUpdate(e: DocumentEvent) = sync()
            override fun changedUpdate(e: DocumentEvent) = sync()
            private fun sync() {
                if (!pathEditedByUser) {
                    pathField.text = worktreeBase.resolve(slug(nameField.text.trim())).toString()
                }
            }
        })
        pathField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = mark()
            override fun removeUpdate(e: DocumentEvent) = mark()
            override fun changedUpdate(e: DocumentEvent) = mark()
            private fun mark() {
                if (pathField.hasFocus()) pathEditedByUser = true
            }
        })
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Task name:") { cell(nameField).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
        row("Base branch:") { cell(baseBranchField).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
        row("Worktree path:") { cell(pathField).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
    }.also { it.preferredSize = it.preferredSize.apply { width = 480 } }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        if (taskName.isBlank()) return ValidationInfo("Enter a task name", nameField)
        if (slug(taskName).isBlank()) return ValidationInfo("Task name must contain letters or digits", nameField)
        if (baseBranch.isBlank()) return ValidationInfo("Enter a base branch", baseBranchField)
        if (pathField.text.isBlank()) return ValidationInfo("Enter a worktree path", pathField)
        if (worktreePath.toFile().exists()) return ValidationInfo("Path already exists", pathField)
        return null
    }

    companion object {
        /** Turn a free-form task name into a filesystem/branch-safe slug. */
        fun slug(name: String): String =
            name.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
    }
}
