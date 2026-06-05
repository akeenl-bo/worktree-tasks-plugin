package dev.akeen.worktreetasks.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Whether/when to run setup commands when a worktree window opens. */
enum class SetupPolicy { SKIP, IF_MISSING, FORCE }

/**
 * What a freshly-opened worktree window should do on startup, recorded by the New Task / Open /
 * Activate actions and consumed once by [WorktreeOpenActivity].
 */
data class PendingOpen(
    val taskName: String,
    val claudeMode: LaunchMode,
    val setup: SetupPolicy,
    val activateServer: Boolean,
    val initialPrompt: String? = null,
)

/**
 * Hand-off between the actions (which open/focus a worktree window) and the startup activity that
 * runs inside that newly-opened window. Application-scoped because the producing and consuming
 * windows are different projects. Keyed by worktree path; consumed exactly once.
 */
@Service(Service.Level.APP)
class PendingLaunchRegistry {

    private val pending = ConcurrentHashMap<String, PendingOpen>()

    private fun key(path: Path) = path.normalize().toString()

    fun put(path: Path, open: PendingOpen) {
        pending[key(path)] = open
    }

    fun take(path: Path): PendingOpen? = pending.remove(key(path))

    companion object {
        fun getInstance(): PendingLaunchRegistry =
            ApplicationManager.getApplication().getService(PendingLaunchRegistry::class.java)
    }
}
