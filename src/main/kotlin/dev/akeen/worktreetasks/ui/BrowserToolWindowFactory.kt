package dev.akeen.worktreetasks.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import javax.swing.SwingConstants

class BrowserToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contents = toolWindow.contentManager
        if (!JBCefApp.isSupported()) {
            val hint = JBLabel("The IDE's embedded browser (JCEF) isn't available.", SwingConstants.CENTER)
            contents.addContent(contents.factory.createContent(hint, "", false))
            return
        }
        val panel = BrowserPanel(project)
        contents.addContent(contents.factory.createContent(panel, "", false).apply { setDisposer(panel) })
    }
}
