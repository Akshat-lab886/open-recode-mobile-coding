package com.dsh.mobile.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.mobile.files.WorkspaceTree
import com.dsh.mobile.preview.PreviewNavigation
import com.dsh.mobile.runtime.AgentRuntime
import com.dsh.mobile.ui.theme.DshTheme
import java.io.File

/**
 * Preview: render an HTML file the agent built, on device.
 *
 * Uses a plain WebView, NOT [AgentWebView]. That view injects the gateway shell
 * JS and wires the agent's approval bridge; running a user's own HTML through
 * it would hand that page the agent's transport, which is not what "preview
 * this file" should mean.
 *
 * Navigation is deliberately constrained to files inside the workspace: links
 * that leave the page are ignored rather than followed.
 */
@Composable
fun PreviewPane(
    configuredWorkspacePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workspace = remember(configuredWorkspacePath) {
        val picked = File(configuredWorkspacePath)
        if (configuredWorkspacePath.isNotBlank() && picked.isDirectory && picked.canRead()) picked
        else AgentRuntime.defaultWorkspace(context)
    }
    val pages = remember(configuredWorkspacePath) {
        WorkspaceTree.findByExtension(workspace, WorkspaceTree.PREVIEWABLE)
    }
    var selected by remember { mutableStateOf<File?>(null) }

    val chosen = selected
    if (chosen == null) {
        if (pages.isEmpty()) {
            Text(
                "Nothing to preview.\n\nHTML files the agent writes to the workspace appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.padding(DshTheme.tokens.spaceLg),
            )
            return
        }
        Column(modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(pages, key = { it.path }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                WorkspaceTree.resolve(workspace, entry.path)?.let { selected = it }
                            }
                            .padding(horizontal = DshTheme.tokens.spaceMd, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.path,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Open",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DshTheme.tokens.spaceMd, vertical = DshTheme.tokens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
        ) {
            Text("←", modifier = Modifier.clickable { selected = null })
            Text(
                chosen.name,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    // The page IS a local file, so it needs file access to load
                    // its own relative css/js.
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean =
                            // Follow links that stay inside the workspace - a real
                            // preview has internal navigation, and blocking every
                            // link made anything but a single self-contained file
                            // useless. Everything else (http, content, traversal
                            // out of the workspace) is blocked, which also keeps
                            // the user from being stranded on a dead page.
                            !PreviewNavigation.allow(request.url?.toString(), workspace)
                    }
                    loadUrl("file://${chosen.absolutePath}")
                }
            },
        )
    }
}
