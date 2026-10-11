package com.dsh.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.mobile.bootstrap.BootstrapManager
import com.dsh.mobile.watchdog.EngineWatchdog

// -- 1. Initialization Progress View ------------------------------------------

/**
 * Full-screen first-boot experience. Streams [BootstrapManager] state into a
 * native progress bar + current step + scrolling detail log — the user sees
 * exactly what is configuring, with zero terminal exposure.
 */
@Composable
fun BootstrapProgressView(
    state: BootstrapManager.BootstrapState,
    onRetry: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("🚀", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "Setting up your AI engine",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "One-time setup — downloading the Linux environment and DeepSeek Harness.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            when (state) {
                is BootstrapManager.BootstrapState.Running -> {
                    Text(
                        state.step,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${state.progress}% complete",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    BootstrapLogTail(state.lines)
                }
                is BootstrapManager.BootstrapState.Failed -> {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Setup hit a snag",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    BootstrapLogTail(state.lines)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Retry Setup")
                    }
                    // Without this, a failed bootstrap is a dead end: the user
                    // cannot reach logs, API keys, or the workspace picker.
                    // That matters most for non-technical users, who are
                    // exactly the ones who hit this screen.
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Article, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("View setup log & settings")
                    }
                }
                else -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Preparing…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun BootstrapLogTail(lines: List<String>) {
    Card(
        Modifier.fillMaxWidth().heightIn(max = 220.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(12.dp),
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

// -- 2. Health error card with [Restart Engine] ----------------------------------

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
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Error, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Engine paused", fontWeight = FontWeight.SemiBold)
            }
            Text(state.reason, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Your workspace files are safe — restart only reconnects the engine.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRestart, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Restart Engine")
                }
                OutlinedButton(
                    onClick = { onOpenDiagnostics() },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Diagnostics") }
            }
            if (state.detail.contains("battery", ignoreCase = true) ||
                state.detail.contains("timed out", ignoreCase = true)
            ) {
                OutlinedButton(
                    onClick = { requestBatteryExemption(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Keep engine awake (battery settings)") }
            }
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

// -- 3. Context-aware Input Accessory Bar -----------------------------------------

/**
 * Mobile-optimized accessory panel docked above the software keyboard.
 *
 * The WebView composer stays the primary input; this bar floats above the
 * shell whenever the keyboard is visible (wired via
 * `WindowInsets.ime` in [DshMobileShell]) and offers one-tap agent commands
 * so users rarely need to type slash-commands by hand: /help,
 * /stop-generation, /clear-context, /reindex-workspace — plus a path-chip row
 * for inserting the current workspace path into the composer.
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
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AgentCommand.entries.forEach { cmd ->
                    AssistChip(
                        onClick = { onCommand(cmd.command) },
                        label = { Text(cmd.shortLabel) },
                        leadingIcon = { Text(cmd.emoji) },
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            workspacePath.ifBlank { "Default workspace" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = {
                            onInsertPath(workspacePath.ifBlank { "/workspace" })
                        }) { Text("Insert path") }
                    }
                }
            }
        }
    }
}

/** Quick-action commands for the accessory bar. */
enum class AgentCommand(val shortLabel: String, val emoji: String, val command: String) {
    HELP("Help", "❓", "/help"),
    STOP("Stop", "⏹", "/stop-generation"),
    CLEAR("Clear", "🧹", "/clear-context"),
    REINDEX("Reindex", "🔄", "/reindex-workspace"),
}
