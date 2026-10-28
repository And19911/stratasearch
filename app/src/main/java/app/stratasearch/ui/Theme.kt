package app.stratasearch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Тёмная контрастная тема: хорошо читается в шахте и не слепит. */
object Palette {
    val Bg = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val SurfaceHigh = Color(0xFF212733)
    val Outline = Color(0xFF39404D)
    val Text = Color(0xFFECEFF4)
    val TextDim = Color(0xFF9AA3B2)
    val Amber = Color(0xFFFFB300)
    val Good = Color(0xFF43D17A)
    val Bad = Color(0xFFFF5A5A)
    val Idle = Color(0xFF5D6470)
}

private val scheme = darkColorScheme(
    primary = Palette.Amber,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3D2E00),
    onPrimaryContainer = Palette.Amber,
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
