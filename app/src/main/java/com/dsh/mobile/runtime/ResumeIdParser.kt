package com.dsh.mobile.runtime

/**
 * Pulls an agent's own conversation/session id out of its stdout or event text.
 *
 * ## Why this exists
 *
 * A mode change (or a crash-respawn) currently terminates the agent and boots a
 * fresh one, so the user loses the running conversation. Mobile-Harness solves
 * this with `bindResume`: it captures the CLI's conversation id from the
 * agent's first `Initialized`/init event and feeds it back on the next spawn as
 * a resume flag (`antigravityCommand` appends `--resume <conversationId>`).
 *
 * DSH Mobile already has the argv half ([AgentCatalog.ControlAdapter.resumeIdArgFor]
 * + `ControlBundle.resumeId`); this object is the capture half. It is a pure
 * function over one line of agent output, so it is unit-testable without a
 * running CLI — the same shape as [com.dsh.mobile.service.AgentLaunch.extractLaunchUrl].
 *
 * ## Key spelling is per-agent
 *
 * Every CLI spells its id differently, exactly as Mobile-Harness's `bindResume`
 * records: claude=`sessionId`, codex=`threadId`, antigravity=`conversationId`,
 * opencode/hermes/omp/devin=`cliSessionId`. [keysFor] returns the spellings to
 * try for an agent, most-specific first.
 *
 * ## What is NOT claimed
 *
 * The key names are the ones the upstream adapters bind. Whether a given CLI
 * prints that key on stdout in a given version is unverified for codex/claude/
 * opencode (no runnable install in this environment); the parser therefore
 * accepts the three common serializations (JSON, `key: value`, `key=value`) and
 * is deliberately conservative about what counts as an id, so a miss degrades
 * to "no resume" rather than to a wrong `--resume` value.
 */
object ResumeIdParser {

    /** JSON/log keys this agent uses for its conversation id, most-specific first. */
    fun keysFor(agentId: String): List<String> = when (agentId) {
        "antigravity" -> listOf("conversationId", "conversation_id")
        "codex" -> listOf("threadId", "thread_id")
        "claude" -> listOf("sessionId", "session_id")
        "opencode" -> listOf("sessionId", "session_id", "cliSessionId")
        "hermes" -> listOf("cliSessionId", "sessionId", "session_id")
        "dsh" -> listOf("sessionId", "session_id")
        else -> listOf("sessionId", "session_id", "conversationId", "threadId")
    }

    /**
     * A value must look like an opaque id, not an English word.
     *
     * Requires at least one digit: agent output is full of `"sessionId": "none"`
     * / `"threadId": "unknown"` placeholders and a resume flag built from one of
     * those makes the next spawn exit non-zero.
     */
    fun isPlausibleId(value: String): Boolean =
        value.length in 8..128 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
            value.any { it.isDigit() }

    /**
     * Extracts [agentId]'s conversation id from one line, or null.
     *
     * Accepts the three serializations seen in CLI output:
     *   - JSON:  `{"conversationId":"c1a2b3d4-..."}`
     *   - log:   `session id: c1a2b3d4-...` / `threadId: c1a2b3d4-...`
     *   - kv:    `SESSION_ID=c1a2b3d4-...`
     */
    fun extractResumeId(agentId: String, line: String): String? {
        if (line.isBlank() || line.length > MAX_LINE) return null
        for (key in keysFor(agentId)) {
            val match = pattern(key).find(line) ?: continue
            val value = match.groupValues[1]
            if (isPlausibleId(value)) return value
        }
        return null
    }

    private fun pattern(key: String): Regex =
        Regex(
            // (?<![A-Za-z0-9_]) keeps `sessionId` from matching inside
            // `cliSessionId`: the per-agent key list must be the only thing that
            // decides what a given harness's id is called.
            """(?<![A-Za-z0-9_])["']?${Regex.escape(key)}["']?\s*[:=]\s*["']?([A-Za-z0-9][A-Za-z0-9_-]{7,127})""",
            RegexOption.IGNORE_CASE,
        )

    /** A single log line should never be this long; reject to bound the regex input. */
    private const val MAX_LINE = 8_192
}
