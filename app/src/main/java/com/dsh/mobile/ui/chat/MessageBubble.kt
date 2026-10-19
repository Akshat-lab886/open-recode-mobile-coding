package com.dsh.mobile.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.chat.ChatAttachment
import com.dsh.mobile.chat.ChatMessage
import com.dsh.mobile.chat.ChatRole

/**
 * One message in the conversation.
 *
 * Deliberately shaped like the reference implementation: the user's own text is
 * plain (they know what they typed), while the agent's reply is rendered as
 * markdown, because that is where headings, code and file paths actually appear.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    onRunCode: ((String) -> Unit)? = null,
    onOpenAttachment: ((ChatAttachment) -> Unit)? = null,
) {
    val fromUser = message.role == ChatRole.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = when {
                fromUser -> MaterialTheme.colorScheme.primaryContainer
                message.error != null -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surface
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(if (fromUser) 0.82f else 0.94f),
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                SelectionContainer {
                    when {
                        message.error != null -> ErrorBody(message.error)
                        fromUser -> Text(
                            text = message.text,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        else -> MarkdownText(
                            markdown = message.text,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                            onRunCode = onRunCode,
                        )
                    }
                }
                if (!fromUser && message.workedMillis > 0L) {
                    Text(
                        text = "Worked for ${formatWorked(message.workedMillis)}",
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                if (message.attachments.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (attachment in message.attachments) {
                            AttachmentChip(attachment, onOpenAttachment)
                        }
                    }
                }
                Spacer(Modifier.size(2.dp))
            }
        }
    }
}

@Composable
private fun ErrorBody(error: String) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun AttachmentChip(attachment: ChatAttachment, onOpen: ((ChatAttachment) -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.then(
            if (onOpen != null) Modifier.clickable { onOpen(attachment) } else Modifier
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (attachment.isImage) Icons.Default.Image else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(7.dp))
            Column(Modifier.widthIn(max = 190.dp)) {
                Text(
                    attachment.displayName,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatBytes(attachment.sizeBytes),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "4.2s" / "1m 12s" - never "0s", which reads as a bug. */
internal fun formatWorked(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(1L)
    return if (totalSeconds < 60) "${totalSeconds}s"
    else "${totalSeconds / 60}m ${totalSeconds % 60}s"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
