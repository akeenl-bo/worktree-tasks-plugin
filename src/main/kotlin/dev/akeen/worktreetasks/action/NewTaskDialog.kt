package dev.akeen.worktreetasks.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * An extra step a pre-filled New Task offers as a checkbox (e.g. "Assign to me" for a Jira ticket),
 * run in the background before the worktree is made.
 */
class TaskOption(val label: String, val default: Boolean, val run: () -> Unit)

/** What New Task opens with when it's started from somewhere else, such as a Jira ticket. */
class TaskPrefill(val name: String, val prompt: String?, val options: List<TaskOption> = emptyList())

/**
 * Collects the inputs for a new task: a friendly name, the parent branch to stack on (the remote
 * default branch, another task's branch, or any typed ref), and the resulting worktree path
 * (auto-derived from the name but editable).
 */
class NewTaskDialog(
    project: Project,
    defaultBaseBranch: String,
    baseChoices: List<String>,
    private val worktreeBase: Path,
    prefill: TaskPrefill? = null,
) : DialogWrapper(project) {

    private val nameField = JBTextField()
    private val baseBranchField = ComboBox(baseChoices.toTypedArray()).apply {
        isEditable = true
        selectedItem = defaultBaseBranch
    }
    private val pathField = JBTextField()
    private var pathEditedByUser = false
    private val optionBoxes = prefill?.options.orEmpty().map { it to JBCheckBox(it.label, it.default) }

    val taskName: String get() = nameField.text.trim()
    val baseBranch: String get() = baseBranchField.editor.item?.toString()?.trim().orEmpty()
    val branchName: String get() = slug(taskName)
    val worktreePath: Path get() = Path.of(pathField.text.trim())
    val chosenOptions: List<TaskOption> get() = optionBoxes.filter { (_, box) -> box.isSelected }.map { it.first }

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
        prefill?.let { nameField.text = it.name }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Task name:") { cell(nameField).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
        row("Parent branch:") { cell(baseBranchField).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
        row("Worktree path:") { cell(pathField).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
        optionBoxes.forEach { (_, box) -> row { cell(box) } }
    }.also { it.preferredSize = it.preferredSize.apply { width = 480 } }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        if (taskName.isBlank()) return ValidationInfo("Enter a task name", nameField)
        if (slug(taskName).isBlank()) return ValidationInfo("Task name must contain letters or digits", nameField)
        if (baseBranch.isBlank()) return ValidationInfo("Enter a parent branch", baseBranchField)
        if (pathField.text.isBlank()) return ValidationInfo("Enter a worktree path", pathField)
        if (worktreePath.toFile().exists()) return ValidationInfo("Path already exists", pathField)
        return null
    }

    companion object {
        private const val MAX_SLUG = 60

        /** Turn a free-form task name into a filesystem/branch-safe slug, cut at a word so ticket titles stay short. */
        fun slug(name: String): String {
            val full = name.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            if (full.length <= MAX_SLUG) return full
            val cut = full.substring(0, MAX_SLUG + 1).substringBeforeLast('-')
            return cut.ifEmpty { full.substring(0, MAX_SLUG) }.trim('-')
        }
    }
}
