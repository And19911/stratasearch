package app.stratasearch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.stratasearch.data.MainViewModel
import app.stratasearch.data.UiState
import app.stratasearch.protocol.DistanceModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(st: UiState, vm: MainViewModel) {
    val env = st.environment
    val points = st.calibrationFor(env)
    var addManual by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Звуковой поиск", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "На экране метки писк учащается по мере приближения",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = st.sound, onCheckedChange = vm::setSound)
                }
            }
        }

        item { SectionTitle("Калибровка расстояния") }
        item { EnvironmentSelector(env, vm::setEnvironment) }
        item {
            val m = DistanceModel.fit(env, points)
            Text(
                when {
                    points.isEmpty() ->
                        "Сейчас — заводская формула. Чтобы расстояние было точнее для ваших ламп, сделайте 3–5 точек " +
                            "на разных расстояниях (например 2, 5, 10, 20, 40 м): откройте метку, встаньте на " +
                            "расстоянии, направьте антенну на лампу и нажмите «Калибровка»."
                    points.map { it.meters }.distinct().size < 2 ->
                        "Одна дистанция: уровень сигнала подстроен, наклон кривой — заводской. " +
                            "Добавьте точки на других расстояниях."
                    else ->
                        "Кривая подобрана по вашим точкам. Неудачные точки (отклонение больше 6 дБ) " +
                            "помечены красным — их лучше удалить и переснять."
                } + "\nМодель: ${m.a.roundToInt()} дБм на 1 м, затухание n = ${"%.1f".format(m.n)}, разброс ±${m.sigmaDb.roundToInt()} дБ.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (points.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    val model = DistanceModel.fit(env, points)
                    points.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider()
                        // Насколько точка отличается от подобранной кривой.
                        val dev = p.rssi - model.rssiAt(p.meters)
                        val bad = kotlin.math.abs(dev) > 6 && points.size >= 3
                        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${formatMeters(p.meters)} м", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("${p.rssi} дБм", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text(
                                (if (dev >= 0) "+" else "") + "${dev.roundToInt()} дБ",
                                Modifier.weight(0.8f),
                                color = if (bad) Palette.Bad else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = { vm.removeCalibrationPoint(env, p) }) {
                                Icon(Icons.Default.Delete, "Удалить", tint = Palette.Bad)
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = { addManual = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Text("  Добавить точку вручную")
            }
        }
        if (points.isNotEmpty()) item {
            TextButton(onClick = { vm.clearCalibration(env) }, modifier = Modifier.fillMaxWidth()) {
                Text("Сбросить калибровку режима", color = Palette.Bad)
            }
        }

        item { SectionTitle("Справка: формула режима") }
        items(listOf(-50, -60, -70, -80, -90)) { r ->
            val f = DistanceModel.estimate(r.toDouble(), env)
            val c = DistanceModel.estimate(r.toDouble(), env, points)
            Row(Modifier.padding(horizontal = 8.dp)) {
                Text("$r дБм", Modifier.weight(1f))
                Text("формула ${formatMeters(f.meters)} м", Modifier.weight(1.4f))
                if (c.fromCalibration) Text("калибр. ${formatMeters(c.meters)} м", Modifier.weight(1.4f), color = Palette.Accent)
            }
        }
    }

    if (addManual) {
        ManualPointDialog(
            envTitle = env.title,
            onSave = { rssi, m -> vm.addCalibrationPoint(env, rssi, m); addManual = false },
            onDismiss = { addManual = false },
        )
    }
}

@Composable
private fun ManualPointDialog(envTitle: String, onSave: (Int, Double) -> Unit, onDismiss: () -> Unit) {
    var rssiText by remember { mutableStateOf("") }
    var metersText by remember { mutableStateOf("") }
    // Знак минус на планшетной клавиатуре неудобен — RSSI всегда отрицательный.
    val rssi = rssiText.trim().removePrefix("-").removePrefix("−").toIntOrNull()?.let { -it }
    val meters = metersText.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Точка калибровки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Режим: $envTitle")
                OutlinedTextField(
                    value = rssiText,
                    onValueChange = { rssiText = it },
                    label = { Text("Сигнал, дБм (минус можно не ставить)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = metersText,
                    onValueChange = { metersText = it },
                    label = { Text("Расстояние, м") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (rssi != null && meters != null) onSave(rssi, meters) },
                enabled = rssi != null && meters != null,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
