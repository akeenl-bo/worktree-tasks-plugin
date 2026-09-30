package dev.akeen.worktreetasks.service

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.LocalFileSystem
import dev.akeen.worktreetasks.git.WorktreeGit
import dev.akeen.worktreetasks.startup.ClaudeLauncher
import dev.akeen.worktreetasks.startup.ProjectLauncher
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opens a task's changes as a guided tour in IntelliJ's diff window: one diff per [ReviewStep], titled
 * with its note and scrolled to its line, so next/previous walks the change in the order it runs.
 * The right side is the live file (editable). Each step has Comment and Send Comments to Claude
 * actions in the diff toolbar.
 */
object ReviewTourService {

    private const val NOTIFICATION_GROUP = "Worktree Tasks"
    const val ADDRESS_COMMENTS_PROMPT =
        "Address my review comments in ${ReviewTours.COMMENTS_FILE}, then delete that file and update ${ReviewTours.TOUR_FILE}."

    private class Prepared(val step: ReviewStep, val baseText: String?)

    fun open(project: Project, worktree: Path) {
        object : Task.Backgroundable(project, "Preparing review", false) {
            override fun run(indicator: ProgressIndicator) {
                val branch = WorktreeGit.currentBranch(worktree)
                val parent = branch?.let { TaskParents.get(worktree, it) }
                val parentRef = parent?.ref ?: ParentSync.defaultBase(worktree)
                val base = parent?.forkPoint?.takeIf { WorktreeGit.revParse(worktree, it) != null }
                    ?: WorktreeGit.mergeBase(worktree, "HEAD", parentRef)
                if (base == null) {
                    notify(project, "Can't find where this branch left $parentRef.")
                    return
                }
                val changes = WorktreeGit.changedFiles(worktree, base)
                val tour = ReviewTours.read(worktree)
                val steps = ReviewTours.steps(tour, changes.map { it.path }) {
                    Files.isRegularFile(worktree.resolve(it))
                }
                val overview = ReviewTours.overview(tour, steps)
                if (steps.isEmpty()) {
                    notify(project, "No changes since $parentRef.")
                    return
                }
                val oldPaths = changes.associate { it.path to (it.oldPath ?: it.path) }
                val prepared = steps.map { step ->
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(worktree.resolve(step.file))
                    Prepared(step, WorktreeGit.showFile(worktree, base, oldPaths[step.file] ?: step.file))
                }
                val baseLabel = "$parentRef @ ${base.take(8)}"
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val requests = prepared.mapIndexed { index, p -> request(project, worktree, p, index, prepared.size, baseLabel) }
                    val chain = listOfNotNull(overview?.let { overviewRequest(project, it) }) + requests
                    DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain(chain), DiffDialogHints.FRAME)
                }
            }
        }.queue()
    }

    private fun request(
        project: Project,
        worktree: Path,
        prepared: Prepared,
        index: Int,
        total: Int,
        baseLabel: String,
    ): DiffRequest {
        val factory = DiffContentFactory.getInstance()
        val path = prepared.step.file
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/'))
        val left = prepared.baseText?.takeUnless { fileType.isBinary }?.let { factory.create(project, it, fileType) }
            ?: factory.createEmpty()
        val right = LocalFileSystem.getInstance().findFileByNioFile(worktree.resolve(path))
            ?.let { factory.create(project, it) }
            ?: factory.createEmpty()
        val title = "${index + 1}/$total  $path" + (prepared.step.note?.let { " — $it" } ?: "")
        return SimpleDiffRequest(title, left, right, baseLabel, "Working tree").apply {
            prepared.step.line?.let { putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(Side.RIGHT, (it - 1).coerceAtLeast(0))) }
            putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(CommentAction(worktree, path), SendCommentsAction(worktree)))
        }
    }

    /** A read-only, single-pane page of the tour's flow bullets and steps, shown before step 1. */
    private fun overviewRequest(project: Project, text: String): DiffRequest {
        val content = DiffContentFactory.getInstance()
            .create(project, text, FileTypeManager.getInstance().getFileTypeByExtension("md"))
        return SimpleDiffRequest("Overview", content, content, null, null).apply {
            putUserData(DiffUserDataKeysEx.FORCE_DIFF_TOOL, UnifiedDiffTool.INSTANCE)
            putUserData(DiffUserDataKeysEx.DISABLE_CONTENTS_EQUALS_NOTIFICATION, true)
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        }
    }

    private fun notify(project: Project, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(content, NotificationType.INFORMATION)
            .notify(project)
    }

    /** Adds `file:line — comment` (line from the caret) to the task's review comments file. */
    private class CommentAction(private val worktree: Path, private val file: String) :
        AnAction("Comment", "Leave a review comment on the caret's line for Claude", AllIcons.General.Balloon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val line = e.getData(CommonDataKeys.EDITOR)?.caretModel?.logicalPosition?.line?.plus(1)
            val where = if (line != null) "$file:$line" else file
            val text = Messages.showMultilineInputDialog(project, "Comment on $where", "Review Comment", null, null, null)
                ?.trim()
            if (!text.isNullOrEmpty()) ReviewTours.appendComment(worktree, where, text)
        }
    }

    /** Types [ADDRESS_COMMENTS_PROMPT] into the task's running Claude session. */
    private class SendCommentsAction(private val worktree: Path) :
        AnAction("Send Comments to Claude", "Ask this task's Claude session to address the review comments", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = ReviewTours.hasComments(worktree)
        }
        override fun actionPerformed(e: AnActionEvent) {
            val taskProject = ProjectLauncher.findOpen(worktree) ?: e.project ?: return
            if (ClaudeLauncher.getInstance(taskProject).sendPrompt(ADDRESS_COMMENTS_PROMPT)) {
                ProjectLauncher.openOrFocus(worktree)
            } else {
                notify(taskProject, "No Claude session is running in this task's window. Open the task, then send again.")
            }
        }
    }
}
