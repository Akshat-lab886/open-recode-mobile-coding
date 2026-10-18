package com.dsh.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dsh.mobile.runtime.AgentRuntime
import com.dsh.mobile.terminal.ShellRunner
import com.dsh.mobile.ui.theme.DshTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One completed command and its output, as shown in the transcript. */
private data class TermLine(val command: String, val output: String, val failed: Boolean)

/**
 * Terminal: run shell commands in the workspace.
 *
 * Runs Android's toybox `sh` as the app user, NOT the agent's proot/Alpine
 * guest. That distinction is stated in the UI because it changes what works:
 * `ls`, `cat`, `grep`, `du` behave normally, while anything expecting a real
 * userland (bashisms, `git`) is unavailable - git in particular does not exist
 * on Android at all, which is why Diffs snapshots instead of shelling out.
 */
@Composable
fun TerminalPane(
    configuredWorkspacePath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workspace = remember(configuredWorkspacePath) {
        val picked = File(configuredWorkspacePath)
        if (configuredWorkspacePath.isNotBlank() && picked.isDirectory && picked.canRead()) picked
        else AgentRuntime.defaultWorkspace(context)
    }

    val lines = remember { mutableStateListOf<TermLine>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit(raw: String) {
        val command = raw.trim()
        if (command.isEmpty() || busy || !ShellRunner.isSubmittable(command)) return
        input = ""
        busy = true
        scope.launch {
            // Shell work is blocking; it must never run on the main thread.
            val result = withContext(Dispatchers.IO) { ShellRunner.run(command, workspace) }
            val failed = result.exitCode != 0
            lines += TermLine(
                command = command,
                output = when {
                    result.timedOut -> "timed out after 30s"
                    result.combined.isBlank() -> "(no output)"
                    else -> result.combined.trimEnd()
                },
                failed = failed,
            )
            busy = false
        }
    }

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                .padding(DshTheme.tokens.spaceSm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (lines.isEmpty()) {
                Text(
                    "Sandboxed shell as the app user, scoped to the workspace.\nAndroid's toybox sh - the agent's full Alpine environment runs inside proot, not here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (line in lines) {
                Text("$ ${line.command}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(
                    line.output,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        }
        QuickCommands(onPick = { submit(it) })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DshTheme.tokens.spaceSm, vertical = DshTheme.tokens.spaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceXs),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("command") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            IconButton(
                onClick = { submit(input) },
                enabled = !busy && input.isNotBlank(),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Run")
            }
        }
    }
}

/**
 * One-tap commands.
 *
 * Deliberately only commands toybox actually has. Mobile-Harness ships `git
 * status` / `git diff` chips, but git does not exist on Android, so copying
 * those chips would hand the user two guaranteed failures.
 */
@Composable
private fun QuickCommands(onPick: (String) -> Unit) {
    val chips = listOf("pwd", "ls -la", "cat", "du -sh .", "find . -maxdepth 2", "echo \$PATH")
    LazyRow(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DshTheme.tokens.spaceXs),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DshTheme.tokens.spaceSm),
    ) {
        items(chips.size) { index ->
            val chip = chips[index]
            Box(
                Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        MaterialTheme.shapes.small,
                    )
                    .clickable { onPick(chip) }
                    .padding(horizontal = DshTheme.tokens.spaceSm, vertical = 6.dp),
            ) {
                Text(
                    chip,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}
