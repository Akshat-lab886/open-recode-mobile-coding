package com.dsh.mobile.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.chat.ChatAttachment
import com.dsh.mobile.chat.Conversation

/**
 * The native conversation.
 *
 * This is the surface that replaces the WebView. It is a plain function of
 * [Conversation] state, which means the whole transcript can be reasoned about
 * and tested without a browser.
 */
@Composable
fun ConversationPane(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    emptyHint: String = "Tell the agent what to build or change.",
    onRunCode: ((String) -> Unit)? = null,
    onOpenAttachment: ((ChatAttachment) -> Unit)? = null,
    onApproval: (id: String, approved: Boolean) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()

    // Follow the tail as content arrives. Keyed on the counts rather than the
    // whole state so a delta that only lengthens a message does not restart the
    // scroll animation on every token.
    val rowCount = conversation.messages.size + conversation.pendingApprovals.size
    // The typing indicator is its own row, so the last index is rowCount (not
    // rowCount - 1) while it is showing. Getting this wrong scrolls to the
    // second-to-last item and leaves the indicator just off-screen, which is the
    // one thing it exists to disprove.
    val lastIndex = if (conversation.awaitingAgent) rowCount else rowCount - 1
    LaunchedEffect(rowCount, conversation.awaitingAgent) {
        if (lastIndex >= 0) {
            runCatching { listState.animateScrollToItem(lastIndex) }
        }
    }

    if (conversation.isEmpty) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(conversation.messages, key = { it.id }) { message ->
            MessageBubble(
                message = message,
                onRunCode = onRunCode,
                onOpenAttachment = onOpenAttachment,
            )
        }
        items(conversation.pendingApprovals, key = { "approval-${it.id}" }) { approval ->
            ApprovalCard(approval = approval, onDecision = { onApproval(approval.id, it) })
        }
        if (conversation.awaitingAgent) {
            item(key = "awaiting") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 6.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TypingDots()
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Working\u2026",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Three pulsing dots.
 *
 * Shown while the agent has produced nothing yet. Without it a slow first token
 * is indistinguishable from a dropped request, and the user sends again.
 */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (index in 0 until 3) {
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
    }
}

/** Compact one-line status used above the transcript. */
@Composable
fun ConversationStatusBar(
    conversation: Conversation,
    modifier: Modifier = Modifier,
) {
    val text = when {
        conversation.pendingApprovals.isNotEmpty() ->
            "${conversation.pendingApprovals.size} action(s) need your approval"
        conversation.awaitingAgent -> "Agent is working"
        else -> "${conversation.messages.size} message(s)"
    }
    Column(modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = if (conversation.pendingApprovals.isNotEmpty()) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(1.dp))
    }
}
