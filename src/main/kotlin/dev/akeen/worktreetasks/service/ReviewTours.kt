package dev.akeen.worktreetasks.service

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** One stop on a review tour: a file, the line to land on, and what to notice there. */
data class ReviewStep(val file: String, val line: Int? = null, val note: String? = null)

data class ReviewTour(val title: String?, val steps: List<ReviewStep>)

/**
 * Review tours are written by Claude into the worktree's gitignored `.claude/review-tour.json`:
 * `{"title": "...", "steps": [{"file": "app/models/x.rb", "line": 12, "note": "..."}]}`, ordered the
 * way the change runs. Review comments go the other way, into `.claude/review-comments.md`.
 */
object ReviewTours {

    const val TOUR_FILE = ".claude/review-tour.json"
    const val COMMENTS_FILE = ".claude/review-comments.md"

    private val LAYERS = listOf(
        listOf("db/"),
        listOf("app/models/"),
        listOf("app/interactors/", "app/services/", "app/workers/", "app/jobs/", "lib/"),
        listOf("app/policies/"),
        listOf("config/routes", "app/controllers/"),
        listOf("app/serializers/", "app/decorators/", "app/presenters/"),
        listOf("app/views/", "app/helpers/", "app/mailers/"),
        listOf("app/javascript/"),
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
        return ReviewTour(root.get("title")?.takeIf { it.isJsonPrimitive }?.asString, steps)
    }

    /**
     * The tour's steps first, in its order (steps on unchanged files stay, as context, when the file
     * exists), then every changed file the tour skipped, by layer, so nothing goes unreviewed.
     */
    internal fun steps(tour: ReviewTour?, changed: List<String>, exists: (String) -> Boolean): List<ReviewStep> {
        val changedSet = changed.toSet()
        val toured = tour?.steps.orEmpty().filter { it.file in changedSet || exists(it.file) }
        val covered = toured.map { it.file }.toSet()
        val rest = changed.distinct()
            .filterNot { it in covered }
            .sortedWith(compareBy({ layerRank(it) }, { it }))
            .map { ReviewStep(it) }
        return toured + rest
    }

    /** Roughly the order a Rails request runs through: schema, models, services, controllers, views, JS; tests last. */
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
