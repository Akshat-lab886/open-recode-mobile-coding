package com.dsh.mobile

import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.mobile.service.AgentRuntimeService
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.dsh.mobile.data.AgentCatalog
import com.dsh.mobile.ui.AgentPickerSheet
import com.dsh.mobile.ui.BootstrapProgressView
import com.dsh.mobile.ui.CommandDispatcher
import com.dsh.mobile.ui.ControlCenterViewModel
import com.dsh.mobile.ui.DshMobileShell
import com.dsh.mobile.ui.rememberAdaptive
import com.dsh.mobile.ui.theme.DshMobileTheme

/**
 * CORE ENTRY POINT — the only Activity.
 *
 * Lifecycle:
 *  1. On launch, the [com.dsh.mobile.bootstrap.BootstrapManager] runs the
 *     first-boot script (rootfs, toolchain, `npm i -g @deepseek-ai/dsh`);
 *     the [BootstrapProgressView] shows live stdout/stderr progress.
 *  2. Only after bootstrap is Ready does the persistent
 *     [AgentRuntimeService] Foreground Service boot the agent processes.
 *  3. [DshMobileShell] renders the visual Control Center around the
 *     full-screen agent WebView — no raw CLI is ever shown.
 */
class MainActivity : ComponentActivity() {

    /**
     * True once we have asked the service to boot in this process.
     *
     * Guards against the Activity being recreated (rotation, low-memory
     * restart, "don't keep activities") from booting a second time. The flag
     * is deliberately NOT saved across process death: after the process dies
     * the sticky service is restarted with a null intent and owns the boot,
     * so re-requesting from a fresh Activity is still correct.
     */
    private var agentBootRequested = false

    // ModalBottomSheet is still experimental in Material3 1.3.1, so the opt-in
    // is scoped to this one composable entry point rather than the class.
    @androidx.compose.material3.ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DshMobileApp
        // Kick off first-boot setup immediately; the service itself also
        // gates on bootstrap, so this is safe to race.
        app.bootstrap.ensureBootstrapped()

        setContent {
            DshMobileTheme {
                val vm: ControlCenterViewModel = viewModel(
                    factory = ControlCenterViewModel.factory(
                        appContext = app,
                        configStore = app.configStore,
                        vault = app.vault,
                        bootstrap = app.bootstrap,
                        watchdog = app.watchdog,
                        diagnostics = app.diagnostics,
                        providers = app.perAgentProviders,
                        onRestartRequest = {
                            startAgentService(AgentRuntimeService.ACTION_RESTART)
                        },
                        commandDispatcher = ::dispatchCommand,
                    ),
                )
                val uiState by vm.uiState.collectAsState()
                val boot by app.bootstrap.state.collectAsState()

                // The agent picker is shown once, after the environment exists,
                // and only until the user answers it. Both facts are persisted
                // by AgentInstallStore, so reopening the app never re-asks and
                // never re-installs.
                val hasSelected by app.agentInstalls.userHasSelected.collectAsState(initial = false)
                val completed by app.agentInstalls.completed.collectAsState(initial = emptySet())
                val showPicker = remember { mutableStateOf(false) }
                val installedIds = remember(completed) {
                    completed.filter { id ->
                        app.agentInstaller.isOnDisk(AgentCatalog.specFor(id) ?: return@filter false)
                    }.toSet()
                }
                LaunchedEffect(hasSelected, boot) {
                    if (!hasSelected &&
                        boot is com.dsh.mobile.bootstrap.BootstrapManager.BootstrapState.Ready
                    ) {
                        showPicker.value = true
                    }
                }

                if (showPicker.value) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val scope = rememberCoroutineScope()
                    ModalBottomSheet(
                        onDismissRequest = {
                            // Dismissing without choosing still counts as an
                            // answer: it must not re-prompt on every launch.
                            showPicker.value = false
                        },
                        sheetState = sheetState,
                    ) {
                        AgentPickerSheet(
                            specs = AgentCatalog.installable,
                            installedIds = installedIds,
                            onSkip = {
                                showPicker.value = false
                            },
                            onConfirm = { chosen ->
                                showPicker.value = false
                                installAgents(app, chosen)
                            },
                        )
                    }
                }

                if (!app.bootstrap.isBootstrapped() &&
                    boot !is com.dsh.mobile.bootstrap.BootstrapManager.BootstrapState.Ready
                ) {
                    // Bulletproof first boot: block the agent UI until the
                    // environment exists — with a friendly progress view.
                    // The service gates on the same marker and boots itself
                    // the moment setup completes (no Activity callback needed
                    // — the state flow below flips to the shell on Ready).
                    BoxWithConstraints {
                        BootstrapProgressView(
                            state = boot,
                            onRetry = { app.bootstrap.ensureBootstrapped(force = true) },
                            layout = rememberAdaptive(),
                        )
                    }
                } else {
                    DshMobileShell(
                        uiState = uiState,
                        onCredentialsSaved = vm::saveCredentials,
                        onWorkspacePicked = vm::setWorkspace,
                        onModeSelected = vm::setAgentMode,
                        onAgentSelected = vm::setActiveAgent,
                        onManageAgents = { showPicker.value = true },
                        sessions = app.sessionHistory.history(),
                        onDeleteSession = { id -> app.sessionHistoryScope.launch { app.sessionHistory.delete(id) } },
                        onClearSessions = { app.sessionHistoryScope.launch { app.sessionHistory.clear() } },
                        onSaveAgentProvider = vm::saveAgentProvider,
                        onApprovalResult = vm::resolveApproval,
                        onDismissApproval = vm::dismissApproval,
                        onWebApproval = vm::addWebApproval,
                        onRetryBoot = { startAgentService(AgentRuntimeService.ACTION_BOOT) },
                        onRetryBootstrap = vm::retryBootstrap,
                        onRestartEngine = vm::restartEngine,
                        onCommand = vm::sendCommand,
                        onInsertPath = vm::insertPath,
                        onCopyAllLogs = vm::copyAllLogs,
                        onCopyTrace = vm::copyTrace,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as DshMobileApp
        if (!app.bootstrap.isBootstrapped()) return

        // Re-attach, never re-boot.
        //
        // This used to send ACTION_BOOT on every foreground. onStart() runs on
        // every return to the app - switching to another app and coming back
        // included - and boot() begins by cancelling the running job and
        // killing the child process. So the app deliberately destroyed live
        // agent work and reloaded the entire environment behind the user's
        // back, which read as "the app cannot keep a session alive".
        //
        // The service is a sticky foreground service that deliberately outlives
        // this Activity, so the work was never actually at risk: we were
        // throwing it away. Re-attaching only re-publishes current state, and
        // EngineWatchdog still owns recovery if the child genuinely dies.
        //
        // Boot is requested exactly once, at first create, when there is
        // nothing running to lose.
        if (!agentBootRequested) {
            agentBootRequested = true
            startAgentService(AgentRuntimeService.ACTION_BOOT)
        } else {
            startAgentService(AgentRuntimeService.ACTION_ATTACH)
        }
    }

    /**
     * Accessory-bar / quick commands always route through the page.
     *
     * Gateway agents: `__dshInsertCommand` POSTs to gateway.js `/send`,
     * which owns the CLI's stdin. DSH web/OpenCode: inserted into the live
     * composer. Routing stdin directly from here as well would double-send,
     * since gateway-ui's own composer already posts.
     */
    private fun dispatchCommand(command: String) {
        CommandDispatcher.dispatch?.invoke(command)
    }

    /**
     * Install the agents the user picked, then record each success.
     *
     * Runs on the app scope, not the Activity, so navigating away mid-install
     * does not abandon it half-done. Each agent is recorded individually so a
     * failure never causes a later app open to reinstall the ones that worked.
     */
    private fun installAgents(app: DshMobileApp, chosen: Set<String>) {
        if (chosen.isEmpty()) return
        (application as DshMobileApp).agentInstallScope.launch {
            val installer = app.agentInstaller
            val specs = AgentCatalog.all.filter { it.id in chosen }
            val pending = installer.pending(chosen, app.agentInstalls.completed.first())
            Log.i(TAG, "installing ${pending.size} of ${specs.size} selected agents")
            installer.installAll(pending).forEach { result ->
                if (result.ok) {
                    app.agentInstalls.markCompleted(result.spec.id)
                    Log.i(TAG, "installed ${result.spec.id}")
                } else {
                    Log.w(TAG, "install failed ${result.spec.id}: ${result.failure}")
                }
            }
            app.agentInstalls.recordSelection(chosen)
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }

    private fun startAgentService(action: String) {
        startForegroundService(
            Intent(this, AgentRuntimeService::class.java).apply {
                this.action = action
            },
        )
    }

    override fun onDestroy() {
        // Service is sticky/foreground: it outlives the Activity on purpose.
        super.onDestroy()
    }
}
