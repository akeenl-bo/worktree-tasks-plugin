package dev.akeen.worktreetasks.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import javax.swing.SwingConstants

/** Task Review starts empty; [dev.akeen.worktreetasks.service.ReviewTourService.open] fills it. */
class ReviewToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val hint = JBLabel("Pick a task in Worktree Tasks and click Review Changes.", SwingConstants.CENTER)
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(hint, "", false))
    }
}
