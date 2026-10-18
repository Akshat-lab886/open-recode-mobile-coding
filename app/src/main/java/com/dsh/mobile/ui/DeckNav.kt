package com.dsh.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.ui.theme.DshTheme

/**
 * The eight top-level destinations of the Universal Agent Deck, in its order.
 *
 * Chat is the only tab that hosts the live agent surface (the WebView for web
 * backends, the gateway UI for CLI ones); the rest are native views over the
 * on-device workspace. Settings reuses the existing Control Center, so it is a
 * navigation target rather than a duplicate of it.
 */
enum class DeckTab(
    val label: String,
    val icon: ImageVector,
    /** Short line explaining the tab; shown only when a tab has no content yet. */
    val blurb: String,
) {
    CHAT("Chat", Icons.Default.Chat, "The live agent."),
    ARTIFACTS("Artifacts", Icons.Default.Article, "Plans, diagrams and reports the agent produced."),
    DIFFS("Diffs", Icons.Default.Difference, "Files the agent changed, as a git diff."),
    FILES("Files", Icons.Default.FolderOpen, "Browse the workspace on-device."),
    TASKS("Tasks", Icons.Default.TaskAlt, "What the agent is working on."),
    TERMINAL("Terminal", Icons.Default.Terminal, "Run shell commands in the workspace."),
    PREVIEW("Preview", Icons.Default.Visibility, "Render the web app the agent built."),
    SETTINGS("Settings", Icons.Default.Settings, "Keys, workspace, mode and effort."),
}

/**
 * The Deck's bottom navigation.
 *
 * Built as a weighted [Row] rather than a `NavigationBar` for one concrete
 * reason: EIGHT destinations do not fit a `NavigationBar` on a 360dp phone —
 * it would either clip the last items or shrink the touch targets below the
 * 48dp accessibility floor. A weighted row scrolls-free, keeps every target at
 * full height, and reads the same as the Deck on both phone and tablet.
 */
@Composable
fun DeckNavBar(
    selected: DeckTab,
    onSelect: (DeckTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeckTab.entries.forEach { tab ->
                DeckNavItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.DeckNavItem(
    tab: DeckTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    // weight(1f) keeps all eight reachable at phone width without scrolling;
    // the icon carries the meaning and the label is the (tiny) confirmation.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .semantics { contentDescription = tab.label }
            .padding(vertical = 2.dp),
    ) {
        Box(
            Modifier
                .size(width = 34.dp, height = 26.dp)
                .background(
                    if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                    RoundedCornerShape(9.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tab.icon,
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
