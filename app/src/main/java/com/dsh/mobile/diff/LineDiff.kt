package com.dsh.mobile.diff

/** One line of a diff. */
enum class DiffKind { UNCHANGED, ADDED, REMOVED }

/**
 * A single line in a rendered diff.
 *
 * [oldNumber] is null for an added line and [newNumber] is null for a removed
 * one, which is what lets the view show the two-column gutter the way every
 * code review tool does.
 */
data class DiffLine(
    val kind: DiffKind,
    val text: String,
    val oldNumber: Int?,
    val newNumber: Int?,
)

/**
 * Line-level diff, pure and dependency-free.
 *
 * ## Why not git
 *
 * Android ships no `git` (it is absent from /system/bin and from toybox), so a
 * Diffs view cannot shell out. Mobile-Harness solves this the same way: it
 * keeps workspace checkpoints and diffs against them. This is the diff half.
 *
 * ## Algorithm and its limits
 *
 * Classic LCS dynamic program. Correct and easy to audit, but O(n*m) memory, so
 * [MAX_LINES] caps each side. Above that [diff] returns null rather than
 * allocating a table that would OOM a 1GB tablet - the caller shows a summary
 * instead of pretending to be a real diff.
 */
object LineDiff {

    /** Per-side cap. 2000x2000 is a ~16MB int table. */
    const val MAX_LINES: Int = 2_000

    /**
     * Diffs [old] against [new], or null when either side exceeds [MAX_LINES].
     *
     * Returns a non-empty list even when the files are identical (the unchanged
     * lines), so callers never have to special-case "no output".
     */
    fun diff(old: List<String>, new: List<String>): List<DiffLine>? {
        if (old.size > MAX_LINES || new.size > MAX_LINES) return null
        val n = old.size
        val m = new.size

        // dp[i][j] = length of the LCS of old[i..] and new[j..]
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (old[i] == new[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (old[i] == new[j]) {
                out += DiffLine(DiffKind.UNCHANGED, old[i], i + 1, j + 1)
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                // Prefer emitting the removal first so a replacement reads
                // "removed then added", which is how review UIs present it.
                out += DiffLine(DiffKind.REMOVED, old[i], i + 1, null)
                i++
            } else {
                out += DiffLine(DiffKind.ADDED, new[j], null, j + 1)
                j++
            }
        }
        while (i < n) out += DiffLine(DiffKind.REMOVED, old[i], ++i, null)
        while (j < m) out += DiffLine(DiffKind.ADDED, new[j], null, ++j)
        return out
    }

    /** How many added/removed lines a rendered diff contains. */
    fun changeCount(lines: List<DiffLine>): Int =
        lines.count { it.kind != DiffKind.UNCHANGED }

    /**
     * Splits file text into diffable lines, dropping a single trailing newline
     * so a file that ends in \n does not read as having a phantom last line.
     */
    fun lines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")
}
