package dev.akeen.worktreetasks.startup

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.nio.file.Path

/**
 * Opens a worktree directory as its own IDE project window, or focuses it if already open.
 * Each worktree gets its own window so its Claude agent persists independently — switching windows
 * (macOS native tabs) never tears down another worktree's agent. Must be called on the EDT.
 */
object ProjectLauncher {

    fun findOpen(path: Path): Project? {
        val normalized = path.normalize()
        return ProjectManager.getInstance().openProjects.firstOrNull { project ->
            project.basePath?.let { Path.of(it).normalize() == normalized } ?: false
        }
    }

    @RequiresEdt
    fun openOrFocus(path: Path): Project? {
        findOpen(path)?.let {
            ProjectUtil.focusProjectWindow(it, true)
            return it
        }
        // New frame so macOS groups it as a native tab (and so we never reuse a frame that holds
        // another worktree's live agent).
        return ProjectUtil.openOrImport(path.normalize(), null, /* forceOpenInNewFrame = */ true)
    }
}
