package com.botillon.flowscribe.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0B0B12)
val InkElevated = Color(0xFF12121C)
val Violet = Color(0xFFC4B5FD)
val VioletDeep = Color(0xFF7C5CFF)
val Mint = Color(0xFF7DE2D1)
val Coral = Color(0xFFFF9E8A)
val Sand = Color(0xFFF3E9D8)

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color(0xFF21154D),
    primaryContainer = Color(0xFF3A2A78),
    onPrimaryContainer = Color(0xFFE8E0FF),
    secondary = Mint,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0F4D43),
    onSecondaryContainer = Color(0xFFB8FFF0),
    tertiary = Coral,
    onTertiary = Color(0xFF4A160C),
    background = Ink,
    onBackground = Color(0xFFEDEAF5),
    surface = InkElevated,
    onSurface = Color(0xFFEDEAF5),
    surfaceVariant = Color(0xFF232233),
    onSurfaceVariant = Color(0xFFB9B5C9),
    outline = Color(0xFF49465C),
    outlineVariant = Color(0xFF2C2A3C),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0906),
    errorContainer = Color(0xFF5C1A14),
    onErrorContainer = Color(0xFFFFDAD5),
    inverseSurface = Color(0xFFEDEAF5),
    inverseOnSurface = Color(0xFF1B1926),
    inversePrimary = Color(0xFF6547D8),
    surfaceTint = Violet,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A2838),
    surfaceDim = Color(0xFF0E0D15),
    surfaceContainerLowest = Color(0xFF08080D),
    surfaceContainerLow = Color(0xFF111019),
    surfaceContainer = Color(0xFF16151F),
    surfaceContainerHigh = Color(0xFF1D1B28),
    surfaceContainerHighest = Color(0xFF252331),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF1D0B57),
    secondary = Color(0xFF00695E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F5E9),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF9C4232),
    onTertiary = Color.White,
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1A1726),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1A1726),
    surfaceVariant = Color(0xFFE6E1F0),
    onSurfaceVariant = Color(0xFF484552),
    outline = Color(0xFF7A7787),
    outlineVariant = Color(0xFFCBC6D6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F2FB),
    surfaceContainer = Color(0xFFEFEBF7),
    surfaceContainerHigh = Color(0xFFE9E5F2),
    surfaceContainerHighest = Color(0xFFE3DFED),
)

private val FlowTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun JoynixScribeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = FlowTypography,
        content = content,
    )
}
