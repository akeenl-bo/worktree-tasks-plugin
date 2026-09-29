package dev.akeen.worktreetasks.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import dev.akeen.worktreetasks.action.NewTaskAction
import dev.akeen.worktreetasks.action.OpenBranchAction
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.service.ClaudeStatus
import dev.akeen.worktreetasks.service.DevServerManager
import dev.akeen.worktreetasks.service.readClaudeStatus
import dev.akeen.worktreetasks.service.TASKS_CHANGED
import dev.akeen.worktreetasks.service.TaskNameStore
import dev.akeen.worktreetasks.service.TaskService
import dev.akeen.worktreetasks.service.TasksChangedListener
import dev.akeen.worktreetasks.service.WorktreeProvisioner
import dev.akeen.worktreetasks.service.WorktreeTask
import dev.akeen.worktreetasks.service.fireTasksChanged
import dev.akeen.worktreetasks.service.fireTasksChangedEverywhere
import dev.akeen.worktreetasks.startup.ClaudeLauncher
import dev.akeen.worktreetasks.startup.LaunchMode
import dev.akeen.worktreetasks.startup.PendingLaunchRegistry
import dev.akeen.worktreetasks.startup.PendingOpen
import dev.akeen.worktreetasks.startup.ProjectLauncher
import dev.akeen.worktreetasks.startup.SetupPolicy
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * Sidebar listing the repository's worktree tasks. Each task opens as its own window (macOS tabs),
 * so its Claude agent persists; switching windows never kills another worktree's agent. Shown in
 * every window, so app-wide state (which task's dev server is active) stays consistent everywhere.
 */
class TaskListPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val model = DefaultListModel<WorktreeTask>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = TaskRenderer()
        emptyText.text = "No worktree tasks yet"
    }

    // Claude status per worktree, polled from the hook-written status files.
    private val statuses = ConcurrentHashMap<Path, ClaudeStatus>()
    @Volatile private var taskPaths: List<Path> = emptyList()
    private val statusAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    init {
        val group = DefaultActionGroup().apply {
            add(NewTaskAction())
            add(OpenBranchAction())
            add(OpenAction())
            add(RunServerAction())
            add(StopServerAction())
            add(RunSetupAction())
            addSeparator()
            add(DeleteWorktreeAction())
            addSeparator()
            add(RefreshAction())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("WorktreeTasksToolbar", group, true)
        toolbar.targetComponent = list
        setToolbar(toolbar.component)
        setContent(JBScrollPane(list))

        // Right-click menu (select the row under the cursor first).
        val popupGroup = DefaultActionGroup().apply {
            add(OpenAction())
            add(RunServerAction())
            add(StopServerAction())
            add(RunSetupAction())
            addSeparator()
            add(DeleteWorktreeAction())
        }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = selectOnPopup(e)
            override fun mouseReleased(e: MouseEvent) = selectOnPopup(e)
            private fun selectOnPopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    val index = list.locationToIndex(e.point)
                    if (index >= 0) list.selectedIndex = index
                }
            }
        })
        PopupHandler.installPopupMenu(list, popupGroup, "WorktreeTasksPopup")

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                selectedTask()?.let { openWorktreeWindow(it) }
                return true
            }
        }.installOn(list)

        project.messageBus.connect(project)
            .subscribe(TASKS_CHANGED, TasksChangedListener { refresh() })

        refresh()
        scheduleStatusPoll()
    }

    private fun scheduleStatusPoll() {
        if (project.isDisposed) return
        statusAlarm.addRequest({
            pollStatuses()
            scheduleStatusPoll()
        }, STATUS_POLL_MS)
    }

    /** Read each worktree's Claude status file (off the EDT); repaint if anything changed. */
    private fun pollStatuses() {
        var changed = false
        val live = taskPaths
        for (path in live) {
            val current = readClaudeStatus(path)
            val previous = if (current == null) statuses.remove(path) else statuses.put(path, current)
            if (previous != current) changed = true
        }
        statuses.keys.retainAll(live.toSet())
        if (changed) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) list.repaint()
            }
        }
    }

    private fun selectedTask(): WorktreeTask? = list.selectedValue

    private fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val tasks = try {
                TaskService.getInstance(project).listTasks()
            } catch (t: Throwable) {
                Logger.getInstance(TaskListPanel::class.java).warn("Failed to list worktree tasks", t)
                emptyList()
            }
            taskPaths = tasks.map { it.path.normalize() }
            ApplicationManager.getApplication().invokeLater {
                val previouslySelected = list.selectedValue?.path
                model.clear()
                tasks.forEach { model.addElement(it) }
                val newIndex = tasks.indexOfFirst { it.path == previouslySelected }
                if (newIndex >= 0) list.selectedIndex = newIndex
            }
        }
    }

    /**
     * Open or focus the task's own window. On first open, [WorktreeOpenActivity] links secrets,
     * installs deps if needed, launches Claude, and (if [activateServer]) starts the dev server.
     */
    private fun openWorktreeWindow(
        // Opening just opens the window + Claude. Setup (yarn install) and the dev server are
        // explicit button actions, so switching tabs never churns anything.
        task: WorktreeTask,
        setup: SetupPolicy = SetupPolicy.SKIP,
        activateServer: Boolean = false,
    ) {
        val alreadyOpen = ProjectLauncher.findOpen(task.path)
        if (alreadyOpen != null) {
            ProjectLauncher.openOrFocus(task.path)
            // Focus the existing Claude terminal (or relaunch if it was closed) without killing a
            // live agent.
            ClaudeLauncher.getInstance(alreadyOpen)
                .focusOrLaunch(task.path, task.name, LaunchMode.CONTINUE)
            if (activateServer) {
                DevServerManager.getInstance(alreadyOpen).activateWithSetupIfNeeded(task.name, task.path)
            }
            return
        }
        // Seed .idea (Ruby SDK / run configs) off the EDT before opening, so RSpec-in-editor works.
        val repoRoot = TaskService.getInstance(project).repoRoot()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (repoRoot != null) {
                WorktreeProvisioner.seedIdeaConfig(WorktreeGit.mainWorktree(repoRoot), task.path)
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                PendingLaunchRegistry.getInstance().put(
                    task.path,
                    PendingOpen(task.name, LaunchMode.CONTINUE, setup, activateServer),
                )
                ProjectLauncher.openOrFocus(task.path)
            }
        }
    }

    /** Whether [task]'s own window currently has its dev server running. */
    private fun serverActive(task: WorktreeTask): Boolean =
        ProjectLauncher.findOpen(task.path)?.let { DevServerManager.getInstance(it).isActive(task.path) } ?: false

    /** Open/focus the worktree window and start its dev server (installing deps first if needed). */
    private fun runServer(task: WorktreeTask) {
        if (DevServerManager.getInstance(project).configuredCommands().isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No dev server commands configured.\nSet them in Settings | Tools | Worktree Tasks.",
                "Run Server",
            )
            return
        }
        openWorktreeWindow(task, setup = SetupPolicy.IF_MISSING, activateServer = true)
    }

    private fun stopServer(task: WorktreeTask) {
        ProjectLauncher.findOpen(task.path)?.let { DevServerManager.getInstance(it).stop() }
    }

    private fun runSetupFor(task: WorktreeTask) {
        val open = ProjectLauncher.findOpen(task.path)
        if (open == null) {
            // Not open: open the window and force setup once it loads.
            openWorktreeWindow(task, setup = SetupPolicy.FORCE)
            return
        }
        object : Task.Backgroundable(project, "Setting up '${task.name}'", false) {
            override fun run(indicator: ProgressIndicator) {
                WorktreeProvisioner.linkSharedFiles(project, task.path)
                ApplicationManager.getApplication().invokeLater {
                    if (!open.isDisposed) DevServerManager.getInstance(open).runSetup(task.name, task.path)
                }
            }
        }.queue()
    }

    private fun deleteWorktree(task: WorktreeTask) {
        if (task.isMain) {
            Messages.showInfoMessage(project, "The main worktree cannot be deleted.", "Delete Worktree")
            return
        }
        val confirm = Messages.showYesNoDialog(
            project,
            "Delete worktree '${task.name}' and remove the task?\n\n${task.path}\n\n" +
                "This deletes the directory and discards any uncommitted changes in it.",
            "Delete Worktree",
            "Delete",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (confirm != Messages.YES) return

        val repoRoot = TaskService.getInstance(project).repoRoot() ?: return

        // Stop its server and close its window/tab before deleting the directory.
        stopServer(task)
        ProjectLauncher.findOpen(task.path)?.let { ProjectManager.getInstance().closeAndDispose(it) }

        // Show a progress indicator. Tie it to a window that stays open (not the one we just
        // closed); if none remains, fall back to a plain pooled thread.
        val host = ProjectManager.getInstance().openProjects.firstOrNull { p ->
            !p.isDisposed && (p.basePath?.let { Path.of(it).normalize() != task.path.normalize() } ?: false)
        }
        if (host != null) {
            object : Task.Backgroundable(host, "Deleting worktree '${task.name}'…", false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    performDelete(repoRoot, task)
                }
            }.queue()
        } else {
            ApplicationManager.getApplication().executeOnPooledThread { performDelete(repoRoot, task) }
        }
    }

    private fun performDelete(repoRoot: Path, task: WorktreeTask) {
        val main = WorktreeGit.mainWorktree(repoRoot)
        // Force, so a provisioned worktree (node_modules, .idea, symlinked secrets, uncommitted
        // work) is reliably deleted rather than refused.
        val result = WorktreeGit.remove(main, task.path, force = true)
        if (!result.success) {
            showErrorOnEdt("git worktree remove failed:\n\n${result.output}")
            return
        }
        TaskNameStore.getInstance().remove(task.path.normalize().toString())

        val branch = task.branch
        if (branch != null && askDeleteBranchOnEdt(branch)) {
            val del = WorktreeGit.deleteBranch(main, branch, force = true)
            if (!del.success) showErrorOnEdt("Branch not deleted:\n\n${del.output}")
        }

        // Refresh every open window's task list now that the worktree is gone.
        ApplicationManager.getApplication().invokeLater { fireTasksChangedEverywhere() }
    }

    private fun askDeleteBranchOnEdt(branch: String): Boolean {
        var answer = false
        ApplicationManager.getApplication().invokeAndWait {
            answer = Messages.showYesNoDialog(
                null,
                "Also delete branch '$branch'? This cannot be undone.",
                "Remove Task",
                Messages.getQuestionIcon(),
            ) == Messages.YES
        }
        return answer
    }

    private fun showErrorOnEdt(message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(null as Project?, message, "Remove Task")
        }
    }

    private inner class OpenAction :
        AnAction("Open", "Open or focus this worktree's window (Claude runs there)", AllIcons.Actions.MenuOpen) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedTask() != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            selectedTask()?.let { openWorktreeWindow(it) }
        }
    }

    private inner class RunServerAction :
        AnAction("Run Server", "Start this worktree's dev server (stops any other)", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val task = selectedTask()
            e.presentation.isEnabled = task != null && !serverActive(task)
        }
        override fun actionPerformed(e: AnActionEvent) {
            selectedTask()?.let { runServer(it) }
        }
    }

    private inner class DeleteWorktreeAction :
        AnAction("Delete Worktree", "Delete this worktree's directory and remove the task", AllIcons.Actions.GC) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val task = selectedTask()
            e.presentation.isEnabled = task != null && !task.isMain
        }
        override fun actionPerformed(e: AnActionEvent) {
            selectedTask()?.let { deleteWorktree(it) }
        }
    }

    private inner class StopServerAction :
        AnAction("Stop Server", "Stop this worktree's dev server", AllIcons.Actions.Suspend) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedTask()?.let { serverActive(it) } ?: false
        }
        override fun actionPerformed(e: AnActionEvent) {
            selectedTask()?.let { stopServer(it) }
        }
    }

    private inner class RunSetupAction :
        AnAction("Install Deps", "Run setup commands (e.g. yarn install) — independent of the dev server", AllIcons.Actions.Download) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedTask() != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            selectedTask()?.let { runSetupFor(it) }
        }
    }

    private inner class RefreshAction :
        AnAction("Refresh", "Reload the task list", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = refresh()
    }

    private inner class TaskRenderer : ColoredListCellRenderer<WorktreeTask>() {
        override fun customizeCellRenderer(
            list: JList<out WorktreeTask>,
            value: WorktreeTask,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            icon = when {
                value.isCurrent -> AllIcons.Actions.Forward
                value.isMain -> AllIcons.Nodes.Folder
                else -> AllIcons.Vcs.Branch
            }
            append(value.name)
            value.branch?.takeIf { it != value.name }?.let {
                append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            if (value.isMain) append("  (main)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (value.isDirty) append("  ●", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.isLocked) append("  🔒", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (serverActive(value)) {
                append("  ▶ serving", SimpleTextAttributes.SYNTHETIC_ATTRIBUTES)
            }
            when (statuses[value.path.normalize()]) {
                ClaudeStatus.WORKING -> append("  ⋯ working", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                ClaudeStatus.NEEDS_INPUT -> append("  ● needs input", SimpleTextAttributes.ERROR_ATTRIBUTES)
                ClaudeStatus.DONE -> append("  ✓ done", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                null -> {}
            }
        }
    }

    companion object {
        private const val STATUS_POLL_MS = 2000
    }
}
