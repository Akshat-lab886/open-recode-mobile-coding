package com.dsh.mobile.chat

/**
 * A minimal parser for flat JSON objects.
 *
 * The gateway's SSE payloads are flat objects of strings and numbers, and
 * `org.json` cannot be used here: this module sets
 * `unitTests.isReturnDefaultValues = true`, so in a JVM unit test JSONObject
 * silently returns null instead of parsing. A test asserting on that would pass
 * while proving nothing.
 *
 * So this is deliberately a real (small) parser rather than a regex. A regex
 * over `"key":` matches a key appearing INSIDE another value, which is exactly
 * how an agent's message text containing `"type":` would be misread as an event
 * type. This walks the object respecting string and escape state.
 *
 * Unsupported by design: nested objects and arrays are skipped and reported as
 * null, because no gateway event uses them. Unknown shapes are ignored rather
 * than guessed at.
 */
object FlatJson {

    /** Parses [json] into a flat map, or null when it is not a JSON object. */
    fun parse(json: String): Map<String, String?>? {
        val p = Parser(json)
        return try {
            p.parseObject()
        } catch (_: Exception) {
            null
        }
    }

    /** The string value for [key], or null when absent or not a string. */
    fun string(json: String, key: String): String? = parse(json)?.get(key)

    /** The integer value for [key], or null when absent or not a number. */
    fun int(json: String, key: String): Int? = parse(json)?.get(key)?.toIntOrNull()

    private class Parser(private val s: String) {
        private var i = 0

        private fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun expect(c: Char) {
            ws()
            if (i >= s.length || s[i] != c) throw IllegalStateException("expected $c at $i")
            i++
        }

        fun parseObject(): Map<String, String?> {
            val out = LinkedHashMap<String, String?>()
            expect('{')
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val key = readString()
                expect(':')
                ws()
                out[key] = readValue()
                ws()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == '}' -> { i++; return out }
                    else -> throw IllegalStateException("expected , or } at $i")
                }
            }
        }

        private fun readValue(): String? {
            ws()
            if (i >= s.length) throw IllegalStateException("truncated")
            return when (val c = s[i]) {
                '"' -> readString()
                '{', '[' -> { skipNested(c); null }
                't' -> { literal("true"); "true" }
                'f' -> { literal("false"); "false" }
                'n' -> { literal("null"); null }
                else -> if (c == '-' || c.isDigit()) readNumber()
                else throw IllegalStateException("bad value at $i")
            }
        }

        /** Braces and brackets inside strings must not end the value early. */
        private fun skipNested(open: Char) {
            val close = if (open == '{') '}' else ']'
            var depth = 0
            var inString = false
            var escaped = false
            while (i < s.length) {
                val c = s[i]
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == open -> depth++
                    !inString && c == close -> {
                        depth--
                        if (depth == 0) { i++; return }
                    }
                }
                i++
            }
            throw IllegalStateException("unterminated nested value")
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) throw IllegalStateException("bad literal at $i")
            i += word.length
        }

        private fun readNumber(): String {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i)
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (i >= s.length) throw IllegalStateException("dangling escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw IllegalStateException("bad \\u")
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw IllegalStateException("unterminated string")
        }
    }
}
