package dev.akeen.worktreetasks.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.abs

/**
 * One hop of the flow: the function at [file]:[line] ([label], e.g. `Chat#ask`), [what] happens there
 * (who calls it, what it does, where it goes next), and, where the change altered it, [before] / [now].
 */
data class ReviewStep(
    val file: String,
    val line: Int? = null,
    val label: String? = null,
    val what: String? = null,
    val before: String? = null,
    val now: String? = null,
)

/** A titled group of bullets on the overview, e.g. Jira, Manual test, Findings. */
data class ReviewSection(val title: String, val bullets: List<String>)

/** A verified code-review finding at [file]:[line]. */
data class ReviewFinding(val file: String, val line: Int?, val severity: String?, val text: String)

/**
 * [summary] is the whole flow as bullets ("When the user clicks X, ..."). PR reviews add [sections]
 * (Jira context, manual test notes, findings) and [findings], which also show on the step they fall in.
 */
data class ReviewTour(
    val title: String?,
    val summary: List<String>,
    val steps: List<ReviewStep>,
    val sections: List<ReviewSection> = emptyList(),
    val findings: List<ReviewFinding> = emptyList(),
    /** The reviewer's overall take on a PR, shown above the flow. */
    val take: String? = null,
)

/**
 * Review tours are written by Claude into the worktree's gitignored `.claude/review-tour.json`:
 * `{"title", "summary": [...], "steps": [{"file", "line", "label", "what", "before", "now"}]}`, steps
 * following the call chain from the user's action inward. Review comments go the other way, into
 * `.claude/review-comments.md`.
 */
object ReviewTours {

    const val TOUR_FILE = ".claude/review-tour.json"
    const val COMMENTS_FILE = ".claude/review-comments.md"

    /** Left by a finished PR review until it's opened, so its window opens straight onto Task Review. */
    const val READY_MARKER = ".claude/review-ready"

    /** The head commit a PR's tour was written for, so the same commit is never reviewed twice. */
    const val REVIEWED_SHA_FILE = ".claude/review-sha"

    /** Steps closer than this in the same file are one screen, so one step. */
    private const val SAME_SCREEN_LINES = 15

    /** Outside-in, the way a user action travels: UI, entry points, then deeper layers, then data. */
    private val LAYERS = listOf(
        listOf("app/javascript/", "app/views/", "app/helpers/"),
        listOf("config/routes", "app/controllers/"),
        listOf("app/policies/", "app/serializers/", "app/decorators/", "app/presenters/"),
        listOf("app/workers/", "app/jobs/", "app/interactors/", "app/services/", "lib/"),
        listOf("app/models/", "app/mailers/"),
        listOf("db/"),
    )
    private val TEST_DIRS = listOf("spec/", "test/", "e2e/", "features/")

    fun read(worktree: Path): ReviewTour? = try {
        val file = worktree.resolve(TOUR_FILE)
        if (Files.isRegularFile(file)) parse(Files.readString(file)) else null
    } catch (_: Throwable) {
        null
    }

    internal fun parse(json: String): ReviewTour? {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val steps = root.getAsJsonArray("steps")?.mapNotNull { element ->
            val step = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val file = step.text("file")?.removePrefix("./") ?: return@mapNotNull null
            ReviewStep(
                file = file,
                line = step.text("line")?.toIntOrNull(),
                label = step.text("label"),
                what = step.text("what") ?: step.text("note"),
                before = step.text("before"),
                now = step.text("now"),
            )
        }.orEmpty()
        val sections = root.getAsJsonArray("sections")?.mapNotNull { element ->
            val section = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val title = section.text("title") ?: return@mapNotNull null
            ReviewSection(title, section.getAsJsonArray("bullets").strings())
        }.orEmpty()
        val findings = root.getAsJsonArray("findings")?.mapNotNull { element ->
            val finding = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            ReviewFinding(
                file = finding.text("file")?.removePrefix("./") ?: return@mapNotNull null,
                line = finding.text("line")?.toIntOrNull(),
                severity = finding.text("severity"),
                text = finding.text("text") ?: return@mapNotNull null,
            )
        }.orEmpty()
        return ReviewTour(root.text("title"), root.getAsJsonArray("summary").strings(), steps, sections, findings, root.text("take"))
    }

    /** The JSON object in a model's reply, which may be wrapped in a code fence or stray prose. */
    internal fun extractJsonObject(reply: String): String? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        return if (start >= 0 && end > start) reply.substring(start, end + 1) else null
    }

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun com.google.gson.JsonArray?.strings(): List<String> =
        this?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()

    /**
     * Which step each finding belongs to: the last step in the finding's file at or above its line
     * (so it lands in the function it's in), else that file's first step. Findings in files the tour
     * never visits stay overview-only.
     */
    fun findingsByStep(findings: List<ReviewFinding>, steps: List<ReviewStep>): Map<Int, List<ReviewFinding>> =
        findings.mapNotNull { finding ->
            val inFile = steps.withIndex().filter { it.value.file == finding.file }
            val index = inFile.lastOrNull { (it.value.line ?: 0) <= (finding.line ?: 0) }?.index ?: inFile.firstOrNull()?.index
            index?.let { it to finding }
        }.groupBy({ it.first }, { it.second })

    /**
     * The tour's steps first, in its order (steps on unchanged files stay, as context, when the file
     * exists), then every changed file the tour skipped, outside-in, so nothing goes unreviewed.
     */
    internal fun steps(tour: ReviewTour?, changed: List<String>, exists: (String) -> Boolean): List<ReviewStep> {
        val changedSet = changed.toSet()
        val toured = tour?.steps.orEmpty().filter { it.file in changedSet || exists(it.file) }
        val covered = toured.map { it.file }.toSet()
        val rest = changed.distinct()
            .filterNot { it in covered }
            .sortedWith(compareBy({ layerRank(it) }, { it }))
            .map { ReviewStep(it) }
        return mergeSameScreen(toured) + rest
    }

    /** Back-to-back steps on the same screen of the same file read as one; hops further apart stay separate. */
    private fun mergeSameScreen(steps: List<ReviewStep>): List<ReviewStep> =
        steps.fold(mutableListOf()) { merged, step ->
            val last = merged.lastOrNull()
            val sameScreen = last != null && last.file == step.file &&
                (last.line == null || step.line == null || abs(last.line - step.line) < SAME_SCREEN_LINES)
            if (last != null && sameScreen) {
                merged[merged.lastIndex] = last.copy(
                    label = last.label ?: step.label,
                    what = join(last.what, step.what),
                    before = join(last.before, step.before),
                    now = join(last.now, step.now),
                )
            } else {
                merged += step
            }
            merged
        }

    private fun join(a: String?, b: String?): String? = listOfNotNull(a, b).joinToString(" ").ifEmpty { null }

    internal fun layerRank(path: String): Int {
        if (TEST_DIRS.any { path.startsWith(it) }) return LAYERS.size + 1
        val layer = LAYERS.indexOfFirst { prefixes -> prefixes.any { path.startsWith(it) } }
        return if (layer >= 0) layer else LAYERS.size
    }

    fun appendComment(worktree: Path, where: String, text: String) {
        val file = worktree.resolve(COMMENTS_FILE)
        Files.createDirectories(file.parent)
        if (Files.notExists(file)) Files.writeString(file, "# Review comments\n\n")
        Files.writeString(file, "- `$where` — ${text.replace("\n", "\n  ")}\n", StandardOpenOption.APPEND)
    }

    fun hasComments(worktree: Path): Boolean = try {
        val file = worktree.resolve(COMMENTS_FILE)
        Files.isRegularFile(file) && Files.readAllLines(file).any { it.startsWith("- ") }
    } catch (_: Throwable) {
        false
    }
}
