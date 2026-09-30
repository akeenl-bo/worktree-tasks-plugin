package dev.akeen.worktreetasks.jira

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleListCellRenderer
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
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URLEncoder
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * The Jira Board tool window: a saved view's tickets laid out in its board's columns (or by status
 * for a plain JQL view). Start Task picks the selected ticket up as a worktree task, or opens the
 * task already made for it; Create Ticket files a new one with every field Jira requires.
 */
class JiraBoardPanel(private val project: Project, private val toolWindow: ToolWindow) :
    SimpleToolWindowPanel(true, true), Disposable {

    private class BoardData(
        val view: JiraView,
        val client: JiraClient,
        val me: String?,
        val columns: List<Pair<BoardColumn, List<JiraIssue>>>,
        val tasks: Map<String, TicketTask>,
    )

    private class NotConfigured : RuntimeException()
    private class NoSite : RuntimeException()

    private val settings = WorktreeTasksSettings.getInstance()
    private var config = JiraConfig()
    private var configError: String? = null
    private val viewCombo = ComboBox<JiraView>().apply {
        renderer = SimpleListCellRenderer.create("") { it.name }
        addActionListener { if (!updatingViews) viewChanged() }
    }
    private var updatingViews = false
    private val content = JPanel(BorderLayout())
    private val status = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 8)
    }
    private val lists = mutableListOf<JBList<JiraIssue>>()
    private var data: BoardData? = null
    private var selected: JiraIssue? = null
    private var generation = 0
    private var loadedAt = 0L
    @Volatile private var myAccountId: String? = null
    private val pollAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        val group = DefaultActionGroup().apply {
            add(RefreshAction())
            addSeparator()
            add(StartAction())
            add(CreateAction())
            add(OpenInJiraAction())
            addSeparator()
            add(DefaultActionGroup("Views", true).apply {
                templatePresentation.icon = AllIcons.General.GearPlain
                add(AddViewAction())
                add(EditViewAction())
                add(RemoveViewAction())
                addSeparator()
                add(OpenConfigAction())
                add(JiraSettingsAction())
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("JiraBoardToolbar", group, true)
        toolbar.targetComponent = this
        val top = JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply { add(viewCombo) }, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.CENTER)
        }
        setToolbar(top)
        setContent(JPanel(BorderLayout()).apply {
            add(content, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        })

        readConfig()
        loadViews()
        val appBus = ApplicationManager.getApplication().messageBus.connect(this)
        appBus.subscribe(JIRA_CHANGED, JiraChangedListener { ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) reload() } })
        // Edits to the config file (in the IDE, or picked up by a VFS refresh) show up without a manual refresh.
        appBus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val path = JiraConfig.path().toString()
                if (events.any { it.path == path }) ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) reload() }
            }
        })
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun toolWindowShown(shown: ToolWindow) {
                if (shown.id == toolWindow.id && System.currentTimeMillis() - loadedAt > STALE_MS) reload()
            }
        })
        schedulePoll()
        reload()
    }

    override fun dispose() {}

    private fun views(): List<JiraView> = config.views

    /** Re-reads the config file; returns whether it changed. */
    private fun readConfig(): Boolean {
        val before = config
        try {
            config = JiraConfig.load()
            configError = null
        } catch (e: Exception) {
            configError = e.message ?: e.toString()
        }
        return config !== before
    }

    private fun saveConfig(views: List<JiraView>, select: String?) {
        config.views = views.toMutableList()
        try {
            JiraConfig.save(config)
        } catch (e: java.io.IOException) {
            Messages.showErrorDialog(project, "Couldn't write ${JiraConfig.path()}: ${e.message}", "Jira Board")
        }
        loadViews(select)
        viewChanged()
    }

    private fun currentView(): JiraView? = viewCombo.selectedItem as? JiraView

    private fun loadViews(select: String? = settings.jiraSelectedView) {
        updatingViews = true
        viewCombo.removeAllItems()
        views().forEach { viewCombo.addItem(it) }
        viewCombo.selectedItem = views().firstOrNull { it.name == select } ?: views().firstOrNull()
        updatingViews = false
    }

    private fun viewChanged() {
        settings.jiraSelectedView = currentView()?.name.orEmpty()
        data = null
        selected = null
        reload()
    }

    private fun schedulePoll() {
        pollAlarm.cancelAllRequests()
        val minutes = settings.jiraPollMinutes
        val delay = if (minutes > 0) minutes.coerceIn(1, 120) * 60_000 else 60_000
        pollAlarm.addRequest({
            if (settings.jiraPollMinutes > 0 && toolWindow.isVisible) reload()
            schedulePoll()
        }, delay)
    }

    fun reload() {
        if (readConfig()) loadViews(currentView()?.name ?: settings.jiraSelectedView)
        configError?.let { error ->
            showMessage(error, "Open Config File" to ::openConfig)
            return
        }
        val view = currentView() ?: run {
            showMessage("No views yet. Add one, or list them under \"views\" in ${JiraConfig.path()}.", "Add View" to ::addView, "Open Config File" to ::openConfig)
            return
        }
        val token = ++generation
        status.text = "Loading ${view.name}…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { load(view) }
            ApplicationManager.getApplication().invokeLater {
                if (token != generation || project.isDisposed) return@invokeLater
                result.onSuccess { show(it) }.onFailure { showFailure(it) }
            }
        }
    }

    private fun load(view: JiraView): BoardData {
        if (config.siteUrl.isEmpty()) throw NoSite()
        val client = JiraClient.forSite(config.siteUrl) ?: throw NotConfigured()
        val me = myAccountId ?: client.myAccountId().also { myAccountId = it }
        val columns = if (view.boardId > 0) {
            groupByColumn(client.boardColumns(view.boardId), client.boardIssues(view.boardId, view.jql.trim()))
        } else {
            val issues = client.search(view.jql.trim())
            groupByColumn(statusColumns(issues), issues)
        }
        val keys = columns.flatMap { (_, issues) -> issues.map { it.key } }
        val tasks = JiraTaskStarter.repoFor(project, view)?.let { JiraTaskStarter.tasksByKey(it, keys) }.orEmpty()
        return BoardData(view, client, me, columns, tasks)
    }

    private fun show(board: BoardData) {
        val keep = selected?.key
        data = board
        loadedAt = System.currentTimeMillis()
        selected = null
        lists.clear()

        val columnsPanel = ColumnsPanel(board.columns.size)
        board.columns.forEach { (column, issues) ->
            val model = DefaultListModel<JiraIssue>().apply { issues.forEach { addElement(it) } }
            val list = cardList(model)
            lists += list
            columnsPanel.add(JPanel(BorderLayout()).apply {
                preferredSize = Dimension(JBUI.scale(COLUMN_WIDTH), JBUI.scale(100))
                add(JBLabel("${column.name}  ${issues.size}").apply {
                    font = JBUI.Fonts.label().asBold()
                    border = JBUI.Borders.empty(6, 6, 4, 6)
                }, BorderLayout.NORTH)
                add(JBScrollPane(list).apply {
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    border = JBUI.Borders.empty()
                }, BorderLayout.CENTER)
            })
            val index = issues.indexOfFirst { it.key == keep }
            if (index >= 0) {
                list.selectedIndex = index
                selected = issues[index]
            }
        }
        setBody(JBScrollPane(columnsPanel).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
        })
        val count = board.columns.sumOf { it.second.size }
        val time = LocalTime.now().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        status.text = "$count ticket${if (count == 1) "" else "s"} · updated $time"
    }

    private fun showFailure(failure: Throwable) {
        data = null
        selected = null
        when {
            failure is NoSite ->
                showMessage("Set \"site\" in ${JiraConfig.path()} to your Jira Cloud URL.", "Open Config File" to ::openConfig)
            failure is NotConfigured ->
                showMessage("Add your Jira email and API token to see this board.", "Open Settings" to ::openSettings)
            failure is JiraException && failure.status == 401 ->
                showMessage(failure.message.orEmpty(), "Open Settings" to ::openSettings)
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

    private fun cardList(model: DefaultListModel<JiraIssue>): JBList<JiraIssue> = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = CardRenderer()
        emptyText.text = "None"
        addListSelectionListener { event ->
            if (event.valueIsAdjusting) return@addListSelectionListener
            val value = selectedValue ?: return@addListSelectionListener
            selected = value
            lists.filter { it !== this }.forEach { it.clearSelection() }
        }
        // Card heights depend on the column's width (summaries wrap), so drop cached sizes on resize.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                fixedCellHeight = 10
                fixedCellHeight = -1
            }
        })
        val list = this
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = selectOnPopup(e)
            override fun mouseReleased(e: MouseEvent) = selectOnPopup(e)
            private fun selectOnPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val index = list.locationToIndex(e.point)
                if (index >= 0) list.selectedIndex = index
            }
        })
        PopupHandler.installPopupMenu(this, DefaultActionGroup().apply {
            add(StartAction())
            add(OpenInJiraAction())
            add(CopyKeyAction())
        }, "JiraBoardPopup")
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                startSelected()
                return true
            }
        }.installOn(this)
    }

    private fun startSelected() {
        val board = data ?: return
        val issue = selected ?: return
        JiraTaskStarter.start(project, board.view, issue, board.tasks[issue.key], board.client, board.me)
    }

    private fun createTicket() {
        val board = data ?: return
        val created = JiraCreateDialog.open(project, board.client, config, board.view, board.me) ?: return
        fireJiraChanged()
        NotificationGroupManager.getInstance().getNotificationGroup("Worktree Tasks")
            .createNotification("Created ${created.key}", created.summary, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Open in Jira") { BrowserUtil.browse(board.client.browseUrl(created.key)) })
            .notify(project)
        if (!created.startNow) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val issue = runCatching { board.client.issue(created.key) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) JiraTaskStarter.start(project, board.view, issue, null, board.client, board.me)
            }
        }
    }

    private fun addView() {
        val view = JiraViewDialog.edit(project, JiraView.of("New view", repoPath = defaultRepo()), views().map { it.name }) ?: return
        saveConfig(views() + view, view.name)
    }

    private fun editView() {
        val current = currentView() ?: return
        val edited = JiraViewDialog.edit(project, current.copy(), views().filter { it !== current }.map { it.name }) ?: return
        saveConfig(views().map { if (it === current) edited else it }, edited.name)
    }

    private fun removeView() {
        val current = currentView() ?: return
        val confirm = Messages.showYesNoDialog(project, "Remove the view \"${current.name}\"?", "Remove View", Messages.getQuestionIcon())
        if (confirm != Messages.YES) return
        saveConfig(views().filter { it !== current }, null)
    }

    private fun openConfig() {
        val path = try {
            JiraConfig.ensureExists()
        } catch (e: java.io.IOException) {
            Messages.showErrorDialog(project, "Couldn't create ${JiraConfig.path()}: ${e.message}", "Jira Board")
            return
        }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    private fun defaultRepo(): String = currentView()?.repoPath ?: project.basePath.orEmpty()

    private fun openSettings() {
        myAccountId = null
        ShowSettingsUtil.getInstance().showSettingsDialog(project, WorktreeTasksConfigurable::class.java)
        schedulePoll()
        reload()
    }

    private fun boardUrl(board: BoardData): String {
        val site = config.siteUrl
        if (board.view.boardId > 0) return "$site/secure/RapidBoard.jspa?rapidView=${board.view.boardId}"
        return "$site/issues/?jql=" + URLEncoder.encode(board.view.jql, Charsets.UTF_8)
    }

    /** Columns side by side; each scrolls its own cards and the row scrolls sideways when narrow. */
    private class ColumnsPanel(count: Int) : JPanel(GridLayout(1, count.coerceAtLeast(1), JBUI.scale(8), 0)), Scrollable {
        init {
            border = JBUI.Borders.empty(0, 6)
        }
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.HORIZONTAL) visibleRect.width else visibleRect.height
        override fun getScrollableTracksViewportWidth(): Boolean = (parent as? JViewport)?.let { it.width > preferredSize.width } ?: false
        override fun getScrollableTracksViewportHeight() = true
    }

    private inner class CardRenderer : ListCellRenderer<JiraIssue> {
        private val title = SimpleColoredComponent().apply { isOpaque = false }
        private val assignee = SimpleColoredComponent().apply { isOpaque = false }
        private val summary = JBLabel()
        private val card = JPanel(BorderLayout(0, JBUI.scale(3))).apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(title, BorderLayout.CENTER)
                add(assignee, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(summary, BorderLayout.CENTER)
        }
        private val cell = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 2)
            add(card, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out JiraIssue>,
            issue: JiraIssue,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val task = data?.tasks?.get(issue.key)
            val focused = list.hasFocus()
            title.clear()
            assignee.clear()
            title.icon = when {
                task != null -> AllIcons.Vcs.Branch
                issue.typeName.equals("Bug", ignoreCase = true) -> AllIcons.General.Error
                else -> null
            }
            title.append(issue.key, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            title.append("  ${issue.typeName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            issue.points?.let { title.append(" · ${formatPoints(it)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            if (issue.statusName.isNotEmpty() && data?.view?.boardId?.let { it > 0 } == true) {
                title.append(" · ${issue.statusName}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            issue.assigneeName?.let { assignee.append(it, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            title.toolTipText = task?.let { "Task: ${it.name}" }

            val width = (list.width - JBUI.scale(28)).coerceAtLeast(JBUI.scale(120))
            summary.text = "<html><div style='width:${width}px'>${StringUtil.escapeXmlEntities(issue.summary)}</div></html>"
            summary.foreground = UIUtil.getListForeground(isSelected, focused)
            title.foreground = UIUtil.getListForeground(isSelected, focused)
            card.background = if (isSelected) UIUtil.getListSelectionBackground(focused) else UIUtil.getListBackground()
            card.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(if (isSelected) card.background else JBColor.border(), 1),
                JBUI.Borders.empty(6, 8),
            )
            cell.background = list.background
            return cell
        }

        private fun formatPoints(points: Double) = if (points % 1.0 == 0.0) "${points.toLong()} pt" else "$points pt"
    }

    private inner class RefreshAction : AnAction("Refresh", "Reload this view from Jira", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = reload()
    }

    private inner class StartAction :
        AnAction("Start Task", "Pick the selected ticket up as a worktree task, or open its task", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val issue = selected
            e.presentation.isEnabled = issue != null && data != null
            e.presentation.text = if (issue != null && data?.tasks?.containsKey(issue.key) == true) "Open Task" else "Start Task"
        }
        override fun actionPerformed(e: AnActionEvent) = startSelected()
    }

    private inner class CreateAction : AnAction("Create Ticket", "File a new Jira ticket from this view", AllIcons.General.Add) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = data != null
        }
        override fun actionPerformed(e: AnActionEvent) = createTicket()
    }

    private inner class OpenInJiraAction :
        AnAction("Open in Jira", "Open the selected ticket, or this view, in the browser", AllIcons.General.Web) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = data != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            val board = data ?: return
            BrowserUtil.browse(selected?.let { board.client.browseUrl(it.key) } ?: boardUrl(board))
        }
    }

    private inner class CopyKeyAction : AnAction("Copy Key", "Copy the ticket key", AllIcons.Actions.Copy) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            selected?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.key)) }
        }
    }

    private inner class AddViewAction : AnAction("Add View…", "Save a new board or JQL view", AllIcons.General.Add) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = addView()
    }

    private inner class EditViewAction : AnAction("Edit View…", "Change this view's board, JQL, repository, or epic", AllIcons.Actions.Edit) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentView() != null
        }
        override fun actionPerformed(e: AnActionEvent) = editView()
    }

    private inner class RemoveViewAction : AnAction("Remove View", "Delete this saved view", AllIcons.General.Remove) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentView() != null
        }
        override fun actionPerformed(e: AnActionEvent) = removeView()
    }

    private inner class OpenConfigAction :
        AnAction("Open Config File", "Edit the site, project, views, and field defaults as JSON", AllIcons.FileTypes.Json) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = openConfig()
    }

    private inner class JiraSettingsAction : AnAction("Jira Settings…", "Config file, email, API token, and Claude's first prompt", AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = openSettings()
    }

    companion object {
        private const val COLUMN_WIDTH = 240
        private const val STALE_MS = 60_000L
    }
}
