package dev.akeen.worktreetasks.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Provisions a worktree with files that git doesn't carry over but the app needs — gitignored
 * secrets/config like `config/master.key` and `.env`. These live only in the main worktree, so we
 * symlink them into the new worktree (a symlink keeps a shared secret like master.key in sync).
 */
object WorktreeProvisioner {

    private val LOG = Logger.getInstance(WorktreeProvisioner::class.java)

    /**
     * Symlink each configured shared file from the main worktree into [worktreePath].
     * Safe to call off the EDT (it shells out to git to find the main worktree).
     * @return number of links created.
     */
    fun linkSharedFiles(project: Project, worktreePath: Path): Int {
        val rels = WorktreeTasksSettings.getInstance().linkedFiles
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (rels.isEmpty()) return 0

        val main = mainWorktree(project) ?: return 0
        val target = worktreePath.normalize()
        if (main == target) return 0 // don't link the main worktree to itself

        var created = 0
        for (rel in rels) {
            try {
                val src = main.resolve(rel)
                val dst = target.resolve(rel)
                if (Files.exists(src) && Files.notExists(dst, LinkOption.NOFOLLOW_LINKS)) {
                    dst.parent?.let { Files.createDirectories(it) }
                    Files.createSymbolicLink(dst, src)
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dst)
                    created++
                }
            } catch (t: Throwable) {
                LOG.warn("Failed to link shared file '$rel' into $target", t)
            }
        }
        return created
    }

    private fun mainWorktree(project: Project): Path? {
        val root = TaskService.getInstance(project).repoRoot() ?: return null
        return WorktreeGit.list(project, root).firstOrNull { it.isMain }?.path?.normalize()
            ?: root.normalize()
    }
}
