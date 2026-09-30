package dev.akeen.worktreetasks.jira

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.ColorUtil
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.akeen.worktreetasks.settings.WorktreeTasksConfigurable
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.Scrollable
import kotlin.math.roundToInt

/**
 * The Jira Board's Dashboard tab: this week's delivered points against a weekly goal, what's in
 * flight, and which tickets to take next, split into today and the rest of the week. Read-only: it
 * never assigns, moves, or edits a ticket. Picking one up is still Start Task on the Board tab.
 */
class JiraDashboardPanel(private val project: Project, private val toolWindow: ToolWindow) :
    SimpleToolWindowPanel(true, true), Disposable {

    private class DashData(
        val client: JiraClient,
        val siteUrl: String,
        val stats: WeekStats,
        val pool: List<JiraIssue>,
        val boardName: String,
    )

    private class Row(val issue: JiraIssue, val tag: String? = null, val note: String? = null)

    private class NotConfigured(message: String, val inSettings: Boolean) : RuntimeException(message)

    private val settings = WorktreeTasksSettings.getInstance()
    private val content = JPanel(BorderLayout())
    private val status = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 8)
    }
    private val details = JiraDetailsPanel()
    private val split = OnePixelSplitter(false, "WorktreeTasks.JiraDashboard.details", 0.62f).apply { secondComponent = details }
    private val goalSpinner = JBIntSpinner(0, 0, 500)
    private var settingGoal = false
    private val lists = mutableListOf<JBList<Row>>()
    private var data: DashData? = null
    private var picks: ClaudePicks? = null
    private var picksAt: LocalTime? = null
    private var asking = false
    private var generation = 0
    private var loadedAt = 0L
    private val pollAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        val group = DefaultActionGroup().apply {
            add(RefreshAction())
            add(AskClaudeAction())
            addSeparator()
            add(OpenInJiraAction())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("JiraDashboardToolbar", group, true)
        toolbar.targetComponent = this
        setToolbar(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
                add(JBLabel("Weekly goal:"))
                add(goalSpinner)
                add(JBLabel("pt"))
            }, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.CENTER)
        })
        setContent(JPanel(BorderLayout()).apply {
            add(content, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        })
        goalSpinner.addChangeListener {
            if (settingGoal) return@addChangeListener
            settings.jiraWeeklyGoal = goalSpinner.number
            render()
        }

        val appBus = ApplicationManager.getApplication().messageBus.connect(this)
        appBus.subscribe(JIRA_CHANGED, JiraChangedListener { ApplicationManager.getApplication().invokeLater { if (!project.isDisposed && isShowing) reload() } })
        appBus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val path = JiraConfig.path().toString()
                if (events.any { it.path == path }) ApplicationManager.getApplication().invokeLater { if (!project.isDisposed && isShowing) reload() }
            }
        })
        // Load when the tab is first shown (and again when it's shown after going stale), not at IDE start.
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing &&
                System.currentTimeMillis() - loadedAt > STALE_MS
            ) {
                reload()
            }
        }
        schedulePoll()
        showMessage("Loading…")
    }

    override fun dispose() {}

    private fun schedulePoll() {
        pollAlarm.cancelAllRequests()
        val minutes = settings.jiraPollMinutes
        pollAlarm.addRequest({
            if (settings.jiraPollMinutes > 0 && toolWindow.isVisible && isShowing) reload()
            schedulePoll()
        }, if (minutes > 0) minutes.coerceIn(1, 120) * 60_000 else 60_000)
    }

    fun reload() {
        val token = ++generation
        loadedAt = System.currentTimeMillis()
        status.text = "Loading this week…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { load() }
            ApplicationManager.getApplication().invokeLater {
                if (token != generation || project.isDisposed) return@invokeLater
                result.onSuccess { data = it; render() }.onFailure { showFailure(it) }
            }
        }
    }

    private fun load(): DashData {
        val config = JiraConfig.load()
        if (config.siteUrl.isEmpty()) throw NotConfigured("Set \"site\" in ${JiraConfig.path()} to your Jira Cloud URL.", inSettings = false)
        val client = JiraClient.forSite(config.siteUrl, config.pointsField) ?: throw NotConfigured("Add your Jira email and API token to see the dashboard.", inSettings = true)
        val dashboard = config.dashboard
        val groups = StatusGroups(dashboard.deliveredStatuses.ifEmpty { client.doneStatusNames() }, dashboard.reviewStatuses)

        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val since = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(4)
        val inProject = config.project.trim().takeIf { it.isNotEmpty() }?.let { "project = ${quote(it)}" }
        val mine = listOfNotNull(
            inProject,
            "assignee = currentUser()",
            "(statusCategory = \"In Progress\" OR status CHANGED AFTER \"${since.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))}\")",
        ).joinToString(" AND ")
        val stats = weekStats(client.histories(mine), groups, now)

        val pickClause = dashboard.pickStatuses.takeIf { it.isNotEmpty() }
            ?.let { names -> "status in (${names.joinToString(", ") { quote(it) }})" }
            ?: "statusCategory = \"To Do\""
        val forMe = "($pickClause) AND (assignee = currentUser() OR assignee is EMPTY)"
        val board = dashboard.boardId.takeIf { it > 0 } ?: config.views.firstOrNull { it.boardId > 0 }?.boardId
        val candidates = if (board != null) {
            client.boardIssues(board, forMe)
        } else {
            client.search(listOfNotNull(inProject, forMe).joinToString(" AND ") + " ORDER BY Rank")
        }
        val me = runCatching { client.myAccountId() }.getOrNull()
        // Tickets already yours come first; within each group the board's rank order stands.
        val pool = candidates.sortedBy { it.assigneeAccountId == null || it.assigneeAccountId != me }
        return DashData(client, config.siteUrl, stats, pool, board?.let { "board $it" } ?: "the project")
    }

    private fun goal(stats: WeekStats): Double {
        val goal = settings.jiraWeeklyGoal.takeIf { it > 0 } ?: stats.average.roundToInt()
        if (goalSpinner.number != goal) {
            settingGoal = true
            goalSpinner.number = goal
            settingGoal = false
        }
        return goal.toDouble()
    }

    private fun render() {
        val dash = data ?: return
        val stats = dash.stats
        val plan = planWeek(stats, goal(stats), dash.pool)
        val claude = picks
        val byKey = dash.pool.associateBy { it.key }
        val selectedKey = lists.firstNotNullOfOrNull { it.selectedValue }?.issue?.key
        lists.clear()

        val body = WidthTrackingPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 12)
        }
        fun add(component: JComponent, gapAbove: Int = 0) {
            component.alignmentX = LEFT_ALIGNMENT
            if (gapAbove > 0) component.border = JBUI.Borders.merge(component.border, JBUI.Borders.emptyTop(gapAbove), true)
            body.add(component)
        }

        val week = stats.weekStart
        val days = when (stats.workdaysLeft) {
            0 -> "weekend"
            1 -> "last workday"
            else -> "${stats.workdaysLeft} workdays left"
        }
        add(JBLabel("Week of ${week.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${week.format(DateTimeFormatter.ofPattern("MMM d"))} · $days · 4-week average ${formatPoints(stats.average)}").apply {
            foreground = UIUtil.getContextHelpForeground()
            toolTipText = "Delivered in each of the last four weeks, oldest first: " + stats.pastWeeks.reversed().joinToString(", ") { formatPoints(it) }
        })
        add(WeekBar(stats.deliveredPoints, stats.inReview.sumOf { it.points ?: 0.0 }, stats.inProgress.sumOf { it.points ?: 0.0 }, plan.goal), 6)
        add(JBLabel(legend(stats, plan)), 6)

        val claudeToday = claude?.today?.mapNotNull { pick -> byKey[pick.key]?.let { Row(it, "Pick up", pick.why) } }
        val claudeRest = claude?.rest?.mapNotNull { pick -> byKey[pick.key]?.let { Row(it, null, pick.why) } }
        if (claude != null) {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(JBLabel("<html><b>Claude's picks</b>${picksAt?.let { " (${it.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))})" } ?: ""}: ${StringUtil.escapeXmlEntities(claude.summary)}&nbsp;&nbsp;</html>"))
                add(ActionLink("Use board order") { picks = null; picksAt = null; render() })
            }, 12)
        }

        add(sectionHeader("In code review", "${formatPoints(plan.review.sumOf { it.points ?: 0.0 })} waiting on reviewers"), 14)
        add(rowList(plan.review.map { Row(it) }, "Nothing in review."))

        val finish = plan.finish.map { Row(it, "Finish") }
        val todayPicks = claudeToday ?: plan.today.map { Row(it, "Pick up") }
        val todayNote = when {
            stats.workdaysLeft == 0 -> "weekend, nothing planned"
            plan.left <= 0.0 -> "goal covered; finish what's in progress"
            else -> "about ${formatPoints(plan.perDay)} to pick up"
        }
        add(sectionHeader("Today", todayNote), 14)
        add(rowList(finish + todayPicks, "Nothing in progress or to pick up today."))

        val restPicks = claudeRest ?: plan.rest.map { Row(it) }
        val restNote = if (stats.workdaysLeft <= 1) "last workday of the week" else "${stats.workdaysLeft - 1} more workday${if (stats.workdaysLeft - 1 == 1) "" else "s"}"
        add(sectionHeader(if (stats.workdaysLeft == 0) "Still open this week" else "Rest of the week", restNote), 14)
        add(rowList(restPicks, if (plan.left <= 0.0) "The goal is covered by what's delivered and in flight." else "No more ranked To Do tickets on ${dash.boardName}."))

        val day = DateTimeFormatter.ofPattern("EEE")
        add(sectionHeader("Delivered this week", formatPoints(stats.deliveredPoints)), 14)
        add(rowList(stats.delivered.map { (issue, at) -> Row(issue, at.atZone(ZoneId.systemDefault()).format(day)) }, "Nothing merged yet this week."))

        if (plan.unpointed.isNotEmpty()) {
            add(JBLabel("Skipped ${plan.unpointed.size} unpointed ticket${if (plan.unpointed.size == 1) "" else "s"}: ${plan.unpointed.joinToString(", ") { it.key }}").apply {
                foreground = UIUtil.getContextHelpForeground()
            }, 12)
        }
        add(JBLabel("Recommendations only: nothing here assigns or moves a ticket. Start Task on the Board tab picks one up.").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }, 16)

        split.firstComponent = JBScrollPane(body).apply { border = JBUI.Borders.empty() }
        setBody(split)
        lists.forEach { list ->
            val index = (0 until list.model.size).firstOrNull { list.model.getElementAt(it).issue.key == selectedKey }
            if (index != null) list.selectedIndex = index
        }
        if (lists.none { it.selectedValue != null }) details.clear()
        val time = LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        status.text = "Updated $time"
    }

    private fun legend(stats: WeekStats, plan: WeekPlan): String {
        fun swatch(color: JBColor, text: String) = "<span style='color:${ColorUtil.toHtmlColor(color)}'>&#9632;</span>&nbsp;$text"
        val parts = listOf(
            swatch(DELIVERED, "Delivered ${formatPoints(stats.deliveredPoints)}"),
            swatch(REVIEW, "In review ${formatPoints(stats.inReview.sumOf { it.points ?: 0.0 })}"),
            swatch(PROGRESS, "In progress ${formatPoints(stats.inProgress.sumOf { it.points ?: 0.0 })}"),
            swatch(EMPTY, "Left to pick up ${formatPoints(plan.left)} of ${formatPoints(plan.goal)}"),
        )
        return "<html>${parts.joinToString("&nbsp;&nbsp;&nbsp;")}</html>"
    }

    private fun sectionHeader(title: String, note: String): JComponent = SimpleColoredComponent().apply {
        isOpaque = false
        append(title, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null))
        append("   $note", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private fun rowList(rows: List<Row>, empty: String): JComponent {
        if (rows.isEmpty()) return JBLabel(empty).apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(4, 4)
        }
        val list = JBList(DefaultListModel<Row>().apply { rows.forEach { addElement(it) } }).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = RowRenderer()
            isOpaque = false
            visibleRowCount = rows.size
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addListSelectionListener { event ->
                if (event.valueIsAdjusting) return@addListSelectionListener
                val row = selectedValue ?: return@addListSelectionListener
                lists.filter { it !== this }.forEach { it.clearSelection() }
                showDetails(row.issue)
            }
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val dash = data ?: return false
                list.selectedValue?.let { BrowserUtil.browse(dash.client.browseUrl(it.issue.key)) }
                return true
            }
        }.installOn(list)
        lists += list
        return list
    }

    private fun showDetails(issue: JiraIssue) {
        val dash = data ?: return
        details.show(issue, dash.client.browseUrl(issue.key), dash.siteUrl, null) { dash.client.descriptionHtml(issue.key) }
    }

    private fun askClaude() {
        val dash = data ?: return
        val stats = dash.stats
        val plan = planWeek(stats, goal(stats), dash.pool)
        val candidates = dash.pool.filter { (it.points ?: 0.0) > 0.0 }.take(MAX_CANDIDATES)
        if (candidates.isEmpty()) {
            Messages.showInfoMessage(project, "There are no pointed To Do tickets to choose from.", "Ask Claude")
            return
        }
        asking = true
        object : Task.Backgroundable(project, "Asking Claude for this week's picks", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Reading ticket descriptions…"
                val descriptions = runCatching { dash.client.descriptions(candidates.map { it.key }) }.getOrDefault(emptyMap())
                    .mapValues { htmlToText(it.value) }
                indicator.text = "Waiting for Claude…"
                val result = runCatching { ClaudePicker.ask(picksPrompt(stats, plan, candidates, descriptions), candidates.map { it.key }.toSet()) }
                ApplicationManager.getApplication().invokeLater {
                    asking = false
                    if (project.isDisposed || indicator.isCanceled) return@invokeLater
                    result
                        .onSuccess { picks = it; picksAt = LocalTime.now(); render() }
                        .onFailure { Messages.showErrorDialog(project, it.message ?: it.toString(), "Ask Claude") }
                }
            }

            override fun onCancel() {
                asking = false
            }
        }.queue()
    }

    private fun showFailure(failure: Throwable) {
        data = null
        when {
            failure is NotConfigured && failure.inSettings ->
                showMessage(failure.message.orEmpty(), "Open Settings" to ::openSettings)
            failure is JiraException && failure.status == 401 -> showMessage(failure.message.orEmpty(), "Open Settings" to ::openSettings)
            else -> showMessage(failure.message ?: failure.toString(), "Retry" to ::reload)
        }
    }

    private fun showMessage(message: String, vararg links: Pair<String, () -> Unit>) {
        status.text = ""
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(24)
            add(JBLabel("<html>${StringUtil.escapeXmlEntities(message).replace("\n", "<br>")}</html>").apply { alignmentX = Component.LEFT_ALIGNMENT })
            links.forEach { (text, onLink) ->
                add(ActionLink(text) { onLink() }.apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyTop(8)
                })
            }
        }
        setBody(JPanel(BorderLayout()).apply { add(body, BorderLayout.NORTH) })
    }

    private fun setBody(component: Component) {
        content.removeAll()
        content.add(component, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, WorktreeTasksConfigurable::class.java)
        schedulePoll()
        reload()
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Fills the scroll pane's width, so rows fit the pane instead of scrolling sideways. */
    private class WidthTrackingPanel : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    /** Delivered, in review, and in progress as one bar against the goal. */
    private class WeekBar(val delivered: Double, val review: Double, val progress: Double, val goal: Double) : JComponent() {
        init {
            preferredSize = Dimension(JBUI.scale(400), JBUI.scale(14))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(14))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(6)
                g2.color = EMPTY
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                val total = maxOf(goal, delivered + review + progress, 1.0)
                var x = 0
                for ((points, color) in listOf(delivered to DELIVERED, review to REVIEW, progress to PROGRESS)) {
                    val w = (width * points / total).roundToInt()
                    if (w <= 0) continue
                    g2.color = color
                    g2.fillRect(x, 0, w, height)
                    x += w
                }
                if (goal > 0.0 && goal < total) {
                    val marker = (width * goal / total).roundToInt()
                    g2.color = JBColor.foreground()
                    g2.fillRect(marker - 1, 0, JBUI.scale(2), height)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private inner class RowRenderer : ListCellRenderer<Row> {
        private val title = SimpleColoredComponent().apply { isOpaque = false }
        private val note = SimpleColoredComponent().apply { isOpaque = false }
        private val cell = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 4)
            add(title, BorderLayout.NORTH)
            add(note, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(list: JList<out Row>, row: Row, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            val issue = row.issue
            title.clear()
            note.clear()
            row.tag?.let { title.append("$it  ", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            title.append(issue.key, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            title.append("  ${issue.summary}")
            val meta = listOfNotNull(
                issue.points?.let { formatPoints(it) } ?: "unpointed",
                issue.statusName.ifEmpty { null },
                if (issue.assigneeName == null) "unassigned" else null,
            ).joinToString(" · ")
            title.append("   $meta", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            row.note?.takeIf { it.isNotBlank() }?.let { note.append(it, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
            note.isVisible = !row.note.isNullOrBlank()
            val focused = list.hasFocus()
            cell.background = if (isSelected) UIUtil.getListSelectionBackground(focused) else list.background
            cell.isOpaque = isSelected
            title.foreground = UIUtil.getListForeground(isSelected, focused)
            return cell
        }
    }

    private inner class RefreshAction : AnAction("Refresh", "Reload this week from Jira", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = reload()
    }

    private inner class AskClaudeAction :
        AnAction("Ask Claude", "Have Claude suggest today's and the rest of the week's picks (read-only; it can't change Jira)", AllIcons.Actions.Lightning) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = data != null && !asking
        }
        override fun actionPerformed(e: AnActionEvent) = askClaude()
    }

    private inner class OpenInJiraAction : AnAction("Open in Jira", "Open the selected ticket in the browser", AllIcons.General.Web) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = data != null && lists.any { it.selectedValue != null }
        }
        override fun actionPerformed(e: AnActionEvent) {
            val dash = data ?: return
            lists.firstNotNullOfOrNull { it.selectedValue }?.let { BrowserUtil.browse(dash.client.browseUrl(it.issue.key)) }
        }
    }

    companion object {
        private const val STALE_MS = 60_000L
        private const val MAX_CANDIDATES = 25
        private val DELIVERED = JBColor(0x5FB865, 0x57965C)
        private val REVIEW = JBColor(0x3574F0, 0x548AF7)
        private val PROGRESS = JBColor(0xE5A33B, 0xD6A142)
        private val EMPTY = JBColor(0xE4E6EB, 0x43454A)
    }
}
