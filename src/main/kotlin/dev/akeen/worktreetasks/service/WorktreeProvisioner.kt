package dev.akeen.worktreetasks.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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

    /**
     * Seed a new worktree's IntelliJ project config from the main worktree so it inherits the Ruby
     * SDK (and any shared run configurations) — otherwise RubyMine opens the worktree with a generic
     * auto-detected SDK and the RSpec gutter/run configs don't work. Copies `.idea/misc.xml` (the
     * project SDK pointer; SDKs are registered IDE-wide so the name resolves) and
     * `.idea/runConfigurations/` if present. MUST run before the worktree project is opened (the IDE
     * rewrites `.idea` on close, so seeding a live project would be clobbered).
     */
    fun seedIdeaConfig(mainWorktree: Path, worktreePath: Path) {
        try {
            if (mainWorktree.normalize() == worktreePath.normalize()) return
            val srcIdea = mainWorktree.resolve(".idea")
            if (!Files.isDirectory(srcIdea)) return
            val dstIdea = worktreePath.resolve(".idea")
            Files.createDirectories(dstIdea)

            val miscSrc = srcIdea.resolve("misc.xml")
            val miscDst = dstIdea.resolve("misc.xml")
            // Only seed the SDK when the worktree doesn't already have a Ruby SDK, so we don't
            // clobber later changes on repeat opens.
            val needsSdk = !Files.isRegularFile(miscDst) || !Files.readString(miscDst).contains("RUBY_SDK")
            if (Files.isRegularFile(miscSrc) && needsSdk) {
                Files.copy(miscSrc, miscDst, StandardCopyOption.REPLACE_EXISTING)
            }
            val runSrc = srcIdea.resolve("runConfigurations")
            if (Files.isDirectory(runSrc)) copyDir(runSrc, dstIdea.resolve("runConfigurations"))
        } catch (t: Throwable) {
            LOG.warn("Failed to seed .idea config into $worktreePath", t)
        }
    }

    private fun copyDir(src: Path, dst: Path) {
        Files.createDirectories(dst)
        Files.list(src).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                Files.copy(file, dst.resolve(file.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun mainWorktree(project: Project): Path? {
        val root = TaskService.getInstance(project).repoRoot() ?: return null
        return WorktreeGit.mainWorktree(root).normalize()
    }
}
