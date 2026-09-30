package dev.akeen.worktreetasks.jira

import com.google.gson.JsonArray
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** One status move from a ticket's changelog. */
data class StatusChange(val at: Instant, val to: String)

/** A ticket with the status moves Jira returned for it. */
data class TicketHistory(val issue: JiraIssue, val changes: List<StatusChange>)

/**
 * Which status names mean what, compared case-insensitively. A ticket is delivered the first time it
 * enters one of [delivered] (for most teams: merged or later), and in review while it sits in [review].
 */
class StatusGroups(delivered: Collection<String>, review: Collection<String>) {
    private val delivered = delivered.map { it.lowercase() }.toSet()
    private val review = review.map { it.lowercase() }.toSet()

    fun isDelivered(status: String) = status.lowercase() in delivered
    fun isReview(status: String) = status.lowercase() in review
}

/** Where the week stands: what's delivered (with when), what's in flight, and the weeks before it. */
data class WeekStats(
    val weekStart: LocalDate,
    val delivered: List<Pair<JiraIssue, Instant>>,
    val deliveredToday: Double,
    val inReview: List<JiraIssue>,
    val inProgress: List<JiraIssue>,
    /** Points delivered in each of the previous weeks, most recent first. */
    val pastWeeks: List<Double>,
    /** Weekdays from today through Friday, today included; 0 on a weekend. */
    val workdaysLeft: Int,
) {
    val deliveredPoints: Double get() = delivered.sumOf { it.first.points ?: 0.0 }
    val inFlightPoints: Double get() = (inReview + inProgress).sumOf { it.points ?: 0.0 }
    val average: Double get() = if (pastWeeks.isEmpty()) 0.0 else pastWeeks.sum() / pastWeeks.size
}

/**
 * What to do with the rest of the week: [review] waits on reviewers, [finish] is work in progress,
 * then [today]'s picks and [rest], sized so delivered + in flight + picks reach the goal ([left] is
 * the gap still to pick up).
 */
data class WeekPlan(
    val goal: Double,
    val left: Double,
    val perDay: Double,
    val review: List<JiraIssue>,
    val finish: List<JiraIssue>,
    val today: List<JiraIssue>,
    val rest: List<JiraIssue>,
    /** Candidates skipped because they have no points to plan with. */
    val unpointed: List<JiraIssue>,
)

/** The first time a ticket entered a delivered status, or null if it never has. */
internal fun firstDelivered(changes: List<StatusChange>, groups: StatusGroups): Instant? =
    changes.filter { groups.isDelivered(it.to) }.minOfOrNull { it.at }

internal fun weekStats(histories: List<TicketHistory>, groups: StatusGroups, now: ZonedDateTime, pastWeekCount: Int = 4): WeekStats {
    val zone = now.zone
    val weekStart = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekStartAt = weekStart.atStartOfDay(zone).toInstant()
    val todayAt = now.toLocalDate().atStartOfDay(zone).toInstant()
    val credited = histories.mapNotNull { history -> firstDelivered(history.changes, groups)?.let { history.issue to it } }

    val thisWeek = credited.filter { (_, at) -> !at.isBefore(weekStartAt) && !at.isAfter(now.toInstant()) }.sortedBy { it.second }
    val pastWeeks = (1..pastWeekCount).map { back ->
        val start = weekStart.minusWeeks(back.toLong()).atStartOfDay(zone).toInstant()
        val end = weekStart.minusWeeks(back.toLong() - 1).atStartOfDay(zone).toInstant()
        credited.filter { (_, at) -> !at.isBefore(start) && at.isBefore(end) }.sumOf { it.first.points ?: 0.0 }
    }
    val current = histories.map { it.issue }.filter { !groups.isDelivered(it.statusName) && it.statusCategory == "indeterminate" }
    val today = now.dayOfWeek
    val workdaysLeft = if (today == DayOfWeek.SATURDAY || today == DayOfWeek.SUNDAY) 0 else DayOfWeek.FRIDAY.value - today.value + 1

    return WeekStats(
        weekStart = weekStart,
        delivered = thisWeek,
        deliveredToday = thisWeek.filter { (_, at) -> !at.isBefore(todayAt) }.sumOf { it.first.points ?: 0.0 },
        inReview = current.filter { groups.isReview(it.statusName) },
        inProgress = current.filterNot { groups.isReview(it.statusName) },
        pastWeeks = pastWeeks,
        workdaysLeft = workdaysLeft,
    )
}

/**
 * Plans the rest of the week from [pool] (already in the order to take them): in-flight work first,
 * then new tickets until today's even share of the gap is covered, then the rest of the gap. Never
 * changes anything; it only orders what's there.
 */
internal fun planWeek(stats: WeekStats, goal: Double, pool: List<JiraIssue>): WeekPlan {
    val left = (goal - stats.deliveredPoints - stats.inFlightPoints).coerceAtLeast(0.0)
    val perDay = if (stats.workdaysLeft > 0) left / stats.workdaysLeft else left
    val (pointed, unpointed) = pool.partition { (it.points ?: 0.0) > 0.0 }

    val today = if (stats.workdaysLeft > 0) takeUntil(pointed, perDay) else emptyList()
    val rest = takeUntil(pointed.drop(today.size), left - today.sumOf { it.points ?: 0.0 })
    return WeekPlan(goal, left, perDay, stats.inReview, stats.inProgress, today, rest, unpointed)
}

/** Leading tickets whose points first reach [target] (the last one may go over). */
private fun takeUntil(tickets: List<JiraIssue>, target: Double): List<JiraIssue> {
    if (target <= 0.0) return emptyList()
    var total = 0.0
    return tickets.takeWhile { ticket ->
        (total < target).also { total += ticket.points ?: 0.0 }
    }
}

/** Claude's suggested order, keyed to tickets the plugin offered it. */
data class ClaudePicks(val today: List<Pick>, val rest: List<Pick>, val summary: String) {
    data class Pick(val key: String, val why: String)
}

/**
 * Reads Claude's reply (a JSON object, possibly fenced or with prose around it) and keeps only picks
 * for [offered] keys, each once, so a made-up or repeated key can't reach the dashboard.
 */
internal fun parseClaudePicks(reply: String, offered: Set<String>): ClaudePicks? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val root = parseJson(reply.substring(start, end + 1))?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val seen = mutableSetOf<String>()
    fun picks(name: String): List<ClaudePicks.Pick> =
        (root.get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())
            .mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .mapNotNull { pick ->
                val key = pick.get("key")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.uppercase() ?: return@mapNotNull null
                if (key !in offered || !seen.add(key)) return@mapNotNull null
                ClaudePicks.Pick(key, pick.get("why")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
            }
    return ClaudePicks(picks("today"), picks("rest"), root.get("summary")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
}

/** The prompt for Claude: the week's numbers, what's done and in flight, and the candidates with their descriptions. */
internal fun picksPrompt(stats: WeekStats, plan: WeekPlan, candidates: List<JiraIssue>, descriptions: Map<String, String>): String {
    fun line(issue: JiraIssue) = buildString {
        append("- ${issue.key} [${issue.typeName}, ${issue.points?.let { formatPoints(it) } ?: "unpointed"}, ${issue.statusName}")
        append(if (issue.assigneeName == null) ", unassigned" else ", assigned to me")
        issue.parentKey?.let { append(", epic $it") }
        append("] ${issue.summary}")
    }
    return buildString {
        appendLine("You are helping a developer plan the rest of their work week from their Jira board. You have no tools; answer only from what's below.")
        appendLine()
        appendLine("Weekly goal: ${formatPoints(plan.goal)}. Delivered so far this week: ${formatPoints(stats.deliveredPoints)}. In flight: ${formatPoints(stats.inFlightPoints)}.")
        appendLine("Left to pick up: ${formatPoints(plan.left)} across ${stats.workdaysLeft} workday(s) including today, about ${formatPoints(plan.perDay)} per day.")
        appendLine()
        appendLine("Delivered this week:")
        stats.delivered.ifEmpty { null }?.forEach { appendLine(line(it.first)) } ?: appendLine("- nothing yet")
        appendLine("In code review (waiting on reviewers):")
        plan.review.ifEmpty { null }?.forEach { appendLine(line(it)) } ?: appendLine("- nothing")
        appendLine("In progress (they'll finish these before picking anything new):")
        plan.finish.ifEmpty { null }?.forEach { appendLine(line(it)) } ?: appendLine("- nothing")
        appendLine()
        appendLine("Candidates: theirs first, then unassigned, each group in board rank order (the team's priority):")
        candidates.forEach { issue ->
            appendLine(line(issue))
            descriptions[issue.key]?.takeIf { it.isNotBlank() }?.let { appendLine("  ${it.take(700)}") }
        }
        appendLine()
        appendLine("Pick tickets for today (about ${formatPoints(plan.perDay)}) and for the rest of the week (the remainder of ${formatPoints(plan.left)}).")
        appendLine("Respect board rank unless there's a clear reason not to: a dependency on or follow-up to what was just delivered or is in flight, a bug, or a small ticket that fits the remaining budget. Prefer tickets already assigned to them.")
        appendLine("Reply with only this JSON, no other text:")
        appendLine("""{"today": [{"key": "PROJ-1", "why": "one short sentence"}], "rest": [{"key": "PROJ-2", "why": "one short sentence"}], "summary": "one sentence on the plan"}""")
    }
}

internal fun formatPoints(points: Double) = if (points % 1.0 == 0.0) "${points.toLong()} pt" else "${"%.1f".format(points)} pt"
