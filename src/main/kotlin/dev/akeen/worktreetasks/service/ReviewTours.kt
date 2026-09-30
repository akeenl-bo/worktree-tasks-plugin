package dev.akeen.worktreetasks.service

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** One stop on a review tour: a file, the line to land on, and what to notice there. */
data class ReviewStep(val file: String, val line: Int? = null, val note: String? = null)

/** [summary] is the whole flow as bullets ("When the user clicks X, ..."), shown as the tour's Overview. */
data class ReviewTour(val title: String?, val summary: List<String>, val steps: List<ReviewStep>)

/**
 * Review tours are written by Claude into the worktree's gitignored `.claude/review-tour.json`:
 * `{"title": "...", "summary": ["When ...", ...], "steps": [{"file": "app/x.rb", "line": 12, "note": "..."}]}`,
 * steps ordered the way the change runs from the user's action inward. Review comments go the other
 * way, into `.claude/review-comments.md`.
 */
object ReviewTours {

    const val TOUR_FILE = ".claude/review-tour.json"
    const val COMMENTS_FILE = ".claude/review-comments.md"

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
            val file = step.get("file")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.removePrefix("./")
            if (file.isNullOrEmpty()) return@mapNotNull null
            ReviewStep(
                file = file,
                line = step.get("line")?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull(),
                note = step.get("note")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() },
            )
        }.orEmpty()
        val summary = root.getAsJsonArray("summary")
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()
        return ReviewTour(root.get("title")?.takeIf { it.isJsonPrimitive }?.asString, summary, steps)
    }

    /**
     * The tour's steps first, in its order (steps on unchanged files stay, as context, when the file
     * exists), then every changed file the tour skipped, outside-in, so nothing goes unreviewed.
     * Back-to-back steps on the same file become one visit; the diff's next-change arrows cover the rest.
     */
    internal fun steps(tour: ReviewTour?, changed: List<String>, exists: (String) -> Boolean): List<ReviewStep> {
        val changedSet = changed.toSet()
        val toured = tour?.steps.orEmpty().filter { it.file in changedSet || exists(it.file) }
        val covered = toured.map { it.file }.toSet()
        val rest = changed.distinct()
            .filterNot { it in covered }
            .sortedWith(compareBy({ layerRank(it) }, { it }))
            .map { ReviewStep(it) }
        return mergeRepeats(toured) + rest
    }

    private fun mergeRepeats(steps: List<ReviewStep>): List<ReviewStep> =
        steps.fold(mutableListOf()) { merged, step ->
            val last = merged.lastOrNull()
            if (last?.file == step.file) {
                val note = listOfNotNull(last.note, step.note).joinToString(" · ").ifEmpty { null }
                merged[merged.lastIndex] = last.copy(note = note)
            } else {
                merged += step
            }
            merged
        }

    internal fun layerRank(path: String): Int {
        if (TEST_DIRS.any { path.startsWith(it) }) return LAYERS.size + 1
        val layer = LAYERS.indexOfFirst { prefixes -> prefixes.any { path.startsWith(it) } }
        return if (layer >= 0) layer else LAYERS.size
    }

    /** The Overview page: the flow bullets, then the numbered steps. Null when the tour has no summary. */
    fun overview(tour: ReviewTour?, steps: List<ReviewStep>): String? {
        if (tour == null || tour.summary.isEmpty()) return null
        return buildString {
            appendLine("# ${tour.title ?: "Review"}")
            appendLine()
            tour.summary.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Steps")
            appendLine()
            steps.forEachIndexed { index, step ->
                appendLine("${index + 1}. `${step.file}`" + (step.note?.let { " — $it" } ?: ""))
            }
        }
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
