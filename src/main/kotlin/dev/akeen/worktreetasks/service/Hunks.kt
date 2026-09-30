package dev.akeen.worktreetasks.service

/**
 * One change block from `git diff -U0` (no context lines). [oldStart]/[oldCount] are in the base
 * file, [newStart]/[newCount] in the head file; a count of 0 is a pure insertion or deletion.
 * Hunks from one diff never overlap, so any subset of them can be applied to the base in order,
 * which is how a big PR is shown part by part without building any commits.
 */
data class Hunk(
    val id: String,
    val file: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val added: List<String>,
    val removed: List<String>,
)

/** A file in the diff; [binary] files have no hunks and move between parts as a whole. */
data class FileDiff(val path: String, val hunks: List<Hunk>, val binary: Boolean = false) {
    /** The id a part uses to claim the whole file (binary files, or any file wholesale). */
    val fileId: String get() = "F:$path"
}

object Hunks {

    private val HEADER = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

    /** Parse `git diff -U0 --no-renames --no-color` output; hunk ids run H1, H2, ... across files. */
    fun parse(diff: String): List<FileDiff> {
        val files = mutableListOf<FileDiff>()
        var path: String? = null
        var hunks = mutableListOf<Hunk>()
        var binary = false
        var counter = 0
        var current: MutableList<String>? = null
        var header: MatchResult? = null

        fun closeHunk() {
            val h = header ?: return
            val lines = current.orEmpty()
            counter++
            hunks += Hunk(
                id = "H$counter",
                file = path!!,
                oldStart = h.groupValues[1].toInt(),
                oldCount = h.groupValues[2].ifEmpty { "1" }.toInt(),
                newStart = h.groupValues[3].toInt(),
                newCount = h.groupValues[4].ifEmpty { "1" }.toInt(),
                added = lines.filter { it.startsWith("+") }.map { it.substring(1) },
                removed = lines.filter { it.startsWith("-") }.map { it.substring(1) },
            )
            header = null
            current = null
        }

        fun closeFile() {
            closeHunk()
            path?.let { files += FileDiff(it, hunks, binary) }
            path = null
            hunks = mutableListOf()
            binary = false
        }

        for (line in diff.lineSequence()) {
            when {
                line.startsWith("diff --git ") -> {
                    closeFile()
                    path = line.substringAfter(" b/")
                }
                line.startsWith("Binary files ") -> binary = true
                line.startsWith("@@") -> {
                    closeHunk()
                    header = HEADER.find(line)
                    current = mutableListOf()
                }
                header != null && (line.startsWith("+") || line.startsWith("-")) -> current?.add(line)
            }
        }
        closeFile()
        return files
    }

    /** [base] with only the [included] hunks applied: the file as it stands after those changes. */
    fun apply(base: List<String>, hunks: List<Hunk>, included: Set<String>): List<String> {
        val result = base.toMutableList()
        var offset = 0
        for (hunk in hunks.sortedBy { it.oldStart }) {
            if (hunk.id !in included) continue
            // A pure insertion goes after old line [oldStart]; otherwise it replaces the old lines.
            val at = (if (hunk.oldCount == 0) hunk.oldStart else hunk.oldStart - 1) + offset
            repeat(hunk.oldCount) { result.removeAt(at) }
            result.addAll(at, hunk.added)
            offset += hunk.added.size - hunk.oldCount
        }
        return result
    }

    /**
     * Where [headLine] (1-based, in the fully changed file) lands in the version with only [included]
     * hunks applied: every excluded hunk above it moved it by its size. A line that only exists inside
     * an excluded hunk maps to where that hunk sits.
     */
    fun mapHeadLine(headLine: Int, hunks: List<Hunk>, included: Set<String>): Int {
        var shift = 0
        for (hunk in hunks.sortedBy { it.newStart }) {
            if (hunk.id in included) continue
            val start = if (hunk.newCount == 0) hunk.newStart + 1 else hunk.newStart
            val end = start + hunk.newCount
            when {
                headLine >= end -> shift += hunk.newCount - hunk.oldCount
                headLine >= start -> return (start - shift).coerceAtLeast(1)
            }
        }
        return (headLine - shift).coerceAtLeast(1)
    }

    /** A numbered list of every hunk for the reviewer to assign to parts. */
    fun index(files: List<FileDiff>): String = buildString {
        for (file in files) {
            appendLine("${file.fileId}  (whole file${if (file.binary) ", binary" else ""})")
            for (hunk in file.hunks) {
                val preview = (hunk.added.firstOrNull { it.isNotBlank() } ?: hunk.removed.firstOrNull { it.isNotBlank() })
                    ?.trim()?.take(90).orEmpty()
                appendLine("  ${hunk.id}  ${file.path}:${hunk.newStart}  -${hunk.oldCount} +${hunk.newCount}  $preview")
            }
        }
    }
}
