package app.stratasearch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Палитра в стиле детектора движения из «Чужих»: зелёный люминофор на чёрном. */
object Palette {
    val Bg = Color(0xFF040906)
    val Surface = Color(0xFF0B1610)
    val SurfaceHigh = Color(0xFF122419)
    val Outline = Color(0xFF1E3A2A)
    val Text = Color(0xFFD9FBE6)
    val TextDim = Color(0xFF7FA893)
    val Accent = Color(0xFF3DFF9C)
    val AccentDim = Color(0xFF1B7A4A)
    val Warn = Color(0xFFFFB300)
    val Good = Color(0xFF3DFF9C)
    val Bad = Color(0xFFFF4D4D)
    val Idle = Color(0xFF4A5A52)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0F3A24),
    onPrimaryContainer = Palette.Accent,
    secondary = Palette.Good,
    onSecondary = Color.Black,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.Outline,
    error = Palette.Bad,
)

private val typography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp),
        labelLarge = t.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    )
}

val HugeNumber = TextStyle(fontSize = 112.sp, fontWeight = FontWeight.Black, lineHeight = 112.sp)

@Composable
fun StrataTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
