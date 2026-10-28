package app.stratasearch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stratasearch.data.MainViewModel
import app.stratasearch.data.TagUi
import app.stratasearch.data.UiState
import app.stratasearch.protocol.DistanceModel
import kotlin.math.roundToInt

/** Экран поиска одной метки: крупное расстояние, «ближе/дальше», писк, график. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetScreen(t: TagUi, st: UiState, vm: MainViewModel) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var calibrating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Метка ${t.tag}") },
                navigationIcon = {
                    IconButton(onClick = { vm.select(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    IconButton(onClick = { vm.setSound(!st.sound) }) {
                        Icon(
                            if (st.sound) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            if (st.sound) "Выключить звук" else "Включить звук",
                            tint = if (st.sound) Palette.Amber else Palette.Idle,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { pad ->
        val age = st.now - t.lastSeen
        val stale = age > MainViewModel.STALE_MS
        val est = DistanceModel.estimate(t.smoothedRssi, st.environment, st.calibrationFor(st.environment))

        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusStrip(st)

            if (stale) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1414))) {
                    Text(
                        "Сигнала нет ${formatAge(age).removeSuffix(" назад")}. Показано последнее значение.",
                        color = Palette.Bad,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // Расстояние
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("примерно", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatMeters(est.meters),
                        style = HugeNumber,
                        color = if (stale) Palette.Idle else Palette.Amber,
                    )
                    Text(" м", fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
                }
                Text(
                    "от ${formatMeters(est.minMeters)} до ${formatMeters(est.maxMeters)} м" +
                        if (est.fromCalibration) " · по калибровке" else " · по формуле",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Trend(t.slope, stale)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Text("Сигнал", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${t.smoothedRssi.roundToInt()} дБм", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    SignalBar(t.smoothedRssi, height = 18)
                    Spacer(Modifier.height(14.dp))
                    Text("Последняя минута", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RssiChart(t.history, st.now)
                }
            }

            SectionTitle("Условия")
            EnvironmentSelector(st.environment, vm::setEnvironment)

            OutlinedButton(onClick = { calibrating = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Straighten, null)
                Spacer(Modifier.width(8.dp))
                Text("Калибровка: я знаю точное расстояние")
            }
            Text(
                "Приёмник ${t.receiver} · ${formatAge(age)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (calibrating) {
        CalibrationDialog(
            rssi = t.smoothedRssi.roundToInt(),
            envTitle = st.environment.title,
            onSave = { m -> vm.addCalibrationPoint(st.environment, t.smoothedRssi.roundToInt(), m); calibrating = false },
            onDismiss = { calibrating = false },
        )
    }
}

@Composable
private fun Trend(slope: Double?, stale: Boolean) {
    val (icon, text, color) = when {
        stale || slope == null -> Triple(Icons.Default.Remove, "Ждём данных…", Palette.Idle)
        slope > 0.3 -> Triple(Icons.Default.ArrowUpward, "БЛИЖЕ", Palette.Good)
        slope < -0.3 -> Triple(Icons.Default.ArrowDownward, "ДАЛЬШЕ", Palette.Bad)
        else -> Triple(Icons.Default.Remove, "Без изменений", Palette.TextDim)
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
    ) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(40.dp), tint = color)
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 30.sp, fontWeight = FontWeight.Black, color = color)
        }
    }
}

@Composable
fun CalibrationDialog(rssi: Int?, envTitle: String, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val meters = text.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Точка калибровки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Режим: $envTitle")
                if (rssi != null) Text("Текущий сигнал: $rssi дБм")
                Text("Измерьте рулеткой расстояние от антенны до метки и введите его:")
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
            TextButton(onClick = { meters?.let(onSave) }, enabled = meters != null) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
