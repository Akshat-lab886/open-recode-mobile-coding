package com.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.diagnostics.DiagnosticsStore
import com.dsh.mobile.ui.theme.DshTheme

/**
 * Hidden developer/diagnostic sheet.
 *
 * Opened via the 5-tap status-dot gesture in [DshMobileShell] (or the Logs
 * button in the tablet side panel — never in the main nav). Tails the live
 * agent log, the harness `harness.log`, and the bootstrap trace with secrets
 * redacted, and offers single-tap clipboard export for bug reports.
 *
 * The body is capped and centred. A monospace log pane stretched across a
 * 1400dp tablet has no usable line length, and it is the one surface in the
 * app where that is most obvious.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    state: DiagnosticsStore.DiagnosticsState,
    copiedFlash: String?,
    onCopyAll: () -> Unit,
    onCopyTrace: (trace: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(DiagTab.AGENT) }
    val tokens = DshTheme.tokens

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = DshTheme.tokens.contentMaxWidth)
                    .padding(horizontal = tokens.spaceLg)
                    .padding(bottom = tokens.spaceLg),
                verticalArrangement = Arrangement.spacedBy(tokens.spaceMd),
            ) {
                Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Secrets are redacted before display and export.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spaceSm),
                    verticalArrangement = Arrangement.spacedBy(tokens.spaceSm),
                ) {
                    DiagTab.entries.forEach { t ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(t.label) },
                        )
                    }
                }

                val (path, body) = when (tab) {
                    DiagTab.AGENT -> state.agentLogFile to state.agentLog
                    DiagTab.HARNESS -> state.harnessLogFile to state.harnessLog
                    DiagTab.BOOTSTRAP -> "dsh-logs/bootstrap.log" to state.bootstrapLog
                }
                Text(
                    path.ifBlank { "(no file yet)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CodeSurface(maxHeight = 340.dp) {
                    Text(
                        body.ifBlank { "(empty — start the engine to generate logs)" },
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(tokens.spaceMd),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spaceMd),
                ) {
                    Button(
                        onClick = onCopyAll,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = tokens.minTouchTarget),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(tokens.spaceSm))
                        Text("Copy all logs")
                    }
                    OutlinedButton(
                        onClick = { onCopyTrace(body) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = tokens.minTouchTarget),
                    ) { Text("Copy this tab") }
                }
                if (copiedFlash != null) {
                    Text(
                        copiedFlash,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Spacer(Modifier.height(tokens.spaceSm))
            }
        }
    }
}

private enum class DiagTab(val label: String) {
    AGENT("Agent"),
    HARNESS("harness.log"),
    BOOTSTRAP("Bootstrap"),
}
