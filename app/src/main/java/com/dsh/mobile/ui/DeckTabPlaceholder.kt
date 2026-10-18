package com.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dsh.mobile.ui.theme.DshTheme

/**
 * Temporary native surface for a Deck tab that has not been built yet.
 *
 * Each of the seven non-Chat tabs is filled in one at a time; this keeps the
 * navigation honest (the destination exists and is reachable) without leaving
 * a blank screen behind a tab the user deliberately tapped. It shows the tab's
 * name, its purpose, and the live workspace so it is never a dead end.
 */
@Composable
fun DeckTabPlaceholder(
    tab: DeckTab,
    uiState: ControlCenterState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(DshTheme.tokens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceMd, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            tab.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            tab.label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            tab.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = DshTheme.tokens.contentMaxWidth),
        )
        val workspace = uiState.config.workspacePath.ifBlank { "Default app workspace" }
        Text(
            workspace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = DshTheme.tokens.contentMaxWidth),
        )
    }
}
