package com.dsh.mobile

import android.app.Application
import com.dsh.mobile.bootstrap.BootstrapManager
import com.dsh.mobile.data.RuntimeConfigStore
import com.dsh.mobile.diagnostics.DiagnosticsStore
import com.dsh.mobile.security.ApiKeyVault
import com.dsh.mobile.watchdog.EngineWatchdog

/**
 * Core entry point of the app process.
 *
 * Owns the singletons that the Foreground Service and the Compose UI share:
 * the encrypted credential vault, the runtime configuration store
 * (workspace path, mode, active agent, resolved port), the first-boot
 * [BootstrapManager], the [EngineWatchdog] health monitor, and the
 * [DiagnosticsStore] log exporter.
 */
class DshMobileApp : Application() {

    lateinit var vault: ApiKeyVault
        private set

    lateinit var configStore: RuntimeConfigStore
        private set

    lateinit var bootstrap: BootstrapManager
        private set

    lateinit var watchdog: EngineWatchdog
        private set

    lateinit var diagnostics: DiagnosticsStore
        private set

    override fun onCreate() {
        super.onCreate()
        vault = ApiKeyVault(this)
        configStore = RuntimeConfigStore(this)
        bootstrap = BootstrapManager(this)
        diagnostics = DiagnosticsStore(this)
        watchdog = EngineWatchdog(
            onRestart = {
                // Workspace data is never wiped — restart only re-mounts.
                startService(
                    android.content.Intent(
                        this,
                        com.dsh.mobile.service.AgentRuntimeService::class.java,
                    ).apply {
                        action = com.dsh.mobile.service.AgentRuntimeService.ACTION_RESTART
                    },
                )
            },
            logTail = { maxChars ->
                // Synchronous file tail — no DataStore/runBlocking on the
                // polling path. Concatenates per-agent logs newest-first.
                runCatching {
                    val dir = java.io.File(filesDir, "dsh-logs")
                    val files = dir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".log") }
                        ?.sortedByDescending { it.lastModified() }
                        .orEmpty()
                    val out = StringBuilder()
                    for (f in files) {
                        if (out.length >= maxChars) break
                        out.appendLine("--- ${f.name} ---")
                        val text = runCatching { f.readText() }.getOrDefault("")
                        out.appendLine(text.takeLast(maxChars - out.length.coerceAtMost(maxChars)))
                    }
                    out.toString().takeLast(maxChars)
                }.getOrDefault("")
            },
        )
    }
}
