package app.stratasearch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.stratasearch.ble.LinkState
import app.stratasearch.data.UiState
import app.stratasearch.protocol.Environment
import java.util.Locale

fun formatMeters(m: Double): String =
    if (m < 10) String.format(Locale.US, "%.1f", m).replace('.', ',') else String.format(Locale.US, "%.0f", m)

fun formatAge(ms: Long): String = when {
    ms < 1500 -> "только что"
    ms < 60_000 -> "${ms / 1000} с назад"
    ms < 3_600_000 -> "${ms / 60_000} мин назад"
    else -> "давно"
}

fun freshnessColor(ageMs: Long): Color = when {
    ageMs < 5_000 -> Palette.Good
    ageMs < 30_000 -> Palette.Amber
    else -> Palette.Idle
}

/** Доля 0..1 для шкалы сигнала: −100 дБм → 0, −35 дБм → 1. */
fun signalFraction(rssi: Double): Float = ((rssi + 100.0) / 65.0).coerceIn(0.0, 1.0).toFloat()

@Composable
fun Dot(color: Color, size: Int = 12) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** Строка состояния связи — видна на всех вкладках. */
@Composable
fun StatusStrip(st: UiState, modifier: Modifier = Modifier) {
    val (color, text) = when {
        st.demo -> Palette.Amber to "ДЕМО-РЕЖИМ — данные не настоящие"
        else -> when (val l = st.link) {
            LinkState.Idle -> Palette.Idle to "Приёмник не подключён"
            is LinkState.Connecting -> Palette.Amber to "Подключение к ${l.name}…"
            is LinkState.Connected -> Palette.Good to "Подключено: ${l.name}"
            is LinkState.Reconnecting -> Palette.Bad to "Связь потеряна, переподключение (${l.attempt})…"
            is LinkState.Failed -> Palette.Bad to l.message
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(color)
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
            val gw = st.gateway
            if (st.receiving && gw != null) {
                val age = st.now - gw.lastSeen
                Text(
                    "Шлюз: пакет №${gw.lastSeq}, ${formatAge(age)} · меток: ${st.tags.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (age > 10_000) Palette.Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentSelector(selected: Environment, onSelect: (Environment) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Environment.entries.forEach { env ->
            FilterChip(
                selected = env == selected,
                onClick = { onSelect(env) },
                label = { Text(env.title, modifier = Modifier.padding(vertical = 8.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
fun SignalBar(rssi: Double, modifier: Modifier = Modifier, height: Int = 14) {
    val f = signalFraction(rssi)
    val color = when {
        f > 0.66f -> Palette.Good
        f > 0.33f -> Palette.Amber
        else -> Palette.Bad
    }
    Box(
        modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(50)).background(Palette.SurfaceHigh)
    ) {
        Box(Modifier.fillMaxWidth(f).height(height.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

/** График RSSI за последние [windowMs]. */
@Composable
fun RssiChart(history: List<Pair<Long, Int>>, now: Long, modifier: Modifier = Modifier, windowMs: Long = 60_000) {
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val minR = -100f
        val maxR = -35f
        fun y(r: Float) = size.height * (1f - ((r - minR) / (maxR - minR)).coerceIn(0f, 1f))
        fun x(t: Long) = size.width * (1f - (now - t).toFloat() / windowMs)

        listOf(-90f, -70f, -50f).forEach { r ->
            drawLine(Palette.Outline, Offset(0f, y(r)), Offset(size.width, y(r)), strokeWidth = 1f)
        }
        val pts = history.filter { now - it.first <= windowMs }
        if (pts.size >= 2) {
            val path = Path()
            pts.forEachIndexed { i, (t, r) ->
                if (i == 0) path.moveTo(x(t), y(r.toFloat())) else path.lineTo(x(t), y(r.toFloat()))
            }
            drawPath(path, Palette.Amber, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }
        pts.lastOrNull()?.let { (t, r) -> drawCircle(Palette.Amber, 9f, Offset(x(t), y(r.toFloat()))) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}
