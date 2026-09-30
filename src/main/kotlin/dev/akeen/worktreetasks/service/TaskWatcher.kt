package dev.akeen.worktreetasks.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.Alarm
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import dev.akeen.worktreetasks.startup.ClaudeLauncher
import dev.akeen.worktreetasks.startup.LaunchMode
import dev.akeen.worktreetasks.startup.ProjectLauncher
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Whether moving from [previous] to [current] is worth telling the user about. */
internal fun shouldNotify(previous: ClaudeStatus?, current: ClaudeStatus?): Boolean =
    current != null && current != previous && current != ClaudeStatus.WORKING

/**
 * One app-wide poller behind every window's sidebar. Every [POLL_MS] it reads cheap signals, each
 * open repo's `.git/worktrees` entries and HEADs plus every task's Claude status file, and only
 * reloads the task lists when the worktrees changed. A status moving to done / needs input raises a
 * notification (and a macOS one while the IDE is in the background). Running once instead of per
 * window keeps every window current and never notifies twice.
 */
@Service(Service.Level.APP)
class TaskWatcher : Disposable {

    private data class Worktree(val path: Path, val branch: String?)

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val started = AtomicBoolean(false)
    private val mainByProject = ConcurrentHashMap<Project, Path>()
    private val signatures = ConcurrentHashMap<Path, String>()
    private val worktrees = ConcurrentHashMap<Path, List<Worktree>>()
    private val statuses = ConcurrentHashMap<Path, ClaudeStatus>()
    private val seen: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    fun status(worktree: Path): ClaudeStatus? = statuses[worktree.normalize()]

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread { MacNotifier.prepare() }
        schedule()
    }

    override fun dispose() {}

    private fun schedule() {
        alarm.addRequest({
            try {
                tick()
            } catch (t: Throwable) {
                LOG.warn("Task watcher tick failed", t)
            }
            schedule()
        }, POLL_MS)
    }

    private fun tick() {
        val open = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
        mainByProject.keys.retainAll(open.toSet())
        val mains = open.mapNotNull { project ->
            mainByProject[project] ?: TaskService.getInstance(project).repoRoot()
                ?.let { WorktreeGit.mainWorktree(it).normalize() }
                ?.also { mainByProject[project] = it }
        }.toSet()
        signatures.keys.retainAll(mains)
        worktrees.keys.retainAll(mains)

        var listChanged = false
        for (main in mains) {
            val signature = worktreeSignature(main)
            if (signatures.put(main, signature) != signature) {
                worktrees[main] = WorktreeGit.list(main).filterNot { it.isBare }.map { Worktree(it.path.normalize(), it.branch) }
                listChanged = true
            }
        }

        val all = worktrees.values.flatten()
        var statusChanged = false
        val toNotify = mutableListOf<Pair<Worktree, ClaudeStatus>>()
        for (worktree in all) {
            val current = readClaudeStatus(worktree.path)
            val previous = if (current == null) statuses.remove(worktree.path) else statuses.put(worktree.path, current)
            // The first read of a worktree (IDE start, new task) only records its state.
            val firstSight = seen.add(worktree.path)
            if (previous != current) {
                statusChanged = true
                if (!firstSight && shouldNotify(previous, current)) toNotify += worktree to current!!
            }
        }
        val live = all.map { it.path }.toSet()
        statuses.keys.retainAll(live)
        seen.retainAll(live)

        val app = ApplicationManager.getApplication()
        if (listChanged) app.invokeLater { fireTasksChangedEverywhere() }
        if (statusChanged) app.invokeLater { app.messageBus.syncPublisher(TASK_STATUS_CHANGED).statusChanged() }
        toNotify.forEach { (worktree, status) -> app.invokeLater { notify(worktree, status) } }
    }

    /** Changes when a worktree is added, removed, or switches branch; no git process needed. */
    private fun worktreeSignature(main: Path): String {
        val gitDir = main.resolve(".git")
        val parts = mutableListOf(readSmall(gitDir.resolve("HEAD")))
        val dir = gitDir.resolve("worktrees")
        if (Files.isDirectory(dir)) {
            Files.list(dir).use { entries ->
                entries.sorted().forEach { parts += "${it.fileName}=${readSmall(it.resolve("HEAD"))}" }
            }
        }
        return parts.joinToString("|")
    }

    private fun readSmall(file: Path): String = try {
        if (Files.isRegularFile(file)) Files.readString(file).trim() else ""
    } catch (_: Throwable) {
        ""
    }

    private fun notify(worktree: Worktree, status: ClaudeStatus) {
        val active = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        val app = ApplicationManager.getApplication()
        // Looking right at that task's window: its terminal already shows it.
        if (app.isActive && active?.basePath?.let { Path.of(it).normalize() } == worktree.path) return

        val name = TaskNameStore.getInstance().nameFor(worktree.path.toString())
            ?: worktree.branch
            ?: worktree.path.fileName.toString()
        val message = if (status == ClaudeStatus.NEEDS_INPUT) "$name needs your input" else "$name is done"
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                message,
                if (status == ClaudeStatus.NEEDS_INPUT) NotificationType.WARNING else NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Open") { focusTask(worktree.path, name) })
        if (status == ClaudeStatus.DONE) {
            notification.addAction(NotificationAction.createSimpleExpiring("Review") {
                (ProjectLauncher.findOpen(worktree.path) ?: active)?.let { ReviewTourService.open(it, worktree.path) }
            })
        }
        notification.notify(active)
        // In the background: a macOS banner that opens this task when clicked. In front: the popup
        // above is enough, so just the sound.
        val sound = MacNotifier.soundName(WorktreeTasksSettings.getInstance().notificationSound)
        val inBackground = !app.isActive
        app.executeOnPooledThread {
            if (inBackground) MacNotifier.banner(NOTIFICATION_GROUP, message, worktree.path, sound) else MacNotifier.sound(sound)
        }
    }

    private fun focusTask(path: Path, name: String) {
        val open = ProjectLauncher.findOpen(path)
        ProjectLauncher.openOrFocus(path)
        open?.let { ClaudeLauncher.getInstance(it).focusOrLaunch(path, name, LaunchMode.CONTINUE) }
    }

    companion object {
        private const val POLL_MS = 2000
        private const val NOTIFICATION_GROUP = "Worktree Tasks"
        private val LOG = Logger.getInstance(TaskWatcher::class.java)

        fun getInstance(): TaskWatcher = ApplicationManager.getApplication().getService(TaskWatcher::class.java)
    }
}
