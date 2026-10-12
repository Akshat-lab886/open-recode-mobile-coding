package com.dsh.mobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
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

/**
 * Lets the page's own controls reach native pickers.
 *
 * The agent's web UI ships controls that assume a desktop shell: "Choose
 * workspace" opens a native folder dialog and nothing else. In this WebView
 * that tap produces no UI at all - verified on-device: the click creates no
 * menu element and raises no error, it just does nothing.
 *
 * The app already has a working Storage Access Framework picker, so the shell
 * script redirects the page's button here instead of shipping a second,
 * parallel picker that would drift.
 */
object NativePickerRouter {
    /** Launch the SAF folder picker. Set by [AgentWebView] on creation. */
    @Volatile var pickFolder: (() -> Unit)? = null

    /** Hand a chosen path back to the page so its label updates. */
    @Volatile var deliverWorkspace: ((String) -> Unit)? = null
}

private const val TAG = "AgentWebView"

/**
 * Pending `<input type="file">` request from the agent's page.
 *
 * ## Why this exists
 *
 * Three separate complaints - "choose workspace" does nothing, "attach file"
 * does nothing, "attach photo" does nothing - are all ONE bug: every one of
 * those controls is an `<input type="file">`, and a WebView only shows a picker
 * if the app implements `WebChromeClient.onShowFileChooser`. Without it the tap
 * is silently swallowed and the page just sits there, which is exactly the
 * "installed and nothing works" feel.
 *
 * Held in a file-level holder rather than Compose state because the callback
 * arrives on the WebView (a View), not from a composable scope, and the result
 * comes back through the activity-result API on a later frame.
 */
private object PendingFileChooser {
    /** Must be invoked exactly once, with the chosen URIs or null. */
    @Volatile var callback: ValueCallback<Array<Uri>>? = null

    /**
     * The WebView, set when it is created.
     *
     * The activity-result callback is registered before the WebView exists, so
     * it needs a way back to the page without capturing an uninitialised val.
     */
    @Volatile var view: WebView? = null

    /** True when the request asked for more than one file. */
    @Volatile var multiple: Boolean = false

    /** Accept types from the input, lowercased; empty means anything. */
    @Volatile var acceptTypes: Array<String> = emptyArray()

    /** True when the input carried a `capture` attribute. */
    @Volatile var wantsCamera: Boolean = false

    fun claim(callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
        this.callback = callback
        // params is nullable in practice; treat every unknown as "one file,
        // any type", which is the most permissive reading and cannot lock the
        // page out.
        multiple = params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        acceptTypes = params?.acceptTypes
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toTypedArray()
            ?: emptyArray()
        wantsCamera = params?.isCaptureEnabled == true
        return callback != null
    }

    /** Complete the request. Calling with null is how the user cancels. */
    fun complete(uris: Array<Uri>?) {
        val cb = callback
        callback = null
        // The contract requires a result even on cancel; a missed call leaves
        // the page's input permanently "busy" and it stops accepting taps.
        cb?.onReceiveValue(uris)
    }

    /** True when this request wants images specifically. */
    fun wantsImages(): Boolean {
        if (acceptTypes.isEmpty()) return false
        return acceptTypes.any { it == "image/*" || it.startsWith("image/") }
    }
}

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

    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // The picker came back. A cancelled pick must still deliver a result so
        // the page's input stops being stuck in a pending state.
        val data: Intent? = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            val uris = when {
                clip != null -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
            // The WebView contract requires the original URIs, but a CLI agent
            // cannot read a content:// URI - it reads files by path. So the
            // files are staged on disk AND the paths are pushed to the page.
            val paths = uris?.mapNotNull { stageAttachment(context, it) }
            PendingFileChooser.complete(uris)
            if (!paths.isNullOrEmpty()) {
                val js = paths.joinToString(",") { path ->
                    "'" + path.replace("\\", "\\\\").replace("'", "\\'") + "'"
                }
                val target = PendingFileChooser.view
                target?.post {
                    target.evaluateJavascript(
                        "window.__dshFilesStaged && window.__dshFilesStaged([$js]);",
                        null,
                    )
                }
            }
        } else {
            PendingFileChooser.complete(null)
        }
    }

    // A dedicated launcher for the camera path, because a capture result comes
    // back with no data at all - only the URI we put into the intent.
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        PendingFileChooser.complete(uri?.let { arrayOf(it) })
    }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            PendingFileChooser.view = this
            addJavascriptInterface(DshJsBridge(onWebApproval, this), "DshBridge")
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
                /**
                 * Show a picker for the page's file inputs.
                 *
                 * This is the callback that makes "choose workspace", "attach
                 * file" and "attach photo" work at all. Returning false (the
                 * default) means the page silently swallows the tap.
                 *
                 * Camera capture is only offered when the page actually asks
                 * for it, because launching a camera is a heavier, more
                 * surprising thing to do on the user's behalf.
                 */
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?,
                ): Boolean {
                    if (!PendingFileChooser.claim(callback, params)) {
                        callback?.onReceiveValue(null)
                        return false
                    }
                    // A `capture` input means the user pointed a camera at
                    // something; sending them to a file browser instead would
                    // be answering a different question. Falls back to the
                    // gallery if no camera app exists.
                    if (PendingFileChooser.wantsCamera && beginCameraCapture(context) {
                            cameraLauncher.launch(it)
                        }
                    ) {
                        return true
                    }
                    activityLauncher.launch(buildChooserIntent(params))
                    return true
                }

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

    // Storage Access Framework folder picker, shared with the page's own
    // workspace control through NativePickerRouter. Declared after the WebView
    // because it posts the chosen path back into the page.
    val workspaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolved = SafWorkspacePath.resolve(context, uri)
            ?: SafWorkspacePath.fallback(context)
        webView.post {
            val escaped = resolved.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript(
                "window.__dshWorkspaceChosen && window.__dshWorkspaceChosen('$escaped');",
                null,
            )
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        NativePickerRouter.pickFolder = { workspaceLauncher.launch(null) }
        onDispose { NativePickerRouter.pickFolder = null }
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
    /** Used to hop onto the UI thread; JS bridge calls are not on it. */
    private val view: WebView?,
) {
    /**
     * Called by the page's workspace control. Launches the native SAF picker.
     *
     * The page cannot open a picker itself on Android - the File System Access
     * API it reaches for exists in this WebView but every call rejects with
     * SecurityError, and the app's own click handler does nothing.
     */
    @JavascriptInterface
    fun requestWorkspacePicker() {
        // @JavascriptInterface calls arrive on a private WebView thread, not
        // the UI thread, so the launcher has to be invoked from the main one.
        view?.post { NativePickerRouter.pickFolder?.invoke() }
    }

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

/**
 * Copy a picked attachment into the agent workspace and return its path.
 *
 * The five CLI agents (Claude Code, Codex, OpenCode, Antigravity, Hermes) all
 * read work from the filesystem, and their stdin carries plain text - so
 * inlining a photo as base64 into a prompt is useless. Staging the file on disk
 * and handing the agent a path is what actually works for code files *and*
 * images, and it is the same mechanism a desktop user gets by dragging a file
 * into the project.
 *
 * Images are copied as-is. Everything else is copied byte-for-byte too: an
 * agent can read any of it, and re-encoding would corrupt binaries.
 */
private fun stageAttachment(context: Context, uri: Uri): String? {
    val name = queryDisplayName(context, uri) ?: return null
    // Never trust a display name as a path - it may contain separators or be
    // absolute, which would let a picked file escape the workspace.
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "attachment" }
    val dir = File(context.filesDir, "workspaces/saf-picked")
    if (!dir.isDirectory && !dir.mkdirs()) return null
    val target = File(dir, safe)
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        } ?: return null
        target.absolutePath
    } catch (_: Exception) {
        null
    }
}

/** Best-effort display name for a content URI. */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}

/** URI handed to a camera app for the current capture, if any. */
private var pendingCameraUri: Uri? = null

/**
 * Build the picker intent for a file input.
 *
 * `createIntent()` alone is not enough in practice: it does not always honour
 * multi-select, and it has no camera option. Both matter here - workspace
 * selection is multi-file in spirit and attachment is usually single - so the
 * intent is built explicitly from the input's own parameters.
 */
private fun buildChooserIntent(params: FileChooserParams?): Intent {
    val wantsImages = PendingFileChooser.wantsImages()
    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = when {
            // An input with several types ("image/*" + "text/plain") is
            // expressed as the wildcard with EXTRA_MIME_TYPES.
            PendingFileChooser.acceptTypes.size > 1 -> "*/*"
            PendingFileChooser.acceptTypes.isNotEmpty() ->
                PendingFileChooser.acceptTypes.first().ifBlank { "*/*" }
            else -> "*/*"
        }
        if (PendingFileChooser.acceptTypes.size > 1) {
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                PendingFileChooser.acceptTypes.copyOf(),
            )
        }
        if (PendingFileChooser.multiple) {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }
    return intent
}

/**
 * Launch the camera for a `capture` input and return the saved photo.
 *
 * Separated from the gallery path because a capture result carries no data:
 * the URI is the one we wrote into the intent, so it has to be kept until the
 * activity comes back.
 */
private fun beginCameraCapture(context: Context, onLaunch: (Intent) -> Unit): Boolean {
    val photo = File(File(context.cacheDir, "captures"), "capture-${System.currentTimeMillis()}.jpg")
    photo.parentFile?.mkdirs()
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo)
    }.getOrNull() ?: return false
    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra(MediaStore.EXTRA_OUTPUT, uri)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    pendingCameraUri = uri
    onLaunch(intent)
    return true
}
