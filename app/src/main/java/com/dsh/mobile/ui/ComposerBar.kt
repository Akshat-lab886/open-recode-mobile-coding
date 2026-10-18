package com.dsh.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dsh.mobile.commands.CommandResolution
import com.dsh.mobile.commands.SlashCommand
import com.dsh.mobile.commands.SlashCommands
import com.dsh.mobile.ui.theme.DshTheme

/**
 * The composer: a real text field plus slash commands.
 *
 * A native field is not decoration. On a tablet the agent's own composer sits
 * inside a WebView, which means an IME that overlays it, no system spellcheck,
 * and a tap target the size of whatever the page decided. This bar is laid out
 * by the app, so it is in the same place on every agent and every screen size.
 *
 * Slash commands are resolved by [SlashCommands], which keeps DSH's own
 * commands verbatim and only expands the prompt-template ones.
 */
@Composable
fun ComposerBar(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }

    // Only offer the palette once the line actually starts a command, so
    // ordinary prose is not buried under a list of buttons.
    val trimmed = text.trimStart()
    val palette: List<SlashCommand> = remember(trimmed) {
        if (!trimmed.startsWith("/")) emptyList()
        else SlashCommands.matching(trimmed.substringBefore(' ').trimStart('/'))
    }

    Column(modifier.fillMaxWidth()) {
        if (palette.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (cmd in palette) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Replace just the command token, keeping any
                                    // argument the user already typed after it.
                                    val rest = trimmed.substringAfter(' ', "")
                                    text = if (rest.isBlank()) "/${cmd.name}"
                                    else "/${cmd.name} $rest"
                                }
                                .padding(horizontal = DshTheme.tokens.spaceMd, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceSm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(cmd.emoji, style = MaterialTheme.typography.bodyMedium)
                            Column {
                                Text(
                                    "/${cmd.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    cmd.summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = DshTheme.tokens.spaceSm, vertical = DshTheme.tokens.spaceXs),
            horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (chip in SlashCommands.QUICK_CHIPS) {
                val label = "${chip.emoji} /${chip.name}"
                Row(
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            MaterialTheme.shapes.small,
                        )
                        .clickable { text = "/${chip.name} " }
                        .padding(horizontal = DshTheme.tokens.spaceSm, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = DshTheme.tokens.spaceSm, vertical = DshTheme.tokens.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceXs),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message, or / for commands") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            IconButton(
                onClick = {
                    val out = when (val r = SlashCommands.resolve(text)) {
                        // An unfinished or unknown command must NOT be sent as
                        // literal text: "/pl" arriving at the agent is noise.
                        is CommandResolution.Pending -> null
                        is CommandResolution.Unknown -> null
                        is CommandResolution.Resolved -> r.text
                        is CommandResolution.Plain -> r.text
                    }
                    if (!out.isNullOrBlank()) {
                        onSend(out)
                        text = ""
                    }
                },
                enabled = text.isNotBlank() &&
                    SlashCommands.resolve(text) !is CommandResolution.Unknown,
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}
