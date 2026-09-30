package dev.akeen.worktreetasks.jira

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Edits one saved Jira Board view. */
class JiraViewDialog private constructor(
    project: Project,
    private val view: JiraView,
    private val takenNames: List<String>,
) : DialogWrapper(project) {

    private val nameField = JBTextField(view.name)
    private val boardField = JBTextField(if (view.boardId > 0) view.boardId.toString() else "")
    private val jqlArea = JBTextArea(view.jql, 3, 50).apply { lineWrap = true; wrapStyleWord = true }
    private val repoField = TextFieldWithBrowseButton().apply {
        text = view.repoPath
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Repository for This View"))
    }
    private val epicField = JBTextField(view.epicKey)

    init {
        title = "Jira View"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("Board id:") {
            cell(boardField).comment("The number after <code>/boards/</code> in the board's URL. Blank = a plain JQL search, grouped by status.")
        }
        row("JQL:") {
            cell(JBScrollPane(jqlArea)).align(AlignX.FILL)
                .comment("With a board, narrows its filter (e.g. <code>assignee = currentUser()</code>); blank shows the whole board.")
        }
        row("Repository:") {
            cell(repoField).align(AlignX.FILL).comment("Checkout Start Task makes worktrees in (<code>~/</code> works). Blank = this window's repository.")
        }
        row("Epic for new tickets:") { cell(epicField).comment("An epic key such as PROJ-123. Blank = none.") }
    }.also { it.preferredSize = it.preferredSize.apply { width = 560 } }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) return ValidationInfo("Enter a name", nameField)
        if (name in takenNames) return ValidationInfo("Another view is named that", nameField)
        val board = boardField.text.trim()
        if (board.isNotEmpty() && (board.toIntOrNull() ?: 0) <= 0) return ValidationInfo("Board id is a number", boardField)
        if (board.isEmpty() && jqlArea.text.isBlank()) return ValidationInfo("Enter a board id or JQL", jqlArea)
        return null
    }

    companion object {
        /** The edited view, or null when cancelled. */
        fun edit(project: Project, view: JiraView, takenNames: List<String>): JiraView? {
            val dialog = JiraViewDialog(project, view, takenNames)
            if (!dialog.showAndGet()) return null
            return view.also {
                it.name = dialog.nameField.text.trim()
                it.boardId = dialog.boardField.text.trim().toIntOrNull() ?: 0
                it.jql = dialog.jqlArea.text.trim()
                it.repoPath = dialog.repoField.text.trim()
                it.epicKey = dialog.epicField.text.trim()
            }
        }
    }
}
