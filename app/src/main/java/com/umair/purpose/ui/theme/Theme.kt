package com.umair.purpose.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.umair.purpose.R
import com.umair.purpose.data.repo.ThemeChoice

/** DESIGN.md color tokens: "Hindu Kush at dusk". */
@Immutable
data class PurposeColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val hairline: Color,
    val danger: Color,
    val isNight: Boolean,
)

val NightColors = PurposeColors(
    background = Color(0xFF1C2533),
    surface = Color(0xFF263244),
    text = Color(0xFFE9ECEF),
    textMuted = Color(0xFF9AA8B8),
    accent = Color(0xFFF0B35E),
    hairline = Color(0xFFE9ECEF).copy(alpha = 0.10f),
    danger = Color(0xFFE07A6B),
    isNight = true,
)

val DayColors = PurposeColors(
    background = Color(0xFFEEF1F2),
    surface = Color(0xFFE1E6EA),
    text = Color(0xFF1C2533),
    textMuted = Color(0xFF5B6B7C),
    accent = Color(0xFFA8661F),
    hairline = Color(0xFF1C2533).copy(alpha = 0.10f),
    danger = Color(0xFFB4473A),
    isNight = false,
)

val Newsreader = FontFamily(
    Font(R.font.newsreader_regular, FontWeight.Normal),
    Font(R.font.newsreader_medium, FontWeight.Medium),
    Font(R.font.newsreader_italic, FontWeight.Normal, FontStyle.Italic),
)

val HankenGrotesk = FontFamily(
    Font(R.font.hanken_grotesk_regular, FontWeight.Normal),
    Font(R.font.hanken_grotesk_medium, FontWeight.Medium),
)

/** DESIGN.md type scale. Colors are applied where used. */
@Immutable
data class PurposeType(
    val openingLine: TextStyle = TextStyle(fontFamily = Newsreader, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal),
    val screenTitle: TextStyle = TextStyle(fontFamily = Newsreader, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    val coachBody: TextStyle = TextStyle(fontFamily = Newsreader, fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
    val letterBody: TextStyle = TextStyle(fontFamily = Newsreader, fontSize = 19.sp, lineHeight = 31.sp, fontWeight = FontWeight.Normal),
    val itemText: TextStyle = TextStyle(fontFamily = Newsreader, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    val userBody: TextStyle = TextStyle(fontFamily = HankenGrotesk, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    val label: TextStyle = TextStyle(fontFamily = HankenGrotesk, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    val meta: TextStyle = TextStyle(fontFamily = HankenGrotesk, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    /** Section headings (Newsreader 22sp). */
    val heading: TextStyle = TextStyle(fontFamily = Newsreader, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
)

val LocalPurposeColors = staticCompositionLocalOf { NightColors }
val LocalPurposeType = staticCompositionLocalOf { PurposeType() }
/** True when the system "remove animations" setting is on. */
val LocalReduceMotion = staticCompositionLocalOf { false }

object Purpose {
    val colors: PurposeColors
        @Composable @ReadOnlyComposable get() = LocalPurposeColors.current
    val type: PurposeType
        @Composable @ReadOnlyComposable get() = LocalPurposeType.current
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

@Composable
fun isNight(choice: ThemeChoice): Boolean = when (choice) {
    ThemeChoice.NIGHT -> true
    ThemeChoice.DAY -> false
    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
}

/**
 * Night is the default. Material components are mapped onto the tokens so nothing falls back to
 * the default purple, and tonal surface tints are switched off.
 */
@Composable
fun PurposeTheme(choice: ThemeChoice = ThemeChoice.NIGHT, content: @Composable () -> Unit) {
    val c = if (isNight(choice)) NightColors else DayColors
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val base = if (c.isNight) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.accent, onPrimary = c.background,
        primaryContainer = c.surface, onPrimaryContainer = c.text,
        secondary = c.textMuted, onSecondary = c.background,
        secondaryContainer = c.surface, onSecondaryContainer = c.text,
        tertiary = c.accent, onTertiary = c.background,
        background = c.background, onBackground = c.text,
        surface = c.background, onSurface = c.text,
        surfaceVariant = c.surface, onSurfaceVariant = c.textMuted,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = c.background, surfaceContainerLow = c.surface,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface,
        surfaceBright = c.surface, surfaceDim = c.background,
        inverseSurface = c.text, inverseOnSurface = c.background, inversePrimary = c.accent,
        outline = c.hairline, outlineVariant = c.hairline,
        error = c.danger, onError = c.background,
        scrim = Color.Black.copy(alpha = 0.5f),
    )
    val t = PurposeType()
    val typography = Typography(
        bodyLarge = t.userBody, bodyMedium = t.userBody, bodySmall = t.meta,
        labelLarge = t.label, labelMedium = t.label, labelSmall = t.meta,
        titleLarge = t.heading, titleMedium = t.itemText, titleSmall = t.label,
        headlineSmall = t.heading, headlineMedium = t.screenTitle, headlineLarge = t.screenTitle,
    )
    CompositionLocalProvider(
        LocalPurposeColors provides c,
        LocalPurposeType provides t,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
