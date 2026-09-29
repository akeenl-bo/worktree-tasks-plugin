package dev.akeen.worktreetasks.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import dev.akeen.worktreetasks.git.WorktreeGit
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Collects inputs for opening an existing branch in a worktree: which branch (local or remote-only),
 * a friendly task name (defaulted from the branch), and the resulting worktree path (auto-derived
 * from the name but editable). Branches already checked out in another worktree are excluded by the
 * caller, so every option here is openable.
 *
 * The branch picker is a search field over a filterable list — repositories can have hundreds of
 * branches, so type-to-filter beats scrolling a combo.
 */
class OpenBranchDialog(
    project: Project,
    private val branches: List<WorktreeGit.BranchRef>,
    private val worktreeBase: Path,
) : DialogWrapper(project) {

    private val searchField = SearchTextField(false)
    private val listModel = CollectionListModel(branches)
    private val branchList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 12
        cellRenderer = SimpleListCellRenderer.create("") { ref ->
            if (ref.isRemote) "${ref.name}  (remote)" else ref.name
        }
        if (!isEmpty) selectedIndex = 0
    }
    private val nameField = JBTextField()
    private val pathField = JBTextField()
    private var nameEditedByUser = false
    private var pathEditedByUser = false

    val selectedBranch: WorktreeGit.BranchRef? get() = branchList.selectedValue
    val taskName: String get() = nameField.text.trim()
    val worktreePath: Path get() = Path.of(pathField.text.trim())

    init {
        title = "Open Branch in Worktree"

        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })
        branchList.addListSelectionListener { if (!it.valueIsAdjusting) syncFromBranch() }

        nameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onEdit()
            override fun removeUpdate(e: DocumentEvent) = onEdit()
            override fun changedUpdate(e: DocumentEvent) = onEdit()
            private fun onEdit() {
                if (nameField.hasFocus()) nameEditedByUser = true
                syncPath()
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
        syncFromBranch()
        init()
    }

    /** Narrow the list to branches whose name contains the query (case-insensitive). */
    private fun applyFilter() {
        val query = searchField.text.trim()
        val previous = branchList.selectedValue
        val matches = if (query.isEmpty()) branches
        else branches.filter { it.name.contains(query, ignoreCase = true) }
        listModel.replaceAll(matches)
        // Keep the prior selection if it survived the filter, else fall back to the first match.
        val keep = matches.indexOf(previous)
        branchList.selectedIndex = if (keep >= 0) keep else if (matches.isEmpty()) -1 else 0
    }

    /** When the selected branch changes, refresh the (unedited) name and path defaults from it. */
    private fun syncFromBranch() {
        val ref = branchList.selectedValue ?: return
        if (!nameEditedByUser) nameField.text = ref.localName
        syncPath()
    }

    private fun syncPath() {
        if (!pathEditedByUser) {
            pathField.text = worktreeBase.resolve(NewTaskDialog.slug(nameField.text.trim())).toString()
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Branch:") { cell(searchField).align(AlignX.FILL) }
        row {
            cell(JBScrollPane(branchList)).align(AlignX.FILL).align(AlignY.FILL)
        }.resizableRow()
        row("Task name:") { cell(nameField).align(AlignX.FILL) }
        row("Worktree path:") { cell(pathField).align(AlignX.FILL) }
    }.also { it.preferredSize = it.preferredSize.apply { width = 480 } }

    override fun getPreferredFocusedComponent(): JComponent = searchField.textEditor

    override fun doValidate(): ValidationInfo? {
        if (selectedBranch == null) return ValidationInfo("Select a branch", searchField)
        if (taskName.isBlank()) return ValidationInfo("Enter a task name", nameField)
        if (NewTaskDialog.slug(taskName).isBlank()) return ValidationInfo("Task name must contain letters or digits", nameField)
        if (pathField.text.isBlank()) return ValidationInfo("Enter a worktree path", pathField)
        if (worktreePath.toFile().exists()) return ValidationInfo("Path already exists", pathField)
        return null
    }
}
