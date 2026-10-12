package com.dsh.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.data.AgentCatalog
import com.dsh.mobile.security.ApiKeyVault
import com.dsh.mobile.ui.theme.DshTheme

/**
 * Per-agent provider settings.
 *
 * ## What this is for
 *
 * Credentials used to be global — one key per vendor, injected into whichever
 * agent was running. That made three normal setups impossible: Claude Code on
 * an Anthropic key while Codex runs on OpenRouter, an agent pointed at a
 * self-hosted or LAN model server, and knowing which agent breaks when one
 * vendor's key is rotated. Each agent now gets its own choice.
 *
 * ## Local inference is possible, and this is how
 *
 * The base URL field is the point of interest for anyone who does not want code
 * leaving the device: point it at a model server on your own network (Ollama,
 * llama.cpp) and nothing is sent to a hosted vendor. The agent process itself
 * has always run on the phone — this only moves the *model* off a cloud
 * endpoint. Note that a phone's own RAM will not run a useful coding model, so
 * in practice this is a server you control, not the handset.
 *
 * ## Secret handling
 *
 * The key field is masked and stored encrypted through [ApiKeyVault] under the
 * agent's own name. A blank field means "leave the stored key alone" — it is
 * never interpreted as "clear the key", so re-saving other settings cannot
 * silently wipe a credential.
 */
@Composable
fun PerAgentProviderSection(
    currentAgent: ActiveAgent,
    installedAgents: List<ActiveAgent>,
    bindingProviderId: String,
    bindingBaseUrl: String?,
    storedKeyMask: String,
    onSave: (agentId: String, providerId: String, key: String?, baseUrl: String, model: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = DshTheme.tokens
    var selectedId by rememberSaveable { mutableStateOf(currentAgent.id) }
    var providerId by rememberSaveable(selectedId) { mutableStateOf(bindingProviderId) }
    var key by rememberSaveable(selectedId) { mutableStateOf("") }
    var baseUrl by rememberSaveable(selectedId) { mutableStateOf(bindingBaseUrl.orEmpty()) }
    var model by rememberSaveable(selectedId) { mutableStateOf("") }
    var error by rememberSaveable(selectedId) { mutableStateOf<String?>(null) }

    // Only agents that are genuinely installed may be configured: offering a
    // provider for a binary that is not on disk would recreate the blank-page
    // failure AgentRuntime.agentEntryPoint documents.
    val selectable = installedAgents.ifEmpty { listOf(currentAgent) }

    Column(
        // No scroll of its own: this section is hosted inside the Control
        // Center's verticalScroll, so adding a second one made the inner
        // scroll inherit an infinite height from its parent and throw
        // "Vertically scrollable component was measured with an infinity
        // maximum height constraints" - which crashed the app on opening
        // Settings. The enclosing panel already scrolls.
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(tokens.spaceMd),
    ) {
        Text(
            "Per-agent provider",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Each agent can use its own provider and endpoint. Blank key means " +
                "keep the stored one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (selectable.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(tokens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                selectable.forEach { agent ->
                    FilterChip(
                        selected = selectedId == agent.id,
                        onClick = { selectedId = agent.id },
                        label = { Text(agent.label.substringBefore(" (")) },
                        modifier = Modifier.heightIn(min = tokens.minTouchTarget),
                    )
                }
            }
        }

        Text(
            "Provider",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.spaceXs),
        ) {
            ApiKeyVault.Provider.entries.forEach { p ->
                FilterChip(
                    selected = providerId.equals(p.name, ignoreCase = true),
                    onClick = { providerId = p.name },
                    label = { Text(p.label) },
                    modifier = Modifier.heightIn(min = tokens.minTouchTarget),
                )
            }
        }

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("${ApiKeyVault.Provider.fromId(providerId).label} key for this agent") },
            placeholder = { Text(storedKeyMask.ifBlank { "Paste key… (blank keeps stored)" }) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; error = null },
            label = { Text("Custom endpoint (optional)") },
            placeholder = { Text("https://api.example.com  or  http://192.168.1.5:11434") },
            singleLine = true,
            isError = error != null,
            supportingText = {
                Text(
                    error ?: "Point this at a model server on your own network to " +
                        "keep inference off hosted services.",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model override (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val trimmed = baseUrl.trim()
                if (trimmed.isNotEmpty() && !isPlausibleEndpoint(trimmed)) {
                    error = "That does not look like a valid http(s) origin."
                    return@Button
                }
                error = null
                onSave(
                    selectedId,
                    providerId,
                    // Blank means "keep", so a null is passed rather than "".
                    key.trim().takeIf { it.isNotBlank() },
                    trimmed,
                    model.trim(),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = tokens.minTouchTarget),
        ) { Text("Save provider for this agent") }

        if (installedAgents.size < AgentCatalog.installable.size) {
            Text(
                "Only installed agents can be configured. Add more from the agent picker.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = tokens.spaceSm),
            )
        }
    }
}

/**
 * Endpoint shape check, mirrored from PerAgentProviderStore.isValidBaseUrl.
 *
 * Deliberately duplicated rather than called: that method lives on a store that
 * needs a Context, and this is pure presentation validation whose only job is
 * to reject obviously-wrong input before the save round-trip. The authoritative
 * check still runs on save.
 */
private fun isPlausibleEndpoint(value: String): Boolean {
    if (!value.startsWith("https://") && !value.startsWith("http://")) return false
    val rest = value.substringAfter("://")
    if (rest.isEmpty() || rest.contains('/') || rest.contains('@') || rest.contains(' ')) return false
    val host = rest.substringBefore(':')
    return host.isNotBlank() && (host.contains('.') || host == "localhost")
}