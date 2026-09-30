package dev.akeen.worktreetasks.jira

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class JiraToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JiraBoardPanel(project, toolWindow)
        val contents = toolWindow.contentManager
        contents.addContent(contents.factory.createContent(panel, "", false).apply { setDisposer(panel) })
    }
}
