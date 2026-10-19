package com.dsh.mobile.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.dsh.mobile.chat.ApprovalPolicy
import com.dsh.mobile.chat.Conversation
import com.dsh.mobile.chat.ConversationReducer
import com.dsh.mobile.chat.EchoReconciler
import com.dsh.mobile.chat.GatewayStreamEvent
import com.dsh.mobile.chat.ToolApproval
import com.dsh.mobile.proxy.VisualGateway
import com.dsh.mobile.runtime.GatewayStreamClient
import com.dsh.mobile.ui.GatewayEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Joins the agent's live event stream to the native conversation.
 *
 * Two sources feed this, and they are not equivalent:
 *
 *  - [GatewayStreamClient] reads `gateway.js`'s `GET /events` SSE stream. This
 *    is the LIVE path: gateway.js already owns the CLI child's stdin/stdout, so
 *    consuming its stream gives real agent output with no change to the launch
 *    path.
 *  - [GatewayEventBus] is subscribed as well, but it is currently DEAD:
 *    `VisualGateway` is never constructed in this app, so nothing ever emits on
 *    it. It is kept wired so that attaching a native gateway later needs no
 *    change here - but nothing should assume it fires today.
 */
class ConversationController(
    private val send: (String) -> Unit,
    private val resolveApproval: (requestId: String, approved: Boolean) -> Unit,
    /**
     * Answers an approval by writing to the CLI's stdin through gateway.js.
     *
     * Separate from [resolveApproval] because they are different mechanisms:
     * `resolveApproval` goes through `VisualGateway`, which this app never
     * constructs, so it would silently do nothing for a gateway-backed agent.
     * A gateway approval is answered on stdin, not by an in-process call.
     */
    private val answerGatewayApproval: (requestId: String, approved: Boolean) -> Unit = { _, _ -> },
    /**
     * Sends a message straight to a gateway-backed CLI's stdin.
     *
     * Required because the shell's normal dispatch is installed by
     * [com.dsh.mobile.ui.AgentWebView] when the page attaches - and the native
     * surface deliberately does not mount that page. Without this the composer
     * would be inert exactly where it is most visible. Returns true when the
     * gateway accepted it.
     */
    private val sendToGateway: (text: String) -> Boolean = { false },
) {
    private val _state = MutableStateFlow(Conversation())
    val state: StateFlow<Conversation> = _state.asStateFlow()

    private val echoes = EchoReconciler()
    private var stream: GatewayStreamClient? = null

    /** Live gateway events. Safe to call from the IO thread. */
    fun onStreamEvent(event: GatewayStreamEvent) {
        when (event) {
            is GatewayStreamEvent.UserEcho -> {
                // The reconciler suppresses the echo of a message this surface
                // already rendered, so the composer stays instant without every
                // message appearing twice. An echo it does not recognise came
                // from another surface and must be shown.
                if (echoes.onEcho(event.text)) {
                    _state.update { ConversationReducer.userSaid(it, event.text) }
                }
            }

            is GatewayStreamEvent.AssistantText ->
                _state.update { ConversationReducer.agentDelta(it, event.text) }

            is GatewayStreamEvent.ApprovalRequired ->
                _state.update {
                    ConversationReducer.toolRequested(
                        it,
                        toApproval(event.id, event.kind, event.summary),
                    )
                }

            is GatewayStreamEvent.Failed ->
                _state.update { ConversationReducer.agentFailed(it, event.message) }

            // The process ended, so whatever was streaming is now final.
            is GatewayStreamEvent.Exited ->
                _state.update { ConversationReducer.agentFinished(it) }

            GatewayStreamEvent.Ignored -> Unit
        }
    }

    /** Secondary (currently dormant) in-process source. See the class comment. */
    fun onGatewayEvent(event: VisualGateway.GatewayEvent) {
        _state.update { current ->
            when (event) {
                is VisualGateway.GatewayEvent.ChatDelta ->
                    ConversationReducer.agentDelta(current, event.text)
                is VisualGateway.GatewayEvent.ApprovalRequested ->
                    ConversationReducer.toolRequested(current, event.request.toApproval())
                is VisualGateway.GatewayEvent.ApprovalResolved ->
                    ConversationReducer.toolResolved(current, event.requestId)
                is VisualGateway.GatewayEvent.RawLine -> current
            }
        }
    }

    /** Points the live stream at the agent's loopback port. */
    fun attachStream(scope: CoroutineScope, port: Int?) {
        detachStream()
        if (port == null || port <= 0) return
        stream = GatewayStreamClient(
            port = port,
            onEvent = { onStreamEvent(it) },
            onClosed = { reason ->
                // A closed stream means the agent is gone; settle whatever was
                // mid-flight so the UI does not show a permanent spinner.
                if (reason != null) {
                    _state.update { ConversationReducer.agentFinished(it) }
                }
            },
        ).also { it.start(scope) }
    }

    fun detachStream() {
        stream?.stop()
        stream = null
    }

    /** The user's turn: render immediately, then hand it to the agent. */
    fun submit(text: String) {
        if (text.isBlank()) return
        _state.update { ConversationReducer.userSaid(it, text) }
        echoes.expectEcho(text)
        // The gateway path is preferred when it applies: the shell's dispatch is
        // only installed while the WebView is attached, so relying on it alone
        // would leave the native composer unable to send anything.
        val delivered = runCatching { sendToGateway(text) }.getOrDefault(false)
        if (!delivered) send(text)
    }

    fun decide(requestId: String, approved: Boolean) {
        _state.update { ConversationReducer.toolResolved(it, requestId) }
        // Both paths are attempted because the controller does not know which
        // backend produced the prompt; the one that does not apply is a no-op.
        answerGatewayApproval(requestId, approved)
        resolveApproval(requestId, approved)
    }

    fun finished(workedMillis: Long = 0L) {
        _state.update { ConversationReducer.agentFinished(it, workedMillis) }
    }

    fun failed(error: String) {
        _state.update { ConversationReducer.agentFailed(it, error) }
    }

    fun clear() {
        echoes.clear()
        _state.value = ConversationReducer.cleared()
    }
}

/**
 * Builds the native approval model from a gateway approval event.
 *
 * Risk comes from [ApprovalPolicy] rather than the gateway: the policy escalates
 * on the CONTENT of the command, while the gateway only knows which category of
 * prompt fired.
 */
internal fun toApproval(id: String, kind: String, summary: String): ToolApproval {
    val isCommand = kind.equals("COMMAND", ignoreCase = true)
    val command = if (isCommand) summary.trim().take(400) else null
    // Assessed once: assess() is pure, and calling it twice to build the
    // caption and the pill invites the two from drifting apart.
    val risk = ApprovalPolicy.assess(kind, command, emptyList())
    return ToolApproval(
        id = id,
        toolName = if (isCommand) "Run command" else "Write files",
        explanation = ApprovalPolicy.describe(risk, command),
        affectedPaths = emptyList(),
        commandPreview = command,
        risk = risk,
    )
}

internal fun VisualGateway.ApprovalRequest.toApproval(): ToolApproval {
    val isCommand = kind == VisualGateway.ApprovalKind.COMMAND
    val command = if (isCommand) rawLine.trim().take(400) else null
    return toApproval(id = id, kind = kind.name, summary = if (isCommand) command ?: summary else summary)
}

/**
 * Remembers a [ConversationController] bound to the live gateway stream for as
 * long as the caller is composed.
 *
 * [streamPort] is the agent's loopback port; when it changes (a restart, a
 * different agent) the stream is repointed rather than left reading a dead
 * socket.
 */
@Composable
fun rememberConversationController(
    streamPort: Int?,
    agentId: String,
    onSend: (String) -> Unit,
    onApprovalResult: (requestId: String, approved: Boolean) -> Unit,
): ConversationController {
    val controller = remember(onSend, onApprovalResult, streamPort, agentId) {
        ConversationController(
            send = onSend,
            resolveApproval = onApprovalResult,
            answerGatewayApproval = { _, approved ->
                val port = streamPort
                if (port != null && port > 0) {
                    com.dsh.mobile.runtime.GatewaySender.answer(port, agentId, approved)
                }
            },
            sendToGateway = { text ->
                val port = streamPort
                port != null && port > 0 &&
                    com.dsh.mobile.runtime.GatewaySender.send(port, text)
            },
        )
    }
    val scope = rememberCoroutineScope()

    DisposableEffect(controller, streamPort) {
        controller.attachStream(scope, streamPort)
        val listener: (VisualGateway.GatewayEvent) -> Unit = { controller.onGatewayEvent(it) }
        GatewayEventBus.subscribe(listener)
        onDispose {
            GatewayEventBus.unsubscribe(listener)
            controller.detachStream()
        }
    }
    return controller
}
