package com.dsh.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the crash that made Settings unusable.
 *
 * Opening the Control Center threw
 * `IllegalStateException: Vertically scrollable component was measured with an
 * infinity maximum height constraints` and the process died.
 *
 * Cause: Control Center is hosted in a `ModalBottomSheet`, which measures its
 * content with UNBOUNDED height. The content was a `Column(Modifier.fillMaxSize()
 * .verticalScroll(...))`, so inside the sheet `fillMaxSize()` resolved to an
 * infinite maximum and Compose refused to measure it.
 *
 * The bug was invisible to unit tests because it only reproduces at runtime in
 * that host, so the shape is asserted against the source instead.
 */
class ControlCenterScrollContractTest {

    private fun source(): String =
        File("src/main/java/com/dsh/mobile/ui/ControlCenter.kt").readText()

    private fun stripped(): String =
        source()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""\s+"""), " ")

    @Test
    fun `scrolling column must not fill max size`() {
        val body = stripped()
        assertFalse(
            "A verticalScroll inside a ModalBottomSheet must never combine with " +
                "fillMaxSize(): the sheet measures content with unbounded height, " +
                "which throws at runtime.",
            Regex("""fillMaxSize\(\)\s*\.verticalScroll""").containsMatchIn(body),
        )
    }

    /**
     * Every sheet hosted in a ModalBottomSheet needs the same guard.
     *
     * The Control Center was fixed first and verified green, then the app still
     * died on launch - because the agent picker sheet, shown at first run, had
     * the identical unbounded-scroll bug. Fixing one host proved nothing about
     * the others, so the rule is now asserted for all of them.
     */
    @Test
    fun `no sheet combines an unbounded scroll`() {
        listOf("AgentPickerSheet.kt", "ControlCenter.kt", "ResilienceViews.kt").forEach { name ->
            val path = File("src/main/java/com/dsh/mobile/ui/$name")
            assertTrue("$name is missing", path.exists())
            val body = path.readText()
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")
                .replace(Regex("""import [^\n]*"""), "")
                .replace(Regex("""\s+"""), " ")
            // Compared by position rather than by regex: the bound is an
            // expression containing its own parentheses, so any "no ')' inside"
            // pattern silently fails to match a perfectly correct call.
            val scrollAt = body.indexOf(".verticalScroll(rememberScrollState())")
            val boundAt = body.indexOf("heightIn(max =")
            if (scrollAt >= 0) {
                assertTrue(
                    "$name scrolls without an explicit height cap; a bottom sheet " +
                        "measures content with unbounded height and the process dies.",
                    boundAt >= 0 && boundAt < scrollAt,
                )
            }
        }
    }

    @Test
    fun `scrolling column is bounded explicitly`() {
        val body = stripped()
        assertTrue(
            "The scroll container must carry an explicit heightIn(max = ...) so it " +
                "is measurable inside a bottom sheet.",
            Regex("""heightIn\(max = maxHeight\)\s*\.verticalScroll""").containsMatchIn(body),
        )
    }
}
