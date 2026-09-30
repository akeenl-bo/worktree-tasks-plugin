package dev.akeen.worktreetasks.service

import dev.akeen.worktreetasks.git.WorktreeGit
import java.nio.file.Files
import java.nio.file.Path

/** A file's two sides within one part; null when the file doesn't exist on that side. */
class PartFile(val before: String?, val after: String?)

/** Everything Task Review needs to show one part of a big PR, computed off the EDT. */
class PreparedPart(
    val label: String,
    val part: ReviewPart,
    /** Steps with lines in this part's "after" version. */
    val steps: List<ReviewStep>,
    /** The same steps' lines in the PR's head, for matching findings. */
    val headLines: List<Int?>,
    val files: Map<String, PartFile>,
    val changed: Set<String>,
    val beforeLabel: String,
    val afterLabel: String,
)

/**
 * Splits a big PR's review into its parts. A commit part shows each file before its first commit and
 * after its last. A hunk part shows each file with every earlier hunk part applied, before and after
 * its own hunks ([Hunks.apply]); nothing is committed or checked out. Whatever no part claims becomes
 * a final "Other changes" / "Other commits" part, so nothing goes unreviewed. Blocking.
 */
object ReviewParts {

    fun prepare(worktree: Path, base: String, tour: ReviewTour): List<PreparedPart> {
        val fileDiffs = Hunks.parse(WorktreeGit.diffU0(worktree, base, "HEAD")).filterNot { it.path.startsWith(".idea/") }
        val byFile = fileDiffs.associateBy { it.path }
        val validIds = fileDiffs.flatMap { f -> f.hunks.map { it.id } + f.fileId }.toSet()
        val baseText = mutableMapOf<String, String?>()
        fun baseOf(path: String) = baseText.getOrPut(path) { WorktreeGit.showFile(worktree, base, path) }

        val result = mutableListOf<PreparedPart>()
        var applied = emptySet<String>()
        val claimed = mutableSetOf<String>()
        val coveredCommits = mutableSetOf<String>()

        fun hunkPart(part: ReviewPart, ids: Set<String>) {
            val before = applied
            val after = applied + ids
            applied = after
            val partFiles = fileDiffs.filter { f -> f.fileId in ids || f.hunks.any { it.id in ids } }
            val lineCount = partFiles.sumOf { f -> f.hunks.filter { it.id in ids }.sumOf { it.added.size + it.removed.size } }
            val steps = ReviewTours.steps(ReviewTour(null, emptyList(), part.steps), partFiles.map { it.path }) {
                Files.isRegularFile(worktree.resolve(it))
            }
            val files = steps.map { it.file }.distinct().associateWith { path ->
                val diff = byFile[path]
                when {
                    diff == null -> Files.readString(worktree.resolve(path)).let { PartFile(it, it) }
                    diff.binary -> PartFile(null, null)
                    else -> {
                        val exists = baseOf(path) != null
                        val baseLines = baseOf(path)?.let(::lines).orEmpty()
                        fun side(included: Set<String>): String? {
                            val touched = diff.hunks.any { it.id in included }
                            return if (!exists && !touched) null else render(Hunks.apply(baseLines, diff.hunks, included))
                        }
                        PartFile(side(before), side(after))
                    }
                }
            }
            val shown = steps.map { step ->
                val diff = byFile[step.file]
                if (step.line == null || diff == null) step else step.copy(line = Hunks.mapHeadLine(step.line, diff.hunks, after))
            }
            result += PreparedPart(
                label = "${result.size + 1} · ${title(part)} · ${partFiles.size} files · $lineCount lines",
                part = part,
                steps = shown,
                headLines = steps.map { it.line },
                files = files,
                changed = partFiles.map { it.path }.toSet(),
                beforeLabel = "Before this part",
                afterLabel = "After this part",
            )
        }

        fun commitPart(part: ReviewPart, shas: List<String>) {
            coveredCommits += shas
            val from = WorktreeGit.revParse(worktree, "${shas.first()}^") ?: base
            val to = shas.last()
            val changed = WorktreeGit.diffNames(worktree, from, to).filterNot { it.startsWith(".idea/") }
            val lineCount = WorktreeGit.numstat(worktree, from, to).values.sum()
            val steps = ReviewTours.steps(ReviewTour(null, emptyList(), part.steps), changed) {
                WorktreeGit.showFile(worktree, to, it) != null
            }
            val files = steps.map { it.file }.distinct().associateWith {
                PartFile(WorktreeGit.showFile(worktree, from, it), WorktreeGit.showFile(worktree, to, it))
            }
            val commits = if (shas.size == 1) "1 commit" else "${shas.size} commits"
            result += PreparedPart(
                label = "${result.size + 1} · ${title(part)} · $commits · $lineCount lines",
                part = part,
                steps = steps,
                headLines = steps.map { it.line },
                files = files,
                changed = changed.toSet(),
                beforeLabel = "Before ${from.take(8)}",
                afterLabel = "After ${to.take(8)}",
            )
        }

        for (part in tour.parts) {
            if (part.commits.isNotEmpty()) {
                val shas = part.commits.mapNotNull { WorktreeGit.revParse(worktree, it) }
                if (shas.isNotEmpty()) commitPart(part, shas)
            } else {
                val ids = expand(part.hunks, byFile).intersect(validIds) - claimed
                if (ids.isNotEmpty()) {
                    claimed += ids
                    hunkPart(part, ids)
                }
            }
        }

        if (tour.parts.any { it.hunks.isNotEmpty() }) {
            val left = validIds - claimed
            if (left.isNotEmpty()) hunkPart(leftover("Other changes", "Changes no part claimed."), left)
        }
        if (tour.parts.any { it.commits.isNotEmpty() }) {
            val all = WorktreeGit.commits(worktree, base).mapNotNull { WorktreeGit.revParse(worktree, it.first) }
            runs(all) { it !in coveredCommits }.forEach { commitPart(leftover("Other commits", "Commits no part claimed."), it) }
        }
        return result
    }

    /** Hunk ids for a part, with `F:path` expanded to every hunk of that file (and the file itself). */
    internal fun expand(ids: List<String>, byFile: Map<String, FileDiff>): Set<String> =
        ids.flatMap { id ->
            if (id.startsWith("F:")) {
                byFile[id.removePrefix("F:")]?.let { f -> f.hunks.map { it.id } + f.fileId }.orEmpty()
            } else {
                listOf(id)
            }
        }.toSet()

    /** Consecutive runs of [items] matching [keep], in order. */
    internal fun <T> runs(items: List<T>, keep: (T) -> Boolean): List<List<T>> =
        items.fold(mutableListOf<MutableList<T>>() to false) { (runs, inRun), item ->
            if (keep(item)) {
                if (inRun) runs.last() += item else runs += mutableListOf(item)
                runs to true
            } else {
                runs to false
            }
        }.first

    /** The part's title without a number the reviewer may have put in front (the label adds its own). */
    private fun title(part: ReviewPart) = part.title.replace(Regex("^\\d+[.):]\\s*"), "")

    private fun leftover(title: String, why: String) = ReviewPart(title, why, emptyList(), emptyList(), emptyList(), emptyList())

    internal fun lines(text: String): List<String> = text.split("\n").let { if (text.endsWith("\n")) it.dropLast(1) else it }

    private fun render(lines: List<String>): String = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
}
