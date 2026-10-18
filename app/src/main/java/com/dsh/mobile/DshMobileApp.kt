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

    /** Local-only session history, pruned to bounded size. */
    lateinit var sessionHistory: com.dsh.mobile.data.SessionHistoryStore

    /** Per-agent provider choice, base URL and encrypted key. */
    lateinit var perAgentProviders: com.dsh.mobile.security.PerAgentProviderStore

    /** Which agents the user chose to install, and whether that happened. */
    lateinit var agentInstalls: com.dsh.mobile.data.AgentInstallStore

    /**
     * Last conversation id each agent reported, so a mode change / respawn can
     * resume that conversation instead of starting fresh (Mobile-Harness
     * `bindResume`). Capture: ResumeIdParser; argv: ControlAdapter.resumeIdArgFor.
     */
    lateinit var resumeIds: com.dsh.mobile.data.ResumeIdStore

    /** On-demand npm installer for the agents the user picked. */
    lateinit var agentInstaller: com.dsh.mobile.runtime.AgentInstaller

    /**
     * Scope for agent installation.
     *
     * On the Application, not the Activity: an install is minutes of work that
     * must survive the user switching apps or rotating mid-download.
     */
    val agentInstallScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /** Fire-and-forget scope for archiving session summaries. */
    val sessionHistoryScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    override fun onCreate() {
        super.onCreate()
        vault = ApiKeyVault(this)
        configStore = RuntimeConfigStore(this)
        bootstrap = BootstrapManager(this)
        diagnostics = DiagnosticsStore(this)
        sessionHistory = com.dsh.mobile.data.SessionHistoryStore(this)
        perAgentProviders = com.dsh.mobile.security.PerAgentProviderStore(this, vault)
        agentInstalls = com.dsh.mobile.data.AgentInstallStore(this)
        resumeIds = com.dsh.mobile.data.ResumeIdStore(this)
        agentInstaller = com.dsh.mobile.runtime.AgentInstaller(this)
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
