package com.dsh.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DESIGN SYSTEM — colour, type, shape and spacing for the whole shell.
 *
 * Before this existed the app set eight colours and let Compose supply
 * everything else, so every surface inherited Material's default type ramp:
 * 16sp display headlines, default tracking, 4dp corners. On a 12" tablet that
 * reads as a phone app stretched to fill, and on a 5" phone the same ramp
 * overfills. The tokens below are what make the layout deliberate instead.
 *
 * Deliberately dependency-free: no downloadable fonts, no image assets, no
 * new Gradle dependencies. Everything here is code, which keeps the APK
 * roughly the size it was.
 */

// -- Palette -----------------------------------------------------------------
//
// One hue per semantic role, each with a matched container. Neutrals are a
// single ramp so surfaces can be ranked (background < surface < container <
// high) instead of the three arbitrary greys the app used before.

private val Ink0 = Color(0xFF0A0D12) // background
private val Ink1 = Color(0xFF10151C) // surface
private val Ink2 = Color(0xFF161C25) // surface container
private val Ink3 = Color(0xFF1D242F) // surface container high
private val Ink4 = Color(0xFF28303D) // surface container highest
private val Line = Color(0xFF2E3744) // outline
private val LineSoft = Color(0xFF212936) // outline variant
private val Chalk = Color(0xFFE8EDF3) // on-surface
private val ChalkDim = Color(0xFF9AA6B6) // on-surface-variant

private val Azure = Color(0xFF6FB4FF) // primary
private val AzureDeep = Color(0xFF16324F) // primary container
private val AzureInk = Color(0xFF04121F) // on-primary

private val Mint = Color(0xFF4ADE9B) // secondary
private val MintDeep = Color(0xFF10331F) // secondary container
private val MintInk = Color(0xFF04140C) // on-secondary

private val Amber = Color(0xFFF5B84D) // tertiary
private val AmberDeep = Color(0xFF3A2A0D) // tertiary container

private val Rose = Color(0xFFFF7A7A) // error
private val RoseDeep = Color(0xFF4A1A1A) // error container

private val DshDarkColors = darkColorScheme(
    primary = Azure,
    onPrimary = AzureInk,
    primaryContainer = AzureDeep,
    onPrimaryContainer = Color(0xFFD3E7FF),
    secondary = Mint,
    onSecondary = MintInk,
    secondaryContainer = MintDeep,
    onSecondaryContainer = Color(0xFFBFF3D6),
    tertiary = Amber,
    onTertiary = Color(0xFF241700),
    tertiaryContainer = AmberDeep,
    onTertiaryContainer = Color(0xFFFFE0B2),
    background = Ink0,
    onBackground = Chalk,
    surface = Ink1,
    onSurface = Chalk,
    surfaceVariant = Ink3,
    onSurfaceVariant = ChalkDim,
    surfaceContainerLowest = Ink0,
    surfaceContainerLow = Ink1,
    surfaceContainer = Ink2,
    surfaceContainerHigh = Ink3,
    surfaceContainerHighest = Ink4,
    surfaceTint = Azure,
    outline = Line,
    outlineVariant = LineSoft,
    error = Rose,
    onError = Color(0xFF2B0606),
    errorContainer = RoseDeep,
    onErrorContainer = Color(0xFFFFD9D9),
    scrim = Color(0xFF000000),
)

// Light scheme removed on purpose. The app is a dark operations console
// wrapped around a dark agent WebView (dsh web / OpenCode / gateway-ui all
// serve a dark surface), so honouring a system light theme would produce a
// white app chrome framing a black page — worse than ignoring the setting.
// DARK_ONLY is the contract, not an oversight.

// -- Type --------------------------------------------------------------------
//
// Material's defaults are tuned for a 16dp-margin phone column. Headlines get
// negative tracking (large type set tight is what separates "designed" from
// "default"); body copy keeps normal tracking for legibility.

private val DshTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 44.sp, lineHeight = 50.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-1.0).sp,
    ),
    displayMedium = TextStyle(
        fontSize = 36.sp, lineHeight = 42.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp,
    ),
    displaySmall = TextStyle(
        fontSize = 30.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontSize = 28.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp, lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, lineHeight = 17.sp,
        fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, lineHeight = 15.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
    ),
)

private val DshShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/**
 * Spacing scale and the content ceiling used by every centred panel.
 *
 * The ceiling is the single most important value here. A credential field or
 * a log pane stretched across a 1500dp tablet is unusable — the eye cannot
 * find the start of the next line — so every column is capped and centred.
 */
data class DshTokens(
    val spaceXs: androidx.compose.ui.unit.Dp = 4.dp,
    val spaceSm: androidx.compose.ui.unit.Dp = 8.dp,
    val spaceMd: androidx.compose.ui.unit.Dp = 12.dp,
    val spaceLg: androidx.compose.ui.unit.Dp = 16.dp,
    val spaceXl: androidx.compose.ui.unit.Dp = 24.dp,
    /** Widest a readable column may get before it is capped and centred. */
    val contentMaxWidth: androidx.compose.ui.unit.Dp = 640.dp,
    /** Widest a form (keys, workspace) may get — narrower than reading text. */
    val formMaxWidth: androidx.compose.ui.unit.Dp = 520.dp,
    /** Side panel width on expanded layouts. */
    val panelWidth: androidx.compose.ui.unit.Dp = 360.dp,
    /** Minimum touch target. Android's accessibility floor is 48dp. */
    val minTouchTarget: androidx.compose.ui.unit.Dp = 48.dp,
)

val LocalDshTokens: ProvidableCompositionLocal<DshTokens> =
    staticCompositionLocalOf { DshTokens() }


object DshTheme {
    val tokens: DshTokens
        @Composable @ReadOnlyComposable get() = LocalDshTokens.current
}

@Composable
fun DshMobileTheme(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDshTokens provides DshTokens()) {
        MaterialTheme(
            colorScheme = DshDarkColors,
            typography = DshTypography,
            shapes = DshShapes,
            content = content,
        )
    }
}
