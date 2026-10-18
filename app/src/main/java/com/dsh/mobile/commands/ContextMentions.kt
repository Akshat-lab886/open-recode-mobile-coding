package com.dsh.mobile.commands

/**
 * `@path` file mentions in the composer.
 *
 * A phone keyboard makes "go look at src/main/java/Foo.kt" painful, so the
 * composer lets the user pick a file and references it by path instead. These
 * helpers are the parsing half, kept pure so the awkward cases - a path with
 * spaces in it, an @ inside an email address, a half-typed token - are pinned
 * by tests instead of discovered on a device.
 */
object ContextMentions {

    /** The character that opens a mention. */
    const val TRIGGER: Char = '@'

    /**
     * Characters allowed inside a mention token.
     *
     * Deliberately narrow: whitespace ends the token, so a path containing
     * spaces cannot be mentioned as one bare token. Quotes are how the UI
     * handles that case, not a wider character class.
     */
    private const val TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-/~"

    /** One parsed mention. */
    data class Mention(val path: String, val start: Int, val end: Int) {
        /** End index is exclusive. */
        val range: IntRange get() = start until end
    }

    /**
     * Index of the '@' opening the token the cursor is inside, or null.
     *
     * Shared by [activeToken] and [applyCompletion] so the two can never
     * disagree about where the token begins - which is exactly the kind of
     * off-by-one that silently corrupts the user's sentence.
     */
    private fun tokenStart(text: String, cursor: Int): Int? {
        val at = cursor.coerceIn(0, text.length)
        var i = at
        while (i > 0 && TOKEN_CHARS.contains(text[i - 1])) i--
        if (i == 0) return null
        if (text[i - 1] != TRIGGER) return null
        return i - 1
    }

    /**
     * The mention path typed so far, WITHOUT the leading '@', or null when the
     * cursor is not inside a mention.
     *
     * Returns an EMPTY string for a bare "@", which is the signal to offer every
     * file rather than no suggestions at all.
     */
    fun activeToken(text: String, cursor: Int = text.length): String? {
        val start = tokenStart(text, cursor) ?: return null
        val at = cursor.coerceIn(0, text.length)
        return text.substring(start + 1, at)
    }

    /**
     * All mentions in [text], in order, without duplicates.
     *
     * Requires a non-word character before the @ so an email address
     * ("dev@example.com") is not read as a mention of "example.com".
     */
    fun parse(text: String): List<Mention> {
        val out = LinkedHashMap<String, Mention>()
        var i = 0
        while (i < text.length) {
            if (text[i] == TRIGGER && (i == 0 || !text[i - 1].isLetterOrDigit())) {
                var j = i + 1
                while (j < text.length && TOKEN_CHARS.contains(text[j])) j++
                val path = text.substring(i + 1, j)
                if (path.isNotEmpty()) out.putIfAbsent(path, Mention(path, i, j))
                i = j
            } else {
                i++
            }
        }
        return out.values.toList()
    }

    /** The distinct paths mentioned in [text]. */
    fun paths(text: String): List<String> = parse(text).map { it.path }

    /**
     * Replaces the mention token the cursor is inside with [path].
     *
     * Returns null when the cursor is not inside a token, so a caller cannot
     * accidentally clobber unrelated text by inserting a file path.
     */
    fun applyCompletion(text: String, path: String, cursor: Int = text.length): String? {
        val start = tokenStart(text, cursor) ?: return null
        val at = cursor.coerceIn(0, text.length)
        // substring(0, start) would keep the original '@' and produce "@@path".
        // Only add a separating space when the remainder does not already begin
        // with whitespace, or completing mid-sentence yields "@Main.kt  tail".
        val rest = text.substring(at)
        val separator = when {
            // At end of text, leave a trailing space so the user can keep typing
            // right after the mention.
            rest.isEmpty() -> " "
            // Mid-sentence: the original whitespace is still there, so do not
            // add a second one or the sentence gains a visible double space.
            rest.first().isWhitespace() -> rest
            else -> " $rest"
        }
        return text.substring(0, start) + TRIGGER + path + separator
    }

    /**
     * Files matching a partial mention token, best match first.
     *
     * Ranked rather than filtered alphabetically: a user typing "@mai" wants
     * Main.kt before Makefile.java, so prefix matches on the basename are
     * preferred over a substring match buried in a directory name.
     */
    fun complete(candidates: List<String>, token: String, limit: Int = 8): List<String> {
        val q = token.trimStart(TRIGGER).lowercase()
        if (q.isEmpty()) return candidates.take(limit)
        val starts = ArrayList<String>()
        val contains = ArrayList<String>()
        for (c in candidates) {
            val lc = c.lowercase()
            when {
                lc.startsWith(q) -> starts += c
                c.substringAfterLast('/').lowercase().startsWith(q) -> starts += c
                lc.contains(q) -> contains += c
            }
        }
        return (starts + contains).take(limit)
    }
}
