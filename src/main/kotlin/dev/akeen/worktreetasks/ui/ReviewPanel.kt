package dev.akeen.worktreetasks.ui

import com.intellij.diff.DiffManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.akeen.worktreetasks.service.ReviewStep
import dev.akeen.worktreetasks.service.ReviewTour
import dev.akeen.worktreetasks.service.ReviewTourService
import dev.akeen.worktreetasks.service.ReviewTours
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.HyperlinkEvent

/**
 * The Task Review tool window's content: the tour's overview, its steps, and the selected step's full
 * explanation (what happens there, before / now, what's next) on the left; that step's diff on the
 * right. Prev / Next and clicking a step move both together.
 */
class ReviewPanel(
    private val project: Project,
    private val worktree: Path,
    private val tour: ReviewTour?,
    private val steps: List<ReviewStep>,
    private val changed: List<Boolean>,
    private val requests: List<DiffRequest>,
) : JPanel(BorderLayout()), Disposable {

    val title: String = tour?.title ?: worktree.fileName.toString()

    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, this, null)
    private val stepList = JBList(steps.indices.toList()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = StepRenderer()
    }
    private val counter = JBLabel()
    private val detail = htmlPane()
    private val findingsByStep = ReviewTours.findingsByStep(tour?.findings.orEmpty(), steps)
    private var current = -1

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "WorktreeTasksReview",
            DefaultActionGroup(PrevAction(), NextAction(), ReviewTourService.SendCommentsAction(worktree)),
            true,
        )
        toolbar.targetComponent = this
        val header = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(counter.apply { border = JBUI.Borders.emptyLeft(8) }, BorderLayout.CENTER)
        }

        val overview = htmlPane().apply { text = overviewHtml() }
        val stepsPanel = JPanel(BorderLayout()).apply {
            add(overview, BorderLayout.NORTH)
            add(JBScrollPane(stepList), BorderLayout.CENTER)
        }
        val left = JBSplitter(true, 0.5f).apply {
            firstComponent = stepsPanel
            secondComponent = JBScrollPane(detail)
        }
        val main = JBSplitter(false, 0.32f).apply {
            firstComponent = left
            secondComponent = diffPanel.component
        }
        add(header, BorderLayout.NORTH)
        add(main, BorderLayout.CENTER)

        stepList.addListSelectionListener { if (!it.valueIsAdjusting) select(stepList.selectedIndex) }
    }

    fun select(index: Int) {
        if (index !in steps.indices || index == current) return
        current = index
        if (stepList.selectedIndex != index) stepList.selectedIndex = index
        stepList.ensureIndexIsVisible(index)
        diffPanel.setRequest(requests[index])
        detail.text = detailHtml(index)
        detail.caretPosition = 0
        counter.text = "${index + 1} / ${steps.size}   ${steps[index].label ?: steps[index].file}"
    }

    override fun dispose() {}

    private fun overviewHtml(): String = html {
        append("<b>${esc(title)}</b>")
        if (tour == null || (tour.summary.isEmpty() && tour.sections.isEmpty())) {
            append("<p>No review tour for this branch; files are listed outside-in, specs last.</p>")
            return@html
        }
        tour.take?.let { append("<p>${esc(it)}</p>") }
        bullets(if (tour.sections.isEmpty()) null else "Flow", tour.summary)
        tour.sections.forEach { bullets(it.title, it.bullets) }
    }

    private fun StringBuilder.bullets(heading: String?, items: List<String>) {
        if (items.isEmpty()) return
        heading?.let { append("<p><b>${esc(it)}</b></p>") }
        append("<ul>")
        items.forEach { append("<li>${esc(it)}</li>") }
        append("</ul>")
    }

    private fun detailHtml(index: Int): String = html {
        val step = steps[index]
        append("<b>${esc(step.label ?: step.file.substringAfterLast('/'))}</b><br>")
        append("<a href='open'>${esc(step.file)}${step.line?.let { ":$it" }.orEmpty()}</a>")
        if (!changed[index]) append(" &nbsp;<i>unchanged, for context</i>")
        if (step.what == null && step.before == null && step.now == null) {
            append("<p><i>Not in the tour.</i></p>")
        }
        step.what?.let { append("<p><b>What happens</b><br>${esc(it)}</p>") }
        step.before?.let { append("<p><b>Before</b><br>${esc(it)}</p>") }
        step.now?.let { append("<p><b>Now</b><br>${esc(it)}</p>") }
        findingsByStep[index]?.forEach { finding ->
            val where = finding.line?.let { "line $it" } ?: "this file"
            val severity = finding.severity?.let { "${esc(it)} · " }.orEmpty()
            append("<p><b>⚠ Finding</b> ($severity$where)<br>${esc(finding.text)}</p>")
        }
        steps.getOrNull(index + 1)?.let { append("<p><b>Next →</b> ${esc(it.label ?: it.file)}</p>") }
    }

    private fun openCurrentFile() {
        val step = steps.getOrNull(current) ?: return
        val file = LocalFileSystem.getInstance().findFileByNioFile(worktree.resolve(step.file)) ?: return
        OpenFileDescriptor(project, file, ((step.line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun htmlPane() = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = JBUI.Fonts.label()
        border = JBUI.Borders.empty(8)
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) openCurrentFile() }
    }

    private inline fun html(body: StringBuilder.() -> Unit) = buildString {
        append("<html><body>")
        body()
        append("</body></html>")
    }

    private fun esc(text: String) = StringUtil.escapeXmlEntities(text)

    private inner class StepRenderer : ColoredListCellRenderer<Int>() {
        override fun customizeCellRenderer(list: JList<out Int>, value: Int, index: Int, selected: Boolean, hasFocus: Boolean) {
            val step = steps[value]
            append("${value + 1}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(step.label ?: step.file.substringAfterLast('/'))
            if (step.label != null) append("  ${step.file.substringAfterLast('/')}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (!changed[value]) append("  context", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            findingsByStep[value]?.let { append("  ⚠ ${it.size}", SimpleTextAttributes.ERROR_ATTRIBUTES) }
        }
    }

    private inner class PrevAction : AnAction("Previous Step", "Go to the previous step", AllIcons.Actions.Back) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = current > 0
        }
        override fun actionPerformed(e: AnActionEvent) = select(current - 1)
    }

    private inner class NextAction : AnAction("Next Step", "Go to the next step", AllIcons.Actions.Forward) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = current < steps.lastIndex
        }
        override fun actionPerformed(e: AnActionEvent) = select(current + 1)
    }
}
