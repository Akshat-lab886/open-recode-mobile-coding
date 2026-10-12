package com.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.AgentSpec
import com.dsh.mobile.ui.theme.LocalDshTokens

/**
 * First-run agent picker.
 *
 * ## Why the user chooses
 *
 * The previous behaviour installed a fixed set of agents on first boot whether
 * or not the user wanted them - hundreds of megabytes and minutes of waiting
 * for software nobody asked for. Nothing installs until this screen is answered,
 * and the answer is remembered, so it is shown once.
 *
 * ## Honest costs
 *
 * Every row carries its real install size and, where the upstream npm package
 * is unofficial or a placeholder, says so. Two of the six entries ship with a
 * caveat and it is displayed rather than buried.
 */
@Composable
fun AgentPickerSheet(
    specs: List<AgentSpec>,
    installedIds: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalDshTokens.current
    // DeepSeek Harness is the app's own harness and is already bundled into the
    // runtime, so it is offered but not charged to the user's storage budget.
    var selected by rememberSaveable {
        mutableStateOf(specs.map { it.id }.toMutableSet())
    }

    val totalBytes = specs.filter { it.id in selected }.sumOf { it.approxInstallBytes }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = tokens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(tokens.spaceSm),
    ) {
        Text(
            "Choose your coding agents",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Nothing is installed until you choose. You can add more later in settings, " +
                "and nothing re-downloads once installed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        specs.forEach { spec ->
            AgentPickerRow(
                spec = spec,
                checked = spec.id in selected,
                installed = spec.id in installedIds,
                onToggle = { on -> selected = selected.toMutableSet().apply {
                    if (on) add(spec.id) else remove(spec.id)
                } },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = tokens.spaceSm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Downloads ${formatBytes(totalBytes)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onSkip) { Text("Skip") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.spaceSm, Alignment.End),
        ) {
            OutlinedButton(
                onClick = { selected = specs.map { it.id }.toMutableSet() },
                modifier = Modifier.heightIn(min = tokens.minTouchTarget),
            ) { Text("Select all") }
            androidx.compose.material3.Button(
                onClick = { onConfirm(selected.toSet()) },
                modifier = Modifier.heightIn(min = tokens.minTouchTarget),
            ) { Text("Install ${selected.size}") }
        }
        Text(
            "Installed agents stay on this device until you remove them.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = tokens.spaceLg),
        )
    }
}

@Composable
private fun AgentPickerRow(
    spec: AgentSpec,
    checked: Boolean,
    installed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val tokens = LocalDshTokens.current
    // toggleable on the whole row, not just the box: the row is the touch
    // target, and a 24dp checkbox alone is below the 48dp floor.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = !installed,
                role = Role.Checkbox,
                onValueChange = onToggle,
            )
            .heightIn(min = tokens.minTouchTarget)
            .padding(vertical = tokens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = !installed)
        Column(modifier = Modifier.padding(start = tokens.spaceSm)) {
            Text(
                spec.label + if (installed) "  (already installed)" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = if (installed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                spec.npmPackage + "  ·  " + formatBytes(spec.approxInstallBytes),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!spec.argvVerified) {
                Text(
                    "Flags not verified against this CLI's help yet.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (spec.provenanceNote.isNotBlank()) {
                Text(
                    spec.provenanceNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/** 180 MB, 1.2 GB, 3 KB — readable on a narrow screen. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%d MB".format(bytes / (1024 * 1024))
    bytes >= 1024 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}