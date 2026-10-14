package com.dsh.mobile.ui

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import com.dsh.mobile.runtime.AgentRuntime
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dsh.mobile.bootstrap.BootstrapManager
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentMode
import com.dsh.mobile.data.RuntimeConfig
import com.dsh.mobile.data.RuntimeConfigStore
import com.dsh.mobile.diagnostics.DiagnosticsStore
import com.dsh.mobile.proxy.VisualGateway
import com.dsh.mobile.security.ApiKeyVault
import com.dsh.mobile.security.PerAgentProviderStore
import com.dsh.mobile.service.AgentRuntimeService
import com.dsh.mobile.watchdog.EngineWatchdog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the resilience-hardened shell renders. No CLI anywhere. */
data class ControlCenterState(
    val config: RuntimeConfig = RuntimeConfig(),
    val deepseekKey: String = "",
    val anthropicKey: String = "",
    val openrouterKey: String = "",
    val hasKeys: Boolean = false,
    val serviceStatus: AgentRuntimeService.ServiceStatus =
        AgentRuntimeService.ServiceStatus.Idle,
    val approvals: List<VisualGateway.ApprovalRequest> = emptyList(),
    val savedFlash: Boolean = false,
    val bootstrap: BootstrapManager.BootstrapState = BootstrapManager.BootstrapState.Idle,
    val watchdog: EngineWatchdog.WatchdogState = EngineWatchdog.WatchdogState.Idle,
    val diagnostics: DiagnosticsStore.DiagnosticsState = DiagnosticsStore.DiagnosticsState(),
    val copiedFlash: String? = null,
    /** Agents whose binary is actually on disk. */
    val installedAgents: List<ActiveAgent> = emptyList(),
    /** Current agent's provider id, base URL and masked key. */
    val providerId: String = "deepseek",
    val providerBaseUrl: String? = null,
    val providerKeyMask: String = "",
)

/** Carries the two earlier combine results so the binding can be folded in. */
private data class ProviderState(
    val core: CoreState,
    val resilience: ResilienceState,
)

/** Combine group 1: config + runtime status + approvals. */
private data class CoreState(
    val config: RuntimeConfig,
    val status: AgentRuntimeService.ServiceStatus,
    val approvals: List<VisualGateway.ApprovalRequest>,
    val saved: Boolean,
)

/** Combine group 2: first-boot, watchdog, diagnostics, clipboard flash. */
private data class ResilienceState(
    val bootstrap: BootstrapManager.BootstrapState,
    val watchdog: EngineWatchdog.WatchdogState,
    val diagnostics: DiagnosticsStore.DiagnosticsState,
    val copiedFlash: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ControlCenterViewModel(
    private val appContext: Context,
    private val configStore: RuntimeConfigStore,
    private val vault: ApiKeyVault,
    private val providers: PerAgentProviderStore,
    private val bootstrap: BootstrapManager,
    private val watchdog: EngineWatchdog,
    private val diagnostics: DiagnosticsStore,
    private val onRestartRequest: () -> Unit = {},
    private val commandDispatcher: (command: String) -> Unit = {},
) : ViewModel() {

    private val flash = MutableStateFlow(false)
    private val copied = MutableStateFlow<String?>(null)

    init {
        // Keep the hidden diagnostics tail warm for the active backend so
        // opening the sheet shows logs instantly.
        viewModelScope.launch {
            configStore.config.collect { cfg ->
                diagnostics.startTailing(cfg.agent)
            }
        }
    }

    /**
     * Two typed `combine` groups, then merged.
     *
     * kotlinx.coroutines only provides typed overloads for 2-5 flows; passing
     * 9 heterogeneous flows falls back to the vararg overload, whose lambda
     * takes a single `Array<T>` — so a 9-parameter lambda would not compile.
     */
    val uiState: StateFlow<ControlCenterState> =
        combine(
            configStore.config,
            AgentServiceStatusFlow.status,
            AgentServiceStatusFlow.approvals,
            AgentServiceStatusFlow.webApprovals,
            flash,
        ) { config, status, approvals, webApprovals, saved ->
            CoreState(
                config = config,
                status = status,
                approvals = approvals + webApprovals,
                saved = saved,
            )
        }.combine(
            combine(
                bootstrap.state,
                watchdog.state,
                diagnostics.state,
                copied,
            ) { boot, watch, diag, copyFlash ->
                ResilienceState(boot, watch, diag, copyFlash)
            },
        ) { core, resilience ->
            ProviderState(core, resilience)
        }.combine(
            configStore.config.flatMapLatest { providers.binding(it.agent.id) },
        ) { state, bindingState ->
            val core = state.core
            val resilience = state.resilience
            val bindingAgent = core.config.agent
            val binding = bindingState
            ControlCenterState(
                config = core.config,
                deepseekKey = vault.apiKey(ApiKeyVault.Provider.DEEPSEEK).mask(),
                anthropicKey = vault.apiKey(ApiKeyVault.Provider.ANTHROPIC).mask(),
                openrouterKey = vault.apiKey(ApiKeyVault.Provider.OPENROUTER).mask(),
                hasKeys = vault.hasAnyKey(),
                serviceStatus = core.status,
                approvals = core.approvals,
                savedFlash = core.saved,
                bootstrap = resilience.bootstrap,
                watchdog = resilience.watchdog,
                diagnostics = resilience.diagnostics,
                copiedFlash = resilience.copiedFlash,
                installedAgents = AgentRuntime.installedAgents(
                    AgentRuntime.agentsDir(appContext),
                ),
                providerId = binding.providerId,
                providerBaseUrl = binding.baseUrl,
                providerKeyMask = vault.agentKey(bindingAgent.id).mask(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControlCenterState())

    /** Native credential form submit — encrypted, then injected as env vars on reboot. */
    fun saveCredentials(deepseek: String, anthropic: String, openrouter: String) {
        if (deepseek.isNotBlank()) vault.save(ApiKeyVault.Provider.DEEPSEEK, deepseek)
        if (anthropic.isNotBlank()) vault.save(ApiKeyVault.Provider.ANTHROPIC, anthropic)
        if (openrouter.isNotBlank()) vault.save(ApiKeyVault.Provider.OPENROUTER, openrouter)
        viewModelScope.launch {
            flash.value = true
            restartService()
            delay(2_500)
            flash.value = false
        }
    }

    /**
     * Save one agent's provider, endpoint, model and (optional) key.
     *
     * A blank [key] means "keep the stored key", never "clear it": the settings
     * form re-reads its masked placeholder on every recomposition, so treating
     * blank as clear would silently wipe a credential whenever the user edited
     * an unrelated field on the same screen.
     *
     * Does NOT restart the service when the change only affects agents other
     * than the running one — an unnecessary restart would kill live work, which
     * is exactly what the resume fix exists to prevent.
     */
    fun saveAgentProvider(
        agentId: String,
        providerId: String,
        key: String?,
        baseUrl: String,
        model: String,
    ) {
        viewModelScope.launch {
            runCatching { providers.setProvider(agentId, providerId) }
            runCatching { providers.setBaseUrl(agentId, baseUrl) }
            runCatching { providers.setModel(agentId, model) }
            if (!key.isNullOrBlank()) providers.saveKeyFor(agentId, key)
            flash.value = true
            // Only the running agent's environment needs re-reading.
            if (agentId == configStore.snapshot().agent.id) restartService()
            delay(2_000)
            flash.value = false
        }
    }

    /** SAF folder picker result — absolute path passed to the agent workspace. */
    fun setWorkspace(absolutePath: String) {
        viewModelScope.launch {
            configStore.setWorkspace(absolutePath)
            restartService()
        }
    }

    fun setAgentMode(mode: AgentMode) {
        viewModelScope.launch {
            configStore.setMode(mode)
            restartService()
        }
    }

    fun setAgentEffort(effort: String) {
        viewModelScope.launch {
            configStore.setEffort(effort)
            restartService()
        }
    }

    fun setActiveAgent(agent: ActiveAgent) {
        viewModelScope.launch {
            configStore.setAgent(agent)
            restartService()
        }
    }

    /**
     * Bottom-sheet decision. `web-*` ids are DOM-detected prompts answered
     * inside the WebView page; anything else belongs to the gateway child
     * process and is answered over its stdin. The sheet clears optimistically.
     */
    fun resolveApproval(requestId: String, approved: Boolean) {
        if (requestId.startsWith("web-")) {
            WebApprovalRouter.answer?.invoke(requestId, approved)
        } else {
            AgentRuntimeService.ServiceHolder.service?.resolveApproval(requestId, approved)
        }
        AgentServiceStatusFlow.removeApproval(requestId)
    }

    fun dismissApproval(requestId: String) = resolveApproval(requestId, false)

    /** WebView DOM prompt detected by dsh-mobile-shell.js — show native sheet. */
    fun addWebApproval(summary: String) {
        AgentServiceStatusFlow.addWebApproval(webPromptToApproval(summary))
    }

    // -- resilience: bootstrap / watchdog / diagnostics -----------------------

    /** Retry button on the bootstrap progress view + error card. */
    fun retryBootstrap() {
        bootstrap.ensureBootstrapped(force = true)
    }

    /** [Restart Engine] button on the watchdog error card. */
    fun restartEngine() {
        watchdog.restartEngine()
        onRestartRequest()
    }

    /** Accessory-bar quick command (/help, /stop-generation, …). */
    fun sendCommand(command: String) {
        commandDispatcher(command)
    }

    /** Accessory-bar path chip — inserts the workspace path into the composer. */
    fun insertPath(path: String) {
        commandDispatcher(path)
    }

    /** Diagnostics sheet: copy every log to the clipboard in one tap. */
    fun copyAllLogs() {
        viewModelScope.launch {
            val ok = diagnostics.copyAllToClipboard()
            copied.value = if (ok) "Logs copied — paste into your bug report." else "Copy failed."
            delay(2_500)
            copied.value = null
        }
    }

    /** Diagnostics sheet: copy the visible tab's trace. */
    fun copyTrace(trace: String) {
        viewModelScope.launch {
            val ok = diagnostics.copyTraceToClipboard(trace)
            copied.value = if (ok) "Trace copied." else "Copy failed."
            delay(2_500)
            copied.value = null
        }
    }

    private fun restartService() {
        // The sticky service re-reads config + vault on restart; keys flow in
        // as environment variables without ever touching the WebView.
        onRestartRequest()
    }

    private fun String.mask(): String =
        if (isBlank()) "" else "••••••••" + takeLast(4)

    companion object {
        fun factory(
            appContext: Context,
            configStore: RuntimeConfigStore,
            vault: ApiKeyVault,
            bootstrap: BootstrapManager,
            watchdog: EngineWatchdog,
            diagnostics: DiagnosticsStore,
            providers: PerAgentProviderStore,
            onRestartRequest: () -> Unit = {},
            commandDispatcher: (command: String) -> Unit = {},
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ControlCenterViewModel(
                    appContext, configStore, vault, providers, bootstrap, watchdog, diagnostics,
                    onRestartRequest, commandDispatcher,
                ) as T
        }
    }
}

/**
 * Thin bridge so the ViewModel (which outlives service restarts) can observe
 * the running [AgentRuntimeService] without holding a Context.
 *
 * The service publishes every status/approval change here; the ViewModel
 * combines these flows with config + WebView-DOM approvals.
 */
object AgentServiceStatusFlow {
    private val _status =
        MutableStateFlow<AgentRuntimeService.ServiceStatus>(
            AgentRuntimeService.ServiceStatus.Idle,
        )
    private val _approvals =
        MutableStateFlow<List<VisualGateway.ApprovalRequest>>(emptyList())
    private val _webApprovals =
        MutableStateFlow<List<VisualGateway.ApprovalRequest>>(emptyList())

    val status: StateFlow<AgentRuntimeService.ServiceStatus> = _status
    val approvals: StateFlow<List<VisualGateway.ApprovalRequest>> = _approvals
    val webApprovals: StateFlow<List<VisualGateway.ApprovalRequest>> = _webApprovals

    fun publishStatus(status: AgentRuntimeService.ServiceStatus) {
        _status.value = status
    }

    fun publishApprovals(approvals: List<VisualGateway.ApprovalRequest>) {
        _approvals.value = approvals
    }

    fun addWebApproval(request: VisualGateway.ApprovalRequest) {
        if (_webApprovals.value.none { it.summary == request.summary }) {
            _webApprovals.value += request
        }
    }

    fun removeApproval(requestId: String) {
        _approvals.value = _approvals.value.filterNot { it.id == requestId }
        _webApprovals.value = _webApprovals.value.filterNot { it.id == requestId }
    }
}
