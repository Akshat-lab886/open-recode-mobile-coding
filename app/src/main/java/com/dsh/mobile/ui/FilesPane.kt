package com.dsh.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
 * Files: browse the workspace the agent works in, and preview text files.
 *
 * Android has no file manager and no git binary, so this is the only way to
 * see what the agent actually produced on disk. Navigation state is a plain
 * workspace-relative path, which keeps it restorable and testable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesPane(
    configuredWorkspacePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workspace = remember(configuredWorkspacePath) {
        val picked = File(configuredWorkspacePath)
        if (configuredWorkspacePath.isNotBlank() && picked.isDirectory && picked.canRead()) picked
        else AgentRuntime.defaultWorkspace(context)
    }

    var dirPath by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var query by remember { mutableStateOf("") }

    val listing = remember(dirPath, workspace) { WorkspaceTree.list(workspace, dirPath) }
    // Filtering is pure and runs on the in-memory listing, so typing stays
    // instant even deep in a large tree.
    val entries = remember(listing, query) { WorkspaceTree.filter(listing.entries, query) }

    Column(modifier.fillMaxSize()) {
        FileBreadcrumbs(
            dirPath = dirPath,
            onNavigate = { dirPath = it },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search files, or ext:kt") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DshTheme.tokens.spaceMd, vertical = DshTheme.tokens.spaceXs),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        if (entries.isEmpty()) {
            Text(
                when {
                    // Saying "this folder is empty" while a search is active is
                    // a lie - the folder has files, the query just missed them.
                    query.isNotBlank() -> "No files match \"$query\"."
                    dirPath.isBlank() -> "The workspace is empty."
                    else -> "This folder is empty."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(DshTheme.tokens.spaceLg),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (listing.parentPath != null) {
                    item(key = "__up") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { dirPath = listing.parentPath }
                                .padding(horizontal = DshTheme.tokens.spaceMd, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Up", modifier = Modifier.size(18.dp))
                            Text("..", style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                    }
                }
                items(entries, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) {
                                dirPath = entry.path
                            } else {
                                // Resolve() is the traversal guard: the row can
                                // only ever open a file under the workspace.
                                val file = WorkspaceTree.resolve(workspace, entry.path)
                                val text = file?.let { WorkspaceTree.preview(it) }
                                preview = if (text != null) entry.name to text else null
                            }
                        },
                    )
                    HorizontalDivider()
                }
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

@Composable
private fun FileBreadcrumbs(dirPath: String, onNavigate: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DshTheme.tokens.spaceMd, vertical = DshTheme.tokens.spaceSm),
        horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "workspace",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onNavigate("") },
        )
        // Accumulated paths, so tapping "main" navigates to src/main and not
        // to a bare "main" relative to wherever the user happens to be.
        var acc = ""
        WorkspaceTree.crumbs(dirPath).forEachIndexed { index, segment ->
            acc = if (acc.isEmpty()) segment else "$acc/$segment"
            val target = acc
            Text("/", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                segment,
                style = MaterialTheme.typography.labelLarge,
                color = if (index == WorkspaceTree.crumbs(dirPath).lastIndex) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.clickable { onNavigate(target) },
            )
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DshTheme.tokens.spaceMd, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
    ) {
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isDirectory) {
            Text(
                WorkspaceTree.formatSize(entry.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
