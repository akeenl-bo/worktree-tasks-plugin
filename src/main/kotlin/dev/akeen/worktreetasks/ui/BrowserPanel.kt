package dev.akeen.worktreetasks.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import dev.akeen.worktreetasks.service.BrowserBridge
import dev.akeen.worktreetasks.service.DevServerManager
import dev.akeen.worktreetasks.service.TASKS_CHANGED
import dev.akeen.worktreetasks.service.TaskNameStore
import dev.akeen.worktreetasks.service.TasksChangedListener
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel

/**
 * The Task Browser tool window's content: an embedded Chromium (JCEF) on the configured dev-server URL,
 * with back / forward / reload, an address bar, DevTools, and which task is serving. Only one task's
 * server runs at a time on the shared port, so the label says whose code the page is. When a server
 * starts, the page reloads once the URL answers.
 */
class BrowserPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val worktree = project.basePath?.let { Path.of(it).normalize() }
    private val browser = JBCefBrowser.createBuilder().build().also { Disposer.register(this, it) }
    private val address = JBTextField()
    private val serving = JBLabel()
    private var servingPath: Path? = null
    private var lastPageUrl: String? = null
    private val waits = AtomicInteger()

    @Volatile
    private var canGoBack = false

    @Volatile
    private var canGoForward = false

    @Volatile
    private var disposed = false

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "WorktreeTasksBrowser",
            DefaultActionGroup(BackAction(), ForwardAction(), ReloadAction(), DevToolsAction(), OpenExternallyAction()),
            true,
        )
        toolbar.targetComponent = this
        address.addActionListener { load(address.text) }
        setToolbar(
            JPanel(BorderLayout()).apply {
                add(toolbar.component, BorderLayout.WEST)
                add(address, BorderLayout.CENTER)
                add(serving.apply { border = JBUI.Borders.empty(0, 8) }, BorderLayout.EAST)
            },
        )
        setContent(browser.component)

        // JCEF runs out of process, so CefFrame and CefBrowser getters are blocking RPCs: frame calls made
        // inside these callbacks did nothing, and polling canGoBack() from toolbar updates would block the EDT.
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(cefBrowser: CefBrowser, frame: CefFrame, url: String) {
                ApplicationManager.getApplication().invokeLater {
                    if (disposed) return@invokeLater
                    val page = cefBrowser.url ?: return@invokeLater
                    address.text = page
                    if (page.startsWith("http")) lastPageUrl = page
                }
            }
        }, browser.cefBrowser)

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(cefBrowser: CefBrowser, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                this@BrowserPanel.canGoBack = canGoBack
                this@BrowserPanel.canGoForward = canGoForward
            }

            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                worktree?.let { cefBrowser.executeJavaScript(BrowserBridge.markerScript(it), "", 0) }
            }
        }, browser.cefBrowser)

        worktree?.let { path ->
            JBCefApp.getInstance().getRemoteDebuggingPort { port ->
                if (port != null && port > 0 && !disposed) BrowserBridge.writeEndpoint(path, port)
            }
        }

        project.messageBus.connect(this).subscribe(TASKS_CHANGED, TasksChangedListener { serverChanged() })
        servingPath = activeServer()
        if (servingPath != null) loadWhenUp(homeUrl()) else browser.loadURL(homeUrl())
        updateLabel()
    }

    private fun homeUrl(): String = WorktreeTasksSettings.getInstance().browserUrl.trim()

    private fun load(input: String) {
        val url = input.trim().takeIf { it.isNotEmpty() } ?: return
        browser.loadURL(if ("://" in url) url else "http://$url")
    }

    /** The one worktree whose dev server is running, in whichever window started it. */
    private fun activeServer(): Path? =
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .firstNotNullOfOrNull { DevServerManager.getInstance(it).activeTaskPath() }

    private fun serverChanged() {
        val active = activeServer()
        if (active != null && active != servingPath) loadWhenUp(lastPageUrl ?: homeUrl())
        servingPath = active
        updateLabel()
    }

    private fun updateLabel(waiting: Boolean = false) {
        val path = servingPath
        serving.text = when {
            path == null -> "No server running"
            waiting -> "Waiting for ${taskName(path)}…"
            else -> "Serving ${taskName(path)}"
        }
        serving.toolTipText = path?.toString()
    }

    private fun taskName(path: Path): String =
        TaskNameStore.getInstance().nameFor(path.toString()) ?: path.fileName.toString()

    /** Poll [url] until the server answers (a booting Rails app refuses connections), then load it. */
    private fun loadWhenUp(url: String) {
        val wait = waits.incrementAndGet()
        updateLabel(waiting = true)
        ApplicationManager.getApplication().executeOnPooledThread {
            val deadline = System.currentTimeMillis() + WAIT_FOR_SERVER_MS
            while (wait == waits.get() && !disposed && System.currentTimeMillis() < deadline) {
                if (answers(url)) {
                    ApplicationManager.getApplication().invokeLater {
                        if (wait != waits.get() || disposed) return@invokeLater
                        browser.loadURL(url)
                        updateLabel()
                    }
                    return@executeOnPooledThread
                }
                Thread.sleep(1_000)
            }
        }
    }

    private fun answers(url: String): Boolean = try {
        val connection = URI(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 1_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            connection.responseCode > 0
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    override fun dispose() {
        disposed = true
        worktree?.let { BrowserBridge.clearEndpoint(it) }
    }

    private inner class BackAction : AnAction("Back", null, AllIcons.Actions.Back) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = canGoBack
        }
        override fun actionPerformed(e: AnActionEvent) = browser.cefBrowser.goBack()
    }

    private inner class ForwardAction : AnAction("Forward", null, AllIcons.Actions.Forward) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = canGoForward
        }
        override fun actionPerformed(e: AnActionEvent) = browser.cefBrowser.goForward()
    }

    private inner class ReloadAction : AnAction("Reload", null, AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = browser.cefBrowser.reload()
    }

    private inner class DevToolsAction : AnAction("Open DevTools", null, AllIcons.Toolwindows.ToolWindowDebugger) {
        override fun actionPerformed(e: AnActionEvent) = browser.openDevtools()
    }

    private inner class OpenExternallyAction :
        AnAction("Open in External Browser", null, AllIcons.Ide.External_link_arrow) {
        override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(browser.cefBrowser.url ?: homeUrl())
    }

    companion object {
        private const val WAIT_FOR_SERVER_MS = 3 * 60 * 1_000L
    }
}
