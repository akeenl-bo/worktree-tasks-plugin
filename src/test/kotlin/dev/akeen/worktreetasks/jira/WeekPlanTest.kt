package dev.akeen.worktreetasks.jira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class WeekPlanTest {

    private val zone = ZoneId.of("America/New_York")
    private val groups = StatusGroups(listOf("Merged", "Ready for QA", "Done"), listOf("Code Review"))
    // Wednesday; the week started Monday Sep 28.
    private val now = ZonedDateTime.of(2026, 9, 30, 14, 0, 0, 0, zone)

    @Test
    fun `credits each ticket to the week it was first merged and splits what's in flight`() {
        val histories = listOf(
            // Merged Monday, then on to QA: counts once, this week.
            history("PROJ-1", "Ready for QA", "indeterminate", 3.0, "2026-09-28T16:27" to "Merged", "2026-09-29T01:09" to "Ready for QA"),
            // Merged last week and Done this week: last week's points.
            history("PROJ-2", "Done", "done", 2.0, "2026-09-24T10:00" to "Merged", "2026-09-29T17:00" to "Done"),
            history("PROJ-3", "Code Review", "indeterminate", 2.0, "2026-09-29T09:00" to "Code Review"),
            history("PROJ-4", "In Progress", "indeterminate", 1.0, "2026-09-30T09:00" to "In Progress"),
        )

        val stats = weekStats(histories, groups, now)

        assertEquals(listOf("PROJ-1"), stats.delivered.map { it.first.key })
        assertEquals(3.0, stats.deliveredPoints, 0.0)
        assertEquals(listOf("PROJ-3"), stats.inReview.map { it.key })
        assertEquals(listOf("PROJ-4"), stats.inProgress.map { it.key })
        assertEquals(listOf(2.0, 0.0, 0.0, 0.0), stats.pastWeeks)
        assertEquals(3, stats.workdaysLeft)
    }

    @Test
    fun `plans in-flight work first, then today's share of the gap, then the rest of the week`() {
        val stats = weekStats(
            listOf(
                history("PROJ-1", "Merged", "indeterminate", 4.0, "2026-09-28T12:00" to "Merged"),
                history("PROJ-3", "Code Review", "indeterminate", 2.0, "2026-09-29T09:00" to "Code Review"),
            ),
            groups,
            now,
        )
        val pool = listOf(issue("PROJ-10", 2.0), issue("PROJ-11", null), issue("PROJ-12", 2.0), issue("PROJ-13", 3.0), issue("PROJ-14", 1.0))

        val plan = planWeek(stats, goal = 12.0, pool = pool)

        assertEquals(6.0, plan.left, 0.0)
        assertEquals(2.0, plan.perDay, 0.0)
        assertEquals(listOf("PROJ-3"), plan.finish.map { it.key })
        assertEquals(listOf("PROJ-10"), plan.today.map { it.key })
        assertEquals(listOf("PROJ-12", "PROJ-13"), plan.rest.map { it.key })
        assertEquals(listOf("PROJ-11"), plan.unpointed.map { it.key })
    }

    @Test
    fun `reads status moves from a changelog search`() {
        val histories = parseHistories(
            """
            {"issues": [{"key": "PROJ-1", "fields": {"summary": "S", "status": {"id": "10642", "name": "Merged", "statusCategory": {"key": "indeterminate"}}},
              "changelog": {"histories": [
                {"created": "2026-09-28T16:27:04.193-0500", "items": [{"field": "status", "fromString": "Code Review", "toString": "Merged"}]},
                {"created": "2026-09-28T16:30:00.000-0500", "items": [{"field": "assignee", "toString": "Sam"}]}
              ]}}]}
            """.trimIndent(),
            pointsField = null,
        )

        assertEquals(listOf(StatusChange(Instant.parse("2026-09-28T21:27:04.193Z"), "Merged")), histories.single().changes)
    }

    @Test
    fun `keeps only Claude's picks for tickets it was offered, each once`() {
        val picks = parseClaudePicks(
            """
            Here's the plan:
            {"today": [{"key": "proj-10", "why": "Follows up yesterday's merge"}, {"key": "PROJ-99", "why": "not offered"}],
             "rest": [{"key": "PROJ-10", "why": "duplicate"}, {"key": "PROJ-12", "why": "Small and ranked next"}],
             "summary": "Finish review, then the follow-up."}
            """.trimIndent(),
            offered = setOf("PROJ-10", "PROJ-12"),
        )

        assertNotNull(picks)
        assertEquals(listOf("PROJ-10"), picks!!.today.map { it.key })
        assertEquals(listOf("PROJ-12"), picks.rest.map { it.key })
        assertEquals("Finish review, then the follow-up.", picks.summary)
    }

    private fun history(key: String, status: String, category: String, points: Double, vararg moves: Pair<String, String>) =
        TicketHistory(
            JiraIssue(key, "Summary $key", status, status, category, "Story", "Me", "me", points, null),
            moves.map { (at, to) -> StatusChange(ZonedDateTime.parse("${at}:00-04:00[America/New_York]").toInstant(), to) },
        )

    private fun issue(key: String, points: Double?) =
        JiraIssue(key, "Summary $key", "10000", "To do", "new", "Story", null, null, points, null)
}
