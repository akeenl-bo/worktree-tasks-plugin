package dev.akeen.worktreetasks.jira

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Jira Board's site, project, views, and create-field defaults, kept in a JSON file outside the
 * IDE settings (by default `~/.config/worktree-tasks/jira.json`) so it can live in any repo, be
 * edited by hand or by Claude, and hold team-specific values the plugin itself doesn't ship with.
 */
class JiraConfig {
    var site: String = ""
    /** Project key new tickets are created in. */
    var project: String = ""
    /** Status Start Task moves a not-started ticket to. Blank = leave the status alone. */
    var startStatus: String = "In Progress"
    /** Create-screen values by field name, e.g. `"Story Points": "2"`. */
    var fieldDefaults: MutableMap<String, String> = mutableMapOf()
    var views: MutableList<JiraView> = mutableListOf()
    var dashboard: DashboardConfig = DashboardConfig()

    val siteUrl: String get() = site.trim().trimEnd('/')

    companion object {
        const val DEFAULT_PATH = "~/.config/worktree-tasks/jira.json"

        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        @Volatile private var cached: Triple<Path, Long, JiraConfig>? = null

        fun path(): Path = Path.of(expandHome(WorktreeTasksSettings.getInstance().jiraConfigPath.trim().ifEmpty { DEFAULT_PATH }))

        /** The file's current contents (re-read only when it changes); an empty config when it doesn't exist yet. */
        fun load(): JiraConfig {
            val file = path()
            if (!Files.exists(file)) return JiraConfig()
            val modified = Files.getLastModifiedTime(file).toMillis()
            cached?.let { (at, time, config) -> if (at == file && time == modified) return config }
            val config = try {
                parse(Files.readString(file))
            } catch (e: JsonParseException) {
                throw IllegalStateException("Couldn't read $file: ${e.message}")
            }
            return config.also { cached = Triple(file, modified, it) }
        }

        fun save(config: JiraConfig) {
            val file = path()
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(config) + "\n")
            cached = Triple(file, Files.getLastModifiedTime(file).toMillis(), config)
        }

        /** Creates the file with an empty skeleton if it's missing, so it can be opened and filled in. */
        fun ensureExists(): Path {
            val file = path()
            if (!Files.exists(file)) save(JiraConfig())
            return file
        }

        internal fun parse(json: String): JiraConfig = gson.fromJson(json, JiraConfig::class.java) ?: JiraConfig()

        fun expandHome(path: String): String =
            if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~") else path
    }
}

/** The Dashboard tab's inputs: where recommendations come from and which statuses mean what. */
class DashboardConfig {
    /** Board whose ranked To Do tickets are recommended. 0 = the first view with a board. */
    var boardId: Int = 0
    /** Statuses that count a ticket as delivered the first time it enters one. Empty = the "done" category. */
    var deliveredStatuses: MutableList<String> = mutableListOf()
    /** In-flight statuses shown as "in review" rather than "in progress". */
    var reviewStatuses: MutableList<String> = mutableListOf()
    /** Statuses a ticket can be recommended from. Empty = the "To Do" category. */
    var pickStatuses: MutableList<String> = mutableListOf()
}
