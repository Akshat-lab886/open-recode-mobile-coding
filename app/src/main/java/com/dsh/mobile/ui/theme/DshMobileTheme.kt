package com.dsh.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DshColors = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF04121F),
    secondary = Color(0xFF3ED598),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF151B24),
    surfaceVariant = Color(0xFF1D2530),
    onBackground = Color(0xFFE8EDF3),
    onSurface = Color(0xFFE8EDF3),
    error = Color(0xFFFF6B6B),
)

@Composable
fun DshMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DshColors, content = content)
}
