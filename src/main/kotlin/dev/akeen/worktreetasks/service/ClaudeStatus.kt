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

/** Tag embedded (as a shell comment) in commands we install, so we can find & replace our own. */
private const val HOOK_TAG = "worktree-tasks-status"

/**
 * Install Claude Code hooks into the worktree's gitignored `.claude/settings.local.json` so the
 * agent reports its status to a file the sidebar watches. Non-destructive to the user's own hooks:
 * it only replaces hooks we previously installed (identified by [HOOK_TAG]). Aborts rather than
 * clobbering a malformed file.
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
        val q = shellQuote(claudeDir.resolve(STATUS_FILE).toString())

        setOurHook(hooks, "UserPromptSubmit", "printf %s working > $q # $HOOK_TAG")
        setOurHook(hooks, "Stop", "printf %s done > $q # $HOOK_TAG")
        // Only permission and elicitation prompts mean "needs input"; the 60s idle notification would
        // overwrite "done" and get stuck.
        setOurHook(hooks, "Notification", "printf %s input > $q # $HOOK_TAG", "permission_prompt|elicitation_dialog")
        // Asking a question or presenting a plan waits on the user without a Notification.
        setOurHook(hooks, "PreToolUse", "printf %s input > $q # $HOOK_TAG", WAITING_TOOLS)
        setOurHook(hooks, "PostToolUse", "printf %s working > $q # $HOOK_TAG", WAITING_TOOLS)
        setOurHook(hooks, "SessionEnd", "rm -f $q # $HOOK_TAG")

        Files.writeString(settingsFile, GsonBuilder().setPrettyPrinting().create().toJson(root))
    } catch (t: Throwable) {
        LOG.warn("Failed to install Claude status hooks in $worktreePath", t)
    }
}

private const val WAITING_TOOLS = "AskUserQuestion|ExitPlanMode"

/** Replace our previously-installed hook for [event] (if any) with [command]; leave others intact. */
private fun setOurHook(hooks: JsonObject, event: String, command: String, matcher: String? = null) {
    val existing = hooks.getAsJsonArray(event)
    val kept = JsonArray()
    existing?.forEach { group -> if (!isOurs(group)) kept.add(group) }
    val hook = JsonObject().apply {
        addProperty("type", "command")
        addProperty("command", command)
    }
    kept.add(
        JsonObject().apply {
            matcher?.let { addProperty("matcher", it) }
            add("hooks", JsonArray().apply { add(hook) })
        },
    )
    hooks.add(event, kept)
}

// Matches our hooks by either the tag or the distinctive status-file name (so older untagged hooks
// installed by previous versions are replaced rather than duplicated).
private fun isOurs(group: com.google.gson.JsonElement): Boolean =
    group.isJsonObject &&
        group.asJsonObject.getAsJsonArray("hooks")?.any { hook ->
            val command = hook.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("command")?.takeIf { it.isJsonPrimitive }?.asString
            command != null && (command.contains(HOOK_TAG) || command.contains(STATUS_FILE))
        } == true

private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
