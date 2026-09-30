package dev.akeen.worktreetasks.jira

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.util.text.StringUtil
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** One ticket as a board card needs it. */
data class JiraIssue(
    val key: String,
    val summary: String,
    val statusId: String,
    val statusName: String,
    /** Jira's status category key: `new`, `indeterminate`, or `done`. */
    val statusCategory: String,
    val typeName: String,
    val assigneeName: String?,
    val assigneeAccountId: String?,
    val points: Double?,
    val parentKey: String?,
)

/** A board column and the statuses mapped into it, in board order. */
data class BoardColumn(val name: String, val statusIds: Set<String>)

data class IssueType(val id: String, val name: String)

data class AllowedValue(val id: String, val label: String) {
    override fun toString() = label
}

/** A field on the create screen for one issue type, as `createmeta` describes it. */
data class CreateField(
    val id: String,
    val name: String,
    val required: Boolean,
    val hasDefault: Boolean,
    val type: String,
    val itemsType: String?,
    val custom: String?,
    val allowed: List<AllowedValue>,
)

data class Transition(val id: String, val name: String, val toName: String)

internal fun parseJson(json: String): JsonElement? = runCatching { JsonParser.parseString(json) }.getOrNull()

private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.arr(name: String): JsonArray? = get(name)?.takeIf { it.isJsonArray }?.asJsonArray
private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }

/** Issues from a `search/jql` or agile board page; points come from the first of [pointsFields] that has a value. */
internal fun parseIssues(json: String, pointsFields: List<String>): List<JiraIssue> {
    val root = parseJson(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    return root.arr("issues")?.objects().orEmpty().mapNotNull { issue ->
        val key = issue.str("key") ?: return@mapNotNull null
        val fields = issue.obj("fields") ?: return@mapNotNull null
        val status = fields.obj("status")
        val assignee = fields.obj("assignee")
        JiraIssue(
            key = key,
            summary = fields.str("summary").orEmpty(),
            statusId = status?.str("id").orEmpty(),
            statusName = status?.str("name").orEmpty(),
            statusCategory = status?.obj("statusCategory")?.str("key").orEmpty(),
            typeName = fields.obj("issuetype")?.str("name").orEmpty(),
            assigneeName = assignee?.str("displayName"),
            assigneeAccountId = assignee?.str("accountId"),
            points = pointsFields.firstNotNullOfOrNull { id -> fields.get(id)?.takeIf { it.isJsonPrimitive }?.asDouble },
            parentKey = fields.obj("parent")?.str("key"),
        )
    }
}

/** A ticket's description as Jira renders it to HTML (`expand=renderedFields`); empty when it has none. */
internal fun parseRenderedDescription(json: String): String =
    parseJson(json)?.takeIf { it.isJsonObject }?.asJsonObject?.obj("renderedFields")?.str("description").orEmpty()

/** Rendered descriptions by key from a search with `expand=renderedFields`. */
internal fun parseRenderedDescriptions(json: String): Map<String, String> {
    val root = parseJson(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
    return root.arr("issues")?.objects().orEmpty().mapNotNull { issue ->
        val key = issue.str("key") ?: return@mapNotNull null
        key to issue.obj("renderedFields")?.str("description").orEmpty()
    }.toMap()
}

private val jiraTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

/** One page of `issue/{key}/changelog` (oldest first): its status moves, how many entries it held, and whether it's the last. */
internal data class ChangelogPage(val changes: List<StatusChange>, val size: Int, val isLast: Boolean)

internal fun parseChangelogPage(json: String): ChangelogPage {
    val root = parseJson(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return ChangelogPage(emptyList(), 0, true)
    val changes = root.arr("values")?.objects().orEmpty().flatMap { history ->
        val at = history.str("created")?.let { runCatching { OffsetDateTime.parse(it, jiraTimestamp).toInstant() }.getOrNull() }
            ?: return@flatMap emptyList()
        history.arr("items")?.objects().orEmpty()
            .filter { it.str("field") == "status" }
            .mapNotNull { item -> item.str("toString")?.let { StatusChange(at, it) } }
    }
    val total = root.get("total")?.takeIf { it.isJsonPrimitive }?.asInt
    val startAt = root.get("startAt")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    val count = root.arr("values")?.size() ?: 0
    val last = root.get("isLast")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: (total == null || startAt + count >= total || count == 0)
    return ChangelogPage(changes, count, last)
}

/** Jira's rendered HTML as plain text, for handing descriptions to Claude. */
internal fun htmlToText(html: String): String =
    StringUtil.unescapeXmlEntities(
        html.replace(Regex("<(br|/p|/li|/h\\d)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "- ")
            .replace(Regex("<[^>]+>"), ""),
    ).replace(Regex("[ \t]+"), " ").replace(Regex("\\s*\n\\s*"), "\n").trim()

internal data class IssuePage(val issues: List<JiraIssue>, val nextPageToken: String?, val isLast: Boolean)

internal fun parseSearchPage(json: String, pointsFields: List<String>): IssuePage {
    val root = parseJson(json)?.takeIf { it.isJsonObject }?.asJsonObject
    val token = root?.str("nextPageToken")
    return IssuePage(parseIssues(json, pointsFields), token, root?.get("isLast")?.asBoolean ?: (token == null))
}

internal fun parseBoardColumns(json: String): List<BoardColumn> {
    val root = parseJson(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    return root.obj("columnConfig")?.arr("columns")?.objects().orEmpty().map { column ->
        BoardColumn(
            name = column.str("name").orEmpty(),
            statusIds = column.arr("statuses")?.objects().orEmpty().mapNotNull { it.str("id") }.toSet(),
        )
    }
}

/** `createmeta/{project}/issuetypes` → the types a ticket can be created as (no sub-tasks). */
internal fun parseIssueTypes(json: String): List<IssueType> {
    val root = parseJson(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    val types = root.arr("issueTypes") ?: root.arr("values") ?: return emptyList()
    return types.objects()
        .filterNot { it.get("subtask")?.asBoolean == true }
        .mapNotNull { type -> IssueType(type.str("id") ?: return@mapNotNull null, type.str("name").orEmpty()) }
}

internal fun parseCreateFields(json: String): List<CreateField> {
    val root = parseJson(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    val fields = root.arr("fields") ?: root.arr("values") ?: return emptyList()
    return fields.objects().mapNotNull { field ->
        val schema = field.obj("schema")
        CreateField(
            id = field.str("fieldId") ?: field.str("key") ?: return@mapNotNull null,
            name = field.str("name").orEmpty(),
            required = field.get("required")?.asBoolean == true,
            hasDefault = field.get("hasDefaultValue")?.asBoolean == true,
            type = schema?.str("type").orEmpty(),
            itemsType = schema?.str("items"),
            custom = schema?.str("custom"),
            allowed = field.arr("allowedValues")?.objects().orEmpty().mapNotNull { value ->
                val id = value.str("id") ?: return@mapNotNull null
                AllowedValue(id, value.str("name") ?: value.str("value") ?: id)
            },
        )
    }
}

internal fun parseTransitions(json: String): List<Transition> {
    val root = parseJson(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    return root.arr("transitions")?.objects().orEmpty().mapNotNull { transition ->
        Transition(
            id = transition.str("id") ?: return@mapNotNull null,
            name = transition.str("name").orEmpty(),
            toName = transition.obj("to")?.str("name").orEmpty(),
        )
    }
}

/** Jira's error body (`errorMessages` plus per-field `errors`) as one readable message. */
internal fun parseError(json: String): String? {
    val root = parseJson(json)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val messages = root.arr("errorMessages")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
    val fieldErrors = root.obj("errors")?.entrySet()?.map { (field, message) -> "$field: ${message.asString}" }.orEmpty()
    return (messages + fieldErrors).joinToString("\n").ifBlank { null }
}

/** Plain text as an Atlassian Document, one paragraph per blank-line-separated block. */
internal fun adf(text: String): JsonObject {
    val paragraphs = JsonArray()
    text.trim().split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.forEach { block ->
        val content = JsonArray()
        block.lines().forEachIndexed { index, line ->
            if (index > 0) content.add(JsonObject().apply { addProperty("type", "hardBreak") })
            if (line.isNotEmpty()) content.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", line)
            })
        }
        paragraphs.add(JsonObject().apply {
            addProperty("type", "paragraph")
            add("content", content)
        })
    }
    return JsonObject().apply {
        addProperty("type", "doc")
        addProperty("version", 1)
        add("content", paragraphs)
    }
}

/** A create-screen field this plugin knows how to fill, or null when it has to be set in Jira. */
internal enum class FieldInput { NUMBER, TEXT, TEXTAREA, CHOICE, MULTI_CHOICE, LABELS }

internal fun inputFor(field: CreateField): FieldInput? = when {
    field.type == "array" && field.allowed.isNotEmpty() -> FieldInput.MULTI_CHOICE
    field.type == "array" && field.itemsType == "string" -> FieldInput.LABELS
    field.allowed.isNotEmpty() -> FieldInput.CHOICE
    field.type == "number" -> FieldInput.NUMBER
    field.type == "string" && field.custom?.endsWith(":textarea") == true -> FieldInput.TEXTAREA
    field.type == "string" || field.type == "date" -> FieldInput.TEXT
    else -> null
}

/** The JSON Jira expects for [field] given what was typed or picked, or null when it's empty. */
internal fun fieldValue(field: CreateField, text: String?, choice: AllowedValue?): JsonElement? {
    val input = inputFor(field) ?: return null
    val trimmed = text?.trim().orEmpty()
    return when (input) {
        FieldInput.NUMBER -> trimmed.toDoubleOrNull()?.let { number ->
            if (number % 1.0 == 0.0) JsonPrimitive(number.toLong()) else JsonPrimitive(number)
        }
        FieldInput.TEXT -> trimmed.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }
        FieldInput.TEXTAREA -> trimmed.takeIf { it.isNotEmpty() }?.let { adf(it) }
        FieldInput.CHOICE -> choice?.let { JsonObject().apply { addProperty("id", it.id) } }
        FieldInput.MULTI_CHOICE -> choice?.let { JsonArray().apply { add(JsonObject().apply { addProperty("id", it.id) }) } }
        FieldInput.LABELS -> trimmed.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
            ?.let { labels -> JsonArray().apply { labels.forEach { add(it) } } }
    }
}

/** Fields the create dialog asks for itself rather than generically. */
internal val HANDLED_FIELDS = setOf("project", "issuetype", "summary", "description", "parent", "assignee", "reporter")

/** Place [issues] into [columns] by status, keeping each column in the order Jira returned. */
internal fun groupByColumn(columns: List<BoardColumn>, issues: List<JiraIssue>): List<Pair<BoardColumn, List<JiraIssue>>> =
    columns.map { column -> column to issues.filter { it.statusId in column.statusIds } }

/** Columns for a plain JQL view: one per status, not-started first and done last. */
internal fun statusColumns(issues: List<JiraIssue>): List<BoardColumn> {
    val order = listOf("new", "indeterminate", "done")
    return issues
        .distinctBy { it.statusId }
        .sortedBy { order.indexOf(it.statusCategory).let { index -> if (index < 0) order.size else index } }
        .map { BoardColumn(it.statusName, setOf(it.statusId)) }
}

/** Whether a branch or task name is the worktree for [key], e.g. `proj-123-fix-login` for PROJ-123. */
internal fun matchesTicket(key: String, branchOrName: String): Boolean =
    Regex("(^|[^A-Za-z0-9])${Regex.escape(key)}($|[^0-9])", RegexOption.IGNORE_CASE).containsMatchIn(branchOrName)
