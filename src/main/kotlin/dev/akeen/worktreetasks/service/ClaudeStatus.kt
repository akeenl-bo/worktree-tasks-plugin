package dev.akeen.worktreetasks.service

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

/** What a worktree's Claude agent is doing, derived from Claude Code hooks. */
enum class ClaudeStatus {
    /** A prompt was submitted; Claude is working. */
    WORKING,

    /** Claude needs your input or a permission decision. */
    NEEDS_INPUT,

    /** Claude finished its response — your turn. */
    DONE,
    ;

    companion object {
        fun fromToken(token: String): ClaudeStatus? = when (token.trim()) {
            "working" -> WORKING
            "input" -> NEEDS_INPUT
            "done" -> DONE
            else -> null
        }
    }
}

private const val STATUS_FILE = ".worktree-status"
private val LOG = Logger.getInstance("dev.akeen.worktreetasks.service.ClaudeStatus")

/** Current Claude status for [worktreePath], or null if unknown/no agent has run. */
fun readClaudeStatus(worktreePath: Path): ClaudeStatus? {
    val file = worktreePath.resolve(".claude").resolve(STATUS_FILE)
    return try {
        if (Files.isRegularFile(file)) ClaudeStatus.fromToken(Files.readString(file)) else null
    } catch (t: Throwable) {
        null
    }
}

/**
 * Install Claude Code hooks into the worktree's gitignored `.claude/settings.local.json` so the
 * agent reports its status to a file the sidebar watches. Non-destructive: merges into any existing
 * local settings and is idempotent (keyed by the status-file path). Aborts rather than clobbering a
 * malformed file.
 */
fun installClaudeStatusHooks(worktreePath: Path) {
    try {
        val claudeDir = worktreePath.resolve(".claude")
        Files.createDirectories(claudeDir)
        val settingsFile = claudeDir.resolve("settings.local.json")

        val root: JsonObject = if (Files.isRegularFile(settingsFile)) {
            try {
                JsonParser.parseString(Files.readString(settingsFile)).asJsonObject
            } catch (t: Throwable) {
                LOG.warn("Not modifying unparseable $settingsFile", t)
                return
            }
        } else {
            JsonObject()
        }

        val hooks = root.getAsJsonObject("hooks") ?: JsonObject().also { root.add("hooks", it) }
        val statusPath = claudeDir.resolve(STATUS_FILE).toString()
        val quoted = shellQuote(statusPath)

        addHookIfMissing(hooks, "UserPromptSubmit", "printf %s working > $quoted", statusPath)
        addHookIfMissing(hooks, "Stop", "printf %s done > $quoted", statusPath)
        addHookIfMissing(hooks, "Notification", "printf %s input > $quoted", statusPath)

        Files.writeString(settingsFile, GsonBuilder().setPrettyPrinting().create().toJson(root))
    } catch (t: Throwable) {
        LOG.warn("Failed to install Claude status hooks in $worktreePath", t)
    }
}

private fun addHookIfMissing(hooks: JsonObject, event: String, command: String, marker: String) {
    val groups = hooks.getAsJsonArray(event) ?: JsonArray().also { hooks.add(event, it) }
    val present = groups.any { group ->
        group.isJsonObject &&
            group.asJsonObject.getAsJsonArray("hooks")?.any { hook ->
                hook.isJsonObject &&
                    hook.asJsonObject.get("command")?.takeIf { it.isJsonPrimitive }?.asString?.contains(marker) == true
            } == true
    }
    if (present) return
    val hook = JsonObject().apply {
        addProperty("type", "command")
        addProperty("command", command)
    }
    val group = JsonObject().apply { add("hooks", JsonArray().apply { add(hook) }) }
    groups.add(group)
}

private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
