package com.dsh.mobile.chat

/**
 * Incremental `text/event-stream` framing.
 *
 * Fed one line at a time from a blocking socket read, returning a payload
 * whenever a frame completes. Incremental rather than "split the whole body"
 * because an SSE response never ends - the stream stays open for the life of
 * the agent, so there is no complete body to split.
 *
 * Per the SSE spec: a blank line dispatches, a line beginning with ':' is a
 * comment, and only ONE leading space after the field colon is stripped (so
 * `data:  x` legitimately carries a leading space).
 */
class SseFramer {

    private val data = StringBuilder()

    /** Feeds one line (without its newline). Returns the payload, or null. */
    fun onLine(line: String): String? {
        if (line.isEmpty()) {
            if (data.isEmpty()) return null
            val payload = data.toString()
            data.setLength(0)
            return payload
        }
        if (line[0] == ':') return null
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val field = line.substring(0, colon)
        var value = line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.drop(1)
        when (field) {
            // Multiple data lines in one frame join with newlines, per spec.
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
            // event/id/retry are not used: the event type is inside the JSON
            // payload, and re-reading it from the field would give two sources
            // of truth that can disagree.
            else -> Unit
        }
        return null
    }

    /** Discards any partial frame; used when the connection drops. */
    fun reset() {
        data.setLength(0)
    }
}
