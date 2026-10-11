package com.dsh.mobile.watchdog

/**
 * Outcome of a single liveness probe.
 *
 * [friendlyReason] is the one-line, non-technical sentence the UI shows on
 * the error card; [detail] is the technical counterpart kept for the
 * diagnostics report.
 */
data class ProbeVerdict(
    val healthy: Boolean,
    val friendlyReason: String = "",
    val detail: String = "",
)

/**
 * PURE liveness classification — no socket, no Android, no clock.
 *
 * WHY THIS EXISTS: the naive contract ("healthy iff HTTP 200") is wrong for
 * the real DSH server. Measured against `dsh --profile web`:
 *
 *  - `GET /health` -> **404** (DSH has no health route at all)
 *  - `GET /`        -> **401** (behind the `/api` browser-trust fence)
 *
 * Both of those are a perfectly healthy server *answering requests*. A
 * 2xx-only check therefore declares a live agent dead and restart-loops it
 * forever. The contract encoded here is:
 *
 *  - Any HTTP status whatsoever (200, 401, 403, 404, 500) => ALIVE.
 *  - Only a transport failure (connection refused, timeout, socket error)
 *    => NOT ALIVE.
 *
 * The watchdog additionally short-circuits on a bare TCP connect, so the
 * HTTP round-trip is only a fallback — see [EngineHealthProbe].
 */
object Liveness {

    /**
     * A server that returns *any* status is alive and answering. Even a 5xx
     * counts: the process is up, so a restart would not fix anything, and
     * looping on a server that is merely erroring would be destructive.
     */
    fun onHttpStatus(code: Int, path: String): ProbeVerdict = ProbeVerdict(
        healthy = true,
        detail = "HTTP $code on $path — server is answering.",
    )

    /**
     * Classifies a transport-level failure. This is the *only* unhealthy
     * path, and it deliberately preserves the friendly wording the UI ships:
     *
     *  - [java.net.ConnectException]   -> "stopped unexpectedly"
     *  - [java.net.SocketTimeoutException] -> "fell asleep" (Doze / battery saver)
     *
     * Both are looked up through the `cause` chain because Android's
     * `HttpURLConnection` (OkHttp-backed) and the JVM's differ in whether they
     * wrap the original exception; classifying only the outermost type would
     * silently downgrade a crash into "not responding".
     */
    fun onTransportFailure(
        error: Throwable?,
        port: Int,
        probeTimeoutMs: Int,
        host: String = "127.0.0.1",
    ): ProbeVerdict {
        val connect = error.findCause<java.net.ConnectException>()
        if (connect != null) {
            return ProbeVerdict(
                healthy = false,
                friendlyReason = "The engine stopped unexpectedly.",
                detail = "Connection refused on $host:$port — process likely crashed.",
            )
        }
        val timeout = error.findCause<java.net.SocketTimeoutException>()
        if (timeout != null) {
            return ProbeVerdict(
                healthy = false,
                friendlyReason = "The engine fell asleep (battery saver may have paused it).",
                detail = "Health probe timed out after ${probeTimeoutMs}ms — " +
                    "check adaptive battery / background restrictions.",
            )
        }
        return ProbeVerdict(
            healthy = false,
            friendlyReason = "The engine is not responding.",
            detail = error?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: "Unknown probe failure.",
        )
    }
}

/** First [T] in the cause chain (inclusive), or null. */
private inline fun <reified T : Throwable> Throwable?.findCause(): T? {
    var current = this
    var guard = 0
    while (current != null && guard++ < 16) {
        if (current is T) return current
        current = current.cause
    }
    return null
}