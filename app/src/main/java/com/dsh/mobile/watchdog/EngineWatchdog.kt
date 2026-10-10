package com.dsh.mobile.watchdog

import android.util.Log
import com.dsh.mobile.runtime.AgentRuntime
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HEALTH CHECK & CRASH RECOVERY — process watchdog.
 *
 * Polls `GET 127.0.0.1:[port]/health` every [POLL_INTERVAL_MS] while the
 * agent is `Running`. Covers both death modes non-technical users hit:
 *
 *  1. Crash — child exits; the loopback socket refuses; the service's own
 *     watchdog may already be restarting, so this layer surfaces *why*.
 *  2. Doze / adaptive battery — Android suspends the process; sockets time
 *     out without a clean exit. Detected via consecutive timeouts.
 *
 * On failure it captures the exact stack trace / last log tail into
 * [WatchdogState.Unhealthy] so the UI shows a friendly error card with a
 * single [Restart Engine] button. Restart re-issues the service boot intent
 * via the injected [onRestart] hook — workspace data is untouched because
 * the workspace dir is never wiped, only re-mounted.
 *
 * Health endpoint contract: any 2xx from `/health` (or `/` as fallback)
 * counts as healthy. CLI-gateway backends serve the gateway UI port, so the
 * same check applies uniformly.
 */
class EngineWatchdog(
    private val onRestart: () -> Unit = {},
    private val logTail: (maxChars: Int) -> String = { "" },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    private val _state = MutableStateFlow<WatchdogState>(WatchdogState.Idle)
    val state: StateFlow<WatchdogState> = _state.asStateFlow()

    private var consecutiveFailures = 0
    private var lastHealthyAt = 0L

    /** Start polling the given loopback port. Safe to call repeatedly. */
    fun start(port: Int) {
        stop()
        consecutiveFailures = 0
        _state.value = WatchdogState.Healthy(port, System.currentTimeMillis())
        lastHealthyAt = System.currentTimeMillis()
        pollJob = scope.launch {
            delay(INITIAL_GRACE_MS) // let the server finish warming up
            while (isActive) {
                checkOnce(port)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        if (_state.value !is WatchdogState.Unhealthy) {
            _state.value = WatchdogState.Idle
        }
    }

    /** [Restart Engine] button handler — clears failure state, boots again. */
    fun restartEngine() {
        consecutiveFailures = 0
        _state.value = WatchdogState.Restarting
        onRestart()
    }

    fun destroy() {
        scope.cancel()
    }

    // -- polling ------------------------------------------------------------

    private suspend fun checkOnce(port: Int) {
        val result = probe(port)
        if (result.healthy) {
            consecutiveFailures = 0
            lastHealthyAt = System.currentTimeMillis()
            val cur = _state.value
            if (cur !is WatchdogState.Healthy || cur.port != port) {
                _state.value = WatchdogState.Healthy(port, lastHealthyAt)
            }
            return
        }

        consecutiveFailures++
        Log.w(TAG, "health check failed #$consecutiveFailures: ${result.detail}")
        if (consecutiveFailures < FAILURE_THRESHOLD) return

        // Capture diagnostics once, at the moment we declare unhealthy.
        val trace = buildDiagnosticTrace(port, result)
        _state.value = WatchdogState.Unhealthy(
            port = port,
            reason = result.friendlyReason,
            detail = result.detail,
            stackTrace = trace,
            downSince = lastHealthyAt,
        )
    }

    private suspend fun probe(port: Int): ProbeResult = withContext(Dispatchers.IO) {
        // Try /health first, fall back to / for servers without a health route.
        val paths = listOf("/health", "/")
        var lastError: Exception? = null
        for (path in paths) {
            try {
                val conn = URL("http://${AgentRuntime.LOOPBACK_HOST}:$port$path")
                    .openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = PROBE_TIMEOUT_MS
                    conn.readTimeout = PROBE_TIMEOUT_MS
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "dsh-mobile-watchdog")
                    conn.connect()
                    val code = conn.responseCode
                    if (code in 200..299) {
                        return@withContext ProbeResult(healthy = true)
                    }
                    lastError = IllegalStateException("HTTP $code on $path")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        val e = lastError
        val friendly: String
        val detail: String
        when (e) {
            is java.net.ConnectException -> {
                friendly = "The engine stopped unexpectedly."
                detail = "Connection refused on 127.0.0.1:$port — process likely crashed."
            }
            is java.net.SocketTimeoutException -> {
                friendly = "The engine fell asleep (battery saver may have paused it)."
                detail = "Health probe timed out after ${PROBE_TIMEOUT_MS}ms — " +
                    "check adaptive battery / background restrictions."
            }
            else -> {
                friendly = "The engine is not responding."
                detail = e?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    ?: "Unknown probe failure."
            }
        }
        ProbeResult(healthy = false, friendlyReason = friendly, detail = detail)
    }

    private fun buildDiagnosticTrace(port: Int, result: ProbeResult): String = buildString {
        appendLine("— DSH Mobile watchdog report —")
        appendLine("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("endpoint: http://${AgentRuntime.LOOPBACK_HOST}:$port/health")
        appendLine("reason: ${result.friendlyReason}")
        appendLine("detail: ${result.detail}")
        appendLine("consecutive failures: $consecutiveFailures")
        appendLine()
        appendLine("— agent log tail —")
        val tail = runCatching { logTail(LOG_TAIL_CHARS) }.getOrDefault("")
        appendLine(tail.ifBlank { "(no log output captured)" })
    }

    private data class ProbeResult(
        val healthy: Boolean,
        val friendlyReason: String = "",
        val detail: String = "",
    )

    sealed interface WatchdogState {
        data object Idle : WatchdogState
        data class Healthy(val port: Int, val lastCheckAt: Long) : WatchdogState
        data object Restarting : WatchdogState
        data class Unhealthy(
            val port: Int,
            val reason: String,
            val detail: String,
            val stackTrace: String,
            val downSince: Long,
        ) : WatchdogState
    }

    private companion object {
        const val TAG = "EngineWatchdog"
        const val POLL_INTERVAL_MS = 5_000L
        const val INITIAL_GRACE_MS = 8_000L
        const val PROBE_TIMEOUT_MS = 3_000
        const val FAILURE_THRESHOLD = 3 // ~15s of failures before alerting
        const val LOG_TAIL_CHARS = 8_000
    }
}
