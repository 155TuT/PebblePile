package com.pebblepile.android

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Android roles from assets/design-tokens.json. Ordinary controls share the page surface. */
object PebbleVisuals {
    val Sand = Color(0xFFF5F3EB)
    val Content = Color(0xFFFFFDF7)
    val SurfaceMuted = Color(0xFFECECE3)
    val Hover = Color(0xFFE6E8DE)
    val Ink = Color(0xFF222C24)
    val Muted = Color(0xFF5E6B5D)
    val Moss = Color(0xFF536D58)
    val MossSoft = Color(0xFFE3EBDD)
    val MossHover = Color(0xFF465E4B)
    val MossPressed = Color(0xFF36503D)
    val Focus = Color(0xFF365B44)
    val Outline = Color(0xFF788374)
    val Border = Color(0xFFDADDD2)
    val Danger = Color(0xFF934C47)
    val DangerSoft = Color(0xFFF5E6E2)
    val Warning = Color(0xFF775C29)
    val WarningSoft = Color(0xFFF3EAD2)
    val Success = Color(0xFF3E6246)
    val SuccessSoft = Color(0xFFE5EFE2)
    val Info = Color(0xFF3C6470)
    val InfoSoft = Color(0xFFE6EEF0)
}

private val colors = with(PebbleVisuals) {
    lightColorScheme(
        primary = Moss, onPrimary = Content, primaryContainer = MossSoft, onPrimaryContainer = MossPressed,
        secondary = Muted, onSecondary = Content, secondaryContainer = SurfaceMuted, onSecondaryContainer = Ink,
        tertiary = Color(0xFF6E5D40), onTertiary = Content, tertiaryContainer = Color(0xFFE5DDC4), onTertiaryContainer = Color(0xFF6E5D40),
        background = Sand, onBackground = Ink, surface = Sand, onSurface = Ink,
        surfaceVariant = SurfaceMuted, onSurfaceVariant = Muted, surfaceTint = Moss,
        surfaceBright = Content, surfaceDim = SurfaceMuted, surfaceContainerLowest = Content,
        surfaceContainerLow = Sand, surfaceContainer = Sand, surfaceContainerHigh = Sand, surfaceContainerHighest = SurfaceMuted,
        outline = Outline, outlineVariant = Border, error = Danger, onError = Content,
        errorContainer = DangerSoft, onErrorContainer = Danger, scrim = Ink,
        inverseSurface = Ink, inverseOnSurface = Sand, inversePrimary = MossSoft,
    )
}

@Composable
fun PebbleComponentTheme(cjk: FontFamily = PebbleWenKai, content: @Composable () -> Unit) {
    val typography = remember(cjk) {
        fun type(size: Int, leading: Int, weight: FontWeight = FontWeight.Normal) =
            TextStyle(fontFamily = cjk, fontSize = size.sp, lineHeight = leading.sp,
                fontWeight = weight, fontSynthesis = FontSynthesis.None)
        val medium = FontWeight.Medium
        Typography(
            displayLarge = type(48,58,medium), displayMedium = type(32,42,medium), displaySmall = type(24,34,medium),
            headlineLarge = type(32,42,medium), headlineMedium = type(24,34,medium), headlineSmall = type(20,30,medium),
            titleLarge = type(24,34,medium), titleMedium = type(20,30,medium), titleSmall = type(18,28,medium),
            bodyLarge = type(16,26), bodyMedium = type(14,20), bodySmall = type(12,18),
            labelLarge = type(16,26,medium), labelMedium = type(14,20,medium), labelSmall = type(12,18),
        )
    }
    val selection = remember { TextSelectionColors(PebbleVisuals.Moss, PebbleVisuals.Moss.copy(alpha = .25f)) }
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides PebbleVisuals.Ink, LocalTextSelectionColors provides selection) {
            ProvideTextStyle(typography.bodyLarge, content)
        }
    }
}
