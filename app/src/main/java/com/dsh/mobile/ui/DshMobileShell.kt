package com.dsh.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dsh.mobile.bootstrap.BootstrapManager
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentMode
import com.dsh.mobile.proxy.VisualGateway
import com.dsh.mobile.service.AgentRuntimeService
import com.dsh.mobile.watchdog.EngineWatchdog

/**
 * VISUAL SHELL — the native wrapper around the agent WebView.
 *
 * Layout: full-screen [AgentWebView] with a top app bar (agent switcher +
 * settings gear) and native layers on top of the agent process:
 *  1. Control Center bottom sheet (credentials / workspace / mode).
 *  2. Approval intercept bottom sheets (Approve / Deny).
 *  3. Bootstrap progress gate on first boot (setup progress, no CLI).
 *  4. Watchdog error card ([Restart Engine]) when the loopback server dies.
 *  5. Input accessory bar docked above the keyboard (quick commands).
 *  6. Hidden diagnostics sheet (5-tap status dot) with one-tap log export.
 *  7. Boot / error states — the user never sees a raw CLI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshMobileShell(
    uiState: ControlCenterState,
    onCredentialsSaved: (deepseek: String, anthropic: String, openrouter: String) -> Unit,
    onWorkspacePicked: (absolutePath: String) -> Unit,
    onModeSelected: (AgentMode) -> Unit,
    onAgentSelected: (ActiveAgent) -> Unit,
    onApprovalResult: (requestId: String, approved: Boolean) -> Unit,
    onDismissApproval: (requestId: String) -> Unit,
    onWebApproval: (summary: String) -> Unit,
    onRetryBoot: () -> Unit,
    onRetryBootstrap: () -> Unit,
    onRestartEngine: () -> Unit,
    onCommand: (command: String) -> Unit,
    onInsertPath: (path: String) -> Unit,
    onCopyAllLogs: () -> Unit,
    onCopyTrace: (trace: String) -> Unit,
) {
    var showControlCenter by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var accessoryExpanded by remember { mutableStateOf(false) }
    var diagTaps by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(uiState.savedFlash) {
        if (uiState.savedFlash) snackbar.showSnackbar("Keys saved — runtime restarting…")
    }
    // Reset the hidden-gesture counter shortly after taps stop.
    LaunchedEffect(diagTaps) {
        if (diagTaps > 0) {
            kotlinx.coroutines.delay(1_500)
            diagTaps = 0
        }
    }

    // First-boot gate: bootstrap progress replaces the whole shell until the
    // environment is Ready (covers service-side BOOT arriving early).
    val boot = uiState.bootstrap
    if (boot is BootstrapManager.BootstrapState.Running ||
        boot is BootstrapManager.BootstrapState.Failed
    ) {
        BootstrapProgressView(state = boot, onRetry = onRetryBootstrap)
        return
    }

    // Keyboard visibility drives the accessory bar (IME insets, no hacks).
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("DSH Mobile · ${uiState.config.agent.label}") },
                actions = {
                    StatusDot(
                        uiState.serviceStatus,
                        onTap = {
                            diagTaps++
                            if (diagTaps >= 5) {
                                diagTaps = 0
                                showDiagnostics = true
                            }
                        },
                    )
                    IconButton(onClick = { showControlCenter = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Control Center")
                    }
                },
            )
        },
        bottomBar = {
            // Context-aware accessory bar: only when the keyboard is up.
            if (imeVisible) {
                InputAccessoryBar(
                    workspacePath = uiState.config.workspacePath,
                    onCommand = onCommand,
                    onInsertPath = onInsertPath,
                    expanded = accessoryExpanded,
                    onToggleExpanded = { accessoryExpanded = !accessoryExpanded },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Watchdog error card docks above the WebView — workspace safe.
            val watch = uiState.watchdog
            if (watch is EngineWatchdog.WatchdogState.Unhealthy) {
                EngineErrorCard(
                    state = watch,
                    onRestart = onRestartEngine,
                    onOpenDiagnostics = { showDiagnostics = true },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val status = uiState.serviceStatus) {
                    is AgentRuntimeService.ServiceStatus.Running -> {
                        AgentWebView(
                            url = status.url,
                            onWebApproval = onWebApproval,
                            modifier = Modifier
                                .fillMaxSize()
                                .imePadding(),
                        )
                    }
                    is AgentRuntimeService.ServiceStatus.Error -> {
                        BootErrorState(status.message, onRetryBoot, Modifier.fillMaxSize())
                    }
                    else -> {
                        BootLoadingState(
                            (status as? AgentRuntimeService.ServiceStatus.Booting)?.step
                                ?: "Starting…",
                            Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    if (showControlCenter) {
        ModalBottomSheet(
            onDismissRequest = { showControlCenter = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ControlCenterSheet(
                uiState = uiState,
                onCredentialsSaved = onCredentialsSaved,
                onWorkspacePicked = onWorkspacePicked,
                onModeSelected = onModeSelected,
                onAgentSelected = onAgentSelected,
            )
        }
    }

    // Dynamic Visual Intercept Sheets — one per pending tool approval.
    uiState.approvals.firstOrNull()?.let { request ->
        ApprovalSheet(
            request = request,
            onApprove = { onApprovalResult(request.id, true) },
            onDeny = { onDismissApproval(request.id) },
        )
    }

    // Hidden diagnostics sheet (5-tap the status dot — never in main nav).
    if (showDiagnostics) {
        DiagnosticsSheet(
            state = uiState.diagnostics,
            copiedFlash = uiState.copiedFlash,
            onCopyAll = onCopyAllLogs,
            onCopyTrace = onCopyTrace,
            onDismiss = { showDiagnostics = false },
        )
    }
}

// -- Control Center ----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlCenterSheet(
    uiState: ControlCenterState,
    onCredentialsSaved: (String, String, String) -> Unit,
    onWorkspacePicked: (String) -> Unit,
    onModeSelected: (AgentMode) -> Unit,
    onAgentSelected: (ActiveAgent) -> Unit,
) {
    var deepseek by remember(uiState.deepseekKey) { mutableStateOf("") }
    var anthropic by remember(uiState.anthropicKey) { mutableStateOf("") }
    var openrouter by remember(uiState.openrouterKey) { mutableStateOf("") }
    val context = LocalContext.current

    // Visual folder picker (Storage Access Framework). The absolute path is
    // passed straight into the agent workspace configuration — no typing.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val picked = resolveTreeToPath(context, uri)
            if (picked != null) onWorkspacePicked(picked)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Control Center", style = MaterialTheme.typography.headlineSmall)

        // a) Model & API credentials (Android Keystore, env-injected).
        Text("Model & API Credentials", fontWeight = FontWeight.SemiBold)
        CredentialField("DeepSeek key", uiState.deepseekKey, deepseek) { deepseek = it }
        CredentialField("Anthropic key (Claude Code)", uiState.anthropicKey, anthropic) {
            anthropic = it
        }
        CredentialField("OpenRouter key", uiState.openrouterKey, openrouter) {
            openrouter = it
        }
        Button(
            onClick = { onCredentialsSaved(deepseek, anthropic, openrouter) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save keys securely") }

        // b) Workspace project selector.
        Text("Workspace Project", fontWeight = FontWeight.SemiBold)
        Text(
            uiState.config.workspacePath.ifBlank { "Default app workspace" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { folderPicker.launch(null) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("📁 Choose project folder") }

        // c) Mode selector toggle.
        Text("Agent Mode", fontWeight = FontWeight.SemiBold)
        AgentMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = uiState.config.mode == mode,
                        onClick = { onModeSelected(mode) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = uiState.config.mode == mode,
                    onClick = { onModeSelected(mode) },
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(mode.label, fontWeight = FontWeight.Medium)
                    Text(
                        mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text("Agent Backend", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActiveAgent.entries.forEach { agent ->
                FilterChip(
                    selected = uiState.config.agent == agent,
                    onClick = { onAgentSelected(agent) },
                    label = { Text(agent.label.substringBefore(" (")) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CredentialField(
    label: String,
    storedMask: String,
    value: String,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(storedMask.ifBlank { "Paste key…" }) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun resolveTreeToPath(context: android.content.Context, uri: android.net.Uri): String? {
    // Persist permission, then stage into the app-private workspace mount so
    // PRoot always receives a plain absolute path.
    return try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val staged = java.io.File(context.filesDir, "workspaces/saf-picked")
        staged.mkdirs()
        staged.absolutePath
    } catch (_: Exception) {
        null
    }
}

// -- Approval intercept sheet -------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(
    request: VisualGateway.ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDeny,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (request.kind == VisualGateway.ApprovalKind.FILE_WRITE) {
                    "📄 Allow file modification?"
                } else {
                    "⚙️ Allow command execution?"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Card(colors = CardDefaults.cardColors()) {
                Text(
                    request.summary,
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "From ${request.agent.label} · review before approving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Deny Execution")
                }
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Approve Action")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// -- Boot states -----------------------------------------------------------------

@Composable
private fun BootLoadingState(step: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(step)
        Spacer(Modifier.height(8.dp))
        Text(
            "Spinning up the on-device agent runtime…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BootErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("⚠️ Boot failed", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry boot") }
    }
}

@Composable
private fun StatusDot(
    status: AgentRuntimeService.ServiceStatus,
    onTap: () -> Unit = {},
) {
    val color = when (status) {
        is AgentRuntimeService.ServiceStatus.Running ->
            MaterialTheme.colorScheme.secondary
        is AgentRuntimeService.ServiceStatus.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(onClick = onTap) {
        androidx.compose.foundation.Canvas(Modifier.padding(4.dp)) {
            drawCircle(color, radius = 14f)
        }
    }
}
