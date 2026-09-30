package dev.akeen.worktreetasks.jira

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class JiraToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contents = toolWindow.contentManager
        val board = JiraBoardPanel(project, toolWindow)
        contents.addContent(contents.factory.createContent(board, "Board", false).apply { setDisposer(board) })
        val dashboard = JiraDashboardPanel(project, toolWindow)
        contents.addContent(contents.factory.createContent(dashboard, "Dashboard", false).apply { setDisposer(dashboard) })
    }
}
