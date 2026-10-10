package com.dsh.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.mobile.service.AgentRuntimeService
import com.dsh.mobile.ui.BootstrapProgressView
import com.dsh.mobile.ui.CommandDispatcher
import com.dsh.mobile.ui.ControlCenterViewModel
import com.dsh.mobile.ui.DshMobileShell
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
                        configStore = app.configStore,
                        vault = app.vault,
                        bootstrap = app.bootstrap,
                        watchdog = app.watchdog,
                        diagnostics = app.diagnostics,
                        onRestartRequest = {
                            startAgentService(AgentRuntimeService.ACTION_RESTART)
                        },
                        commandDispatcher = ::dispatchCommand,
                    ),
                )
                val uiState by vm.uiState.collectAsState()
                val boot by app.bootstrap.state.collectAsState()

                if (!app.bootstrap.isBootstrapped() &&
                    boot !is com.dsh.mobile.bootstrap.BootstrapManager.BootstrapState.Ready
                ) {
                    // Bulletproof first boot: block the agent UI until the
                    // environment exists — with a friendly progress view.
                    // The service gates on the same marker and boots itself
                    // the moment setup completes (no Activity callback needed
                    // — the state flow below flips to the shell on Ready).
                    BootstrapProgressView(
                        state = boot,
                        onRetry = { app.bootstrap.ensureBootstrapped(force = true) },
                    )
                } else {
                    DshMobileShell(
                        uiState = uiState,
                        onCredentialsSaved = vm::saveCredentials,
                        onWorkspacePicked = vm::setWorkspace,
                        onModeSelected = vm::setAgentMode,
                        onAgentSelected = vm::setActiveAgent,
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
        // Re-attach to the sticky service on every foreground; the service
        // gates internally on bootstrap, so late setup still boots cleanly.
        val app = application as DshMobileApp
        if (app.bootstrap.isBootstrapped()) {
            startAgentService(AgentRuntimeService.ACTION_BOOT)
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
