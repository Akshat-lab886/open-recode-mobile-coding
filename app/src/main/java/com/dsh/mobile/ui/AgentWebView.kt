package com.dsh.mobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.proxy.VisualGateway
import java.io.BufferedReader

/**
 * Routes Bottom-Sheet decisions for DOM-detected prompts back into the page.
 *
 * [AgentWebView] registers its answer lambda here on creation so the
 * [ControlCenterViewModel] can answer `web-*` approvals without holding a
 * WebView reference. Gateway (stdin) approvals bypass this router.
 */
object WebApprovalRouter {
    @Volatile var answer: ((requestId: String, approved: Boolean) -> Unit)? = null
}

private const val TAG = "AgentWebView"

/** True once the agent has handed the WebView an auth cookie. */
private fun hasAuthCookie(): Boolean =
    CookieManager.getInstance()
        .getCookie("http://127.0.0.1")
        ?.contains("dsh-auth-") == true

/**
 * High-performance System WebView container inside Compose.
 *
 * - Renders the agent's graphical interface (DSH web / OpenCode serve /
 *   gateway-ui.html for CLI-only agents) from the loopback URL.
 * - Injects `dsh-mobile-shell.js` after every page load to force the
 *   responsive mobile transformation (drawers, tabs, pinned composer).
 * - Exposes the `DshBridge` JS interface: DOM-detected tool prompts are
 *   forwarded via [onWebApproval] and re-emitted as native approval sheets;
 *   decisions flow back via `__dshResolveApproval(id, approved)`.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AgentWebView(
    url: String?,
    onWebApproval: (summary: String) -> Unit,
    onGatewayDelta: (text: String) -> Unit = {},
    /**
     * Mirrors `AdaptiveLayout.useSidePanel`. Pushed into the page because the
     * page's own viewport is not the window: on the side-panel path it is
     * 361dp narrower, so a media query in the page disagrees with the native
     * layout and either duplicates navigation or removes it outright (a
     * landscape phone loses Files that way).
     */
    nativeSidePanel: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shellJs = remember { loadShellJs(context) }

    // Lets `adb forward localabstract:webview_devtools_remote_<pid>` expose the
    // live DOM over CDP. Without it a client-side render failure is
    // indistinguishable from a blank page.
    WebView.setWebContentsDebuggingEnabled(true)

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            addJavascriptInterface(DshJsBridge(onWebApproval), "DshBridge")
            WebApprovalRouter.answer = { requestId, approved ->
                post {
                    evaluateJavascript(
                        "window.__dshResolveApproval && " +
                            "window.__dshResolveApproval('$requestId', $approved);",
                        null,
                    )
                }
            }
            // The shell owns command dispatch: the page is always attached here, so
            // accessory-bar taps land in whichever composer is live (gateway.js
            // /send for CLI agents, the live composer for DSH web).
            CommandDispatcher.dispatch = { command -> post { dispatchCommand(command) } }

            // The auth cookie is set by a 303 off `/?token=...` and the WebView
            // keeps it in memory only. Without this the cookie can be lost
            // across a process restart, after which `/` answers 401 and the
            // app renders a silently blank page. Persist it on every write.
            CookieManager.getInstance().setAcceptCookie(true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false // keep all navigation inside the shell

                /**
                 * A 401 here is the blank-screen failure mode: the agent
                 * replies with a 68-byte text body that renders as nothing,
                 * and the app looks broken with no explanation.
                 */
                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    val code = errorResponse?.statusCode ?: -1
                    if (code >= 400) {
                        Log.w(
                            TAG,
                            "http ${request?.method} ${request?.url} -> $code " +
                                "authCookiePresent=" + hasAuthCookie(),
                        )
                    }
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    Log.w(
                        TAG,
                        "load error ${request?.url}: " +
                            "${error?.errorCode} ${error?.description} " +
                            "(mainFrame=${request?.isForMainFrame})",
                    )
                    super.onReceivedError(view, request, error)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    // The shell is injected on every load, so the native
                    // presentation has to be re-stated every load too.
                    view?.evaluateJavascript(
                        "window.__dshSetNativeChrome && window.__dshSetNativeChrome($nativeSidePanel);",
                        null,
                    )
                    Log.i(
                        TAG,
                        "page finished: $loadedUrl title=${view?.title} " +
                            "authCookiePresent=" + hasAuthCookie(),
                    )
                    view?.evaluateJavascript(shellJs, null)
                }
            }

            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    // The SPA runs entirely client-side; a boot-time JS error is
                    // the difference between "renders" and "black screen", and
                    // it is invisible without this.
                    if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        Log.w(TAG, "console: ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                    }
                    return true
                }
            }
        }
    }

    // Push gateway chat deltas into gateway-ui.html when in gateway mode.
    androidx.compose.runtime.DisposableEffect(webView) {
        val listener: (VisualGateway.GatewayEvent) -> Unit = { event ->
            if (event is VisualGateway.GatewayEvent.ChatDelta) {
                onGatewayDelta(event.text)
                webView.post {
                    val escaped = event.text
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                    webView.evaluateJavascript(
                        "window.__gatewayDelta && window.__gatewayDelta('$escaped');",
                        null,
                    )
                }
            }
        }
        GatewayEventBus.subscribe(listener)
        onDispose { GatewayEventBus.unsubscribe(listener) }
    }

    LaunchedEffect(url) {
        if (url != null && webView.url != url) webView.loadUrl(url)
    }

    // Re-push on every load and on every layout change: crossing the breakpoint
    // swaps the whole presentation, and the page must follow immediately rather
    // than waiting for its next navigation.
    LaunchedEffect(nativeSidePanel) {
        webView.post {
            webView.evaluateJavascript(
                "window.__dshSetNativeChrome && window.__dshSetNativeChrome($nativeSidePanel);",
                null,
            )
        }
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

/** Fan-out for gateway stream events to whichever WebView is attached. */
object GatewayEventBus {
    private val listeners = mutableSetOf<(VisualGateway.GatewayEvent) -> Unit>()

    @Synchronized fun subscribe(l: (VisualGateway.GatewayEvent) -> Unit) {
        listeners += l
    }

    @Synchronized fun unsubscribe(l: (VisualGateway.GatewayEvent) -> Unit) {
        listeners -= l
    }

    fun emit(event: VisualGateway.GatewayEvent) {
        val snapshot: List<(VisualGateway.GatewayEvent) -> Unit>
        synchronized(this) { snapshot = listeners.toList() }
        snapshot.forEach { runCatching { it(event) } }
    }
}

private fun loadShellJs(context: Context): String = try {
    context.assets.open("dsh-mobile-shell.js").use { input ->
        input.bufferedReader().use(BufferedReader::readText)
    }
} catch (_: Exception) {
    ""
}

/** JS interface injected as `window.DshBridge` for approval interception. */
class DshJsBridge(
    private val onApproval: (summary: String) -> Unit,
) {
    @JavascriptInterface
    fun postApprovalRequest(json: String) {
        // {"id": "...", "summary": "...", "source": "webview-dom"}
        val summary = Regex("\"summary\"\\s*:\\s*\"(.*?)\"")
            .find(json)?.groupValues?.getOrNull(1)
            ?: json.take(280)
        onApproval(summary.unescapeJson())
    }

    private fun String.unescapeJson(): String =
        replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
}

/**
 * Forwards gateway-ui composer text to the CLI child's stdin. Registered in
 * [DshMobileShell] via the ViewModel's sendCommand path.
 */

/** In-page command insertion shared by every backend. */
fun WebView.dispatchCommand(command: String) {
    val escaped = command
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
    // gateway-ui.html fast path first, DSH/OpenCode composer fallback.
    evaluateJavascript(
        "(function(){var c='$escaped';" +
            "if(window.__dshInsertCommand){window.__dshInsertCommand(c);return;}" +
            "var box=document.querySelector('[data-composer] input,[data-composer] textarea,.chat-input-container textarea');" +
            "if(box){box.focus();document.execCommand('insertText',false,c);" +
            "var f=box.closest('form');if(f){var s=f.querySelector('[type=\"submit\"],button.send');if(s)s.click();else f.requestSubmit();}}" +
            "})();",
        null,
    )
}

/**
 * Dispatches accessory-bar commands into the page.
 *
 * Web backends (DSH web / OpenCode): inserts the command into the visible
 * composer and submits it. Gateway backends: the composer lives in
 * gateway-ui.html (`__dshInsertCommand`), which forwards over the
 * `DshGateway.send` bridge. Registered alongside [WebApprovalRouter].
 */
object CommandDispatcher {
    @Volatile var dispatch: ((command: String) -> Unit)? = null
}

/** Adapter: WebView DOM prompts become native approval requests. */
fun webPromptToApproval(
    summary: String,
    agent: ActiveAgent = ActiveAgent.DEEPSEEK_HARNESS,
): VisualGateway.ApprovalRequest =
    VisualGateway.ApprovalRequest(
        id = "web-${System.currentTimeMillis()}-${summary.hashCode()}",
        agent = agent,
        kind = if (summary.contains("file", ignoreCase = true) ||
            summary.contains("modif", ignoreCase = true)
        ) {
            VisualGateway.ApprovalKind.FILE_WRITE
        } else {
            VisualGateway.ApprovalKind.COMMAND
        },
        summary = summary.take(280),
        rawLine = summary,
    )
