package dev.akeen.worktreetasks.service

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.registry.Registry
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lets the task's Claude agent drive its window's Task Browser. JCEF's remote debugging port (an IDE
 * registry key, read at startup) exposes every embedded browser over CDP; each Task Browser tags its
 * page with [MARKER] and writes the endpoint to `.claude/.worktree-browser`, so Playwright's
 * `connectOverCDP` can find this worktree's page among the IDE's others.
 */
object BrowserBridge {

    const val DEBUG_PORT_KEY = "ide.browser.jcef.debug.port"
    const val MARKER = "__worktreeTask"
    private const val ENDPOINT_FILE = ".worktree-browser"
    private val LOG = Logger.getInstance(BrowserBridge::class.java)

    /** The configured debug port: -1 = off, 0 = random. Takes effect after an IDE restart. */
    var debugPort: Int
        get() = Registry.intValue(DEBUG_PORT_KEY, -1)
        set(value) = Registry.get(DEBUG_PORT_KEY).setValue(value)

    fun markerScript(worktree: Path): String =
        "window.$MARKER = ${JsonPrimitive(worktree.normalize().toString())};"

    fun writeEndpoint(worktree: Path, port: Int) {
        try {
            val json = JsonObject().apply {
                addProperty("cdp", "http://127.0.0.1:$port")
                addProperty("worktree", worktree.normalize().toString())
            }
            val dir = worktree.resolve(".claude")
            Files.createDirectories(dir)
            Files.writeString(dir.resolve(ENDPOINT_FILE), GsonBuilder().setPrettyPrinting().create().toJson(json))
        } catch (t: Throwable) {
            LOG.warn("Failed to write the Task Browser endpoint in $worktree", t)
        }
    }

    fun clearEndpoint(worktree: Path) {
        try {
            Files.deleteIfExists(worktree.resolve(".claude").resolve(ENDPOINT_FILE))
        } catch (t: Throwable) {
            LOG.warn("Failed to clear the Task Browser endpoint in $worktree", t)
        }
    }
}
