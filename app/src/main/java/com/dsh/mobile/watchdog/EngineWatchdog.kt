package com.dsh.mobile.watchdog

import android.util.Log
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
 * Polls the agent's loopback port every [POLL_INTERVAL_MS] while the agent
 * is `Running`. Covers both death modes non-technical users hit:
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
 * LIVENESS CONTRACT (this is the part that must not regress): a **TCP
 * connection alone proves the server is alive**, and *any* HTTP status —
 * 200, 401, 403, 404 — also proves it is alive and answering. Measured
 * against the real `dsh --profile web`: `/health` returns **404** (DSH has
 * no such route) and `/` returns **401** (the `/api` browser-trust fence).
 * A 2xx-only check therefore kills a perfectly healthy agent in a restart
 * loop. Only transport failures (refused connection, timeout, socket error)
 * are unhealthy. See [Liveness] and [EngineHealthProbe].
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

    /**
     * Blocking probe, off the caller's thread. TCP-connect first, HTTP only
     * as a fallback — see [EngineHealthProbe] for why a 404 `/health` or a
     * 401 `/` must never be read as "dead".
     */
    private suspend fun probe(port: Int): ProbeVerdict = withContext(Dispatchers.IO) {
        EngineHealthProbe.probe(LOOPBACK_HOST, port, PROBE_TIMEOUT_MS)
    }

    private fun buildDiagnosticTrace(port: Int, result: ProbeVerdict): String = buildString {
        appendLine("— DSH Mobile watchdog report —")
        appendLine("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("endpoint: http://$LOOPBACK_HOST:$port/ (tcp $LOOPBACK_HOST:$port)")
        appendLine("reason: ${result.friendlyReason}")
        appendLine("detail: ${result.detail}")
        appendLine("consecutive failures: $consecutiveFailures")
        appendLine()
        appendLine("— agent log tail —")
        val tail = runCatching { logTail(LOG_TAIL_CHARS) }.getOrDefault("")
        appendLine(tail.ifBlank { "(no log output captured)" })
    }

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

        /**
         * Duplicated from `AgentRuntime.LOOPBACK_HOST` (a `const val`, so the
         * compiler inlines it either way) to keep the watchdog free of any
         * runtime-initialised dependency — the probe path must stay testable
         * on a plain JVM.
         */
        const val LOOPBACK_HOST = "127.0.0.1"
        const val POLL_INTERVAL_MS = 5_000L
        /**
         * Measured on-device: `dsh web` needs 30-60s to load its plugin tree
         * and bind the port. The previous 8s grace plus 3x5s of failures hit
         * that threshold at ~23s and restarted the agent while it was still
         * booting - which rebinds a new port and restarts the wait again.
         */
        const val INITIAL_GRACE_MS = 75_000L
        const val PROBE_TIMEOUT_MS = 3_000
        const val FAILURE_THRESHOLD = 3 // ~15s of failures after the grace period
        const val LOG_TAIL_CHARS = 8_000
    }
}
