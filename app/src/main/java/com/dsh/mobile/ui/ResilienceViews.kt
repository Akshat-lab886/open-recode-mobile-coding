package com.dsh.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.bootstrap.BootstrapManager
import com.dsh.mobile.ui.theme.DshTheme
import com.dsh.mobile.watchdog.EngineWatchdog

// -- 1. Initialization Progress View ------------------------------------------

/**
 * Full-screen first-boot experience. Streams [BootstrapManager] state into a
 * native progress bar + current step + scrolling detail log — the user sees
 * exactly what is configuring, with zero terminal exposure.
 *
 * [layout] caps the column. Without it the progress bar, the step text and
 * the log pane each stretch the full width of a tablet, which is both ugly
 * and harder to read than the phone layout.
 */
@Composable
fun BootstrapProgressView(
    state: BootstrapManager.BootstrapState,
    onRetry: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    layout: AdaptiveLayout = AdaptiveLayout.PhonePortrait,
    modifier: Modifier = Modifier,
) {
    val tokens = DshTheme.tokens
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // A tablet has room for a wider column, and the log
                    // pane beside a 520dp form is cramped next to it.
                    .widthIn(
                        max = if (layout.useTwoColumnForms) {
                            DshTheme.tokens.contentMaxWidth
                        } else {
                            DshTheme.tokens.formMaxWidth
                        },
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(tokens.spaceXl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Setting up your engine",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(tokens.spaceXs))
                Text(
                    "One-time setup — unpacking the Linux environment and the " +
                        "DeepSeek Harness agent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(tokens.spaceXl))

                when (state) {
                    is BootstrapManager.BootstrapState.Running -> {
                        Text(
                            state.step,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(tokens.spaceMd))
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(tokens.spaceSm))
                        Text(
                            "${state.progress}% complete",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(tokens.spaceLg))
                        BootstrapLogTail(state.lines)
                    }
                    is BootstrapManager.BootstrapState.Failed -> {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(tokens.spaceMd))
                        Text(
                            "Setup hit a snag",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(tokens.spaceSm))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(tokens.spaceLg))
                        BootstrapLogTail(state.lines)
                        Spacer(Modifier.height(tokens.spaceLg))
                        Button(
                            onClick = onRetry,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = tokens.minTouchTarget),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(tokens.spaceSm))
                            Text("Retry setup")
                        }
                        // Without this, a failed bootstrap is a dead end: the
                        // user cannot reach logs, API keys, or the workspace
                        // picker. That matters most for non-technical users,
                        // who are exactly the ones who hit this screen.
                        Spacer(Modifier.height(tokens.spaceSm))
                        TextButton(
                            onClick = onOpenDiagnostics,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = tokens.minTouchTarget),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
                            Spacer(Modifier.width(tokens.spaceSm))
                            Text("View setup log & settings")
                        }
                    }
                    else -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(tokens.spaceMd))
                        Text("Preparing…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun BootstrapLogTail(lines: List<String>) {
    CodeSurface(maxHeight = 240.dp) {
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(DshTheme.tokens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lines.takeLast(60)) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -- 2. Health error card with [Restart Engine] --------------------------------

/**
 * Friendly crash-recovery card shown when the watchdog declares the engine
 * unhealthy. Explains *why* in plain language, keeps workspace data safe
 * (restart only re-mounts, never wipes), and offers a single recovery tap.
 */
@Composable
fun EngineErrorCard(
    state: EngineWatchdog.WatchdogState.Unhealthy,
    onRestart: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tokens = DshTheme.tokens
    PanelCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(tokens.spaceSm))
            Text(
                "Engine paused",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Spacer(Modifier.height(tokens.spaceSm))
        Text(
            state.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            "Your workspace files are safe — restart only reconnects the engine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(tokens.spaceMd))
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.spaceMd)) {
            Button(
                onClick = onRestart,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = tokens.minTouchTarget),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(tokens.spaceSm))
                Text("Restart engine")
            }
            OutlinedButton(
                onClick = { onOpenDiagnostics() },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = tokens.minTouchTarget),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Diagnostics") }
        }
        if (state.detail.contains("battery", ignoreCase = true) ||
            state.detail.contains("timed out", ignoreCase = true)
        ) {
            Spacer(Modifier.height(tokens.spaceMd))
            OutlinedButton(
                onClick = { requestBatteryExemption(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = tokens.minTouchTarget),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Keep engine awake (battery settings)") }
        }
    }
}

private fun requestBatteryExemption(context: android.content.Context) {
    runCatching {
        val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            context.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }
    }
}

// -- 3. Context-aware Input Accessory Bar -------------------------------------

/**
 * Mobile-optimized accessory panel docked above the software keyboard.
 *
 * The WebView composer stays the primary input; this bar floats above the
 * shell whenever the keyboard is visible (wired via `WindowInsets.ime` in
 * [DshMobileShell]) and offers one-tap agent commands so users rarely need to
 * type slash-commands by hand: /help, /stop-generation, /clear-context,
 * /reindex-workspace — plus a path chip for inserting the current workspace.
 *
 * [FlowRow] rather than [Row]: four chips plus a label do not fit on a narrow
 * phone, and a Row clips the overflow off-screen with no way to reach it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InputAccessoryBar(
    workspacePath: String,
    onCommand: (command: String) -> Unit,
    onInsertPath: (path: String) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {},
) {
    val tokens = DshTheme.tokens
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = tokens.contentMaxWidth)
                    .padding(horizontal = tokens.spaceMd, vertical = tokens.spaceSm),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(tokens.spaceSm),
                    verticalArrangement = Arrangement.spacedBy(tokens.spaceSm),
                ) {
                    AgentCommand.entries.forEach { cmd ->
                        androidx.compose.material3.AssistChip(
                            onClick = { onCommand(cmd.command) },
                            label = { Text(cmd.shortLabel) },
                            leadingIcon = { Text(cmd.emoji) },
                        )
                    }
                }
                AnimatedVisibility(visible = expanded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            workspacePath.ifBlank { "Default workspace" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(tokens.spaceSm))
                        androidx.compose.material3.TextButton(
                            onClick = { onInsertPath(workspacePath.ifBlank { "/workspace" }) },
                        ) { Text("Insert path") }
                    }
                }
            }
        }
    }
}

/** Quick-action commands for the accessory bar and the tablet quick panel. */
enum class AgentCommand(val shortLabel: String, val emoji: String, val command: String) {
    HELP("Help", "❓", "/help"),
    STOP("Stop", "⏹", "/stop-generation"),
    CLEAR("Clear", "🧹", "/clear-context"),
    REINDEX("Reindex", "🔄", "/reindex-workspace"),
}
