package com.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.dsh.mobile.bootstrap.BootstrapManager
import com.dsh.mobile.service.AgentRuntimeService
import com.dsh.mobile.tasks.TaskItem
import com.dsh.mobile.tasks.TaskSummary
import com.dsh.mobile.tasks.TaskUrgency
import com.dsh.mobile.ui.theme.DshTheme

/**
 * Tasks: what the agent needs from the user right now.
 *
 * This is deliberately not a to-do list the agent maintains - it is derived
 * from live engine state by [TaskSummary], so it can never drift from reality
 * the way a self-reported checklist does. A pending tool approval shows up
 * here because the agent is genuinely blocked on it.
 */
@Composable
fun TasksPane(
    uiState: ControlCenterState,
    modifier: Modifier = Modifier,
) {
    val items = remember(uiState) {
        TaskSummary.build(
            com.dsh.mobile.tasks.TaskInput(
                engineRunning = uiState.serviceStatus is AgentRuntimeService.ServiceStatus.Running,
                engineError = (uiState.serviceStatus as? AgentRuntimeService.ServiceStatus.Error)?.message,
                engineUnhealthy = uiState.watchdog is com.dsh.mobile.watchdog.EngineWatchdog.WatchdogState.Unhealthy,
                pendingApprovals = uiState.approvals.size,
                bootstrapBusy = uiState.bootstrap is BootstrapManager.BootstrapState.Running,
                bootstrapLabel = uiState.bootstrap.labelOrNull(),
            )
        )
    }

    LazyColumn(
        modifier.fillMaxSize().padding(DshTheme.tokens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
    ) {
        items(items, key = { it.title }) { TaskRow(it) }
    }
}

/** Human label for whichever bootstrap phase is current, if any. */
private fun BootstrapManager.BootstrapState.labelOrNull(): String? = when (this) {
    is BootstrapManager.BootstrapState.Running -> step
    else -> null
}

@Composable
private fun TaskRow(item: TaskItem) {
    val tint: Color = when (item.urgency) {
        TaskUrgency.BLOCKING -> MaterialTheme.colorScheme.error
        TaskUrgency.ATTENTION -> MaterialTheme.colorScheme.primary
        TaskUrgency.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon: ImageVector = when (item.urgency) {
        TaskUrgency.BLOCKING -> Icons.Default.Block
        TaskUrgency.ATTENTION -> Icons.Default.Warning
        TaskUrgency.INFO -> Icons.Default.CheckCircle
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = DshTheme.tokens.spaceXs),
        horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = item.urgency.name, tint = tint, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = tint)
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
