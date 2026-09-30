package dev.akeen.worktreetasks.service

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.nio.file.Path

/**
 * How the plugin gets the user's attention about a worktree: an IntelliJ popup in whichever window
 * they're in, plus, with the IDE in the background, a macOS banner that opens [worktree] when clicked
 * (in front, the popup is enough, so just the sound).
 */
object TaskAlerts {

    private const val NOTIFICATION_GROUP = "Worktree Tasks"

    /** The window the user is in, if any. */
    fun activeProject(): Project? = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project

    /** True when the user is looking right at [worktree]'s window. */
    fun isLookingAt(worktree: Path): Boolean =
        ApplicationManager.getApplication().isActive &&
            activeProject()?.basePath?.let { Path.of(it).normalize() } == worktree.normalize()

    @RequiresEdt
    fun show(message: String, worktree: Path, type: NotificationType, actions: List<NotificationAction>) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message, type)
        actions.forEach { notification.addAction(it) }
        notification.notify(activeProject())

        val app = ApplicationManager.getApplication()
        val sound = MacNotifier.soundName(WorktreeTasksSettings.getInstance().notificationSound)
        val inBackground = !app.isActive
        app.executeOnPooledThread {
            if (inBackground) MacNotifier.banner(NOTIFICATION_GROUP, message, worktree, sound) else MacNotifier.sound(sound)
        }
    }
}
