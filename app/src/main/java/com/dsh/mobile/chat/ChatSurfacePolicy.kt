package com.dsh.mobile.chat

/**
 * Decides whether the Chat tab can be served natively for a given agent.
 *
 * This exists because of a measured fact, not a preference:
 *
 *  - claude / codex / hermes / antigravity boot UNDER `gateway.js`, which owns
 *    the CLI child's stdin/stdout and republishes it as SSE on `GET /events`.
 *    The native surface reads that stream, so for these agents it is live.
 *
 *  - dsh and opencode boot their own servers and never touch gateway.js, so
 *    there is NO stream to read. `dsh web` additionally requires a token from
 *    its printed entry URL and speaks a WebSocket mux on `/api/remote.mux`
 *    rather than SSE.
 *
 * Rendering the native pane for dsh would produce a transcript that shows the
 * user's own messages and never an agent reply - the exact "looks finished,
 * does nothing" failure docs/REPLAN.md exists to stop. So dsh falls back to the
 * web UI, and the UI says why instead of leaving an empty box.
 *
 * When a native DSH transport lands, this is the single place to change.
 */
enum class ChatSurface {
    /** A live stream exists; the native transcript is authoritative. */
    NATIVE,

    /** No native stream exists yet; the web UI is the honest choice. */
    WEB_FALLBACK,
    ;

    companion object {

        /**
         * Agents whose output the native surface can actually read.
         *
         * Kept as ids rather than an enum reference so it stays comparable with
         * the catalogue's string ids and trivially testable.
         */
        val STREAM_CAPABLE_IDS: Set<String> = setOf(
            "claude",
            "codex",
            "hermes",
            "antigravity",
        )

        /**
         * Chooses the surface for [agentId].
         *
         * Unknown ids fall back to the web UI: an unknown agent is by definition
         * one whose stream has not been verified, and guessing "native" would
         * show a pane that may never fill.
         */
        fun forAgent(agentId: String?): ChatSurface =
            if (agentId != null && agentId in STREAM_CAPABLE_IDS) NATIVE else WEB_FALLBACK

        /** Why [agentId] got the surface it did, phrased for the user. */
        fun reasonFor(agentId: String?, surface: ChatSurface): String = when {
            surface == NATIVE -> "Native transcript, streamed from the agent."
            agentId == null -> "Waiting for an agent. Showing the web UI meanwhile."
            else -> "Native chat needs an agent that streams over the gateway. " +
                "$agentId does not, so this is the web UI."
        }
    }
}
