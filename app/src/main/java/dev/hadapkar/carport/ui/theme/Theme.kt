package dev.hadapkar.carport.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * CarPort's theme. Material You colours from the wallpaper in both styles. [expressive] applies
 * the parts of Material 3 Expressive that stable Material 3 (1.4) exposes publicly: rounder,
 * larger shapes and bolder headings. (Expressive motion is still internal to the library.)
 * Classic is the plain Material 3 look, kept as the fallback.
 */
@Composable
fun CarPortTheme(expressive: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // minSdk 35, so Material You dynamic colour is always available.
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    if (expressive) {
        MaterialTheme(colorScheme = colors, shapes = ExpressiveShapes, typography = ExpressiveTypography, content = content)
    } else {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/** Rounder, larger corners, as in Material 3 Expressive. */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Default type scale with bolder headings and titles. */
private val ExpressiveTypography: Typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}
