package com.dsh.mobile.ui

import com.dsh.mobile.ui.theme.DshTokens
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the window-size rules that decide between the phone presentation and the
 * tablet side panel.
 *
 * Adaptive.kt is deliberately pure Kotlin with no Compose or Android dependency
 * precisely so this can run as a plain JVM test.
 */
class AdaptiveLayoutTest {

    private fun at(w: Int, h: Int) = AdaptiveLayout(widthDp = w.toFloat(), heightDp = h.toFloat())

    @Test
    fun `the shared device fixtures resolve as documented`() {
        // The fixtures are the real viewports the rules are written against, so
        // a change to one that flips its classification is a change to the rule.
        assertFalse(AdaptiveLayout.PhonePortrait.useSidePanel)
        assertFalse(AdaptiveLayout.PhoneSmall.useSidePanel)
        assertFalse(AdaptiveLayout.PhoneLandscape.useSidePanel)
        assertTrue(AdaptiveLayout.TabletPortrait.useSidePanel)
        assertTrue(AdaptiveLayout.TabletLandscape.useSidePanel)
    }

    @Test
    fun `a phone portrait window never opens the side panel`() {
        assertFalse(at(411, 891).useSidePanel)
    }

    @Test
    fun `a small phone never opens the side panel`() {
        assertFalse(at(320, 568).useSidePanel)
    }

    @Test
    fun `a medium tablet portrait window opens the side panel`() {
        assertTrue(at(934, 1400).useSidePanel)
    }

    @Test
    fun `a large tablet landscape window opens the side panel`() {
        assertTrue(at(1400, 934).useSidePanel)
    }

    /**
     * The regression this two-axis rule exists to prevent. 891dp is past the
     * 840dp width breakpoint, so a width-only rule handed a rotated phone a
     * 360dp panel and left the agent a 531dp sliver.
     */
    @Test
    fun `a landscape phone is not treated as a tablet`() {
        assertFalse(at(891, 411).useSidePanel)
    }

    @Test
    fun `width and height breakpoints are exact`() {
        // Width: Compact <600, Medium 600-839, Expanded >=840.
        assertFalse(at(599, 900).useSidePanel)
        assertFalse(at(839, 900).useSidePanel)
        assertTrue(at(840, 900).useSidePanel)

        // Height: Compact <480, Medium 480-899, Expanded >=900. The rule needs
        // only "not Compact", so exactly 480dp still opens the panel.
        assertFalse(at(900, 479).useSidePanel)
        assertTrue(at(900, 480).useSidePanel)
        assertTrue(at(900, 900).useSidePanel)
    }

    @Test
    fun `the accessory bar is suppressed on a short window`() {
        assertFalse(at(891, 411).showAccessoryBar)
        assertTrue(at(411, 891).showAccessoryBar)
    }

    @Test
    fun `exactly one presentation is active at any size`() {
        listOf(320 to 568, 411 to 891, 891 to 411, 934 to 1400, 1400 to 934)
            .forEach { (w, h) ->
                val layout = at(w, h)
                assertTrue(
                    "$w x $h must resolve to exactly one presentation",
                    layout.useSidePanel xor !layout.useSidePanel,
                )
            }
    }

    @Test
    fun `two-up forms are a window-level decision only`() {
        assertTrue(at(1400, 934).useTwoColumnForms)
        assertFalse(at(411, 891).useTwoColumnForms)
    }

    @Test
    fun `the side panel stays narrower than the two-up form threshold`() {
        val tokens = DshTokens()
        assertTrue(
            "the ${tokens.panelWidth.value}dp panel must stay a single-column host",
            tokens.panelWidth < TWO_UP_MIN_WIDTH,
        )
    }
}
