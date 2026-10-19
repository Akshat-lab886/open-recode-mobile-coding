package com.dsh.mobile.proxy

import android.util.Log
import com.dsh.mobile.data.ActiveAgent
import java.io.BufferedReader
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Visual proxy gateway for CLI-only agents (Claude Code, Codex).
 *
 * The agent child process keeps its stdin/stdout pipes attached here instead
 * of any terminal. This class:
 *
 *  1. Streams stdout/stderr lines into structured [GatewayEvent]s
 *     (chat text vs. tool-call proposal vs. approval prompt).
 *  2. Serves the parsed conversation to the WebView over an in-app
 *     loopback WebSocket (implemented in assets/gateway-ui.html), giving
 *     CLI agents the same chat-and-file interface as DSH web.
 *  3. Answers stdin confirmation prompts ("Do you want to run this
 *     command?", "Allow file modification?") with the user's Bottom Sheet
 *     decision instead of typed `y/n`.
 */
class VisualGateway(
    private val agent: ActiveAgent,
    private val workspace: File,
) {
    private val pendingApprovals = ConcurrentLinkedQueue<ApprovalRequest>()
    private val listeners = mutableSetOf<(GatewayEvent) -> Unit>()
    private val running = AtomicBoolean(false)

    @Volatile
    private var childStdin: OutputStream? = null

    fun addListener(listener: (GatewayEvent) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    fun removeListener(listener: (GatewayEvent) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    private fun emit(event: GatewayEvent) {
        val snapshot: List<(GatewayEvent) -> Unit>
        synchronized(listeners) { snapshot = listeners.toList() }
        snapshot.forEach { runCatching { it(event) }.onFailure {
            Log.w(TAG, "gateway listener failed", it)
        } }
    }

    /**
     * Attach to an already-started [Process]. Reads both streams on
     * background threads; call [detach] to stop.
     */
    fun attach(process: Process) {
        if (!running.compareAndSet(false, true)) return
        childStdin = process.outputStream
        Thread({ pump(process.inputStream.bufferedReader(), "stdout") },
            "gateway-${agent.name}-out").apply { isDaemon = true; start() }
        Thread({ pump(process.errorStream.bufferedReader(), "stderr") },
            "gateway-${agent.name}-err").apply { isDaemon = true; start() }
    }

    fun detach() {
        running.set(false)
        runCatching { childStdin?.close() }
        childStdin = null
    }

    /**
     * Resolve an approval prompt detected on the stream. `approved=true`
     * writes the affirmative token the CLI expects; otherwise the denial
     * token. Both end with a newline so the blocked stdin read completes.
     */
    suspend fun resolveApproval(requestId: String, approved: Boolean) =
        withContext(Dispatchers.IO) {
            val affirmative = AFFIRMATIVE_TOKENS[agent] ?: "yes"
            val denial = "no"
            runCatching {
                childStdin?.let {
                    it.write(((if (approved) affirmative else denial) + "\n").toByteArray())
                    it.flush()
                }
            }.onFailure { Log.e(TAG, "failed to answer approval $requestId", it) }
            pendingApprovals.removeIf { it.id == requestId }
            emit(GatewayEvent.ApprovalResolved(requestId, approved))
        }

    /**
     * Free-form input from the accessory bar / gateway-ui composer.
     * Fire-and-forget: the CLI reads it as the next stdin line.
     */
    fun sendInput(text: String) {
        runCatching {
            childStdin?.let {
                it.write((text + "\n").toByteArray())
                it.flush()
            }
        }.onFailure { Log.e(TAG, "failed to send gateway input", it) }
    }

    private fun pump(reader: BufferedReader, stream: String) {
        var buffer = StringBuilder()
        try {
            while (running.get()) {
                val line = reader.readLine() ?: break
                val text = line
                Log.d(TAG, "[$stream] $text")
                emit(GatewayEvent.RawLine(stream, text))
                detectApproval(text)?.let { request ->
                    pendingApprovals += request
                    emit(GatewayEvent.ApprovalRequested(request))
                }
                val chat = extractChatText(text, buffer)
                if (chat != null) emit(GatewayEvent.ChatDelta(chat))
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "stream pump ended", e)
        } finally {
            runCatching { reader.close() }
            if (buffer.isNotEmpty()) emit(GatewayEvent.ChatDelta(buffer.toString()))
        }
    }

    /** Matches stdin confirmation prompts across Claude/Codex/OpenCode CLIs. */
    private fun detectApproval(line: String): ApprovalRequest? {
        val lowered = line.lowercase()
        val kind = when {
            APPROVAL_PATTERNS.any { it.containsMatchIn(lowered) } -> ApprovalKind.COMMAND
            FILE_PATTERNS.any { it.containsMatchIn(lowered) } -> ApprovalKind.FILE_WRITE
            else -> return null
        }
        return ApprovalRequest(
            id = "appr-${System.currentTimeMillis()}-${line.hashCode()}",
            agent = agent,
            kind = kind,
            summary = line.trim().take(280),
            rawLine = line.trim(),
        )
    }

    /** Best-effort extraction of human-readable text (incl. JSONL deltas). */
    private fun extractChatText(line: String, buffer: StringBuilder): String? {
        val trimmed = line.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching {
                val obj = JSONObject(trimmed)
                for (key in arrayOf("text", "delta", "content", "message")) {
                    val v = obj.optString(key, "")
                    if (v.isNotBlank()) {
                        buffer.append(v)
                        return v
                    }
                }
            }
        }
        if (trimmed.isEmpty() || looksLikePrompt(trimmed)) return null
        buffer.append(trimmed).append('\n')
        return trimmed
    }

    private fun looksLikePrompt(text: String): Boolean {
        val lowered = text.lowercase()
        return APPROVAL_PATTERNS.any { it.containsMatchIn(lowered) } ||
            FILE_PATTERNS.any { it.containsMatchIn(lowered) }
    }

    enum class ApprovalKind { COMMAND, FILE_WRITE }

    data class ApprovalRequest(
        val id: String,
        val agent: ActiveAgent,
        val kind: ApprovalKind,
        val summary: String,
        val rawLine: String,
    )

    sealed interface GatewayEvent {
        data class RawLine(val stream: String, val text: String) : GatewayEvent
        data class ChatDelta(val text: String) : GatewayEvent
        data class ApprovalRequested(val request: ApprovalRequest) : GatewayEvent
        data class ApprovalResolved(val requestId: String, val approved: Boolean) : GatewayEvent
    }

    private companion object {
        const val TAG = "VisualGateway"

        val APPROVAL_PATTERNS = listOf(
            Regex("do you want to run"),
            Regex("allow .{0,40}command"),
            // Real CLIs (Claude Code et al.) word it "Allow <agent> to run <cmd>?"
            // with no literal "command" — without this the agent hangs on a
            // blocked stdin read and no approval sheet is ever shown.
            Regex("allow .{0,40}to run"),
            Regex("confirm .{0,40}execut"),
            Regex("\\[y/n\\]"),
            Regex("proceed\\?"),
        )
        val FILE_PATTERNS = listOf(
            Regex("allow file modification"),
            Regex("overwrite .{0,60}\\?"),
            Regex("create file .{0,60}\\?"),
            Regex("apply diff"),
        )
        /** Single source of truth lives in [ApprovalTokens]. */
        val AFFIRMATIVE_TOKENS: Map<ActiveAgent, String> get() = ApprovalTokens.AFFIRMATIVE_TOKENS

    }
}

/**
 * The line that answers an approval prompt for a given agent.
 *
 * A CLI approval is not an RPC: it is a line on stdin. The accepted token
 * differs per tool (codex wants `y`, most want `yes`), so answering with the
 * wrong one leaves the child blocked while the UI looks like it did something.
 *
 * Lives outside [VisualGateway] because the gateway is not the only caller:
 * the native surface answers through `gateway.js`'s `/send` route, since
 * gateway.js owns the child's stdin.
 */
object ApprovalTokens {

    /** The affirmative stdin token per tool. Codex is the odd one out. */
    val AFFIRMATIVE_TOKENS: Map<ActiveAgent, String> = mapOf(
        ActiveAgent.CLAUDE_CODE to "yes",
        ActiveAgent.CODEX to "y",
        ActiveAgent.OPENCODE to "yes",
        ActiveAgent.DEEPSEEK_HARNESS to "yes",
        ActiveAgent.HERMES to "yes",
        ActiveAgent.ANTIGRAVITY to "yes",
    )

    fun answerFor(agentId: String, approved: Boolean): String {
        val affirmative = ActiveAgent.entries
            .firstOrNull { it.id == agentId }
            ?.let { AFFIRMATIVE_TOKENS[it] }
            ?: "yes"
        return if (approved) affirmative else "no"
    }
}
