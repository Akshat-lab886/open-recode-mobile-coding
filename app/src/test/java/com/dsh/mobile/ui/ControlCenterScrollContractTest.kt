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
