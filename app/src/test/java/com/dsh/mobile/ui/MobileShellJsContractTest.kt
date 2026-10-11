package com.dsh.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shell stylesheet and the native layout have to agree on one question:
 * "is this a tablet?". Two implementations of that answer previously drifted,
 * and the failure is severe in both directions.
 *
 *  - Native said yes, page said no: the hamburger and Chat/Files tab bar
 *    stayed on screen beside the native side panel.
 *  - Native said no, page said yes: a landscape phone (891x411dp) is past the
 *    840dp width breakpoint, so the page deleted its own navigation and the
 *    user had no route to Files at all.
 *
 * These tests pin the contract. The decision is now pushed from native via
 * `window.__dshSetNativeChrome`, so the media query is only a fallback.
 */
class MobileShellJsContractTest {

    private val shellJs: String by lazy {
        val f = File("src/main/assets/dsh-mobile-shell.js")
        if (f.exists()) f.readText() else File("app/src/main/assets/dsh-mobile-shell.js").readText()
    }

    @Test
    fun `the script parses`() {
        // A syntax error here means the whole mobile shell silently stops
        // applying, which looks exactly like an agent failure.
        val proc = ProcessBuilder("node", "--check", filePath())
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("node --check failed: $out", proc.waitFor() == 0)
    }

    private fun filePath(): String {
        val f = File("src/main/assets/dsh-mobile-shell.js")
        return if (f.exists()) f.path else File("app/src/main/assets/dsh-mobile-shell.js").path
    }

    @Test
    fun `native owns the presentation decision and the page obeys`() {
        assertTrue(
            "the page must expose a setter for the native decision",
            shellJs.contains("window.__dshSetNativeChrome"),
        )
    }

    /**
     * The page must decide in JavaScript, not with a CSS media query.
     *
     * Measured on-device at a 1923x1202dp window: innerHeight was 1157 and
     * matchMedia('(min-height: 1px)').matches was FALSE, while
     * matchMedia('(max-height: 1px)') was true. The agent page collapses the
     * document root, so this WebView reports media-query heights against a ~0px
     * box. A min-height query therefore never matches, at any window size - the
     * wide presentation could not activate, and it looked correct in a desktop
     * browser.
     */
    @Test
    fun `the page decides from measured js size, not a height media query`() {
        val isWide = Regex("""function isWide\(\) \{([\s\S]*?)\n  \}""")
            .find(shellJs)?.groupValues?.get(1).orEmpty()
        assertTrue("could not find isWide(): " + isWide, isWide.isNotEmpty())
        assertTrue(
            "isWide() must use the measured viewport, got: " + isWide,
            isWide.contains("innerWidth") && isWide.contains("innerHeight"),
        )
        // Strip comments: the explanatory comment legitimately names
        // matchMedia, but no *statement* may call it.
        val code = isWide.lines()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        assertTrue(
            "isWide() must not call matchMedia; the height half can never match " +
                "in this WebView: " + code,
            !code.contains("matchMedia"),
        )
    }

    @Test
    fun `the wide css is keyed on the native class, not a media query`() {
        assertTrue(
            "the wide rules must be reachable from body.dsh-native-wide",
            shellJs.contains("body.dsh-native-wide"),
        )
        val styles = Regex("""var css = \[(.*?)\];""")
            .find(shellJs)?.groupValues?.get(1).orEmpty()
        assertTrue(
            "the stylesheet must not key the wide presentation off a media " +
                "query whose height never matches: " + styles,
            !styles.contains("@media"),
        )
    }

    @Test
    fun `one class feeds both the chrome and the stylesheet`() {
        assertTrue(
            "apply() must maintain the class so chrome and CSS cannot disagree",
            Regex("""function apply\(\) \{[\s\S]*?dsh-native-wide[\s\S]*?ensureChrome""")
                .containsMatchIn(shellJs),
        )
    }

    @Test
    fun `the native flag drives the css too, not only the media query`() {
        assertTrue(
            "the wide CSS must be reachable via the native body class",
            shellJs.contains("body.dsh-native-wide"),
        )
        assertTrue(
            "the body class must be toggled by the native setter",
            shellJs.contains("classList.toggle('dsh-native-wide'"),
        )
    }

    @Test
    fun `the approval bridge contract is preserved`() {
        // Native calls this by name; renaming it silently breaks approvals.
        assertTrue(shellJs.contains("window.__dshResolveApproval"))
        assertTrue(shellJs.contains("DshBridge.postApprovalRequest"))
    }

    @Test
    fun `the approval regex still matches the prompts it is meant to`() {
        val patterns = listOf(
            "do you want to run",
            "allow .*command",
            "allow file modification",
            "overwrite .*?\\?",
            "proceed\\?",
            "\\[y\\/n\\]",
        )
        patterns.forEach {
            assertTrue("approval pattern lost: $it", shellJs.contains(it))
        }
    }

    @Test
    fun `the document height repair survived the responsive rewrite`() {
        // Without this the agent page paints as a black rectangle: the SPA's
        // html/body/#root{height:100%} chain collapses to 0 inside the WebView.
        assertTrue(shellJs.contains("repairDocumentHeight"))
        assertTrue(shellJs.contains("100vh"))
    }

    @Test
    fun `the script is idempotent`() {
        assertTrue(shellJs.contains("__dshMobileShellInstalled"))
    }

    /**
     * `vh` resolves to 0 in this WebView.
     *
     * Measured live over CDP at a 601x898 CSS-px viewport: `100vh`, `50vh`,
     * `1vh` and `50%` ALL measured 0, while `100px` measured 100 and `100vw`
     * measured 601. The agent page collapses the document root, so the WebView
     * resolves viewport-percentage *heights* against a zero-height box.
     *
     * The consequence was the user-reported bug: every agent surface sized in
     * `vh` collapsed. The settings panel computed to 0px tall and the API-key
     * dialog's `max-height: calc(100vh - 48px)` computed to `max(0, -48) = 0`,
     * clipping ~150px of form to 56px - a narrow unusable strip.
     */
    @Test
    fun `the viewport height repair exists because vh resolves to zero`() {
        assertTrue(
            "the measured-pixel height must be exposed as --dsh-vh",
            shellJs.contains("--dsh-vh"),
        )
        assertTrue(
            "the height must be measured in JS, since vh cannot be trusted",
            shellJs.contains("window.visualViewport") ||
                shellJs.contains("window.innerHeight"),
        )
        assertTrue(
            "the repair sheet must be installed",
            shellJs.contains("dsh-vh-repair"),
        )
    }

    /**
     * A user who cannot reliably tap a control cannot use the settings at all.
     * Measured on-device before the fix: 27 of 30 visible buttons were under
     * the 48dp floor, the smallest at 12 CSS px.
     */
    @Test
    fun `touch targets are raised to the 48dp floor in device pixels`() {
        assertTrue(
            "the touch floor must derive from devicePixelRatio, not a hard-coded px",
            shellJs.contains("devicePixelRatio"),
        )
        assertTrue(
            "48dp must be expressed in CSS px via a variable",
            shellJs.contains("--dsh-touch"),
        )
        // `button[class]` outranks the agent's own min-height on a bare
        // `button`, which is what actually wins the cascade.
        assertTrue(
            "the override must outrank the agent's class-level min-height",
            Regex("""button,\\?button\\?\[class\\?\]\\?\\?\{min-height:var\\?\(--dsh-touch""")
                .containsMatchIn(shellJs) ||
                shellJs.contains("button[class]{min-height:var(--dsh-touch"),
        )
    }

    /**
     * Width is NOT broken - `vw` measured correctly - so the repair must not
     * touch width. A `vw` override would be cargo-culting the fix.
     */
    @Test
    fun `the repair touches heights only, never widths`() {
        val block = shellJs.substringAfter("sheet.textContent = [", "].join")
        assertFalse(
            "100vw resolves correctly here; overriding it would be a new bug: $block",
            block.contains("100vw") || block.contains("vw)"),
        )
    }

    /**
     * The pre-existing document-height repair used `min-height: 100vh`, which
     * is self-defeating when `vh` is the broken unit.
     */
    @Test
    fun `the document height repair no longer repairs with the broken unit`() {
        val fn = shellJs.substringAfter("function repairDocumentHeight()")
            .substringBefore("/* ---")
        // Strip comments: the function's own comment explains why it must not
        // use 100vh, so the literal string legitimately appears there.
        val code = fn.lines().joinToString("\n") { it.substringBefore("//") }
        assertTrue(
            "repairDocumentHeight must not set 100vh in code: " + code,
            !code.contains("100vh"),
        )
    }

    @Test
    fun `the viewport height is re-synced on resize and keyboard changes`() {
        assertTrue(
            "visualViewport must be tracked so the soft keyboard re-measures",
            shellJs.contains("visualViewport.addEventListener"),
        )
    }

}
