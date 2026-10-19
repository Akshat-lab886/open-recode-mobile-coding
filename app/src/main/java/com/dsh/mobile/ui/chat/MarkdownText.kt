package com.dsh.mobile.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.chat.InlineSpan
import com.dsh.mobile.chat.Markdown
import com.dsh.mobile.chat.MarkdownBlock
import com.dsh.mobile.chat.parseInline

/**
 * Renders an agent reply natively.
 *
 * This replaces the WebView's markdown. The reference implementation
 * (Mobile-Harness) renders agent output in Compose with copyable, runnable code
 * blocks, and that is the bar: an agent reply is the thing a user reads most,
 * so it should not be a web page.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onRunCode: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    val textColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Header -> HeaderBlock(block, textColor)
                is MarkdownBlock.Paragraph -> Text(
                    text = inline(markdown = block.text, color = textColor),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                )
                is MarkdownBlock.Bullet -> ListRow(
                    marker = "\u2022",
                    depth = block.depth,
                    text = block.text,
                    color = textColor,
                )
                is MarkdownBlock.Numbered -> ListRow(
                    marker = "${block.number}.",
                    depth = block.depth,
                    text = block.text,
                    color = textColor,
                )
                is MarkdownBlock.CodeBlock -> CodeBlock(block, onRunCode)
                MarkdownBlock.Divider -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun HeaderBlock(block: MarkdownBlock.Header, color: Color) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp)
        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp)
        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
    Text(text = inline(block.text, color), style = style)
}

@Composable
private fun ListRow(marker: String, depth: Int, text: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            marker,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = inline(markdown = text, color = color),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
        )
    }
}

/**
 * A fenced code block with Copy, and Run when the surface offers a terminal.
 *
 * Long lines scroll HORIZONTALLY rather than wrapping: a wrapped shell command
 * or stack trace is materially harder to read, and misreading a command is the
 * kind of error that costs real time.
 */
@Composable
private fun CodeBlock(block: MarkdownBlock.CodeBlock, onRunCode: ((String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language.ifBlank { "code" }.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onRunCode != null) {
                    TextButton(onClick = { onRunCode(block.code) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Run", style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(block.code)) },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Text(
                text = block.code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 19.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Builds an AnnotatedString from the pure inline spans. */
@Composable
private fun inline(markdown: String, color: Color) = buildAnnotatedString {
    for (span in parseInline(markdown)) {
        when (span) {
            is InlineSpan.Text -> append(span.text)
            is InlineSpan.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = MaterialTheme.colorScheme.surfaceVariant,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            ) { append(span.text) }
            is InlineSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(span.text)
            }
        }
    }
}
