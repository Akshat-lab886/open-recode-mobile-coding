package com.dsh.mobile.chat

/**
 * Prevents duplicate user bubbles when the gateway echoes a sent message.
 *
 * The native composer renders the user's turn immediately, so the UI feels
 * instant. The gateway then echoes that same text back over SSE once it has
 * written it to the child. Without reconciliation every message appears twice.
 *
 * It cannot simply ignore all echoes: a message sent from the WEB surface never
 * went through the native composer, so its echo is the only signal that it
 * happened and must be shown. The reconciler therefore suppresses exactly as
 * many echoes as it was told to expect, in order, and passes everything else
 * through.
 *
 * Bounded: if a send never produces an echo (the child died first), the
 * expectation must not leak and swallow a genuine echo later.
 */
class EchoReconciler(private val maxPending: Int = 20) {

    private val pending = ArrayDeque<String>()
    private var suppressed: Int = 0

    /** Records that [text] was rendered locally and an echo is expected. */
    @Synchronized
    fun expectEcho(text: String) {
        pending.addLast(text)
        while (pending.size > maxPending) pending.removeFirst()
    }

    /**
     * Handles an incoming echo.
     *
     * Returns true when the caller should render it, false when it is the echo
     * of a message already on screen.
     */
    @Synchronized
    fun onEcho(text: String): Boolean {
        // Prefer the oldest matching expectation so ordering is preserved, but
        // fall back to dropping the oldest unknown one rather than leaking.
        val index = pending.indexOfFirst { it == text }
        if (index >= 0) {
            pending.removeAt(index)
            suppressed++
            return false
        }
        return true
    }

    /** How many echoes have been suppressed; useful for diagnostics and tests. */
    @Synchronized
    fun suppressedCount(): Int = suppressed

    @Synchronized
    fun clear() {
        pending.clear()
        suppressed = 0
    }
}
