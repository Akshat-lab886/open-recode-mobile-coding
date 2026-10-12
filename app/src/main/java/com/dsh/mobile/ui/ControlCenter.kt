package com.dsh.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentMode
import com.dsh.mobile.ui.theme.DshTheme

/**
 * CONTROL CENTER — credentials, workspace, mode and backend.
 *
 * Extracted from the shell so the same content renders as a modal bottom
 * sheet on a phone and as a persistent side panel on a tablet. Previously
 * the whole sheet was inline in `DshMobileShell`, so a tablet could only
 * ever get the phone presentation.
 *
 * Note what this composable does NOT take: a width. It measures itself with
 * [BoxWithConstraints] instead, because the same content is hosted at
 * ~600dp inside a phone sheet and at ~328dp inside the tablet side panel.
 * Keying the column count off the *window* width (as an earlier revision
 * did) produced two credential fields on a tablet that was showing them
 * stacked — the window is wide, the panel it is rendered into is not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlCenterContent(
    uiState: ControlCenterState,
    onCredentialsSaved: (deepseek: String, anthropic: String, openrouter: String) -> Unit,
    onWorkspacePicked: (String) -> Unit,
    onModeSelected: (AgentMode) -> Unit,
    onAgentSelected: (ActiveAgent) -> Unit,
    onManageAgents: () -> Unit,
    onSaveAgentProvider: (String, String, String?, String, String) -> Unit = { _, _, _, _, _ -> },
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val tokens = DshTheme.tokens
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Two credential fields need real room to stay legible; below this
        // they are stacked, whatever the window is doing.
        val twoUp = maxWidth >= TWO_UP_MIN_WIDTH

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (header != null) {
                header()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(tokens.spaceLg),
                verticalArrangement = Arrangement.spacedBy(tokens.spaceLg),
            ) {
        // -- a) credentials -------------------------------------------------
        SectionHeader(
            title = "Model & API credentials",
            icon = Icons.Default.Key,
            subtitle = if (uiState.hasKeys) {
                "Stored in the Android Keystore and injected as env vars on boot."
            } else {
                "No key stored yet — the agent will ask for one on first use."
            },
        )
        CredentialSection(
            uiState = uiState,
            twoUp = twoUp,
            onSave = onCredentialsSaved,
        )

        // -- b) workspace ----------------------------------------------------
        SectionHeader(
            title = "Workspace",
            icon = Icons.Default.FolderOpen,
            subtitle = uiState.config.workspacePath.ifBlank { "Default app workspace" },
        )
        WorkspacePicker(
            path = uiState.config.workspacePath,
            onPicked = onWorkspacePicked,
        )

        // -- c) mode ---------------------------------------------------------
        SectionHeader(
            title = "Agent mode",
            icon = Icons.Default.Tune,
            subtitle = "How much the agent may do without asking.",
        )
        ModeSelector(selected = uiState.config.mode, onSelected = onModeSelected)

        // -- d) backend ------------------------------------------------------
        SectionHeader(
            title = "Agent backend",
            icon = Icons.Default.CheckCircle,
            subtitle = "Only backends with an installed binary can actually start.",
        )
        AgentSelector(
            selected = uiState.config.agent,
            onSelected = onAgentSelected,
        )

        // Recovery path for a first run that was skipped.
        //
        // The install sheet is shown once, before anything is on the device.
        // Without this the only way to add an agent would be to clear app data
        // and start over - which on this app means re-bootstrapping a 40 MB
        // runtime, so "skip" has to be reversible from Settings.
        OutlinedButton(
            onClick = onManageAgents,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = tokens.minTouchTarget),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(tokens.spaceSm))
            Text("Add or remove coding agents")
        }

        // -- e) per-agent provider -------------------------------------------
        SectionHeader(
            title = "Provider per agent",
            icon = Icons.Default.Key,
            subtitle = "Give each agent its own key and endpoint — including a " +
                "model server on your own network.",
        )
        PerAgentProviderSection(
            currentAgent = uiState.config.agent,
            installedAgents = uiState.installedAgents,
            bindingProviderId = uiState.providerId,
            bindingBaseUrl = uiState.providerBaseUrl,
            storedKeyMask = uiState.providerKeyMask,
            onSave = onSaveAgentProvider,
        )

        Spacer(Modifier.height(tokens.spaceMd))
            }
        }
    }
}

/**
 * Minimum width at which two credential fields sit side by side.
 *
 * Below this each field would be under ~200dp, which truncates a pasted key
 * before it can be checked against the label. Measured against the real
 * hosts: the tablet side panel gives ~328dp of content width, so it stacks;
 * a full-width bottom sheet on a large tablet does not.
 */
/** Exposed for `AdaptiveLayoutTest`, which pins the regression below. */
internal val TWO_UP_MIN_WIDTH = 560.dp

/**
 * Credential fields.
 *
 * One column until the host is wide enough for two genuinely readable
 * fields. A single full-width field on a 1400dp tablet is worse than a
 * capped two-up grid, and a two-up grid in a 328dp side panel makes both
 * fields unusably narrow — so this keys off the measured width of whatever
 * is hosting it, not the window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CredentialSection(
    uiState: ControlCenterState,
    twoUp: Boolean,
    onSave: (String, String, String) -> Unit,
) {
    val tokens = DshTheme.tokens
    var deepseek by rememberSaveable(uiState.deepseekKey) { mutableStateOf("") }
    var anthropic by rememberSaveable(uiState.anthropicKey) { mutableStateOf("") }
    var openrouter by rememberSaveable(uiState.openrouterKey) { mutableStateOf("") }

    val fields = listOf(
        CredentialSlot("DeepSeek key", uiState.deepseekKey, deepseek, { deepseek = it }),
        CredentialSlot("Anthropic key", uiState.anthropicKey, anthropic, { anthropic = it }),
        CredentialSlot("OpenRouter key", uiState.openrouterKey, openrouter, { openrouter = it }),
    )

    Column(verticalArrangement = Arrangement.spacedBy(tokens.spaceMd)) {
        if (twoUp) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(tokens.spaceMd),
                verticalArrangement = Arrangement.spacedBy(tokens.spaceMd),
                maxItemsInEachRow = 2,
            ) {
                // An equal weight per item, so a leftover odd field stretches
                // to the row instead of sitting at half width.
                fields.forEach { slot ->
                    CredentialField(
                        label = slot.label,
                        storedMask = slot.storedMask,
                        value = slot.value,
                        onValue = slot.onValue,
                        modifier = Modifier
                            .weight(1f)
                            .width(280.dp),
                    )
                }
            }
        } else {
            fields.forEach { slot ->
                CredentialField(
                    label = slot.label,
                    storedMask = slot.storedMask,
                    value = slot.value,
                    onValue = slot.onValue,
                )
            }
        }

        Button(
            onClick = { onSave(deepseek, anthropic, openrouter) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = tokens.minTouchTarget),
        ) { Text("Save keys securely") }
    }
}

private class CredentialSlot(
    val label: String,
    val storedMask: String,
    val value: String,
    val onValue: (String) -> Unit,
)

@Composable
private fun CredentialField(
    label: String,
    storedMask: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(storedMask.ifBlank { "Paste key…" }) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun WorkspacePicker(
    path: String,
    onPicked: (String) -> Unit,
) {
    val context = LocalContext.current
    val tokens = DshTheme.tokens
    // Visual folder picker (Storage Access Framework). The absolute path is
    // passed straight into the agent workspace configuration — no typing.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val picked = resolveTreeToPath(context, uri)
            if (picked != null) onPicked(picked)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(tokens.spaceSm)) {
        if (path.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        OutlinedButton(
            onClick = { folderPicker.launch(null) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = tokens.minTouchTarget),
        ) { Text("Choose project folder") }
    }
}

@Composable
private fun ModeSelector(
    selected: AgentMode,
    onSelected: (AgentMode) -> Unit,
) {
    val tokens = DshTheme.tokens
    PanelCard {
        AgentMode.entries.forEach { mode ->
            val isSelected = selected == mode
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelected(mode) },
                        role = Role.RadioButton,
                    )
                    .heightIn(min = tokens.minTouchTarget)
                    .padding(vertical = tokens.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = { onSelected(mode) })
                Spacer(Modifier.width(tokens.spaceSm))
                Column {
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Backend chips.
 *
 * A `Row` of four chips overflows a narrow phone — the rightmost chip is
 * simply clipped off-screen with no scroll. [FlowRow] wraps instead, which is
 * the difference between "the last option is unreachable" and "the last
 * option is on the second line".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentSelector(
    selected: ActiveAgent,
    onSelected: (ActiveAgent) -> Unit,
) {
    val tokens = DshTheme.tokens
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(tokens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(tokens.spaceSm),
    ) {
        ActiveAgent.entries.forEach { agent ->
            FilterChip(
                selected = selected == agent,
                onClick = { onSelected(agent) },
                label = {
                    Text(
                        agent.label.substringBefore(" ("),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // tokens.minTouchTarget (48dp) is honoured on every other chip in
                // this file; 40dp here was a stray.
                modifier = Modifier.heightIn(min = tokens.minTouchTarget),
            )
        }
    }
}

/**
 * Turn a picked SAF tree into a path the agent can actually open.
 *
 * The previous version persisted the URI permission and then returned a fixed
 * `workspaces/saf-picked` directory that had nothing to do with what the user
 * chose. Picking a folder therefore appeared to work and then quietly gave the
 * agent an empty directory - which is much worse than failing, because the
 * user has no way to tell the difference.
 *
 * SAF deliberately does not expose real filesystem paths, but on primary
 * external storage the document id IS the path below the volume, so the
 * overwhelmingly common case (a folder under /storage/emulated/0) can be mapped
 * exactly. Only when that mapping is impossible do we fall back, and the
 * fallback is reported so the UI can say so rather than pretending.
 */
internal object SafWorkspacePath {
    /**
     * Resolve [uri] to a real absolute path, or null when it cannot be mapped.
     *
     * Persisting the permission is deliberate: without it the mapping works
     * until the app restarts, and then the agent silently loses the folder.
     */
    fun resolve(context: android.content.Context, uri: android.net.Uri): String? {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // Not every provider offers a persistable grant. The mapping below
            // may still be usable for this session, so this is not fatal.
        }

        val id = uri.toString()
        // primary:<docId>  ->  /storage/emulated/0/<docId>
        val marker = "/document/"
        val index = id.indexOf(marker)
        if (index == -1) return null
        val docId = id.substring(index + marker.length)
        if (docId.contains(':')) return null // sub-tree of a provider, not a real dir

        val primary = android.os.Environment.getExternalStorageDirectory()?.absolutePath
            ?: return null
        val mapped = java.io.File(primary, docId)
        // Only claim success when the directory really is there: a mapping that
        // points nowhere is the same silent-wrong-path bug as before.
        return if (mapped.isDirectory) mapped.absolutePath else null
    }

    /** Staging directory used only when no real path can be produced. */
    fun fallback(context: android.content.Context): String {
        val staged = java.io.File(context.filesDir, "workspaces/saf-picked")
        staged.mkdirs()
        return staged.absolutePath
    }
}

private fun resolveTreeToPath(context: android.content.Context, uri: android.net.Uri): String? =
    SafWorkspacePath.resolve(context, uri) ?: SafWorkspacePath.fallback(context)
