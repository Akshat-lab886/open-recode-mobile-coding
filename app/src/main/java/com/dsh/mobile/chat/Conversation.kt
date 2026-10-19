package com.dsh.mobile.chat

/**
 * The conversation the agent surface renders.
 *
 * A plain immutable value, and every transition is a pure function on it. That
 * is deliberate: the previous architecture pushed this state INTO a WebView and
 * let JavaScript own it, which is why none of it could be tested and why the
 * UI could not be reasoned about without a browser.
 */
data class Conversation(
    val messages: List<ChatMessage> = emptyList(),
    val pendingApprovals: List<ToolApproval> = emptyList(),
    /** True between the user's send and the first agent output. */
    val awaitingAgent: Boolean = false,
) {
    val isEmpty: Boolean get() = messages.isEmpty()
}

/**
 * Turns a stream of agent events into [Conversation] state.
 *
 * Agents emit many deltas per turn, so the central rule is that consecutive
 * deltas belong to ONE message. Getting that wrong produces a bubble per token,
 * which is the classic way a streaming chat UI looks broken.
 */
object ConversationReducer {

    /**
     * Messages retained. A long session would otherwise hold every byte the
     * agent ever wrote for the life of the process - the same unbounded-growth
     * mistake the terminal transcript had.
     */
    const val MAX_MESSAGES: Int = 400

    /** Cap on a single message, so one runaway reply cannot exhaust memory. */
    const val MAX_MESSAGE_CHARS: Int = 200_000

    private const val TRUNCATION_NOTICE = "\n\n... (truncated)"

    private fun bound(messages: List<ChatMessage>): List<ChatMessage> =
        if (messages.size <= MAX_MESSAGES) messages
        else messages.subList(messages.size - MAX_MESSAGES, messages.size).toList()

    /**
     * Appends [delta], keeping the result within [MAX_MESSAGE_CHARS].
     *
     * The truncation notice is budgeted INSIDE the cap and is guaranteed to be
     * present whenever anything was dropped. An earlier revision returned early
     * once the message hit the cap, which silently discarded the rest of a
     * runaway reply with no indication that anything was missing - the user
     * would have read a cut-off answer believing it was complete.
     */
    private fun appendCapped(existing: String, delta: String): String {
        if (existing.endsWith(TRUNCATION_NOTICE)) return existing
        if (existing.length + delta.length <= MAX_MESSAGE_CHARS) return existing + delta
        val keep = (MAX_MESSAGE_CHARS - TRUNCATION_NOTICE.length).coerceAtLeast(0)
        return existing.take(keep) + TRUNCATION_NOTICE
    }

    /** The user's turn. Ends any in-flight agent message. */
    fun userSaid(
        state: Conversation,
        text: String,
        attachments: List<ChatAttachment> = emptyList(),
    ): Conversation {
        val settled = state.messages.map { if (it.streaming) it.copy(streaming = false) else it }
        val message = ChatMessage(
            id = "user-${settled.size}",
            role = ChatRole.USER,
            text = text,
            attachments = attachments,
        )
        return state.copy(
            messages = bound(settled + message),
            awaitingAgent = true,
        )
    }

    /**
     * A chunk of agent text.
     *
     * Appends to the trailing agent message when it is still streaming, and
     * otherwise opens a new one. A user message in between therefore starts a
     * fresh reply rather than appending to the previous turn.
     */
    fun agentDelta(state: Conversation, delta: String): Conversation {
        if (delta.isEmpty()) return state
        val last = state.messages.lastOrNull()
        if (last != null && last.role == ChatRole.AGENT && last.streaming) {
            val updated = last.copy(text = appendCapped(last.text, delta))
            return state.copy(messages = state.messages.dropLast(1) + updated, awaitingAgent = false)
        }
        val message = ChatMessage(
            id = "agent-${state.messages.size}",
            role = ChatRole.AGENT,
            text = delta,
            streaming = true,
        )
        return state.copy(
            messages = bound(state.messages + message),
            awaitingAgent = false,
        )
    }

    /** The agent stopped; freeze the trailing message and record the duration. */
    fun agentFinished(state: Conversation, workedMillis: Long = 0L): Conversation {
        val last = state.messages.lastOrNull() ?: return state.copy(awaitingAgent = false)
        if (last.role != ChatRole.AGENT) return state.copy(awaitingAgent = false)
        val updated = last.copy(streaming = false, workedMillis = workedMillis)
        return state.copy(messages = state.messages.dropLast(1) + updated, awaitingAgent = false)
    }

    /** The turn failed. Surfaced as a message, never swallowed. */
    fun agentFailed(state: Conversation, error: String): Conversation {
        val settled = state.messages.map { if (it.streaming) it.copy(streaming = false) else it }
        val failed = ChatMessage(
            id = "error-${settled.size}",
            role = ChatRole.AGENT,
            text = "",
            error = error,
        )
        return state.copy(messages = bound(settled + failed), awaitingAgent = false)
    }

    /** A tool call needs a decision. */
    fun toolRequested(state: Conversation, approval: ToolApproval): Conversation {
        // Re-requesting the same approval id must not stack duplicates.
        if (state.pendingApprovals.any { it.id == approval.id }) return state
        return state.copy(pendingApprovals = state.pendingApprovals + approval)
    }

    /** The decision was made, here or elsewhere. */
    fun toolResolved(state: Conversation, approvalId: String): Conversation =
        state.copy(pendingApprovals = state.pendingApprovals.filterNot { it.id == approvalId })

    fun cleared(): Conversation = Conversation()

    /** A one-line summary for a notification or a session list. */
    fun preview(state: Conversation, maxChars: Int = 80): String {
        val last = state.messages.lastOrNull { it.text.isNotBlank() } ?: return ""
        val flat = last.text.replace('\n', ' ').trim()
        return if (flat.length <= maxChars) flat else flat.take(maxChars - 1) + "\u2026"
    }
}

/** Convenience: run several events through the reducer in order. */
fun Conversation.apply(vararg events: (Conversation) -> Conversation): Conversation =
    events.fold(this) { acc, event -> event(acc) }
