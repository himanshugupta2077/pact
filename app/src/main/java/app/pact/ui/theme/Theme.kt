package app.pact.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF0E0D0B)
val InkElevated = Color(0xFF1C1A16)
val InkLine = Color(0xFF6B6458)
val Paper = Color(0xFFF4EFE6)
val PaperMuted = Color(0xFFC9C1B4)
val Rust = Color(0xFFC45C26)
val Sage = Color(0xFF8FA382)
val Danger = Color(0xFFC4543A)

private val DarkColors = darkColorScheme(
    primary = Rust,
    onPrimary = Paper,
    primaryContainer = Color(0xFF3A2418),
    onPrimaryContainer = Paper,
    secondary = Sage,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF243024),
    onSecondaryContainer = Paper,
    tertiary = PaperMuted,
    onTertiary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = InkElevated,
    onSurface = Paper,
    surfaceVariant = Color(0xFF2A2620),
    onSurfaceVariant = PaperMuted,
    surfaceContainerLowest = Color(0xFF0A0908),
    surfaceContainerLow = Color(0xFF161410),
    surfaceContainer = InkElevated,
    surfaceContainerHigh = Color(0xFF26231E),
    surfaceContainerHighest = Color(0xFF302C26),
    outline = InkLine,
    outlineVariant = Color(0xFF3A352E),
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    inversePrimary = Rust,
    error = Danger,
    onError = Paper,
    errorContainer = Color(0xFF3A1510),
    onErrorContainer = Color(0xFFFFDAD4),
    scrim = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = Rust,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3D5C4),
    onPrimaryContainer = Ink,
    secondary = Sage,
    onSecondary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFECE6DA),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE0D8CB),
    onSurfaceVariant = Color(0xFF5C564C),
    outline = Color(0xFF8A8276),
    outlineVariant = Color(0xFFD4CBBC),
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    error = Danger,
    onError = Color.White,
)

private val Typography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.5).sp,
        color = Color.Unspecified,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 38.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
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
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun PactTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.onBackground,
            content = content,
        )
    }
}

@Composable
fun pactFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = scheme.onBackground,
        unfocusedTextColor = scheme.onBackground,
        disabledTextColor = scheme.onSurfaceVariant,
        focusedContainerColor = scheme.surface,
        unfocusedContainerColor = scheme.surface,
        focusedPlaceholderColor = scheme.onSurfaceVariant,
        unfocusedPlaceholderColor = scheme.onSurfaceVariant,
        focusedLabelColor = scheme.onSurfaceVariant,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        cursorColor = Rust,
        focusedBorderColor = Rust,
        unfocusedBorderColor = scheme.outline,
        focusedLeadingIconColor = scheme.onSurfaceVariant,
        unfocusedLeadingIconColor = scheme.onSurfaceVariant,
        focusedTrailingIconColor = scheme.onSurfaceVariant,
        unfocusedTrailingIconColor = scheme.onSurfaceVariant,
    )
}
