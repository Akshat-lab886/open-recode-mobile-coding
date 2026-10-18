package com.dsh.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dsh.mobile.files.FileEntry
import com.dsh.mobile.files.WorkspaceTree
import com.dsh.mobile.runtime.AgentRuntime
import com.dsh.mobile.ui.theme.DshTheme
import java.io.File

/**
 * Artifacts: the reports, plans and assets the agent produced.
 *
 * Derived from the workspace by file type rather than from anything the agent
 * claims it wrote - an artifact you cannot open on disk is not an artifact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsPane(
    configuredWorkspacePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workspace = remember(configuredWorkspacePath) {
        val picked = File(configuredWorkspacePath)
        if (configuredWorkspacePath.isNotBlank() && picked.isDirectory && picked.canRead()) picked
        else AgentRuntime.defaultWorkspace(context)
    }
    val entries = remember(configuredWorkspacePath) {
        WorkspaceTree.findByExtension(workspace, WorkspaceTree.ARTIFACT_EXTENSIONS)
    }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (entries.isEmpty()) {
        Text(
            "No artifacts yet.\n\nPlans, reports and generated files the agent writes to the workspace show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(DshTheme.tokens.spaceLg),
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(entries, key = { it.path }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val file = WorkspaceTree.resolve(workspace, entry.path)
                            val text = file?.let { WorkspaceTree.preview(it) }
                            preview = text?.let { entry.name to it }
                        }
                        .padding(horizontal = DshTheme.tokens.spaceMd, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        WorkspaceTree.formatSize(entry.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }

    preview?.let { (name, body) ->
        ModalBottomSheet(
            onDismissRequest = { preview = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().padding(DshTheme.tokens.spaceMd)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = DshTheme.tokens.spaceSm),
                )
            }
        }
    }
}
