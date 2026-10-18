package com.dsh.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dsh.mobile.diff.DiffKind
import com.dsh.mobile.diff.DiffLine
import com.dsh.mobile.diff.FileSnapshot
import com.dsh.mobile.diff.WorkspaceBaselineStore
import com.dsh.mobile.diff.WorkspaceChanges
import com.dsh.mobile.diff.WorkspaceDiff
import com.dsh.mobile.runtime.AgentRuntime
import com.dsh.mobile.ui.theme.DshTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the Diffs view renders, computed off the main thread. */
private data class DiffsState(
    val loading: Boolean = true,
    val changes: WorkspaceChanges = WorkspaceChanges(emptyList(), emptyList(), emptyList(), emptyList()),
    val baseline: Map<String, FileSnapshot> = emptyMap(),
    val current: Map<String, FileSnapshot> = emptyMap(),
    val hasBaseline: Boolean = false,
)

/**
 * Diffs: what the agent changed in the workspace since the last baseline.
 *
 * Android ships no git (absent from /system/bin and toybox), so this diffs
 * against a stored snapshot instead of a revision - the same approach
 * Mobile-Harness takes with its workspace checkpoints. [WorkspaceDiff] owns the
 * comparison; this file is only the view plus IO plumbing.
 *
 * The baseline deliberately lives OUTSIDE the workspace directory, or the scan
 * would report the baseline file as a change to itself.
 */
@Composable
fun DiffsPane(
    configuredWorkspacePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workspace = remember(configuredWorkspacePath) {
        val picked = File(configuredWorkspacePath)
        if (configuredWorkspacePath.isNotBlank() && picked.isDirectory && picked.canRead()) picked
        else AgentRuntime.defaultWorkspace(context)
    }
    val store = remember(workspace) {
        WorkspaceBaselineStore(File(workspace.parentFile, "workspace-baseline.txt"))
    }

    var state by remember { mutableStateOf(DiffsState()) }
    var expanded by remember { mutableStateOf<String?>(null) }

    suspend fun scanInto(s: DiffsState): DiffsState = withContext(Dispatchers.IO) {
        val baseline = store.load()
        val current = WorkspaceDiff.scan(workspace)
        s.copy(
            loading = false,
            changes = WorkspaceDiff.compare(baseline, current),
            baseline = baseline,
            current = current,
            hasBaseline = baseline.isNotEmpty(),
        )
    }

    LaunchedEffect(workspace) {
        state = scanInto(state)
    }

    Column(modifier.fillMaxSize()) {
        DiffHeader(
            state = state,
            onAccept = {
                store.save(state.current)
                expanded = null
                state = state.copy(hasBaseline = true)
            },
            onSetBaseline = {
                store.save(state.current)
                expanded = null
                state = state.copy(hasBaseline = true, changes = WorkspaceChanges(
                    emptyList(), emptyList(), emptyList(), state.current.keys.sorted()))
            },
        )
        HorizontalDivider()
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !state.hasBaseline -> EmptyHint(
                "No baseline yet.\n\nTake one, let the agent work, then come back to see exactly what it changed."
            )
            state.changes.isEmpty -> EmptyHint("Nothing changed since the baseline.")
            else -> {
                // LazyListScope is NOT composable, so the scheme colours have to
                // be read here and passed in rather than read inside the scope.
                val addedTint = MaterialTheme.colorScheme.tertiary
                val modifiedTint = MaterialTheme.colorScheme.primary
                val deletedTint = MaterialTheme.colorScheme.error
                val toggle: (String) -> Unit = { path -> expanded = if (expanded == path) null else path }
                LazyColumn(Modifier.fillMaxSize()) {
                    DiffSection("Added", state.changes.added, addedTint, state, expanded, toggle)
                    DiffSection("Modified", state.changes.modified, modifiedTint, state, expanded, toggle)
                    DiffSection("Deleted", state.changes.deleted, deletedTint, state, expanded, toggle)
                }
            }
        }
    }
}

@Composable
private fun DiffHeader(
    state: DiffsState,
    onAccept: () -> Unit,
    onSetBaseline: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshTheme.tokens.spaceMd, vertical = DshTheme.tokens.spaceSm),
        horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (state.changes.isEmpty) "No changes" else "${state.changes.changedCount} changed",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onSetBaseline, enabled = !state.loading) {
            Text(if (state.hasBaseline) "Rebase" else "Set baseline")
        }
        if (state.hasBaseline && !state.changes.isEmpty) {
            Button(onClick = onAccept, enabled = !state.loading) { Text("Accept") }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(DshTheme.tokens.spaceLg), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.DiffSection(
    title: String,
    paths: List<String>,
    tint: Color,
    state: DiffsState,
    expanded: String?,
    onToggle: (String) -> Unit,
) {
    if (paths.isEmpty()) return
    item(key = "hdr-$title") {
        Text(
            "$title (${paths.size})",
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            modifier = Modifier.padding(horizontal = DshTheme.tokens.spaceMd, vertical = DshTheme.tokens.spaceSm),
        )
    }
    items(paths, key = { "$title/$it" }) { path ->
        Column(Modifier.fillMaxWidth().clickable { onToggle(path) }) {
            Text(
                path,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = DshTheme.tokens.spaceMd, vertical = 6.dp),
            )
            if (expanded == path) {
                val lines = WorkspaceDiff.diffFile(state.baseline[path], state.current[path])
                if (lines == null) {
                    Text(
                        "Too large to diff on device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = DshTheme.tokens.spaceMd, vertical = 4.dp),
                    )
                } else {
                    DiffBody(lines)
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun DiffBody(lines: List<DiffLine>) {
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        for (line in lines) {
            val bg = when (line.kind) {
                DiffKind.ADDED -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.30f)
                DiffKind.REMOVED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.30f)
                DiffKind.UNCHANGED -> Color.Transparent
            }
            Row(Modifier.fillMaxWidth().background(bg)) {
                Text(
                    "${line.oldNumber?.toString() ?: ""} ${line.newNumber?.toString() ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Text(
                    (if (line.kind == DiffKind.ADDED) "+" else if (line.kind == DiffKind.REMOVED) "-" else " ") + line.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
