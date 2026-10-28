package app.stratasearch.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stratasearch.data.MainViewModel
import app.stratasearch.data.TagUi
import app.stratasearch.data.UiState
import app.stratasearch.protocol.DistanceModel
import app.stratasearch.protocol.RelativeDirection
import app.stratasearch.protocol.normalizeDeg
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Половина угла веера трекера, градусы. */
private const val FAN_HALF = 60f

private fun glow(color: Color, size: Int, weight: FontWeight = FontWeight.Bold) = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = weight,
    fontSize = size.sp,
    color = color,
    shadow = Shadow(color = color.copy(alpha = 0.8f), blurRadius = 18f),
)

/** Экран поиска метки в стиле детектора движения из «Чужих». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetScreen(t: TagUi, st: UiState, vm: MainViewModel) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var calibrating by remember { mutableStateOf(false) }

    // Волна импульса: запускается на каждый пинг.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(st.pingTick) {
        if (st.pingTick > 0) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(durationMillis = 900, easing = LinearEasing))
        }
    }

    val age = st.now - t.lastSeen
    val stale = t.isStale(st.now)
    val est = DistanceModel.estimate(t.smoothedRssi, st.environment, st.calibrationFor(st.environment))
    val relative = if (st.bearing != null && st.heading != null) normalizeDeg(st.bearing.bearingDeg - st.heading) else null

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("МЕТКА ${t.tag}", style = glow(Palette.Accent, 22)) },
                navigationIcon = {
                    IconButton(onClick = { vm.select(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Palette.Accent)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.setSound(!st.sound) }) {
                        Icon(
                            if (st.sound) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            if (st.sound) "Выключить звук" else "Включить звук",
                            tint = if (st.sound) Palette.Accent else Palette.Idle,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- Экран трекера ----
            Box(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .border(2.dp, Palette.AccentDim, RoundedCornerShape(18.dp))
                    .background(Color.Black, RoundedCornerShape(18.dp))
                    .padding(6.dp),
            ) {
                TrackerDisplay(
                    meters = est.meters,
                    relativeDeg = relative,
                    pulse = pulse.value,
                    stale = stale,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    "ПРИЁМНИК ${t.receiver}",
                    style = glow(Palette.AccentDim, 12, FontWeight.Normal),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
                Text(
                    "${t.smoothedRssi.roundToInt()} dBm",
                    style = glow(Palette.AccentDim, 12, FontWeight.Normal),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }

            // ---- Табло: зона крупно, метры — ориентировочно ----
            val main = if (stale) Palette.Idle else Palette.Accent
            Text(est.zone.title, style = glow(main, 64, FontWeight.Black), textAlign = TextAlign.Center)
            Text("≈ ${formatMeters(est.meters)} M", style = glow(main, 34))
            Text(
                "скорее всего от ${formatMeters(est.minMeters)} до ${formatMeters(est.maxMeters)} м · " +
                    if (est.fromCalibration) "по калибровке" else "по заводской формуле",
                style = glow(Palette.TextDim, 14, FontWeight.Normal),
                textAlign = TextAlign.Center,
            )
            NextSignal(t, st.now)

            if (stale) {
                Text(
                    "НЕТ СИГНАЛА ${formatAge(age).removeSuffix(" назад").uppercase()}",
                    style = glow(Palette.Bad, 20),
                    textAlign = TextAlign.Center,
                )
            }

            DirectionPanel(st, relative, onReset = vm::resetBearing)
            TrendPanel(t.trendDb, stale)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Text("СИГНАЛ", Modifier.weight(1f), style = glow(Palette.TextDim, 14, FontWeight.Normal))
                        Text("${t.smoothedRssi.roundToInt()} дБм", style = glow(Palette.Accent, 16))
                    }
                    Spacer(Modifier.height(10.dp))
                    SignalBar(t.smoothedRssi, height = 16)
                    Spacer(Modifier.height(12.dp))
                    RssiChart(t.history, st.now)
                }
            }

            SectionTitle("Условия", Modifier.fillMaxWidth())
            EnvironmentSelector(st.environment, vm::setEnvironment, Modifier.fillMaxWidth())

            OutlinedButton(onClick = { calibrating = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Straighten, null)
                Spacer(Modifier.width(8.dp))
                Text("Калибровка: я на известном расстоянии")
            }
            if (st.calibrationFor(st.environment).isNotEmpty()) {
                Text(
                    "Точек калибровки в режиме «${st.environment.title}»: ${st.calibrationFor(st.environment).size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    val session = st.calibrationSession
    if (session != null) {
        CalibrationProgressDialog(session, t, st.now, onSaveNow = vm::finishCalibration, onCancel = vm::cancelCalibration)
    } else if (calibrating) {
        CalibrationDialog(
            rssi = null,
            envTitle = st.environment.title,
            onSave = { m -> vm.startCalibration(m); calibrating = false },
            onDismiss = { calibrating = false },
            confirmText = "Начать",
        )
    }
}

/**
 * Веер трекера: вершина внизу по центру, вверх — куда смотрит антенна.
 * Дуги — дальность, отметка — метка. Пока направление неизвестно, метка рисуется дугой
 * на нужной дальности по всему вееру.
 */
@Composable
private fun TrackerDisplay(meters: Double, relativeDeg: Double?, pulse: Float, stale: Boolean, modifier: Modifier) {
    val range = remember(meters) { listOf(5.0, 10.0, 20.0, 40.0, 80.0, 160.0).firstOrNull { meters <= it * 0.9 } ?: 320.0 }
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            color = Palette.Accent.toArgb()
        }
    }
    Canvas(modifier) {
        val apex = Offset(size.width / 2, size.height * 0.96f)
        val radius = minOf(size.height * 0.9f, size.width / 2 / sin(Math.toRadians(FAN_HALF.toDouble())).toFloat() * 0.98f)
        val start = -90f - FAN_HALF
        val sweep = FAN_HALF * 2
        val main = if (stale) Palette.Idle else Palette.Accent

        // Фон веера с лёгким свечением.
        drawArc(
            brush = Brush.radialGradient(
                listOf(Palette.AccentDim.copy(alpha = 0.35f), Color.Transparent),
                center = apex, radius = radius,
            ),
            startAngle = start, sweepAngle = sweep, useCenter = true,
            topLeft = apex - Offset(radius, radius), size = Size(radius * 2, radius * 2),
        )

        // Дуги дальности.
        labelPaint.textSize = 13.dp.toPx()
        labelPaint.color = Palette.AccentDim.toArgb()
        for (k in 1..4) {
            val r = radius * k / 4f
            drawArc(
                Palette.AccentDim, start, sweep, false,
                topLeft = apex - Offset(r, r), size = Size(r * 2, r * 2),
                style = Stroke(width = if (k == 4) 3f else 1.5f),
            )
            val label = "${formatMeters(range * k / 4)}m"
            val a = Math.toRadians(FAN_HALF.toDouble())
            drawContext.canvas.nativeCanvas.drawText(
                label, apex.x + r * sin(a).toFloat() + 6f, apex.y - r * cos(a).toFloat(), labelPaint,
            )
        }
        // Лучи.
        for (deg in -60..60 step 20) {
            val end = polar(apex, radius, deg.toDouble())
            drawLine(Palette.AccentDim.copy(alpha = if (deg == 0) 0.9f else 0.45f), apex, end, strokeWidth = if (deg == 0) 2f else 1f)
        }

        // Волна импульса.
        if (pulse < 1f && !stale) {
            val r = radius * pulse
            drawArc(
                main.copy(alpha = (1f - pulse) * 0.9f), start, sweep, false,
                topLeft = apex - Offset(r, r), size = Size(r * 2, r * 2),
                style = Stroke(width = 6f, cap = StrokeCap.Round),
            )
        }

        // Метка.
        val rTarget = radius * (meters / range).toFloat().coerceIn(0.03f, 1f)
        // Отметка ярче сразу после того, как через неё прошла волна.
        val passed = pulse * radius >= rTarget
        val bright = if (stale) 0.35f else if (passed) 0.4f + 0.6f * (1f - pulse) else 0.4f
        if (relativeDeg == null) {
            drawArc(
                main.copy(alpha = bright * 0.8f), start, sweep, false,
                topLeft = apex - Offset(rTarget, rTarget), size = Size(rTarget * 2, rTarget * 2),
                style = Stroke(width = 14f, cap = StrokeCap.Round),
            )
        } else {
            val shown = relativeDeg.coerceIn(-FAN_HALF.toDouble(), FAN_HALF.toDouble())
            val behind = kotlin.math.abs(relativeDeg) > 135
            val p = if (behind) Offset(apex.x, apex.y - 4f) else polar(apex, rTarget, shown)
            blip(p, main, bright)
        }

        // Строчная развёртка, как на ЭЛТ.
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += 4.dp.toPx()
        }
    }
}

private fun polar(apex: Offset, r: Float, deg: Double): Offset {
    val a = Math.toRadians(deg)
    return Offset(apex.x + r * sin(a).toFloat(), apex.y - r * cos(a).toFloat())
}

private fun DrawScope.blip(p: Offset, color: Color, alpha: Float) {
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = p, radius = 46f),
        radius = 46f, center = p,
    )
    drawCircle(Color.White.copy(alpha = alpha), radius = 11f, center = p)
}

@Composable
private fun DirectionPanel(st: UiState, relative: Double?, onReset: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                !st.compassAvailable -> Text(
                    "В планшете нет компаса — направление определить нельзя, работает только дальность.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                relative != null -> {
                    val dir = RelativeDirection.of(relative)
                    val arrow = when (dir) {
                        RelativeDirection.AHEAD -> "▲"
                        RelativeDirection.RIGHT -> "▶"
                        RelativeDirection.LEFT -> "◀"
                        RelativeDirection.BEHIND -> "▼"
                    }
                    Text("ЦЕЛЬ", style = glow(Palette.TextDim, 14, FontWeight.Normal))
                    Text("$arrow ${dir.title}", style = glow(Palette.Accent, 40, FontWeight.Black))
                    Text(
                        "поверните на ${kotlin.math.abs(relative).roundToInt()}° " + if (relative >= 0) "вправо" else "влево",
                        style = glow(Palette.TextDim, 14, FontWeight.Normal),
                    )
                    TextButton(onClick = onReset) { Text("Новый пеленг (сменил место)") }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Explore, null, Modifier.size(28.dp), tint = Palette.Warn)
                        Spacer(Modifier.width(10.dp))
                        Text("НАПРАВЛЕНИЕ НЕИЗВЕСТНО", style = glow(Palette.Warn, 18))
                    }
                    Spacer(Modifier.height(6.dp))
                    val interval = st.selected?.intervalMs
                    val rare = interval != null && interval >= 10_000
                    Text(
                        if (rare)
                            "Сигнал редкий, поэтому по шагам. Держите планшет вместе с антенной. " +
                                "Направьте её, дождитесь сигнала, повернитесь на 90° и снова дождитесь. " +
                                "Нужно 4 стороны (≈${formatInterval(interval!! * 4)})."
                        else
                            "Держите планшет вместе с антенной и медленно повернитесь на месте на полный круг " +
                                "(около 30 секунд). Приложение запомнит, где сигнал сильнее.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (rare) {
                        Text(
                            "Замеров с разных сторон: ${(st.bearingCoverage * 8).roundToInt()} из 4",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // Прогресс: нужно «прослушать» хотя бы половину круга.
                    val progress = (st.bearingCoverage / 0.5).coerceIn(0.0, 1.0)
                    Box(Modifier.fillMaxWidth().height(10.dp).background(Palette.SurfaceHigh, RoundedCornerShape(50))) {
                        Box(
                            Modifier.fillMaxWidth(progress.toFloat()).height(10.dp)
                                .background(Palette.Accent, RoundedCornerShape(50))
                        )
                    }
                    Text(
                        if (progress >= 1.0) "Обзор набран, но сигнал со всех сторон одинаковый — продолжайте поворот"
                        else "обзор: ${(st.bearingCoverage * 360).roundToInt()}°",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendPanel(trendDb: Double?, stale: Boolean) {
    // Порог 3 дБ: меньшие изменения — обычный разброс сигнала, а не движение.
    val (text, color) = when {
        stale -> "· · ·" to Palette.Idle
        trendDb == null -> "ЖДЁМ 2-Й СИГНАЛ" to Palette.Idle
        trendDb >= 3 -> "▲ БЛИЖЕ  +${trendDb.roundToInt()} дБ" to Palette.Good
        trendDb <= -3 -> "▼ ДАЛЬШЕ  ${trendDb.roundToInt()} дБ" to Palette.Bad
        else -> "= БЕЗ ИЗМЕНЕНИЙ" to Palette.TextDim
    }
    Box(
        Modifier.fillMaxWidth().border(2.dp, color.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = glow(color, 26, FontWeight.Black))
    }
}

@Composable
fun CalibrationDialog(
    rssi: Int?,
    envTitle: String,
    onSave: (Double) -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Сохранить",
) {
    var text by remember { mutableStateOf("") }
    val meters = text.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Точка калибровки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Режим: $envTitle")
                if (rssi != null) Text("Сигнал: $rssi дБм")
                Text(
                    if (rssi == null)
                        "Встаньте на известном расстоянии от лампы (измерьте рулеткой), направьте антенну " +
                            "точно на лампу и не двигайтесь. Приложение дождётся 3 новых сигналов и усреднит их."
                    else "Измерьте рулеткой расстояние от антенны до метки и введите его:"
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Расстояние, м") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { meters?.let(onSave) }, enabled = meters != null) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Когда ждать следующий сигнал лампы — при редком сигнале это главное, что нужно знать. */
@Composable
private fun NextSignal(t: TagUi, now: Long) {
    val age = now - t.lastSeen
    val interval = t.intervalMs
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Text("Сигнал ${formatAge(age)}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (interval != null && interval >= 3_000) {
                    Text("лампа: раз в ~${formatInterval(interval)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (interval != null && interval >= 3_000) {
                val left = interval - age
                Spacer(Modifier.height(8.dp))
                val f = (age.toFloat() / interval).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(8.dp).background(Palette.SurfaceHigh, RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxWidth(f).height(8.dp).background(Palette.AccentDim, RoundedCornerShape(50)))
                }
                Text(
                    if (left > 0) "следующий через ~${formatInterval(left)} — не двигайтесь, если ждёте замер"
                    else "сигнал задерживается…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun formatInterval(ms: Long): String = if (ms < 90_000) "${(ms + 500) / 1000} с" else "${(ms + 30_000) / 60_000} мин"

@Composable
private fun CalibrationProgressDialog(
    session: app.stratasearch.data.CalibrationSession,
    t: TagUi,
    now: Long,
    onSaveNow: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Калибровка: ${formatMeters(session.meters)} м") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Не двигайтесь, антенна направлена на лампу.")
                Text(
                    "Принято сигналов: ${session.samples.size} из ${session.target}",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (session.samples.isNotEmpty()) {
                    Text("Замеры: ${session.samples.joinToString(", ")} дБм", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val waited = now - session.startedAt
                val interval = t.intervalMs
                Text(
                    "Ждём ${formatInterval(waited)}" +
                        (if (interval != null && interval >= 3_000) ", лампа передаёт раз в ~${formatInterval(interval)}" else ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSaveNow, enabled = session.samples.isNotEmpty()) {
                Text("Сохранить по ${session.samples.size}")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
