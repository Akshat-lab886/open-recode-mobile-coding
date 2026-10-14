package com.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentCatalog
import com.dsh.mobile.data.AgentMode
import com.dsh.mobile.data.ControlOption
import com.dsh.mobile.ui.theme.DshTheme
import com.dsh.mobile.ui.theme.DshTokens

/**
 * One labeled chip-group selector for a control group (mode or effort).
 *
 * Uses Material3 AssistChips arranged horizontally. Each chip is a minimum
 * 48dp touch target so small mobile screens stay tappable, and the group is
 * allowed to scroll on small screens (handled by the parent's verticalScroll).
 */
@Composable
private fun ControlSection(
    title: String,
    subtitle: String,
    options: List<ControlOption>,
    selected: String,
    onSelect: (String) -> Unit,
    tokens: DshTokens = DshTheme.tokens,
) {
    Column(verticalArrangement = Arrangement.spacedBy(tokens.spaceXs)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(tokens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEach { opt ->
                    val isSel = opt.value == selected
                    val cs = MaterialTheme.colorScheme
                    AssistChip(
                        onClick = { onSelect(opt.value) },
                        label = {
                            Text(
                                opt.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        modifier = Modifier
                            .heightIn(min = tokens.minTouchTarget)
                            .widthIn(min = tokens.minTouchTarget / 2),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (isSel) cs.primary else cs.surfaceVariant,
                            labelColor = if (isSel) cs.onPrimary else cs.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * The unified, sparse per-agent control surface.
 *
 * Replaces the single global ModeSelector with controls that reflect WHATEVER
 * harness is active — the user's ask: "make in dsh the user can change modes
 * but in the claude code the UI has effort options and this thing will be
 * for all the harnesses."
 *
 * Each agent's real knobs come from [AgentSpec.control] (verified against each
 * CLI's real --help; see AgentCatalog.ControlAdapter). We render modes and
 * effort as chip groups and a model slot as a button. DSH uses its preset list
 * (ptc/standard/minimal/cordis); codex uses sandbox+plan; claude uses
 * permission-mode; antigravity uses --mode + effort; opencode uses --variant.
 *
 * The underlying agent keeps its full native capability: we do NOT re-render
 * the harness's own UI — ControlAdapter.bundleForMode turns the selection into
 * the real CLI argv/flags handed to buildCommand. Switching preset/mode restarts
 * the agent (ServiceStatus restart), because DSH pins presets at session
 * creation, so the mode change writes the new preset in the boot path.
 */
@Composable
fun AgentControlPanel(
    agent: ActiveAgent,
    selectedMode: AgentMode,
    onModeSelected: (AgentMode) -> Unit,
    onEffortSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    tokens: DshTokens = DshTheme.tokens,
) {
    val spec = agent.spec ?: return
    val control = spec.control

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.42f).dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = tokens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(tokens.spaceSm),
    ) {
        // Mode / variant selector — the harness's real modes.
        if (control.modes.isNotEmpty()) {
            val mapped = AgentCatalog.ControlAdapter.modeFor(agent, selectedMode)
            ControlSection(
                title = "Mode",
                subtitle = spec.modeSubtitle,
                options = control.modes,
                selected = mapped,
                onSelect = { onModeSelected(AgentCatalog.ControlAdapter.modeFor(agent, it)) },
                tokens = tokens,
            )
        }
        // Effort / reasoning depth — only for harnesses that have it.
        if (control.efforts.isNotEmpty()) {
            ControlSection(
                title = "Effort",
                subtitle = spec.effortSubtitle,
                options = control.efforts,
                selected = control.efforts.first().value,
                onSelect = { onEffortSelected(it) },
                tokens = tokens,
            )
        }
        // Model override slot (free, discovered per provider) — shown as a button.
        if (control.hasModel) {
            TextButton(
                onClick = { /* opens provider model picker */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = tokens.minTouchTarget),
            ) {
                Text(
                    "Choose model",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (agent == ActiveAgent.DEEPSEEK_HARNESS && control.modes.isNotEmpty()) {
            Text(
                "Switching presets restarts the agent to apply the new profile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = tokens.spaceXs),
            )
        }
    }
}
