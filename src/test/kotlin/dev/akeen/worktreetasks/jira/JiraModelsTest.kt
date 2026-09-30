package dev.akeen.worktreetasks.jira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JiraModelsTest {

    @Test
    fun `places board issues into the board's columns by status`() {
        val columns = parseBoardColumns(
            """
            {"id": 7, "columnConfig": {"columns": [
              {"name": "To Do", "statuses": [{"id": "10617"}, {"id": "10000"}]},
              {"name": "In Progress", "statuses": [{"id": "3"}, {"id": "10002"}]}
            ]}}
            """.trimIndent(),
        )
        val issues = parseIssues(
            """
            {"total": 2, "issues": [
              {"key": "PROJ-12", "fields": {"summary": "Record every message", "status": {"id": "10002", "name": "Code Review", "statusCategory": {"key": "indeterminate"}},
                "issuetype": {"name": "Story"}, "assignee": {"displayName": "Sam", "accountId": "acct-1"}, "customfield_10117": 2.0}},
              {"key": "PROJ-9", "fields": {"summary": "Extract images", "status": {"id": "10617", "name": "Blocked", "statusCategory": {"key": "new"}},
                "issuetype": {"name": "Story"}, "assignee": null, "parent": {"key": "PROJ-1"}}}
            ]}
            """.trimIndent(),
            // Two fields named "Story Points": the first is empty for this project, the second holds the value.
            pointsFields = listOf("customfield_10026", "customfield_10117"),
        )

        val board = groupByColumn(columns, issues)

        assertEquals(listOf("To Do" to listOf("PROJ-9"), "In Progress" to listOf("PROJ-12")), board.map { (column, cards) -> column.name to cards.map { it.key } })
        assertEquals(2.0, issues.first().points)
        assertEquals("acct-1", issues.first().assigneeAccountId)
        assertEquals("PROJ-1", issues.last().parentKey)
    }

    @Test
    fun `fills required create fields in the shape Jira expects`() {
        val fields = parseCreateFields(
            """
            {"fields": [
              {"fieldId": "components", "name": "Components", "required": true, "schema": {"type": "array", "items": "component"},
               "allowedValues": [{"id": "501", "name": "Platform"}]},
              {"fieldId": "customfield_10117", "name": "Story Points", "required": true, "schema": {"type": "number"}},
              {"fieldId": "customfield_10736", "name": "Team", "required": true, "schema": {"type": "option"},
               "allowedValues": [{"id": "733", "value": "Platform"}]},
              {"fieldId": "summary", "name": "Summary", "required": true, "schema": {"type": "string"}}
            ]}
            """.trimIndent(),
        ).associateBy { it.id }

        val components = fields.getValue("components")
        val team = fields.getValue("customfield_10736")

        assertEquals("""[{"id":"501"}]""", fieldValue(components, null, components.allowed.first()).toString())
        assertEquals("2", fieldValue(fields.getValue("customfield_10117"), "2", null).toString())
        assertEquals("""{"id":"733"}""", fieldValue(team, null, team.allowed.first()).toString())
        assertEquals("Platform", team.allowed.first().label)
    }

    @Test
    fun `reads views and field defaults from the config file`() {
        val config = JiraConfig.parse(
            """
            {"site": "https://example.atlassian.net/", "project": "PROJ",
             "fieldDefaults": {"Story Points": "2"},
             "views": [{"name": "Mine", "boardId": 7, "jql": "assignee = currentUser()", "repoPath": "~/dev/app", "epicKey": "PROJ-1"}]}
            """.trimIndent(),
        )

        assertEquals("https://example.atlassian.net", config.siteUrl)
        assertEquals("In Progress", config.startStatus)
        assertEquals("2", config.fieldDefaults["Story Points"])
        assertEquals(listOf("Mine" to 7), config.views.map { it.name to it.boardId })
        assertEquals(System.getProperty("user.home") + "/dev/app", JiraConfig.expandHome(config.views.single().repoPath))
    }

    @Test
    fun `finds a ticket's worktree by the key in its branch`() {
        assertTrue(matchesTicket("PROJ-123", "proj-123-record-system-message"))
        assertTrue(matchesTicket("PROJ-123", "PROJ-123/asset-site-section"))
        assertFalse(matchesTicket("PROJ-12", "proj-123-record-system-message"))
    }
}
