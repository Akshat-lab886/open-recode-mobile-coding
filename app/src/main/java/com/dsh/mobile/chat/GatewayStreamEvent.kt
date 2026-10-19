package com.dsh.mobile.chat

/**
 * The events `gateway.js` publishes on `GET /events`.
 *
 * Mirrors the emitter in `assets/visual-gateway/gateway.js`. This is the live
 * source the native conversation needs: the WebView's `gateway.js` owns the CLI
 * child's stdin/stdout and already parses it, so consuming that stream means the
 * native surface gets real agent output WITHOUT re-plumbing the launch path.
 */
sealed interface GatewayStreamEvent {
    /** The user's own turn, echoed back after it was written to the child. */
    data class UserEcho(val text: String) : GatewayStreamEvent

    /** Visible agent output. */
    data class AssistantText(val text: String) : GatewayStreamEvent

    /** The CLI is blocked waiting for a yes/no on stdin. */
    data class ApprovalRequired(
        val id: String,
        val kind: String,
        val summary: String,
    ) : GatewayStreamEvent

    data class Failed(val message: String) : GatewayStreamEvent

    data class Exited(val code: Int?) : GatewayStreamEvent

    /** Raw stdout/stderr, and anything unrecognised. Not conversation. */
    data object Ignored : GatewayStreamEvent
}

object GatewayStreamEvents {

    /**
     * Maps one SSE payload to an event.
     *
     * Unrecognised types are [GatewayStreamEvent.Ignored] rather than an error:
     * `gateway.js` is a separate process that ships independently, so a newer
     * gateway emitting a type this build has never heard of must degrade to
     * "not shown" instead of tearing down the stream.
     */
    fun parse(payload: String): GatewayStreamEvent {
        val type = FlatJson.string(payload, "type") ?: return GatewayStreamEvent.Ignored
        return when (type) {
            "echo" -> {
                val text = FlatJson.string(payload, "text")
                if (text.isNullOrEmpty()) GatewayStreamEvent.Ignored
                else GatewayStreamEvent.UserEcho(text)
            }
            "message" -> {
                val text = FlatJson.string(payload, "text")
                if (text.isNullOrEmpty()) GatewayStreamEvent.Ignored
                else GatewayStreamEvent.AssistantText(text)
            }
            "approval" -> GatewayStreamEvent.ApprovalRequired(
                id = FlatJson.string(payload, "id") ?: "unknown",
                kind = FlatJson.string(payload, "kind") ?: "COMMAND",
                summary = FlatJson.string(payload, "summary") ?: "",
            )
            "error" -> GatewayStreamEvent.Failed(
                FlatJson.string(payload, "message") ?: "Agent reported an error",
            )
            "exit" -> GatewayStreamEvent.Exited(FlatJson.int(payload, "code"))
            // "raw" is deliberately dropped: raw stdout is process noise, and
            // rendering it would bury the reply. It still reaches dsh-logs/.
            else -> GatewayStreamEvent.Ignored
        }
    }
}
