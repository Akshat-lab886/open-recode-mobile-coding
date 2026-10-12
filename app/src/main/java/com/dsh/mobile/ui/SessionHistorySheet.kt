package com.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.SessionHistoryStore
import com.dsh.mobile.ui.theme.DshTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Browsable list of past agent sessions.
 *
 * The store has been recording sessions since it was added, but nothing ever
 * displayed them: the user could not see what had run, could not read a
 * transcript, and could not delete one. On a device that keeps everything
 * local, "local only" is not a complete answer unless the data is actually
 * reachable and removable by the person who owns it.
 */
@Composable
fun SessionHistorySheet(
    sessions: Flow<List<SessionHistoryStore.Session>>,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = DshTheme.tokens
    // Same bound the other sheets use: a bottom sheet measures content with
    // unbounded height and LazyColumn throws against an infinite maximum.
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    val scope = rememberCoroutineScope()
    val items by sessions.collectAsState(initial = emptyList())

    Column(
        modifier = modifier
            .fillMaxWidth()
            // ModalBottomSheet measures content with unbounded height; a
            // LazyColumn against an infinite maximum throws at runtime.
            .heightIn(max = maxSheetHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spaceLg, vertical = tokens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Past sessions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (items.isNotEmpty()) {
                TextButton(onClick = { scope.launch { onClearAll() } }) {
                    Text("Clear all")
                }
            }
        }

        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .padding(tokens.spaceLg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No sessions recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight - 64.dp),
            verticalArrangement = Arrangement.spacedBy(tokens.spaceSm),
        ) {
            items(items, key = { it.id }) { session ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spaceLg, vertical = tokens.spaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            session.label,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${session.agentId} · ${formatDuration(session.durationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (session.preview.isNotBlank()) {
                            Text(
                                session.preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(tokens.spaceSm))
                    IconButton(
                        onClick = { scope.launch { onDelete(session.id) } },
                        modifier = Modifier.size(tokens.minTouchTarget),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete session",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** Compact duration for the list; long sessions get hours, short ones seconds. */
private fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1_000}s"
    ms < 3_600_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
    else -> "${ms / 3_600_000}h ${(ms % 3_600_000) / 60_000}m"
}