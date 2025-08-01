package org.operatorfoundation.signalbridge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = WSPRPrimary,
    secondary = WSPRSecondary,
    tertiary = WSPRAccent,
    error = WSPRError
)

private val LightColorScheme = lightColorScheme(
    primary = WSPRPrimary,
    secondary = WSPRSecondary,
    tertiary = WSPRAccent,
    error = WSPRError
)

@Composable
fun SignalBridgeDemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when
    {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * WSPR-specific colors for use outside of Material theme
 */
object WSPRColors
{
    val primary = WSPRPrimary
    val secondary = WSPRSecondary    // Teal
    val accent = WSPRAccent       // Amber
    val success = WSPRSuccess      // Green
    val warning = WSPRWarning      // Orange
    val error = WSPRError        // Red
    val background = Color(0xFFFAFAFA)   // Light gray
    val surface = Color(0xFFFFFFFF)      // White
    val surfaceVariant = Color(0xFFF5F5F5) // Very light gray
    val onSurface = Color(0xFF1C1B1F)    // Dark gray
    val onSurfaceVariant = Color(0xFF49454F) // Medium gray
}