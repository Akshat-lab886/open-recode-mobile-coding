package com.dsh.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dsh.mobile.DshMobileApp
import com.dsh.mobile.MainActivity
import com.dsh.mobile.bootstrap.BootstrapManager
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.RuntimeConfig
import com.dsh.mobile.proxy.VisualGateway
import com.dsh.mobile.runtime.AgentRuntime
import com.dsh.mobile.runtime.RuntimePayloadState
import com.dsh.mobile.ui.AgentServiceStatusFlow
import com.dsh.mobile.ui.GatewayEventBus
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SERVICE ORCHESTRATION — mounts directories, injects keys, boots agents.
 *
 * Persistent Android Foreground Service (sticky). On [ACTION_BOOT] it:
 *  1. Reads [RuntimeConfig] (workspace path, agent mode, active agent).
 *  2. Resolves the workspace: the SAF-picked dir (or the default
 *     app-private workspace) bind-mounted at `/workspace` for PRoot.
 *  3. Pulls API keys from [com.dsh.mobile.security.ApiKeyVault] and injects
 *     them silently as child-process environment variables.
 *  4. Picks a free loopback port, spawns the agent (`npx @deepseek-ai/dsh
 *     web` equivalent, or the visual-gateway wrapper for CLI-only agents),
 *     bound tightly to 127.0.0.1.
 *  5. Health-checks the local port; restarts with backoff on crashes.
 *  6. For CLI-only agents, attaches [VisualGateway] so stdin confirmations
 *     surface as native approval sheets instead of terminal prompts.
 *
 * Every status/approval change is published to [AgentServiceStatusFlow] so
 * the Compose UI updates live; gateway stream events also fan out over
 * [GatewayEventBus] to the attached WebView. Approvals are answered via
 * [resolveApproval], which writes to the child's stdin.
 */
class AgentRuntimeService : LifecycleService() {

    private var bootJob: Job? = null
    private var child: Process? = null
    private var gateway: VisualGateway? = null

    private val _status = MutableStateFlow<ServiceStatus>(ServiceStatus.Idle)
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    private val _approvalRequests =
        MutableStateFlow<List<VisualGateway.ApprovalRequest>>(emptyList())

    private fun setStatus(status: ServiceStatus) {
        _status.value = status
        AgentServiceStatusFlow.publishStatus(status)
    }

    private fun setApprovals(approvals: List<VisualGateway.ApprovalRequest>) {
        _approvalRequests.value = approvals
        AgentServiceStatusFlow.publishApprovals(approvals)
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        ServiceHolder.service = this
        setStatus(_status.value)
        setApprovals(_approvalRequests.value)
    }

    override fun onDestroy() {
        stopChild()
        ServiceHolder.service = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return Binder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification("Starting agent runtime…"))
        when (intent?.action) {
            ACTION_BOOT, ACTION_RESTART -> boot()
            ACTION_STOP -> {
                stopChild()
                setStatus(ServiceStatus.Idle)
                stopSelf()
            }
        }
        return START_STICKY
    }

    // -- boot sequence ------------------------------------------------------

    /**
     * How long to wait for `dsh web` to print its authenticated banner.
     * Measured on-device boot is well over 30s, so this is generous; the
     * late-banner watcher covers anything longer.
     */
    private val LAUNCH_URL_TIMEOUT_MS = 60_000L

    private fun boot() {
        // A fresh intent (e.g. credential save, workspace/mode switch) must
        // pre-empt the running boot/watchdog job — never be dropped by it.
        bootJob?.cancel()
        stopChildLocked()
        bootJob = lifecycleScope.launch {
            // 0. First-boot gate: the agent binaries only exist after the
            // bootstrap script finishes. Block here (with visible progress in
            // the UI via BootstrapManager.state) instead of spawning a child
            // that instantly crash-loops.
            val bootstrap = (application as DshMobileApp).bootstrap
            if (!bootstrap.isBootstrapped()) {
                setStatus(ServiceStatus.Booting("Running first-time setup…"))
                bootstrap.ensureBootstrapped()
                // Suspend until bootstrap completes; bootJob cancellation
                // (fresh intent / stop) aborts the wait cleanly.
                withContext(Dispatchers.IO) {
                    while (true) {
                        coroutineContext.ensureActive()
                        if (bootstrap.isBootstrapped()) break
                        val s = bootstrap.state.value
                        if (s is BootstrapManager.BootstrapState.Failed) {
                            throw IllegalStateException(s.message)
                        }
                        kotlinx.coroutines.delay(500)
                    }
                }
            }
            Log.i(TAG, "boot: bootstrap gate passed")
            setStatus(ServiceStatus.Booting("Preparing runtime…"))
            try {
                val app = application as DshMobileApp

                // Gateway bundle ships inside the APK but argv points at the
                // filesystem; copy it out before anything spawns.
                Log.i(TAG, "boot: staging bundled assets")
                AgentRuntime.stageBundledAssets(this@AgentRuntimeService)
                Log.i(TAG, "boot: assets staged")

                // Surface payload gaps up front rather than letting the child
                // die with an opaque "no such file" deep in the boot.
                val payload = AgentRuntime.payloadState(this@AgentRuntimeService)
                Log.i(TAG, "boot: payload state = $payload")
                if (payload is RuntimePayloadState.NotConfigured) {
                    val names = payload.missing.joinToString(", ") {
                        "${it.id} (${it.description})"
                    }
                    setStatus(
                        ServiceStatus.Error(
                            message = "Runtime payload not installed.\n\n" +
                                "Missing: $names\n\n" +
                                "Node.js publishes no Android build, so these artifacts " +
                                "cannot be bundled with the app. Point the app at a payload " +
                                "source (see runtime-assets.json) and restart.",
                            retryable = true,
                        ),
                    )
                    return@launch
                }

                val config = app.configStore.snapshot()
                val agentsHome = AgentRuntime.agentsDir(this@AgentRuntimeService)
                val runnable = AgentRuntime.resolveRunnableAgent(config.agent, agentsHome)
                if (runnable != config.agent) {
                    Log.w(
                        TAG,
                        "configured agent ${config.agent} is not installed; " +
                            "falling back to $runnable (installed: " +
                            "${AgentRuntime.installedAgents(agentsHome)})",
                    )
                }
                val agent = runnable
                Log.i(TAG, "boot: agent=${config.agent} -> $agent proot=${AgentRuntime.isProotActive(this@AgentRuntimeService)}")
                val workspace = resolveWorkspace(config)
                val port = AgentRuntime.findFreePort(config.loopbackPort)
                app.configStore.setPort(port)

                // 1. Mount / prepare directories.
                setStatus(ServiceStatus.Booting("Mounting workspace…"))
                prepareMounts(workspace)

                // PRoot guests need a resolv.conf; a stock Alpine minirootfs
                // has none, so agent installs would fail on DNS.
                if (AgentRuntime.isProotActive(this@AgentRuntimeService)) {
                    AgentRuntime.ensureGuestDns(this@AgentRuntimeService)
                }

                // 2. Inject API keys silently as env vars.
                val apiEnv = app.vault.asEnvironment()
                val env = AgentRuntime.buildEnvironment(
                    apiEnv = apiEnv,
                    workspace = workspace,
                    mode = config.mode,
                    port = port,
                    insideProot = AgentRuntime.isProotActive(this@AgentRuntimeService),
                    prootTmpDir = AgentRuntime.prootTmpDir(this@AgentRuntimeService),
                    native = AgentRuntime.nativePaths(this@AgentRuntimeService),
                )
                if (apiEnv.isEmpty()) {
                    Log.w(TAG, "no API keys stored — agent will boot unauthenticated")
                }

                // 3. Spawn the agent bound to loopback.
                Log.i(TAG, "boot: spawning; node=${AgentRuntime.nodeBinary(this@AgentRuntimeService).absolutePath}")
                setStatus(ServiceStatus.Booting("Booting ${agent.label}…"))
                val command = AgentRuntime.buildCommand(
                    context = this@AgentRuntimeService,
                    agent = agent,
                    workspace = workspace,
                    port = port,
                )
                Log.i(TAG, "exec: ${command.joinToString(" ")}")

                // stdout is teed to the log AND scanned: `dsh web` prints its
                // real tokenized entry URL there, and that token is the only
                // thing that authenticates — the bare origin answers 401.
                val launchUrl = AtomicReference<String?>(null)
                val logFile = AgentRuntime.logFile(this@AgentRuntimeService, agent)

                val process = withContext(Dispatchers.IO) {
                    ProcessBuilder(command)
                        .directory(workspace)
                        .apply {
                            environment().putAll(env)
                            redirectErrorStream(false)
                        }
                        .start()
                }
                child = process

                AgentLaunch.pump(process.inputStream, logFile, launchUrl) { line ->
                    Log.d(TAG, "[agent] ${AgentLaunch.redactUrl(line)}")
                }
                // stderr is appended to the same log so diagnostics see it.
                AgentLaunch.pump(process.errorStream, logFile) { line ->
                    Log.w(TAG, "[agent:err] ${AgentLaunch.redactUrl(line)}")
                }

                // 4. CLI-only agents run under visual-gateway/gateway.js,
                // which OWNS the real CLI's stdin/stdout. VisualGateway must
                // NOT attach here: it would capture gateway.js's own pipes
                // (the wrong process) and write approval tokens to a stdin
                // gateway.js never reads. Approvals instead travel
                // gateway SSE -> DshBridge -> native sheet, and answers go
                // back via POST /send. Logs still reach dsh-logs/ because the
                // child's stdout/stderr are pumped above.
                gateway = null

                // 5. Health-check the loopback port, then go live.
                setStatus(ServiceStatus.Booting("Waiting for local server…"))
                val healthy = waitForPort(port, timeoutMs = 60_000)
                if (!healthy) throw IllegalStateException(
                    "Agent did not open 127.0.0.1:$port in time — see dsh-logs.",
                )

                // Prefer the agent's own authenticated URL when it printed
                // one. Backends that print nothing (the visual gateway) fall
                // back to the plain origin, which is correct for them.
                val origin = "http://${AgentRuntime.LOOPBACK_HOST}:$port"
                var entryUrl = origin
                // VERIFIED ON DEVICE: `dsh web` takes tens of seconds to boot
                // before it prints its banner. The previous 2.5s budget almost
                // always timed out, and the silent fallback to the bare origin
                // is a page that answers 401 and renders blank - which reads
                // to the user as "the app is installed and nothing works".
                // That timeout was the bug, not a cosmetic issue.
                withTimeoutOrNull(LAUNCH_URL_TIMEOUT_MS) {
                    while (launchUrl.get() == null) delay(100)
                    entryUrl = launchUrl.get() ?: origin
                }
                if (entryUrl == origin && !AgentRuntime.isGatewayAgent(agent)) {
                    Log.w(
                        TAG,
                        "no authenticated banner after ${LAUNCH_URL_TIMEOUT_MS}ms; " +
                            "WebView will load the bare origin, which answers 401",
                    )
                }

                // The banner can still arrive after we go Running (slow boot,
                // slow plugin tree). Keep watching and upgrade the URL in place
                // rather than leaving the user on a permanently blank 401 page.
                if (entryUrl == origin && !AgentRuntime.isGatewayAgent(agent)) {
                    watchForLaunchUrl(process, logFile, launchUrl, agent, port)
                }

                updateNotification("${agent.label} running on :$port")
                setStatus(
                    ServiceStatus.Running(
                        agent = agent,
                        port = port,
                        url = entryUrl,
                        // java.lang.Process.pid() is not in the Android SDK
                        // baseline; use a stable local handle instead.
                        pidHint = "pid-${process.hashCode()}",
                    ),
                )
                // Arm the crash watchdog: 5s loopback pings from here on.
                // Workspace data is never at risk — recovery only re-mounts.
                runCatching {
                    (application as DshMobileApp).watchdog.start(port)
                }.onFailure { Log.w(TAG, "watchdog failed to start", it) }

                // 6. Watchdog: block until the child exits, then restart with
                // backoff. The outer job is cancelled on ACTION_RESTART /
                // ACTION_STOP, so coroutine cancellation — never a status
                // check — is the single stop signal.
                val exit = try {
                    withContext(Dispatchers.IO) { process.waitFor() }
                } catch (e: Exception) {
                    // Cancelled by a fresh boot/stop intent: exit quietly, the
                    // replacement job owns the runtime now.
                    if (!isActive) return@launch
                    throw e
                }
                Log.w(TAG, "agent exited ($exit) — restarting with backoff")
                setStatus(ServiceStatus.Booting("Agent exited — restarting…"))
                delay(RESTART_BACKOFF_MS)
                boot()
            } catch (e: Exception) {
                Log.e(TAG, "boot failed", e)
                setStatus(
                    ServiceStatus.Error(e.message ?: "Boot failed", retryable = true),
                )
                updateNotification("Boot failed: ${e.message}")
            }
        }
    }

    /**
     * Once the agent is Running, keep scanning its stdout for the tokenized
     * banner and republish the authenticated URL when it shows up.
     *
     * Safe to run beside the initial pump: [AgentLaunch.pump] is only reading,
     * and the streams are closed when the child exits.
     */
    private fun watchForLaunchUrl(
        process: Process,
        logFile: File,
        launchUrl: AtomicReference<String?>,
        agent: ActiveAgent,
        port: Int,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val found = AtomicReference<String?>(null)
            AgentLaunch.pump(process.inputStream, logFile, found) {}
            val url = found.get()
            if (url != null) {
                Log.i(TAG, "late banner arrived; republishing authenticated URL")
                setStatus(
                    ServiceStatus.Running(
                        agent = agent,
                        port = port,
                        url = url,
                        pidHint = "pid-${process.hashCode()}",
                    ),
                )
            }
        }
    }

    private fun resolveWorkspace(config: RuntimeConfig): File {
        val picked = config.workspacePath
        if (picked.isNotBlank()) {
            val dir = File(picked)
            if (dir.isDirectory && dir.canRead()) return dir
            Log.w(TAG, "stored workspace unreadable, falling back: $picked")
        }
        return AgentRuntime.defaultWorkspace(this)
    }

    private fun prepareMounts(workspace: File) {
        workspace.mkdirs()
        AgentRuntime.runtimeRoot(this).mkdirs()
        AgentRuntime.agentsDir(this).mkdirs()
        File(filesDir, "dsh-logs").mkdirs()
    }

    private suspend fun waitForPort(port: Int, timeoutMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    java.net.Socket(AgentRuntime.LOOPBACK_HOST, port).close()
                    return@withContext true
                } catch (_: Exception) {
                    delay(500)
                }
                if (child?.isAlive == false) return@withContext false
            }
            false
        }

    // -- approvals ----------------------------------------------------------

    /**
     * Fan-out for gateway stream events. Kept as the single place gateway
     * output is re-published; no gateway is attached today (gateway.js owns
     * the child's pipes), so this is the seam for a future direct-spawn
     * backend that exposes its own pipes instead of an HTTP/SSE surface.
     */
    private fun onGatewayEvent(event: VisualGateway.GatewayEvent) {
        GatewayEventBus.emit(event)
        when (event) {
            is VisualGateway.GatewayEvent.ApprovalRequested ->
                setApprovals(_approvalRequests.value + event.request)
            is VisualGateway.GatewayEvent.ApprovalResolved ->
                setApprovals(
                    _approvalRequests.value.filterNot { it.id == event.requestId },
                )
            else -> Unit
        }
    }

    /**
     * Called by the ViewModel when the user taps Approve/Deny on the sheet.
     *
     * Both backends answer inside the WebView: gateway agents POST to
     * gateway.js `/send` (which owns stdin), DSH web/OpenCode click their own
     * confirm control. Both arrive here as `web-*` ids handled by
     * WebApprovalRouter, so this service only needs to clear the sheet.
     */
    fun resolveApproval(requestId: String, approved: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            gateway?.resolveApproval(requestId, approved)
            setApprovals(_approvalRequests.value.filterNot { it.id == requestId })
            AgentServiceStatusFlow.removeApproval(requestId)
        }
    }

    fun pendingApprovals(): StateFlow<List<VisualGateway.ApprovalRequest>> =
        _approvalRequests.asStateFlow()

    // -- lifecycle helpers --------------------------------------------------

    private fun stopChild() {
        bootJob?.cancel()
        bootJob = null
        stopChildLocked()
    }

    /** Kills the child process + gateway without touching the boot job. */
    private fun stopChildLocked() {
        runCatching { (application as DshMobileApp).watchdog.stop() }
        gateway?.detach()
        gateway = null
        setApprovals(emptyList())
        runCatching { child?.destroy() }
        runCatching {
            if (child?.isAlive == true) {
                Thread.sleep(1500)
                child?.destroyForcibly()
            }
        }
        child = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Agent runtime",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DSH Mobile runtime")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    inner class Binder : android.os.Binder() {
        fun service(): AgentRuntimeService = this@AgentRuntimeService
    }

    /** Process-wide handle so the ViewModel can reach the running service. */
    object ServiceHolder {
        @Volatile var service: AgentRuntimeService? = null
    }

    sealed interface ServiceStatus {
        data object Idle : ServiceStatus
        data class Booting(val step: String) : ServiceStatus
        data class Running(
            val agent: ActiveAgent,
            val port: Int,
            val url: String,
            val pidHint: String,
        ) : ServiceStatus
        data class Error(val message: String, val retryable: Boolean) : ServiceStatus
    }

    companion object {
        const val ACTION_BOOT = "com.dsh.mobile.action.BOOT"
        const val ACTION_RESTART = "com.dsh.mobile.action.RESTART"
        const val ACTION_STOP = "com.dsh.mobile.action.STOP"

        private const val TAG = "AgentRuntimeService"
        private const val CHANNEL_ID = "agent_runtime"
        private const val NOTIFICATION_ID = 41
        private const val RESTART_BACKOFF_MS = 5_000L
    }
}
