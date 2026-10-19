package com.dsh.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dsh.mobile.bootstrap.BootstrapManager
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.proxy.VisualGateway
import com.dsh.mobile.service.AgentRuntimeService
import com.dsh.mobile.ui.chat.ConversationPane
import com.dsh.mobile.ui.chat.rememberConversationController
import com.dsh.mobile.ui.theme.DshTheme
import com.dsh.mobile.watchdog.EngineWatchdog

/**
 * VISUAL SHELL — the native wrapper around the agent WebView.
 *
 * Two presentations of one layout, chosen by window width ([AdaptiveLayout]):
 *
 *  - Compact / Medium (phone, small tablet portrait): a top app bar, the
 *    agent filling the rest, and Control Center / diagnostics as modal bottom
 *    sheets. This is the presentation that has always worked.
 *  - Expanded (large tablet, either orientation): a persistent side panel
 *    holding live engine state and quick actions, with settings replacing it
 *    in place, and the agent keeping every remaining pixel. There is width to
 *    spare here, so settings stop being a modal interruption and become
 *    somewhere you keep.
 *
 * Native layers over the agent process:
 *  1. Control Center (credentials / workspace / mode / backend).
 *  2. Approval intercept sheets (Approve / Deny).
 *  3. Bootstrap progress gate on first boot.
 *  4. Watchdog error card ([Restart Engine]) when the loopback server dies.
 *  5. Input accessory bar docked above the keyboard.
 *  6. Hidden diagnostics sheet (5-tap status dot) with one-tap log export.
 *  7. Boot / error states — the user never sees a raw CLI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshMobileShell(
    uiState: ControlCenterState,
    onCredentialsSaved: (deepseek: String, anthropic: String, openrouter: String) -> Unit,
    onWorkspacePicked: (absolutePath: String) -> Unit,
    onModeSelected: (com.dsh.mobile.data.AgentMode) -> Unit,
    onEffortSelected: (String) -> Unit,
    onAgentSelected: (ActiveAgent) -> Unit,
    onManageAgents: () -> Unit = {},
    sessions: kotlinx.coroutines.flow.Flow<List<com.dsh.mobile.data.SessionHistoryStore.Session>>? = null,
    onDeleteSession: (String) -> Unit = {},
    onClearSessions: () -> Unit = {},
    onSaveAgentProvider: (String, String, String?, String, String) -> Unit = { _, _, _, _, _ -> },
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
    var showControlCenter by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var accessoryExpanded by rememberSaveable { mutableStateOf(false) }
    // The Deck's selected top-level destination. Saveable so a rotation or a
    // process death returns to the tab the user was actually working in.
    var deckTab by rememberSaveable { mutableStateOf(DeckTab.CHAT) }
    // Set by Diffs' "Open" action, consumed by Files. Not saveable: a pending
    // cross-tab jump is meaningless after the process dies, and restoring it
    // would yank the user into Files on next launch.
    var filesRevealPath by remember { mutableStateOf<String?>(null) }
    // A command handed to the Terminal tab by the chat's "Run" action. Cleared
    // once consumed, so it cannot replay on a later visit to the tab.
    var terminalCommand by remember { mutableStateOf<String?>(null) }

    // The native conversation is the PRIMARY Chat surface (docs/REPLAN.md: the
    // WebView stops being the centre of the app). The web UI stays one tap away
    // for anything only it can do.
    // Which surface the agent actually supports, decided by measured fact rather
    // than preference: dsh and opencode never touch gateway.js, so they have no
    // stream for the native pane to read. Showing it anyway would render the
    // user's own messages and never a reply.
    val chatSurface = com.dsh.mobile.chat.ChatSurface.forAgent(uiState.config.agent.id)
    val nativeAvailable = chatSurface == com.dsh.mobile.chat.ChatSurface.NATIVE
    // The user's choice is honoured only where it is possible; the saveable flag
    // remembers the preference for the agents that can stream.
    var preferNativeChat by rememberSaveable { mutableStateOf(true) }
    val nativeChat = nativeAvailable && preferNativeChat
    val streamPort = (uiState.serviceStatus as? AgentRuntimeService.ServiceStatus.Running)?.port
    val chatController = rememberConversationController(
        streamPort = streamPort,
        onSend = onCommand,
        onApprovalResult = onApprovalResult,
    )
    val conversation by chatController.state.collectAsState()

    // Code blocks offer Run; it belongs in the Terminal tab, where the command
    // is visible with its output rather than hidden behind the chat.
    val runInTerminal: (String) -> Unit = { command ->
        terminalCommand = command
        deckTab = DeckTab.TERMINAL
    }
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
        BoxWithConstraints {
            val layout = rememberAdaptive()
            BootstrapProgressView(
                state = boot,
                onRetry = onRetryBootstrap,
                onOpenDiagnostics = { showDiagnostics = true },
                layout = layout,
            )
        }
        // The sheet is normally rendered at the end of the normal path, which
        // this early return skips — render it here too, otherwise the escape
        // hatch on the failure screen opens nothing.
        if (showDiagnostics) {
            DiagnosticsSheet(
                state = uiState.diagnostics,
                copiedFlash = uiState.copiedFlash,
                doctorResults = uiState.doctorResults,
                onCopyAll = onCopyAllLogs,
                onCopyTrace = onCopyTrace,
                onDismiss = { showDiagnostics = false },
            )
        }
        return
    }

    // Keyboard visibility drives the accessory bar (IME insets, no hacks).
    // derivedStateOf: WindowInsets.ime is animated, so reading it during
    // composition recomposed the whole shell (WebView included) on every frame
    // of the keyboard show/hide animation.
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeVisible by remember { derivedStateOf { imeBottom > 0 } }

    BoxWithConstraints {
        val layout = rememberAdaptive()

        if (layout.useSidePanel) {
            ExpandedLayout(
                uiState = uiState,
                layout = layout,
                panelMode = if (showControlCenter) PanelMode.CONTROL_CENTER else PanelMode.STATUS,
                deckTab = deckTab,
                filesRevealPath = filesRevealPath,
                onOpenInFiles = { path ->
                    filesRevealPath = path
                    deckTab = DeckTab.FILES
                },
                onRevealConsumed = { filesRevealPath = null },
                nativeChat = nativeChat,
                nativeAvailable = nativeAvailable,
                chatSurfaceReason = com.dsh.mobile.chat.ChatSurface.reasonFor(
                    uiState.config.agent.id,
                    chatSurface,
                ),
                onToggleNativeChat = { preferNativeChat = !preferNativeChat },
                conversation = conversation,
                onChatSubmit = chatController::submit,
                onApprovalDecide = chatController::decide,
                onRunInTerminal = runInTerminal,
                terminalCommand = terminalCommand,
                onTerminalCommandConsumed = { terminalCommand = null },
                onDeckTabSelected = { tab ->
                    deckTab = tab
                    // Settings is the one Deck tab that is an existing surface,
                    // not a new one: it IS the Control Center.
                    if (tab == DeckTab.SETTINGS) showControlCenter = true
                },
                onOpenControlCenter = { showControlCenter = true },
                onCloseControlCenter = { showControlCenter = false },
                onOpenDiagnostics = { showDiagnostics = true },
                onRetryBoot = onRetryBoot,
                onRestartEngine = onRestartEngine,
                onWebApproval = onWebApproval,
                onCommand = onCommand,
                onInsertPath = onInsertPath,
                imeVisible = imeVisible && layout.showAccessoryBar,
                accessoryExpanded = accessoryExpanded,
                onToggleAccessory = { accessoryExpanded = !accessoryExpanded },
                snackbar = snackbar,
                onCredentialsSaved = onCredentialsSaved,
                onWorkspacePicked = onWorkspacePicked,
                onModeSelected = onModeSelected,
            onEffortSelected = onEffortSelected,
                onAgentSelected = onAgentSelected,
                onManageAgents = onManageAgents,
                sessions = sessions,
                onDeleteSession = onDeleteSession,
                onClearSessions = onClearSessions,
                onSaveAgentProvider = onSaveAgentProvider,
            )
        } else {
            CompactLayout(
                uiState = uiState,
                layout = layout,
                deckTab = deckTab,
                filesRevealPath = filesRevealPath,
                onOpenInFiles = { path ->
                    filesRevealPath = path
                    deckTab = DeckTab.FILES
                },
                onRevealConsumed = { filesRevealPath = null },
                nativeChat = nativeChat,
                nativeAvailable = nativeAvailable,
                chatSurfaceReason = com.dsh.mobile.chat.ChatSurface.reasonFor(
                    uiState.config.agent.id,
                    chatSurface,
                ),
                onToggleNativeChat = { preferNativeChat = !preferNativeChat },
                conversation = conversation,
                onChatSubmit = chatController::submit,
                onApprovalDecide = chatController::decide,
                onRunInTerminal = runInTerminal,
                terminalCommand = terminalCommand,
                onTerminalCommandConsumed = { terminalCommand = null },
                onDeckTabSelected = { tab ->
                    deckTab = tab
                    // Settings is the one Deck tab that is an existing surface,
                    // not a new one: it IS the Control Center.
                    if (tab == DeckTab.SETTINGS) showControlCenter = true
                },
                onOpenControlCenter = { showControlCenter = true },
                onTapStatus = {
                    diagTaps++
                    if (diagTaps >= 5) {
                        diagTaps = 0
                        showDiagnostics = true
                    }
                },
                onRetryBoot = onRetryBoot,
                onRestartEngine = onRestartEngine,
                onOpenDiagnostics = { showDiagnostics = true },
                onWebApproval = onWebApproval,
                onCommand = onCommand,
                onInsertPath = onInsertPath,
                imeVisible = imeVisible && layout.showAccessoryBar,
                accessoryExpanded = accessoryExpanded,
                onToggleAccessory = { accessoryExpanded = !accessoryExpanded },
                snackbar = snackbar,
            )
        }

        // Approvals and diagnostics are NOT presentation-dependent.
        //
        // An earlier revision wrapped them in `if (!layout.useSidePanel)` on the
        // reasoning that a tablet already shows this content in its side panel.
        // That was wrong and it locked real functionality out of tablets:
        //   - ApprovalSheet is the ONLY approve/deny UI in the app, while
        //     dsh-mobile-shell.js hides the raw prompt the moment it detects it.
        //     So on a tablet the agent waited forever on a tool approval with no
        //     way to answer it.
        //   - DiagnosticsSheet is the only route to the log tail and the
        //     one-tap clipboard export. The bootstrap-failure path renders the
        //     same sheet outside this guard, so it worked during setup and then
        //     silently stopped working.
        // The tablet side panel shows a *summary*; an approval is an interrupt
        // and a log dump is a debugging surface. Neither belongs behind a
        // presentation branch.
        uiState.approvals.firstOrNull()?.let { request ->
            ApprovalSheet(
                request = request,
                onApprove = { onApprovalResult(request.id, true) },
                onDeny = { onDismissApproval(request.id) },
            )
        }

        // Hidden diagnostics sheet (5-tap the status dot, or the panel's Logs
        // button — never in the primary nav).
        if (showDiagnostics) {
            DiagnosticsSheet(
                state = uiState.diagnostics,
                copiedFlash = uiState.copiedFlash,
                doctorResults = uiState.doctorResults,
                onCopyAll = onCopyAllLogs,
                onCopyTrace = onCopyTrace,
                onDismiss = { showDiagnostics = false },
            )
        }

        // Only the Control Center sheet itself is presentation-dependent: on a
        // tablet the same content renders into the persistent side panel, and
        // mounting both would duplicate every control on screen.
        if (!layout.useSidePanel && showControlCenter) {
            ModalBottomSheet(
                onDismissRequest = { showControlCenter = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    ControlCenterContent(
                        uiState = uiState,
                        onCredentialsSaved = onCredentialsSaved,
                        onWorkspacePicked = onWorkspacePicked,
                        onModeSelected = onModeSelected,
            onEffortSelected = onEffortSelected,
                        onAgentSelected = onAgentSelected,
                        onManageAgents = onManageAgents,
                        sessions = sessions,
                        onDeleteSession = onDeleteSession,
                        onClearSessions = onClearSessions,
                        onSaveAgentProvider = onSaveAgentProvider,
                        modifier = Modifier.widthIn(max = DshTheme.tokens.contentMaxWidth),
                    )
                }
            }
        }

        // Hardware/gesture Back should close whatever the tablet panel opened,
        // not fall through to the launcher. Compose has no navigation stack here
        // to pop, so this is the whole of it.
        BackHandler(enabled = layout.useSidePanel && showControlCenter) {
            showControlCenter = false
        }
    }
}

private enum class PanelMode { STATUS, CONTROL_CENTER }

/**
 * Shown if the Settings tab is rendered without the Control Center being open.
 *
 * On a phone Settings opens the Control Center as a sheet, and on a tablet it
 * takes over the side panel - both handled above. This composable exists so the
 * `when` is exhaustive over [DeckTab] with no dead branch, and so a future
 * layout that reaches this state says something honest instead of rendering an
 * empty screen.
 */
@Composable
private fun SettingsRelocatedNote(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Settings is the Control Center.\nIt opens alongside this panel.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -- Compact / Medium ---------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactLayout(
    uiState: ControlCenterState,
    layout: AdaptiveLayout,
    deckTab: DeckTab,
    onDeckTabSelected: (DeckTab) -> Unit,
    filesRevealPath: String?,
    onOpenInFiles: (String) -> Unit,
    onRevealConsumed: () -> Unit,
    nativeChat: Boolean,
    nativeAvailable: Boolean,
    chatSurfaceReason: String,
    onToggleNativeChat: () -> Unit,
    conversation: com.dsh.mobile.chat.Conversation,
    onChatSubmit: (String) -> Unit,
    onApprovalDecide: (String, Boolean) -> Unit,
    onRunInTerminal: (String) -> Unit,
    terminalCommand: String?,
    onTerminalCommandConsumed: () -> Unit,
    onOpenControlCenter: () -> Unit,
    onTapStatus: () -> Unit,
    onRetryBoot: () -> Unit,
    onRestartEngine: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onWebApproval: (String) -> Unit,
    onCommand: (String) -> Unit,
    onInsertPath: (String) -> Unit,
    imeVisible: Boolean,
    accessoryExpanded: Boolean,
    onToggleAccessory: () -> Unit,
    snackbar: SnackbarHostState,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    AgentHeroTitle(
                        displayedAgent(uiState.config.agent, uiState.serviceStatus),
                        uiState.serviceStatus,
                        modeLabel = uiState.config.mode.label,
                        effort = uiState.config.effort,
                        compact = true,
                    )
                },
                actions = {
                    StatusCapsule(uiState.serviceStatus, onClick = onTapStatus)
                    Spacer(Modifier.width(DshTheme.tokens.spaceXs))
                    IconButton(onClick = onOpenControlCenter) {
                        Icon(Icons.Default.Settings, contentDescription = "Control Center")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    // Mobile-Harness keeps the hero bar transparent so the stage
                    // reads as the page, not as a card under a toolstrip.
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                // No statusBarsPadding(): TopAppBar already applies
                // systemBars.only(Horizontal + Top) internally, so adding it here
                // made the bar a full status-bar taller with a dead band above
                // the title.
            )
        },
        bottomBar = {
            Column {
                // Context-aware accessory bar: only when the keyboard is up. It
                // sits ABOVE the deck nav so the composer is always reachable.
                if (imeVisible) {
                    InputAccessoryBar(
                        workspacePath = uiState.config.workspacePath,
                        onCommand = onCommand,
                        onInsertPath = onInsertPath,
                        expanded = accessoryExpanded,
                        onToggleExpanded = onToggleAccessory,
                        // imePadding(), not just navigationBarsPadding(): while the
                        // keyboard is up the navigation-bar inset is ~0, so the bar
                        // was docking behind the IME instead of above it.
                        modifier = Modifier.imePadding().navigationBarsPadding(),
                    )
                }
                // The Deck's eight destinations. Always present: it is the app's
                // primary structure now, not a panel behind a gesture.
                DeckNavBar(selected = deckTab, onSelect = onDeckTabSelected)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Watchdog error card docks above the WebView — workspace safe.
            val watch = uiState.watchdog
            if (watch is EngineWatchdog.WatchdogState.Unhealthy) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    EngineErrorCard(
                        state = watch,
                        onRestart = onRestartEngine,
                        onOpenDiagnostics = onOpenDiagnostics,
                        modifier = Modifier
                            .widthIn(max = DshTheme.tokens.contentMaxWidth)
                            .padding(horizontal = DshTheme.tokens.spaceMd, vertical = DshTheme.tokens.spaceSm),
                    )
                }
            }
            when (deckTab) {
                // Only Chat hosts the live agent surface. The other tabs are
                // native views over the workspace, so mounting the WebView for
                // them would burn battery behind a screen the user cannot see.
                DeckTab.CHAT -> Column(Modifier.weight(1f).fillMaxSize()) {
                if (nativeChat) {
                    ChatSurfaceHeader(
                        native = true,
                        nativeAvailable = nativeAvailable,
                        reason = chatSurfaceReason,
                        onToggle = onToggleNativeChat,
                    )
                    ConversationPane(
                        conversation = conversation,
                        onRunCode = onRunInTerminal,
                        onApproval = onApprovalDecide,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    ChatSurfaceHeader(
                        native = false,
                        nativeAvailable = nativeAvailable,
                        reason = chatSurfaceReason,
                        onToggle = onToggleNativeChat,
                    )
                    AgentStage(
                        uiState = uiState,
                        layout = layout,
                        onWebApproval = onWebApproval,
                        onRetryBoot = onRetryBoot,
                        // imePadding() is required, not cosmetic: with
                        // enableEdgeToEdge() the window is NOT resized for the keyboard on
                        // API 30+, and the page pins its composer with
                        // position:sticky;bottom:0, so without this the one control the
                        // user must reach sits under the IME.
                        modifier = Modifier
                            .weight(1f)
                            .imePadding(),
                    )
                }
                    // Native composer under the agent surface. imePadding on the
                    // stage, not here: the bar must stay put while the field
                    // takes focus, or the layout jumps on every keystroke.
                    ComposerBar(
                        workspacePath = uiState.config.workspacePath,
                        onSend = if (nativeChat) onChatSubmit else onCommand,
                    )
                }
                // Native tab surfaces (one per round as they are built).
                DeckTab.DIFFS -> DiffsPane(
                    configuredWorkspacePath = uiState.config.workspacePath,
                    onOpenInFiles = onOpenInFiles,
                    modifier = Modifier.weight(1f),
                )
                DeckTab.FILES -> FilesPane(
                    configuredWorkspacePath = uiState.config.workspacePath,
                    revealPath = filesRevealPath,
                    onRevealConsumed = onRevealConsumed,
                    modifier = Modifier.weight(1f),
                )
                DeckTab.TERMINAL -> TerminalPane(
                    configuredWorkspacePath = uiState.config.workspacePath,
                    pendingCommand = terminalCommand,
                    onPendingConsumed = onTerminalCommandConsumed,
                    modifier = Modifier.weight(1f),
                )
                DeckTab.TASKS -> TasksPane(
                    uiState = uiState,
                    modifier = Modifier.weight(1f),
                )
                DeckTab.ARTIFACTS -> ArtifactsPane(
                    configuredWorkspacePath = uiState.config.workspacePath,
                    modifier = Modifier.weight(1f),
                )
                DeckTab.PREVIEW -> PreviewPane(
                    configuredWorkspacePath = uiState.config.workspacePath,
                    modifier = Modifier.weight(1f),
                )
                // Settings is the one Deck tab that is an existing surface: the
                // Control Center, which opens as a side panel on tablet and as a
                // sheet on phone. This branch is the honest statement that there
                // is no separate native pane, rather than a dead placeholder.
                DeckTab.SETTINGS -> SettingsRelocatedNote(modifier = Modifier.weight(1f))
            }
        }
    }
}

// -- Expanded (tablet) --------------------------------------------------------

@Composable
private fun ExpandedLayout(
    uiState: ControlCenterState,
    layout: AdaptiveLayout,
    panelMode: PanelMode,
    deckTab: DeckTab,
    onDeckTabSelected: (DeckTab) -> Unit,
    filesRevealPath: String?,
    onOpenInFiles: (String) -> Unit,
    onRevealConsumed: () -> Unit,
    nativeChat: Boolean,
    nativeAvailable: Boolean,
    chatSurfaceReason: String,
    onToggleNativeChat: () -> Unit,
    conversation: com.dsh.mobile.chat.Conversation,
    onChatSubmit: (String) -> Unit,
    onApprovalDecide: (String, Boolean) -> Unit,
    onRunInTerminal: (String) -> Unit,
    terminalCommand: String?,
    onTerminalCommandConsumed: () -> Unit,
    onOpenControlCenter: () -> Unit,
    onCloseControlCenter: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRetryBoot: () -> Unit,
    onRestartEngine: () -> Unit,
    onWebApproval: (String) -> Unit,
    onCommand: (String) -> Unit,
    onInsertPath: (String) -> Unit,
    imeVisible: Boolean,
    accessoryExpanded: Boolean,
    onToggleAccessory: () -> Unit,
    snackbar: SnackbarHostState,
    onCredentialsSaved: (String, String, String) -> Unit,
    onWorkspacePicked: (String) -> Unit,
    onModeSelected: (com.dsh.mobile.data.AgentMode) -> Unit,
    onEffortSelected: (String) -> Unit,
    onAgentSelected: (ActiveAgent) -> Unit,
    onManageAgents: () -> Unit = {},
    sessions: kotlinx.coroutines.flow.Flow<List<com.dsh.mobile.data.SessionHistoryStore.Session>>? = null,
    onDeleteSession: (String) -> Unit = {},
    onClearSessions: () -> Unit = {},
    onSaveAgentProvider: (String, String, String?, String, String) -> Unit = { _, _, _, _, _ -> },
) {
    val tokens = DshTheme.tokens
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                if (imeVisible) {
                    InputAccessoryBar(
                        workspacePath = uiState.config.workspacePath,
                        onCommand = onCommand,
                        onInsertPath = onInsertPath,
                        expanded = accessoryExpanded,
                        onToggleExpanded = onToggleAccessory,
                        // imePadding(), not just navigationBarsPadding(): while the
                        // keyboard is up the navigation-bar inset is ~0, so the bar
                        // was docking behind the IME instead of above it.
                        modifier = Modifier.imePadding().navigationBarsPadding(),
                    )
                }
                DeckNavBar(selected = deckTab, onSelect = onDeckTabSelected)
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            Surface(
                modifier = Modifier
                    .width(tokens.panelWidth)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.fillMaxSize()) {
                    when (panelMode) {
                        PanelMode.STATUS -> StatusPanel(
                            uiState = uiState,
                            onOpenControlCenter = onOpenControlCenter,
                            onOpenDiagnostics = onOpenDiagnostics,
                            onRestartEngine = onRestartEngine,
                            onCommand = onCommand,
                        )
                        PanelMode.CONTROL_CENTER -> ControlCenterContent(
                            uiState = uiState,
                            onCredentialsSaved = onCredentialsSaved,
                            onWorkspacePicked = onWorkspacePicked,
                            onModeSelected = onModeSelected,
            onEffortSelected = onEffortSelected,
                            onAgentSelected = onAgentSelected,
                            onManageAgents = onManageAgents,
                            sessions = sessions,
                            onDeleteSession = onDeleteSession,
                            onClearSessions = onClearSessions,
                            onSaveAgentProvider = onSaveAgentProvider,
                            modifier = Modifier.fillMaxSize(),
                            // Without this the panel is a one-way door: on a
                            // tablet there is no system back affordance to
                            // fall through to, because settings replaced the
                            // status view rather than overlaying it.
                            header = { PanelHeader(title = "Settings", onBack = onCloseControlCenter) },
                        )
                    }
                }
            }
            VerticalRule()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val watch = uiState.watchdog
                if (watch is EngineWatchdog.WatchdogState.Unhealthy) {
                    EngineErrorCard(
                        state = watch,
                        onRestart = onRestartEngine,
                        onOpenDiagnostics = onOpenDiagnostics,
                        modifier = Modifier
                            .widthIn(max = DshTheme.tokens.contentMaxWidth)
                            .align(Alignment.TopCenter)
                            .padding(DshTheme.tokens.spaceMd),
                    )
                }
                when (deckTab) {
                    DeckTab.CHAT -> Column(Modifier.fillMaxSize()) {
                        if (nativeChat) {
                            ChatSurfaceHeader(
                        native = true,
                        nativeAvailable = nativeAvailable,
                        reason = chatSurfaceReason,
                        onToggle = onToggleNativeChat,
                    )
                            ConversationPane(
                                conversation = conversation,
                                onRunCode = onRunInTerminal,
                                onApproval = onApprovalDecide,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            ChatSurfaceHeader(
                        native = false,
                        nativeAvailable = nativeAvailable,
                        reason = chatSurfaceReason,
                        onToggle = onToggleNativeChat,
                    )
                            AgentStage(
                                uiState = uiState,
                                layout = layout,
                                onWebApproval = onWebApproval,
                                onRetryBoot = onRetryBoot,
                                modifier = Modifier.weight(1f).imePadding(),
                            )
                        }
                        ComposerBar(
                            workspacePath = uiState.config.workspacePath,
                            onSend = if (nativeChat) onChatSubmit else onCommand,
                        )
                    }
                    DeckTab.DIFFS -> DiffsPane(
                        configuredWorkspacePath = uiState.config.workspacePath,
                        onOpenInFiles = onOpenInFiles,
                        modifier = Modifier.fillMaxSize(),
                    )
                    DeckTab.FILES -> FilesPane(
                        configuredWorkspacePath = uiState.config.workspacePath,
                        revealPath = filesRevealPath,
                        onRevealConsumed = onRevealConsumed,
                        modifier = Modifier.fillMaxSize(),
                    )
                    DeckTab.TERMINAL -> TerminalPane(
                        configuredWorkspacePath = uiState.config.workspacePath,
                        pendingCommand = terminalCommand,
                        onPendingConsumed = onTerminalCommandConsumed,
                        modifier = Modifier.fillMaxSize(),
                    )
                    DeckTab.TASKS -> TasksPane(
                        uiState = uiState,
                        modifier = Modifier.fillMaxSize(),
                    )
                    DeckTab.ARTIFACTS -> ArtifactsPane(
                        configuredWorkspacePath = uiState.config.workspacePath,
                        modifier = Modifier.fillMaxSize(),
                    )
                    DeckTab.PREVIEW -> PreviewPane(
                        configuredWorkspacePath = uiState.config.workspacePath,
                        modifier = Modifier.fillMaxSize(),
                    )
                    DeckTab.SETTINGS -> SettingsRelocatedNote(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun VerticalRule() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * Title bar for a side-panel sub-view.
 *
 * The tablet panel swaps its content rather than stacking a new screen, so
 * this is the only way back. It has to be there: the Android system back
 * button does not pop a Compose state change, and the panel sits beside the
 * agent rather than over it, so there is no scrim to dismiss either.
 */
@Composable
private fun PanelHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshTheme.tokens.spaceSm, vertical = DshTheme.tokens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to engine status",
            )
        }
        Spacer(Modifier.width(DshTheme.tokens.spaceXs))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The agent surface plus its boot / error states, capped on wide windows. */
/**
 * The Native / Web UI switch above the Chat surface.
 *
 * Both surfaces stay available on purpose. The native one is the product, but
 * the web UI is the only place some things exist (provider settings, the DSH
 * session browser), so removing it would be a downgrade rather than a rewrite.
 */
@Composable
private fun ChatSurfaceHeader(
    native: Boolean,
    nativeAvailable: Boolean,
    reason: String,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshTheme.tokens.spaceMd, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (native) "Native" else "Web UI",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!native) {
                // Say WHY the native pane is unavailable. A silent fallback
                // reads as a bug; a stated one reads as a limit.
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
        if (nativeAvailable) {
            Text(
                text = if (native) "Switch to Web UI" else "Back to native",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = DshTheme.tokens.spaceSm, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun AgentStage(
    uiState: ControlCenterState,
    layout: AdaptiveLayout,
    onWebApproval: (String) -> Unit,
    onRetryBoot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            // widthIn BEFORE fillMaxSize, deliberately. fillMaxSize() sets the
            // child's min width to the parent's width, so with the order reversed
            // the max below could never win — the cap was inert on exactly the
            // case it exists for, and produced minWidth > maxWidth constraints.
            Modifier
                .widthIn(
                    max = if (layout.useSidePanel) {
                        // A tablet already spends its width on the side panel,
                        // so the agent keeps everything that is left.
                        Dp.Unspecified
                    } else {
                        // A phone-width column: cap it so a landscape phone
                        // does not stretch a chat transcript to 900dp lines.
                        DshTheme.tokens.contentMaxWidth + 160.dp
                    },
                )
                .fillMaxSize(),
        ) {
            when (val status = uiState.serviceStatus) {
                is AgentRuntimeService.ServiceStatus.Running -> {
                    AgentWebView(
                        url = status.url,
                        onWebApproval = onWebApproval,
                        nativeSidePanel = layout.useSidePanel,
                        modifier = Modifier.fillMaxSize(),
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

// -- Tablet side panel --------------------------------------------------------

@Composable
private fun StatusPanel(
    uiState: ControlCenterState,
    onOpenControlCenter: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRestartEngine: () -> Unit,
    onCommand: (String) -> Unit,
) {
    val tokens = DshTheme.tokens
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = tokens.spaceLg, vertical = tokens.spaceXl),
            verticalArrangement = Arrangement.spacedBy(tokens.spaceLg),
        ) {
            AgentHeroTitle(
                displayedAgent(uiState.config.agent, uiState.serviceStatus),
                uiState.serviceStatus,
                modeLabel = uiState.config.mode.label,
                effort = uiState.config.effort,
                compact = false,
            )
            Spacer(Modifier.height(tokens.spaceXs))

            val status = uiState.serviceStatus
            StatusPill(
                label = statusLabel(status),
                color = statusColor(status),
                detail = (status as? AgentRuntimeService.ServiceStatus.Running)?.port
                    ?.let { "127.0.0.1:$it" },
            )

            SectionHeader(title = "Engine")
            PanelCard {
                DetailRow("Backend", uiState.config.agent.label)
                DetailRow("Mode", uiState.config.mode.label)
                DetailRow(
                    "Workspace",
                    uiState.config.workspacePath.ifBlank { "Default app workspace" },
                )
                DetailRow(
                    "Credentials",
                    if (uiState.hasKeys) "Stored" else "Not set",
                    valueColor = if (uiState.hasKeys) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }

            val watch = uiState.watchdog
            if (watch is EngineWatchdog.WatchdogState.Unhealthy) {
                SectionHeader(title = "Attention")
                PanelCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        watch.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(tokens.spaceMd))
                    TextButton(
                        onClick = onRestartEngine,
                        modifier = Modifier.heightIn(min = tokens.minTouchTarget),
                    ) {
                        Text("Restart engine")
                    }
                }
            }

            SectionHeader(title = "Quick commands")
            QuickCommandGrid(onCommand = onCommand)

            Spacer(Modifier.height(tokens.spaceMd))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spaceMd, vertical = tokens.spaceSm),
            horizontalArrangement = Arrangement.spacedBy(tokens.spaceSm),
        ) {
            TextButton(
                onClick = onOpenControlCenter,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = tokens.minTouchTarget),
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Settings")
            }
            TextButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = tokens.minTouchTarget),
            ) {
                Icon(Icons.Default.Description, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Logs")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickCommandGrid(onCommand: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
    ) {
        AgentCommand.entries.forEach { cmd ->
            AssistChip(
                onClick = { onCommand(cmd.command) },
                label = { Text(cmd.shortLabel) },
                leadingIcon = { Text(cmd.emoji) },
            )
        }
    }
}

// -- Shared bits --------------------------------------------------------------

/**
 * The agent the app bar should name.
 *
 * The configured agent is what the user *picked*; the running agent is what
 * actually *served*. Those differ whenever the configured one is not installed
 * and the service falls back, and the bar was naming the configured one - so a
 * device running DeepSeek Harness displayed "Claude Code (via gateway)" while
 * the log correctly said it fell back. A status line that names the wrong agent
 * is the same class of lie as a green check on a broken build, just quieter.
 */
internal fun displayedAgent(
    configured: ActiveAgent,
    status: AgentRuntimeService.ServiceStatus,
): ActiveAgent =
    (status as? AgentRuntimeService.ServiceStatus.Running)?.agent ?: configured

/**
 * Per-agent glyph for the status-hero pill.
 *
 * One icon per backend, so which engine is live is legible at a glance without
 * reading the label - the same job Mobile-Harness's 34dp agent pill does.
 */
private fun agentHeroIcon(agent: ActiveAgent): ImageVector = when (agent) {
    ActiveAgent.DEEPSEEK_HARNESS -> Icons.Default.SmartToy
    ActiveAgent.CLAUDE_CODE -> Icons.Default.AutoAwesome
    ActiveAgent.CODEX -> Icons.Default.Terminal
    ActiveAgent.OPENCODE -> Icons.Default.Code
    ActiveAgent.HERMES -> Icons.Default.Hub
    ActiveAgent.ANTIGRAVITY -> Icons.Default.Memory
}

/**
 * Mobile-Harness-style status hero: a 34dp agent-icon pill beside a two-line
 * title.
 *
 * The subtitle carries the control state the session is ACTUALLY running under
 * (mode, and effort when the harness has one), because that is the thing a user
 * changes and then cannot see. Deliberately sparse: the bar answers "which
 * agent, in what mode" and nothing else - every other control stays in the
 * Control Centre, which is what keeps the phone layout from becoming a wall of
 * chips.
 */
@Composable
private fun AgentHeroTitle(
    agent: ActiveAgent,
    status: AgentRuntimeService.ServiceStatus,
    modeLabel: String,
    effort: String?,
    compact: Boolean,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = accent.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
            modifier = Modifier.size(34.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    agentHeroIcon(agent),
                    // Decorative: the label beside it already names the agent.
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.width(DshTheme.tokens.spaceSm))
        Column {
            Text(
                if (compact) agent.label else "DSH Mobile",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(modeLabel)
                    if (!effort.isNullOrBlank()) {
                        append(" · ").append(effort.replaceFirstChar { it.uppercase() })
                    }
                    if (!compact) append(" · ").append(agent.label)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The live-status capsule (Mobile-Harness's status pill): a tinted
 * `RoundedCornerShape(50)` pill holding a state dot and a short label.
 *
 * Replaces the old bare status dot IconButton. Tapping it still runs the
 * 5-tap-to-diagnostics gesture, so that affordance is preserved rather than
 * removed - the capsule makes it discoverable instead of hiding it in an
 * unlabelled dot.
 */
@Composable
private fun StatusCapsule(
    status: AgentRuntimeService.ServiceStatus,
    onClick: () -> Unit,
) {
    val tone = statusColor(status)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = tone.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.35f)),
        modifier = Modifier
            .heightIn(min = 34.dp)
            .semantics { contentDescription = "Engine status (tap 5 times for logs)" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(tone, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                statusLabel(status),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

internal fun statusLabel(status: AgentRuntimeService.ServiceStatus): String =
    when (status) {
        is AgentRuntimeService.ServiceStatus.Running -> "Running"
        is AgentRuntimeService.ServiceStatus.Booting -> "Starting"
        is AgentRuntimeService.ServiceStatus.Error -> "Error"
        else -> "Idle"
    }

@Composable
internal fun statusColor(status: AgentRuntimeService.ServiceStatus): Color =
    when (status) {
        is AgentRuntimeService.ServiceStatus.Running -> MaterialTheme.colorScheme.secondary
        is AgentRuntimeService.ServiceStatus.Booting -> MaterialTheme.colorScheme.tertiary
        is AgentRuntimeService.ServiceStatus.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
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
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = DshTheme.tokens.contentMaxWidth)
                    .padding(DshTheme.tokens.spaceLg),
                verticalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceMd),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (request.kind == VisualGateway.ApprovalKind.FILE_WRITE) {
                            Icons.Default.Description
                        } else {
                            Icons.Default.Settings
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(DshTheme.tokens.spaceMd))
                    Text(
                        if (request.kind == VisualGateway.ApprovalKind.FILE_WRITE) {
                            "Allow file modification?"
                        } else {
                            "Allow command execution?"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                PanelCard {
                    Text(
                        request.summary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "From ${request.agent.label} · review before approving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceMd),
                ) {
                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = DshTheme.tokens.minTouchTarget),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Deny")
                    }
                    androidx.compose.material3.Button(
                        onClick = onApprove,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = DshTheme.tokens.minTouchTarget),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Approve")
                    }
                }
                Spacer(Modifier.height(DshTheme.tokens.spaceSm))
            }
        }
    }
}

// -- Boot states --------------------------------------------------------------

@Composable
private fun BootLoadingState(step: String, modifier: Modifier = Modifier) {
    val tokens = DshTheme.tokens
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = tokens.formMaxWidth)
                .padding(tokens.spaceXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(tokens.spaceLg))
            Text(
                step,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(tokens.spaceSm))
            Text(
                "Starting the on-device agent runtime. The first launch unpacks " +
                    "the Linux environment and can take a few minutes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BootErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = DshTheme.tokens
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = tokens.formMaxWidth)
                .padding(tokens.spaceXl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(tokens.spaceMd))
            Text("Boot failed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(tokens.spaceSm))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(tokens.spaceLg))
            androidx.compose.material3.Button(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = tokens.minTouchTarget),
            ) {
                Icon(Icons.Default.Description, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Retry boot")
            }
        }
    }
}
