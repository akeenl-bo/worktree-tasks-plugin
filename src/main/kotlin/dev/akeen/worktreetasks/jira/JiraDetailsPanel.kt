package dev.akeen.worktreetasks.jira

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.net.URI
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.StyleSheet

/**
 * The selected ticket beside the board: key, summary, type/status/points/assignee/epic, and its
 * description as Jira renders it. Descriptions load on selection, one request per ticket, and are
 * kept until the board next reloads.
 */
class JiraDetailsPanel : JPanel(BorderLayout()) {

    private val key = ActionLink("") { current?.let { BrowserUtil.browse(it.url) } }
    private val summary = JBTextArea().apply {
        font = JBUI.Fonts.label().biggerOn(2f).asBold()
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
    }
    private val meta = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val description = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().withGapsBetweenParagraphs().withStyleSheet(styles()).build()
        isEditable = false
        isOpaque = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = JBUI.Fonts.label()
        border = JBUI.Borders.empty(8, 0)
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) event.url?.let { BrowserUtil.browse(it.toString()) }
        }
    }
    private val header = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(key, BorderLayout.NORTH)
        add(summary, BorderLayout.CENTER)
        add(meta, BorderLayout.SOUTH)
    }
    private val cache = mutableMapOf<String, String>()
    private var current: Shown? = null
    private var base: java.net.URL? = null

    private class Shown(val issueKey: String, val url: String)

    init {
        border = JBUI.Borders.empty(8, 12)
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(description).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }, BorderLayout.CENTER)
        clear()
    }

    fun clear() {
        current = null
        header.isVisible = false
        setBody("<p>Select a ticket to see its description.</p>")
    }

    /** Forget loaded descriptions so the next selection reads them fresh (after a board reload). */
    fun forget() = cache.clear()

    /** Shows [issue]; [load] fetches its rendered description HTML and runs off the EDT. */
    fun show(issue: JiraIssue, url: String, siteUrl: String, taskName: String?, load: () -> String) {
        current = Shown(issue.key, url)
        header.isVisible = true
        key.text = issue.key
        summary.text = issue.summary
        header.revalidate()
        meta.text = listOfNotNull(
            issue.typeName.ifEmpty { null },
            issue.statusName.ifEmpty { null },
            issue.points?.let { if (it % 1.0 == 0.0) "${it.toLong()} pt" else "$it pt" },
            issue.assigneeName ?: "Unassigned",
            issue.parentKey?.let { "Epic $it" },
            taskName?.let { "Task: $it" },
        ).joinToString(" · ")
        base = runCatching { URI.create("$siteUrl/").toURL() }.getOrNull()

        cache[issue.key]?.let { showDescription(it); return }
        setBody("<p>Loading description…</p>")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(load)
            ApplicationManager.getApplication().invokeLater {
                if (current?.issueKey != issue.key) return@invokeLater
                result
                    .onSuccess { html -> cache[issue.key] = html; showDescription(html) }
                    .onFailure { setBody("<p>Couldn't load the description: ${StringUtil.escapeXmlEntities(it.message.orEmpty())}</p>") }
            }
        }
    }

    private fun showDescription(html: String) =
        setBody(html.takeIf { it.isNotBlank() }?.let { withoutImages(it) } ?: "<p><i>No description.</i></p>")

    private fun setBody(html: String) {
        description.text = "<html><body>$html</body></html>"
        (description.document as? HTMLDocument)?.base = base
        description.caretPosition = 0
    }

    private fun styles(): StyleSheet {
        val size = JBUI.Fonts.label().size
        val link = ColorUtil.toHtmlColor(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        return StyleSheet().apply {
            addRule("h1, h2, h3, h4 { font-weight: bold; margin-top: ${size}px; margin-bottom: 4px; }")
            addRule("h1 { font-size: ${size + 5}px; } h2 { font-size: ${size + 3}px; } h3 { font-size: ${size + 1}px; } h4 { font-size: ${size}px; }")
            addRule("p { margin-top: 0; margin-bottom: 6px; }")
            addRule("ul, ol { margin-top: 0; margin-bottom: 6px; margin-left: 18px; margin-left-ltr: 18px; }")
            addRule("ul { list-style-type: disc; } ol { list-style-type: decimal; }")
            addRule("li { margin-bottom: 3px; }")
            addRule("tt, code, pre { font-family: monospace; }")
            addRule("a { color: $link; }")
        }
    }

    // Attachments need the user's auth, which the HTML view can't send, so they'd only show as broken images.
    private fun withoutImages(html: String) = html.replace(Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE), "<i>[image, open in Jira]</i>")
}
