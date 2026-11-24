package com.traccar.nativeapp.ui.compose.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

/** 五档字号，移植自 Jiumi（Apache-2.0）。 */
fun jiumiTypography(isDark: Boolean): Typography {
    val medium = FontWeight(if (isDark) 480 else 500)
    val semibold = FontWeight(if (isDark) 580 else 600)
    val page = TextStyle(fontWeight = semibold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp, lineBreak = LineBreak.Heading)
    val large = TextStyle(fontWeight = semibold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp, lineBreak = LineBreak.Heading)
    val title = TextStyle(fontWeight = semibold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
    val body = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
    val small = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum")
    val smallStrong = small.copy(fontWeight = medium)
    val caption = TextStyle(fontWeight = medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum")
    return Typography(
        displayLarge = large, displayMedium = large, displaySmall = large,
        headlineLarge = page, headlineMedium = large, headlineSmall = large,
        titleLarge = large, titleMedium = title, titleSmall = title,
        bodyLarge = body, bodyMedium = body, bodySmall = small,
        labelLarge = smallStrong, labelMedium = caption, labelSmall = caption
    )
}

/** 三档墨色 + 填充色，移植自 Jiumi（Apache-2.0）。 */
object Ink {
    val primary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface
    val secondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val tertiary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val fill: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val track: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)
}
