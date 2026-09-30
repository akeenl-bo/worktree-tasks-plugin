package dev.akeen.worktreetasks.ui

import com.intellij.diff.DiffManager
import com.intellij.diff.requests.NoDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.akeen.worktreetasks.service.ReviewFinding
import dev.akeen.worktreetasks.service.ReviewTour
import dev.akeen.worktreetasks.service.ReviewTourService
import dev.akeen.worktreetasks.service.ReviewTours
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.HyperlinkEvent

/**
 * The Task Review tool window's content: the overview, the steps, and the selected step's full
 * explanation (what happens there, before / now, findings, what's next) on the left; that step's diff
 * on the right. A big PR has several [views] (an overview plus one per part) picked at the top; Next at
 * the end of a part carries on into the next, and finished parts get a ✓.
 */
class ReviewPanel(
    private val project: Project,
    private val worktree: Path,
    private val tour: ReviewTour?,
    private val views: List<ReviewView>,
) : JPanel(BorderLayout()), Disposable {

    val title: String = tour?.title ?: worktree.fileName.toString()

    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, this, null)
    private val done = mutableSetOf<Int>()
    private val rows = DefaultListModel<Int>()
    private val stepList = JBList(rows).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RowRenderer()
    }
    private val picker = ComboBox(views.indices.toList().toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { index -> (if (index in done) "✓ " else "") + views[index].label }
    }
    private val counter = JBLabel()
    private val overview = htmlPane()
    private val detail = htmlPane()
    private var viewIndex = -1
    private var current = -1
    private var findingsByStep: Map<Int, List<ReviewFinding>> = emptyMap()
    private val view: ReviewView get() = views[viewIndex]

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

        // Overview, steps and the step's explanation each scroll and resize on their own; IntelliJ
        // remembers the splitter positions.
        val stepsAndDetail = JBSplitter(true, "WorktreeTasks.Review.steps", 0.45f).apply {
            firstComponent = JBScrollPane(stepList)
            secondComponent = JBScrollPane(detail)
        }
        val left = JPanel(BorderLayout()).apply {
            if (views.size > 1) add(picker, BorderLayout.NORTH)
            add(
                JBSplitter(true, "WorktreeTasks.Review.overview", 0.3f).apply {
                    firstComponent = JBScrollPane(overview)
                    secondComponent = stepsAndDetail
                },
                BorderLayout.CENTER,
            )
        }
        val main = JBSplitter(false, "WorktreeTasks.Review.main", 0.32f).apply {
            firstComponent = left
            secondComponent = diffPanel.component
        }
        add(header, BorderLayout.NORTH)
        add(main, BorderLayout.CENTER)

        picker.addActionListener { if (picker.selectedIndex != viewIndex) showView(picker.selectedIndex, 0) }
        stepList.addListSelectionListener {
            if (it.valueIsAdjusting || stepList.selectedIndex < 0) return@addListSelectionListener
            if (view.isPartsOverview) showView(stepList.selectedIndex + 1, 0) else select(stepList.selectedIndex)
        }
        showView(0, 0)
    }

    /** Select step [index] of the current view. */
    fun select(index: Int) {
        if (view.isPartsOverview || index !in view.steps.indices || index == current) return
        current = index
        if (stepList.selectedIndex != index) stepList.selectedIndex = index
        stepList.ensureIndexIsVisible(index)
        diffPanel.setRequest(view.requests[index])
        detail.text = detailHtml(index)
        detail.caretPosition = 0
        val step = view.steps[index]
        counter.text = "${index + 1} / ${view.steps.size}   ${step.label ?: step.file}"
        if (index == view.steps.lastIndex && views.size > 1) {
            done += viewIndex
            picker.repaint()
        }
    }

    override fun dispose() {}

    private fun showView(index: Int, stepIndex: Int) {
        if (index !in views.indices) return
        viewIndex = index
        current = -1
        if (picker.selectedIndex != index) picker.selectedIndex = index
        val withHeadLines = view.steps.mapIndexed { i, step -> step.copy(line = view.headLines.getOrNull(i) ?: step.line) }
        findingsByStep = ReviewTours.findingsByStep(tour?.findings.orEmpty(), withHeadLines)
        rows.clear()
        (if (view.isPartsOverview) views.indices.drop(1) else view.steps.indices).forEach { rows.addElement(it) }
        overview.text = overviewHtml()
        overview.caretPosition = 0
        if (view.isPartsOverview) {
            diffPanel.setRequest(NoDiffRequest.INSTANCE)
            counter.text = "${views.size - 1} parts"
            detail.text = html { append("<p>Pick a part to start, or press Next.</p>") }
        } else {
            select(stepIndex.coerceIn(0, (view.steps.size - 1).coerceAtLeast(0)))
        }
    }

    private fun overviewHtml(): String = html {
        val part = view.part
        if (part != null) {
            append("<b>${esc(view.label)}</b>")
            part.why?.let { append("<p>${esc(it)}</p>") }
            bullets("Flow", part.summary)
            return@html
        }
        append("<b>${esc(title)}</b>")
        if (tour == null || (tour.summary.isEmpty() && tour.sections.isEmpty() && tour.parts.isEmpty())) {
            append("<p>No review tour for this branch; files are listed outside-in, specs last.</p>")
            return@html
        }
        tour.take?.let { append("<p>${esc(it)}</p>") }
        bullets(if (tour.sections.isEmpty() && views.size == 1) null else "Flow", tour.summary)
        tour.sections.forEach { bullets(it.title, it.bullets) }
        if (views.size > 1) bullets("Parts", views.drop(1).map { v -> v.label + (v.part?.why?.let { " — $it" }.orEmpty()) })
    }

    private fun StringBuilder.bullets(heading: String?, items: List<String>) {
        if (items.isEmpty()) return
        heading?.let { append("<p><b>${esc(it)}</b></p>") }
        append("<ul>")
        items.forEach { append("<li>${esc(it)}</li>") }
        append("</ul>")
    }

    private fun detailHtml(index: Int): String = html {
        val step = view.steps[index]
        append("<b>${esc(step.label ?: step.file.substringAfterLast('/'))}</b><br>")
        append("<a href='open'>${esc(step.file)}${step.line?.let { ":$it" }.orEmpty()}</a>")
        if (!view.changed[index]) append(" &nbsp;<i>unchanged, for context</i>")
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
        val next = view.steps.getOrNull(index + 1)?.let { it.label ?: it.file }
            ?: views.getOrNull(viewIndex + 1)?.takeIf { views.size > 1 }?.let { "part ${it.label}" }
        next?.let { append("<p><b>Next →</b> ${esc(it)}</p>") }
    }

    /** Opens the file at the step's line. Part views show a version of it; the editor shows the current file. */
    private fun openCurrentFile() {
        val step = view.steps.getOrNull(current) ?: return
        val file = LocalFileSystem.getInstance().findFileByNioFile(worktree.resolve(step.file)) ?: return
        val line = view.headLines.getOrNull(current) ?: step.line ?: 1
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
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

    private inner class RowRenderer : ColoredListCellRenderer<Int>() {
        override fun customizeCellRenderer(list: JList<out Int>, value: Int, index: Int, selected: Boolean, hasFocus: Boolean) {
            if (view.isPartsOverview) {
                if (value in done) append("✓ ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(views[value].label)
                return
            }
            val step = view.steps[value]
            append("${value + 1}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(step.label ?: step.file.substringAfterLast('/'))
            if (step.label != null) append("  ${step.file.substringAfterLast('/')}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (!view.changed[value]) append("  context", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            findingsByStep[value]?.let { append("  ⚠ ${it.size}", SimpleTextAttributes.ERROR_ATTRIBUTES) }
        }
    }

    private inner class PrevAction : AnAction("Previous Step", "Go to the previous step (or the end of the previous part)", AllIcons.Actions.Back) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = current > 0 || viewIndex > 0
        }
        override fun actionPerformed(e: AnActionEvent) {
            if (current > 0) select(current - 1) else showView(viewIndex - 1, Int.MAX_VALUE)
        }
    }

    private inner class NextAction : AnAction("Next Step", "Go to the next step (or the start of the next part)", AllIcons.Actions.Forward) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (!view.isPartsOverview && current < view.steps.lastIndex) || viewIndex < views.lastIndex
        }
        override fun actionPerformed(e: AnActionEvent) {
            if (!view.isPartsOverview && current < view.steps.lastIndex) select(current + 1) else showView(viewIndex + 1, 0)
        }
    }
}
