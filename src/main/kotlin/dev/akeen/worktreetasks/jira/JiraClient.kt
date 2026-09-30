package dev.akeen.worktreetasks.jira

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import dev.akeen.worktreetasks.settings.WorktreeTasksSettings
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class JiraException(message: String, val status: Int = 0) : RuntimeException(message)

/**
 * The Jira API token, kept in the IDE's password safe (macOS Keychain) rather than settings XML.
 * The password safe refuses slow calls on the EDT, so reads happen on the client's thread and
 * [save] writes in the background.
 */
object JiraCredentials {
    private val attributes = CredentialAttributes(generateServiceName("Worktree Tasks", "Jira API token"))

    fun read(): String = PasswordSafe.instance.getPassword(attributes).orEmpty()

    fun save(token: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            PasswordSafe.instance.setPassword(attributes, token.trim().ifEmpty { null })
            WorktreeTasksSettings.getInstance().jiraTokenSaved = token.isNotBlank()
            fireJiraChanged()
        }
    }
}

/**
 * Jira Cloud REST calls as the user (email + API token over basic auth). Every call blocks, so run
 * them off the EDT. Board reads use the agile API; searches use `search/jql` (the old `search`
 * endpoint is gone from Jira Cloud).
 */
class JiraClient private constructor(private val site: String, private val auth: String, private val pointsOverride: String = "") {

    fun myAccountId(): String =
        parseJson(get("/rest/api/3/myself"))?.asJsonObject?.get("accountId")?.asString
            ?: throw JiraException("Jira didn't say who you are")

    fun boardColumns(boardId: Int): List<BoardColumn> = parseBoardColumns(get("/rest/agile/1.0/board/$boardId/configuration"))

    fun boardIssues(boardId: Int, jql: String): List<JiraIssue> {
        val points = pointsFields()
        val issues = mutableListOf<JiraIssue>()
        var startAt = 0
        while (issues.size < MAX_ISSUES) {
            val query = listOf(
                "jql" to jql,
                "fields" to issueFields(points),
                "startAt" to startAt.toString(),
                "maxResults" to "100",
            ).filter { it.second.isNotBlank() }
            val json = get("/rest/agile/1.0/board/$boardId/issue?${form(query)}")
            val page = parseIssues(json, points)
            issues += page
            val total = parseJson(json)?.asJsonObject?.get("total")?.asInt ?: 0
            startAt += page.size
            if (page.isEmpty() || startAt >= total) break
        }
        return issues
    }

    fun search(jql: String): List<JiraIssue> {
        val points = pointsFields()
        return searchPages(jql, issueFields(points).split(","), null).flatMap { parseIssues(it, points) }
    }

    /**
     * Issues matching [jql] with every status move each one made, for weekly delivery stats. Each
     * ticket's full changelog comes from its own endpoint (search results don't carry it reliably),
     * a few tickets at a time.
     */
    fun histories(jql: String): List<TicketHistory> {
        val issues = search(jql)
        if (issues.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(CHANGELOG_THREADS, issues.size))
        try {
            return issues.map { issue -> pool.submit<TicketHistory> { TicketHistory(issue, statusChanges(issue.key)) } }
                .map { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause as? JiraException ?: JiraException(e.cause?.message ?: e.toString())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun statusChanges(key: String): List<StatusChange> {
        val changes = mutableListOf<StatusChange>()
        var startAt = 0
        while (true) {
            val page = parseChangelogPage(get("/rest/api/3/issue/${encode(key)}/changelog?startAt=$startAt&maxResults=100"))
            changes += page.changes
            if (page.isLast || page.size == 0) return changes
            startAt += page.size
        }
    }

    /** Rendered description HTML for each of [keys], in one search. */
    fun descriptions(keys: Collection<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val jql = "key in (${keys.joinToString(",")})"
        return searchPages(jql, listOf("description"), "renderedFields").fold(emptyMap()) { all, page -> all + parseRenderedDescriptions(page) }
    }

    /** Names of the site's statuses in the "done" category. */
    fun doneStatusNames(): Set<String> {
        val statuses = parseJson(get("/rest/api/3/status"))?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptySet()
        return statuses.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .filter { it.getAsJsonObject("statusCategory")?.get("key")?.asString == "done" }
            .mapNotNull { it.get("name")?.asString }
            .toSet()
    }

    /** Raw `search/jql` pages for [jql], up to [MAX_ISSUES] issues. */
    private fun searchPages(jql: String, fields: List<String>, expand: String?): List<String> {
        val pages = mutableListOf<String>()
        var count = 0
        var token: String? = null
        while (count < MAX_ISSUES) {
            val body = JsonObject().apply {
                addProperty("jql", jql)
                addProperty("maxResults", 100)
                add("fields", JsonArray().apply { fields.forEach { add(it) } })
                expand?.let { addProperty("expand", it) }
                token?.let { addProperty("nextPageToken", it) }
            }
            val json = post("/rest/api/3/search/jql", body)
            val page = parseSearchPage(json, emptyList())
            pages += json
            count += page.issues.size
            token = page.nextPageToken
            if (page.isLast || token == null || page.issues.isEmpty()) break
        }
        return pages
    }

    fun issue(key: String): JiraIssue? {
        val points = pointsFields()
        val json = get("/rest/api/3/issue/${encode(key)}?fields=${encode(issueFields(points))}")
        return parseIssues("{\"issues\":[$json]}", points).firstOrNull()
    }

    fun descriptionHtml(key: String): String =
        parseRenderedDescription(get("/rest/api/3/issue/${encode(key)}?fields=description&expand=renderedFields"))

    fun issueTypes(projectKey: String): List<IssueType> =
        parseIssueTypes(get("/rest/api/3/issue/createmeta/${encode(projectKey)}/issuetypes?maxResults=100"))

    fun createFields(projectKey: String, issueTypeId: String): List<CreateField> =
        parseCreateFields(get("/rest/api/3/issue/createmeta/${encode(projectKey)}/issuetypes/${encode(issueTypeId)}?maxResults=200"))

    /** Creates the ticket and returns its key. */
    fun create(fields: JsonObject): String =
        parseJson(post("/rest/api/3/issue", JsonObject().apply { add("fields", fields) }))?.asJsonObject?.get("key")?.asString
            ?: throw JiraException("Jira didn't return the new ticket's key")

    fun assign(key: String, accountId: String) {
        send("PUT", "/rest/api/3/issue/${encode(key)}/assignee", JsonObject().apply { addProperty("accountId", accountId) })
    }

    /** Moves [key] to the status named [statusName] through whichever transition leads there. */
    fun moveTo(key: String, statusName: String) {
        val transitions = parseTransitions(get("/rest/api/3/issue/${encode(key)}/transitions"))
        val transition = transitions.firstOrNull { it.toName.equals(statusName, ignoreCase = true) }
            ?: throw JiraException("$key has no transition to \"$statusName\" (available: ${transitions.joinToString { it.toName }.ifEmpty { "none" }})")
        val body = JsonObject().apply { add("transition", JsonObject().apply { addProperty("id", transition.id) }) }
        send("POST", "/rest/api/3/issue/${encode(key)}/transitions", body)
    }

    fun browseUrl(key: String): String = "$site/browse/$key"

    /**
     * The story-point field ids to read (custom field ids differ per site): the config's `pointsField`
     * if set, else every field named like story points, since a site can have several with the same
     * name and only one holds a given project's values. Looked up once per site.
     */
    private fun pointsFields(): List<String> {
        if (pointsOverride.isNotBlank()) return listOf(pointsOverride.trim())
        return pointsFieldsBySite.getOrPut(site) {
            val fields = parseJson(get("/rest/api/3/field"))?.takeIf { it.isJsonArray }?.asJsonArray ?: return@getOrPut emptyList()
            fields.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                .filter { it.get("name")?.asString?.trim()?.lowercase() in POINTS_FIELD_NAMES }
                .mapNotNull { it.get("id")?.asString }
        }
    }

    private fun issueFields(points: List<String>) =
        (listOf("summary", "status", "issuetype", "assignee", "parent") + points).joinToString(",")

    private fun get(path: String): String = send("GET", path, null)

    private fun post(path: String, body: JsonObject): String = send("POST", path, body)

    private fun send(method: String, path: String, body: JsonObject?): String {
        val request = HttpRequest.newBuilder(URI.create(site + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .apply {
                if (body == null) method(method, HttpRequest.BodyPublishers.noBody())
                else header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
            }
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw JiraException("Couldn't reach $site: ${e.message}")
        }
        val status = response.statusCode()
        if (status in 200..299) return response.body().orEmpty()
        val message = when (status) {
            401 -> "Jira rejected your email or API token."
            403 -> parseError(response.body().orEmpty()) ?: "Jira says you don't have permission for that."
            else -> parseError(response.body().orEmpty()) ?: "Jira returned HTTP $status."
        }
        throw JiraException(message, status)
    }

    companion object {
        private const val MAX_ISSUES = 500
        private const val CHANGELOG_THREADS = 6
        private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        private val POINTS_FIELD_NAMES = setOf("story points", "story point estimate")
        private val pointsFieldsBySite = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

        /**
         * A client for [site], or null when the email or token isn't set yet. [pointsField] pins the
         * story-point field id (the config's `pointsField`). Reads the keychain, so not on the EDT.
         */
        fun forSite(site: String, pointsField: String = ""): JiraClient? {
            val email = WorktreeTasksSettings.getInstance().jiraEmail.trim()
            val token = JiraCredentials.read()
            if (site.isEmpty() || email.isEmpty() || token.isEmpty()) return null
            val auth = "Basic " + Base64.getEncoder().encodeToString("$email:$token".toByteArray())
            return JiraClient(site, auth, pointsField)
        }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        private fun form(params: List<Pair<String, String>>): String =
            params.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
    }
}
