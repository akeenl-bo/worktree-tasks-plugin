package dev.akeen.worktreetasks.jira

import com.google.gson.JsonObject
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Files a ticket in the config file's project. Beyond summary, description, and epic, it asks for
 * whatever the chosen issue type's create screen requires (custom fields such as story points or
 * components included), pre-filled from the config's `fieldDefaults`, else the last values used.
 */
class JiraCreateDialog private constructor(
    private val project: Project,
    private val client: JiraClient,
    private val projectKey: String,
    types: List<IssueType>,
    initialType: IssueType,
    initialFields: List<CreateField>,
    epicKey: String,
    private val myAccountId: String?,
    private val fieldDefaults: Map<String, String>,
) : DialogWrapper(project) {

    class Created(val key: String, val summary: String, val startNow: Boolean)

    private val settings = WorktreeTasksSettings.getInstance()
    private val fieldsByType = mutableMapOf(initialType.id to initialFields)
    private val typeCombo = ComboBox(types.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.name }
        selectedItem = initialType
    }
    private val summaryField = JBTextField()
    private val descriptionArea = JBTextArea(6, 50).apply { lineWrap = true; wrapStyleWord = true }
    private val epicField = JBTextField(epicKey)
    private val assignBox = JBCheckBox("Assign to me", true).apply { isVisible = myAccountId != null }
    private val startBox = JBCheckBox("Start a task for it now", false)
    private val fieldsHolder = JPanel(BorderLayout())
    private var editors: List<FieldEditor> = emptyList()
    private var created: Created? = null

    init {
        title = "Create Jira Ticket"
        setOKButtonText("Create")
        showFields(initialFields)
        typeCombo.addActionListener { typeChanged() }
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Type:") { cell(typeCombo) }
        row("Summary:") { cell(summaryField).align(AlignX.FILL) }
        row("Description:") { cell(JBScrollPane(descriptionArea)).align(AlignX.FILL) }
        row("Parent epic:") { cell(epicField).comment("Blank = no epic") }
        group("Required by Jira") {
            row { cell(fieldsHolder).align(AlignX.FILL) }
        }
        row { cell(assignBox) }
        row { cell(startBox) }
    }.also { it.preferredSize = it.preferredSize.apply { width = 560 } }

    override fun getPreferredFocusedComponent(): JComponent = summaryField

    private fun selectedType(): IssueType = typeCombo.selectedItem as IssueType

    private fun typeChanged() {
        val type = selectedType()
        val fields = fieldsByType[type.id] ?: try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<List<CreateField>, JiraException> { client.createFields(projectKey, type.id) },
                "Loading ${type.name} Fields…",
                false,
                project,
            ).also { fieldsByType[type.id] = it }
        } catch (e: JiraException) {
            setErrorText(e.message)
            return
        }
        setErrorText(null)
        showFields(fields)
        pack()
    }

    private fun showFields(fields: List<CreateField>) {
        editors = fields.filter { it.required && it.id !in HANDLED_FIELDS }.map { FieldEditor(it) }
        fieldsHolder.removeAll()
        fieldsHolder.add(panel {
            if (editors.isEmpty()) row { label("Nothing else is required for this type.") }
            editors.forEach { editor -> row("${editor.field.name}:") { cell(editor.component).align(AlignX.FILL) } }
        }, BorderLayout.CENTER)
        fieldsHolder.revalidate()
        fieldsHolder.repaint()
    }

    override fun doValidate(): ValidationInfo? {
        if (summaryField.text.isBlank()) return ValidationInfo("Enter a summary", summaryField)
        editors.forEach { editor ->
            if (editor.input == null) {
                if (!editor.field.hasDefault) return ValidationInfo("${editor.field.name} has to be set in Jira; this dialog can't fill a ${editor.field.type} field", editor.component)
            } else if (editor.value() == null && !editor.field.hasDefault) {
                return ValidationInfo("${editor.field.name} is required", editor.component)
            }
        }
        return null
    }

    override fun doOKAction() {
        val type = selectedType()
        val summary = summaryField.text.trim()
        val fields = JsonObject().apply {
            add("project", JsonObject().apply { addProperty("key", projectKey) })
            add("issuetype", JsonObject().apply { addProperty("id", type.id) })
            addProperty("summary", summary)
            descriptionArea.text.takeIf { it.isNotBlank() }?.let { add("description", adf(it)) }
            epicField.text.trim().takeIf { it.isNotEmpty() }?.let { epic -> add("parent", JsonObject().apply { addProperty("key", epic) }) }
            if (assignBox.isSelected && myAccountId != null) add("assignee", JsonObject().apply { addProperty("accountId", myAccountId) })
            editors.forEach { editor -> editor.value()?.let { add(editor.field.id, it) } }
        }
        val key = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<String, JiraException> { client.create(fields) },
                "Creating Ticket…",
                false,
                project,
            )
        } catch (e: JiraException) {
            setErrorText(e.message)
            return
        }
        settings.jiraIssueType = type.name
        editors.forEach { editor -> editor.remembered()?.let { settings.jiraLastFieldValues[editor.field.name] = it } }
        created = Created(key, summary, startBox.isSelected)
        super.doOKAction()
    }

    /** One required field's input, pre-filled from the value used last time. */
    private inner class FieldEditor(val field: CreateField) {
        val input: FieldInput? = inputFor(field)
        private val remembered = fieldDefaults[field.name] ?: settings.jiraLastFieldValues[field.name]
        private val text: JBTextField? = when (input) {
            FieldInput.NUMBER, FieldInput.TEXT, FieldInput.LABELS -> JBTextField(remembered.orEmpty())
            else -> null
        }
        private val area: JBTextArea? = if (input == FieldInput.TEXTAREA) JBTextArea(remembered.orEmpty(), 3, 40) else null
        private val combo: ComboBox<AllowedValue>? = if (input == FieldInput.CHOICE || input == FieldInput.MULTI_CHOICE) {
            ComboBox(field.allowed.toTypedArray()).apply {
                selectedItem = field.allowed.firstOrNull { it.label.trim() == remembered?.trim() }
            }
        } else null

        val component: JComponent = text ?: area?.let { JBScrollPane(it) } ?: combo
            ?: JBLabel("Set in Jira (${field.type})").apply { foreground = UIUtil.getErrorForeground() }

        fun value() = fieldValue(field, text?.text ?: area?.text, combo?.selectedItem as? AllowedValue)

        fun remembered(): String? = (combo?.selectedItem as? AllowedValue)?.label ?: (text?.text ?: area?.text)?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** Loads the project's issue types and the default type's fields, then asks. Null when cancelled or unavailable. */
        fun open(project: Project, client: JiraClient, config: JiraConfig, view: JiraView, myAccountId: String?): Created? {
            val settings = WorktreeTasksSettings.getInstance()
            val projectKey = config.project.trim().ifEmpty {
                Messages.showErrorDialog(project, "Set \"project\" in ${JiraConfig.path()} to create tickets.", "Create Jira Ticket")
                return null
            }
            val loaded = try {
                ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    ThrowableComputable<Pair<List<IssueType>, List<CreateField>>, JiraException> {
                        val types = client.issueTypes(projectKey)
                        val type = types.firstOrNull { it.name == settings.jiraIssueType } ?: types.firstOrNull()
                        types to (type?.let { client.createFields(projectKey, it.id) }.orEmpty())
                    },
                    "Loading Jira Fields…",
                    true,
                    project,
                )
            } catch (e: JiraException) {
                Messages.showErrorDialog(project, e.message, "Create Jira Ticket")
                return null
            }
            val (types, fields) = loaded
            val type = types.firstOrNull { it.name == settings.jiraIssueType } ?: types.firstOrNull() ?: run {
                Messages.showErrorDialog(project, "You can't create tickets in $projectKey.", "Create Jira Ticket")
                return null
            }
            val dialog = JiraCreateDialog(project, client, projectKey, types, type, fields, view.epicKey.trim(), myAccountId, config.fieldDefaults)
            return if (dialog.showAndGet()) dialog.created else null
        }
    }
}
