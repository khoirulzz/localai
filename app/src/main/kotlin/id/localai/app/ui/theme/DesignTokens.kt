package id.localai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class LocalAiColors(
    val background: Color,
    val foreground: Color,
    val secondaryText: Color,
    val userBubble: Color,
    val inputSurface: Color,
    val hairline: Color,
    val interactive: Color,
    val interactiveContent: Color,
    val searchActiveSurface: Color,
    val searchActiveText: Color,
    val isDark: Boolean
)

val LightColors = LocalAiColors(
    background = Color(0xFFFFFFFF),
    foreground = Color(0xFF171717),
    secondaryText = Color(0xFF767676),
    userBubble = Color(0xFFF2F2F2),
    inputSurface = Color(0xFFF4F4F4),
    hairline = Color(0xFFE8E8E8),
    interactive = Color(0xFF171717),
    interactiveContent = Color(0xFFFFFFFF),
    searchActiveSurface = Color(0xFF171717),
    searchActiveText = Color(0xFFFFFFFF),
    isDark = false
)

val DarkColors = LocalAiColors(
    background = Color(0xFF101010),
    foreground = Color(0xFFF5F5F5),
    secondaryText = Color(0xFFA4A4A4),
    userBubble = Color(0xFF282828),
    inputSurface = Color(0xFF242424),
    hairline = Color(0xFF343434),
    interactive = Color(0xFFF0F0F0),
    interactiveContent = Color(0xFF101010),
    searchActiveSurface = Color(0xFFF0F0F0),
    searchActiveText = Color(0xFF101010),
    isDark = true
)

object LocalAiTypography {
    val headerModel = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 22.sp
    )
    val assistantBody = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp
    )
    val userBody = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 23.sp
    )
    val composerInput = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 23.sp
    )
    val metadata = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp
    )
    val codeBlock = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp
    )
}

object LocalAiDimens {
    val TopBarHeight = 56.dp
    val HorizontalMargin = 16.dp
    val ComposerSideMargin = 12.dp
    val ComposerRadius = 24.dp
    val UserBubbleRadius = 19.dp
    val MinTouchTarget = 48.dp
    val SendVisualSize = 36.dp
}

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

val LocalAppColors = staticCompositionLocalOf { LightColors }

@Composable
fun resolveColors(mode: ThemeMode): LocalAiColors {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    return if (dark) DarkColors else LightColors
}
