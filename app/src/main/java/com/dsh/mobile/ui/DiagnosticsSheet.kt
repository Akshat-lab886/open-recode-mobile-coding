package com.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.mobile.diagnostics.DiagnosticsStore

/**
 * Hidden developer/diagnostic sheet.
 *
 * Opened via the status-dot long-press / 5-tap gesture in [DshMobileShell]
 * (never in the main nav). Tails the live agent log, the harness
 * `harness.log`, and the bootstrap trace with secrets redacted, and offers
 * single-tap clipboard export for bug reports.
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Diagnostics",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Secrets are redacted before display and export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            )
            Card(colors = CardDefaults.cardColors()) {
                Text(
                    body.ifBlank { "(empty — start the engine to generate logs)" },
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onCopyAll,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text("Copy all logs")
                }
                OutlinedButton(
                    onClick = { onCopyTrace(body) },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("Copy this tab") }
            }
            if (copiedFlash != null) {
                Text(
                    copiedFlash,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private enum class DiagTab(val label: String) {
    AGENT("Agent"),
    HARNESS("harness.log"),
    BOOTSTRAP("Bootstrap"),
}
