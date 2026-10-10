package com.dsh.mobile.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.runtime.AgentRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

/**
 * SECURE LOG EXPORTER — backing store for the hidden diagnostics sheet.
 *
 * Tails the real agent log files on a 2s poll:
 *  - `filesDir/dsh-logs/<agent>.log` (child stdout capture — always present)
 *  - `~/.dsh/logs/harness.log` equivalent inside the runtime root when the
 *    harness writes it (`dsh-runtime/.dsh/logs/harness.log`)
 *  - `filesDir/dsh-logs/bootstrap.log` (first-boot trace)
 *
 * [copyAllToClipboard] bundles the selected log + raw traces into one
 * plain-text payload for pasting into a bug report. No keys are included —
 * [redact] strips anything shaped like an API key before display or export.
 */
class DiagnosticsStore(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tailJob: Job? = null

    private val _state = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    fun startTailing(agent: ActiveAgent) {
        stopTailing()
        tailJob = scope.launch {
            while (isActive) {
                _state.value = collect(agent)
                delay(TAIL_INTERVAL_MS)
            }
        }
    }

    fun stopTailing() {
        tailJob?.cancel()
        tailJob = null
    }

    fun destroy() {
        scope.cancel()
    }

    /** One-tap export: full bundle copied to the Android clipboard. */
    fun copyAllToClipboard(): Boolean {
        val s = _state.value
        val payload = buildString {
            appendLine("=== DSH Mobile diagnostics ===")
            appendLine("exported: ${DATE_FMT.format(Date())}")
            appendLine("agent: ${s.agent.label}")
            appendLine()
            appendLine("--- agent log (${s.agentLogFile}) ---")
            appendLine(s.agentLog.ifBlank { "(empty)" })
            appendLine()
            appendLine("--- harness.log (${s.harnessLogFile}) ---")
            appendLine(s.harnessLog.ifBlank { "(not written by this backend)" })
            appendLine()
            appendLine("--- bootstrap.log ---")
            appendLine(s.bootstrapLog.ifBlank { "(empty)" })
        }
        return try {
            val cm = appContext.getSystemService(ClipboardManager::class.java) ?: return false
            cm.setPrimaryClip(ClipData.newPlainText("dsh-diagnostics", payload))
            true
        } catch (e: Exception) {
            Log.e(TAG, "clipboard export failed", e)
            false
        }
    }

    fun copyTraceToClipboard(trace: String): Boolean = try {
        val cm = appContext.getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("dsh-trace", redact(trace)))
        true
    } catch (e: Exception) {
        Log.e(TAG, "trace copy failed", e)
        false
    }

    // -- collection ---------------------------------------------------------

    private fun collect(agent: ActiveAgent): DiagnosticsState {
        val agentFile = AgentRuntime.logFile(appContext, agent)
        val harnessFile = harnessLogFile()
        val bootstrapFile = File(appContext.filesDir, "dsh-logs/bootstrap.log")
        return DiagnosticsState(
            agent = agent,
            agentLogFile = agentFile.absolutePath,
            harnessLogFile = harnessFile.absolutePath,
            agentLog = redact(tail(agentFile, AGENT_TAIL_CHARS)),
            harnessLog = redact(tail(harnessFile, HARNESS_TAIL_CHARS)),
            bootstrapLog = redact(tail(bootstrapFile, BOOTSTRAP_TAIL_CHARS)),
        )
    }

    private fun harnessLogFile(): File {
        // Harness writes under its home dir; inside our runtime that maps to
        // <runtimeRoot>/.dsh/logs/harness.log. Also probe the legacy ~/.dsh
        // location for rooted/host-side runs.
        val inRuntime = File(AgentRuntime.runtimeRoot(appContext), ".dsh/logs/harness.log")
        if (inRuntime.exists()) return inRuntime
        val legacy = File(System.getProperty("user.home").orEmpty(), ".dsh/logs/harness.log")
        return if (legacy.exists()) legacy else inRuntime
    }

    private fun tail(file: File, maxChars: Int): String {
        if (!file.exists() || !file.canRead()) return ""
        return runCatching {
            val len = file.length()
            if (len <= maxChars) return@runCatching file.readText()
            // Read only the tail window without loading multi-MB logs.
            file.inputStream().use { input ->
                input.skip(len - maxChars)
                // Drop a possibly-partial first line.
                input.bufferedReader().run {
                    readLine()
                    readText()
                }
            }
        }.getOrDefault("")
    }

    /** Strips key-shaped secrets before display/export. Never logs raw keys. */
    private fun redact(text: String): String {
        var out = text
        // sk-… / ds-… / or-… tokens and long bare hex/base64 runs.
        out = KEY_TOKEN_RE.replace(out, "***REDACTED***")
        out = LONG_SECRET_RE.replace(out, "***REDACTED***")
        // Explicit env assignments that may echo values.
        out = ENV_ASSIGN_RE.replace(out, "$1=***REDACTED***")
        return out
    }

    data class DiagnosticsState(
        val agent: ActiveAgent = ActiveAgent.DEEPSEEK_HARNESS,
        val agentLogFile: String = "",
        val harnessLogFile: String = "",
        val agentLog: String = "",
        val harnessLog: String = "",
        val bootstrapLog: String = "",
    )

    private companion object {
        const val TAG = "DiagnosticsStore"
        const val TAIL_INTERVAL_MS = 2_000L
        const val AGENT_TAIL_CHARS = 12_000
        const val HARNESS_TAIL_CHARS = 12_000
        const val BOOTSTRAP_TAIL_CHARS = 6_000
        val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        // sk-ant-…, sk-or-v1-…, ds_…, Bearer eyJ…, or 32+ char token runs.
        val KEY_TOKEN_RE = Regex(
            "(sk-ant-[A-Za-z0-9\\-_]+|sk-or-v1-[A-Za-z0-9\\-_]+|sk-[A-Za-z0-9]{16,}|" +
                "ds_[A-Za-z0-9\\-_]+|Bearer\\s+[A-Za-z0-9\\-_.~+/=]+)",
        )
        val LONG_SECRET_RE = Regex("\\b[A-Za-z0-9_\\-]{40,}\\b")
        val ENV_ASSIGN_RE = Regex(
            "(?i)(DEEPSEEK_API_KEY|DSH_API_KEY|ANTHROPIC_API_KEY|OPENROUTER_API_KEY|OPENAI_API_KEY)\\s*=\\s*[^\\s]+",
        )
    }
}
