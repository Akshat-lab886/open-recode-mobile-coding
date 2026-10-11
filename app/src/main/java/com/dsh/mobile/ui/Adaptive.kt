package com.dsh.mobile.ui

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ADAPTIVE LAYOUT — one breakpoint decision, shared by every screen.
 *
 * The app has to work on a 5" phone and on a 12.4" tablet, and the two have
 * genuinely different answers rather than "the same layout, more padding":
 *
 *  - Phone: Control Center and diagnostics are modal bottom sheets. There is
 *    not enough width to show them beside the agent without squeezing the
 *    conversation, which is the one thing the user came here to read.
 *  - Tablet: there is room for a persistent side panel, so the settings stop
 *    being a modal interruption and become somewhere you keep.
 *
 * Breakpoints are the Material 3 canonical ones (600dp / 840dp) because they
 * already describe phone / small-tablet / large-tablet consistently across
 * every other decision in this file.
 *
 * This is deliberately plain Kotlin with no Compose or Android types in the
 * decision itself, so [AdaptiveLayoutTest] can pin the boundaries without a
 * device. The composable wrappers only supply the measurements.
 */

enum class WidthClass { Compact, Medium, Expanded }

enum class HeightClass { Compact, Medium, Expanded }

/** Width breakpoints in dp, matching the Material 3 window size classes. */
object Breakpoints {
    val mediumWidth: Dp = 600.dp
    val expandedWidth: Dp = 840.dp
    val mediumHeight: Dp = 480.dp
    val expandedHeight: Dp = 900.dp

    fun widthClass(widthDp: Float): WidthClass = when {
        widthDp < mediumWidth.value -> WidthClass.Compact
        widthDp < expandedWidth.value -> WidthClass.Medium
        else -> WidthClass.Expanded
    }

    fun heightClass(heightDp: Float): HeightClass = when {
        heightDp < mediumHeight.value -> HeightClass.Compact
        heightDp < expandedHeight.value -> HeightClass.Medium
        else -> HeightClass.Expanded
    }
}

/**
 * Resolved layout decisions for one window size.
 *
 * Held as data rather than read ad-hoc inside each composable so that the
 * same window always produces the same answers — and so the answers can be
 * asserted in a unit test.
 */
data class AdaptiveLayout(
    val widthDp: Float,
    val heightDp: Float,
) {
    val widthClass: WidthClass get() = Breakpoints.widthClass(widthDp)
    val heightClass: HeightClass get() = Breakpoints.heightClass(heightDp)

    /**
     * Large tablet: persistent side panel, multi-column forms.
     *
     * Requires BOTH axes, which is the whole reason this is not just
     * `widthClass == Expanded`. A phone in landscape is routinely wider than
     * 840dp - an 891x411dp window is a real, common viewport - and opening a
     * 360dp panel there leaves the agent a 531dp sliver in a keyboard-heavy
     * landscape layout. Height is what distinguishes "large tablet" from
     * "rotated phone".
     *
     * Pinned by `AdaptiveLayoutTest.a landscape phone is not treated as a tablet`.
     */
    val useSidePanel: Boolean
        get() = widthClass == WidthClass.Expanded && heightClass != HeightClass.Compact

    /**
     * True when the window itself is wide enough for a genuinely readable
     * two-column *full-bleed* screen — the first-boot setup view is the one
     * consumer.
     *
     * Do NOT use this to decide column counts for content hosted inside the
     * side panel. A 1923dp tablet window renders the panel at 360dp, so a
     * component that keys off the window instead of its own measured width
     * lays out for far more room than it has. Control Center measures
     * itself for exactly this reason.
     */
    val useTwoColumnForms: Boolean get() = useSidePanel

    /**
     * Height is the scarce axis here. On a landscape phone the keyboard eats
     * most of the viewport, so the quick-command bar has to go; when there is
     * height to spare it stays.
     */
    val showAccessoryBar: Boolean get() = heightDp >= Breakpoints.mediumHeight.value

    companion object {
        // Real device viewports, used by AdaptiveLayoutTest as the fixtures
        // for the rules above. Named here so the sizes have one definition.
        val PhonePortrait = AdaptiveLayout(411f, 891f)
        val PhoneSmall = AdaptiveLayout(320f, 568f)
        val PhoneLandscape = AdaptiveLayout(891f, 411f)
        val TabletPortrait = AdaptiveLayout(934f, 1400f)
        val TabletLandscape = AdaptiveLayout(1400f, 934f)
    }
}

/** Current layout decisions, from the enclosing [BoxWithConstraints]. */
@Composable
fun BoxWithConstraintsScope.rememberAdaptive(): AdaptiveLayout =
    AdaptiveLayout(maxWidth.value, maxHeight.value)

/** Spacing scale accessor, kept next to the breakpoints that consume it. */
val AdaptiveLayout.tokens
    @Composable @ReadOnlyComposable get() = com.dsh.mobile.ui.theme.DshTheme.tokens
