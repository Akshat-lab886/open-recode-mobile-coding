package com.dsh.mobile.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.dsh.mobile.chat.ApprovalPolicy
import com.dsh.mobile.chat.Conversation
import com.dsh.mobile.chat.ConversationReducer
import com.dsh.mobile.chat.ToolApproval
import com.dsh.mobile.proxy.VisualGateway
import com.dsh.mobile.ui.GatewayEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Joins the agent's event stream to the native conversation.
 *
 * This is the seam that replaces the WebView. The events were always there -
 * [GatewayEventBus] already carried them - the previous architecture just
 * forwarded them into a page's JavaScript instead of into Compose. Subscribing
 * here means the transcript is native state, observable and testable, with the
 * WebView no longer in the path.
 */
class ConversationController(
    private val send: (String) -> Unit,
    private val resolveApproval: (requestId: String, approved: Boolean) -> Unit,
) {
    private val _state = MutableStateFlow(Conversation())
    val state: StateFlow<Conversation> = _state.asStateFlow()

    /** Applies one gateway event. Safe to call from any thread. */
    fun onEvent(event: VisualGateway.GatewayEvent) {
        _state.update { current ->
            when (event) {
                is VisualGateway.GatewayEvent.ChatDelta ->
                    ConversationReducer.agentDelta(current, event.text)

                is VisualGateway.GatewayEvent.ApprovalRequested ->
                    ConversationReducer.toolRequested(current, event.request.toApproval())

                is VisualGateway.GatewayEvent.ApprovalResolved ->
                    ConversationReducer.toolResolved(current, event.requestId)

                // Raw stdout is not conversation; showing it would bury the
                // reply in process noise. It stays in the log.
                is VisualGateway.GatewayEvent.RawLine -> current
            }
        }
    }

    /** The user's turn: render it immediately, then hand it to the agent. */
    fun submit(text: String) {
        if (text.isBlank()) return
        _state.update { ConversationReducer.userSaid(it, text) }
        send(text)
    }

    /** The user answered an approval. */
    fun decide(requestId: String, approved: Boolean) {
        _state.update { ConversationReducer.toolResolved(it, requestId) }
        resolveApproval(requestId, approved)
    }

    /** A turn ended. */
    fun finished(workedMillis: Long = 0L) {
        _state.update { ConversationReducer.agentFinished(it, workedMillis) }
    }

    /** The turn failed. */
    fun failed(error: String) {
        _state.update { ConversationReducer.agentFailed(it, error) }
    }

    fun clear() {
        _state.value = ConversationReducer.cleared()
    }
}

/**
 * Maps a gateway approval onto the native card's model.
 *
 * Risk comes from [ApprovalPolicy] rather than the gateway, because the policy
 * escalates on the CONTENT of the command - the gateway only knows which tool
 * category fired.
 */
internal fun VisualGateway.ApprovalRequest.toApproval(): ToolApproval {
    val isCommand = kind == VisualGateway.ApprovalKind.COMMAND
    val command = if (isCommand) rawLine.trim().take(400) else null
    return ToolApproval(
        id = id,
        toolName = if (isCommand) "Run command" else "Write files",
        explanation = summary.trim(),
        affectedPaths = emptyList(),
        commandPreview = command,
        risk = ApprovalPolicy.assess(kind.name, command, emptyList()),
    )
}

/**
 * Remembers a [ConversationController] bound to the gateway bus for as long as
 * the caller is composed.
 */
@Composable
fun rememberConversationController(
    onSend: (String) -> Unit,
    onApprovalResult: (requestId: String, approved: Boolean) -> Unit,
): ConversationController {
    // Keyed on the callbacks so a recomposition with a new lambda does not
    // silently keep dispatching to a stale one.
    val controller = remember(onSend, onApprovalResult) {
        ConversationController(send = onSend, resolveApproval = onApprovalResult)
    }
    DisposableEffect(controller) {
        val listener: (VisualGateway.GatewayEvent) -> Unit = { controller.onEvent(it) }
        GatewayEventBus.subscribe(listener)
        onDispose { GatewayEventBus.unsubscribe(listener) }
    }
    return controller
}
