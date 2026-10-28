package app.stratasearch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun TagsScreen(st: UiState, vm: MainViewModel, onGoConnect: () -> Unit) {
    var filterDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ReceiverFilterBar(st, onOpen = { filterDialog = true }, onClear = { vm.setReceiverFilter(null) })
        EnvironmentSelector(st.environment, vm::setEnvironment, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val tags = st.visibleTags
        when {
            !st.receiving -> EmptyState(
                title = "Нет связи с приёмником",
                text = "Подключитесь к ящику-приёмнику по Bluetooth, чтобы увидеть метки.",
                action = "Подключиться" to onGoConnect,
            )
            tags.isEmpty() && st.receiverFilter != null -> EmptyState(
                title = "Приёмник ${st.receiverFilter} меток не слышит",
                text = "Включён фильтр: показываются только метки этого приёмника. " +
                    "Метки других приёмников скрыты (всего слышно: ${st.tags.size}).",
                action = "Показать все приёмники" to { vm.setReceiverFilter(null) },
            )
            tags.isEmpty() -> EmptyState(
                title = "Меток пока не слышно",
                text = if (st.gateway != null)
                    "Приёмник на связи и присылает служебные пакеты (${st.gateway.packets} шт.). " +
                        "Как только он услышит метку, она появится здесь."
                else "Ждём данных от приёмника…",
                action = null,
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(tags, key = { it.key }) { t -> TagCard(t, st, onClick = { vm.select(t.key) }) }
                item {
                    TextButton(onClick = vm::clearTags, modifier = Modifier.fillMaxWidth()) {
                        Text("Очистить список")
                    }
                }
            }
        }
    }

    if (filterDialog) {
        ReceiverFilterDialog(
            receivers = st.receivers,
            current = st.receiverFilter,
            tagCount = { r -> st.tags.count { it.receiver == r } },
            onSelect = { vm.setReceiverFilter(it); filterDialog = false },
            onDismiss = { filterDialog = false },
        )
    }
}

@Composable
private fun ReceiverFilterBar(st: UiState, onOpen: () -> Unit, onClear: () -> Unit) {
    val f = st.receiverFilter
    if (f == null) {
        OutlinedButton(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp),
        ) {
            Icon(Icons.Default.FilterAlt, null)
            Spacer(Modifier.width(8.dp))
            Text("Все приёмники (${st.receivers.size}) — выбрать один")
        }
    } else {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onOpen),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FilterAlt, null, tint = Palette.Accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text("Только приёмник $f", style = MaterialTheme.typography.titleMedium, color = Palette.Accent)
                    val hidden = st.tags.size - st.visibleTags.size
                    if (hidden > 0) Text("скрыто меток других приёмников: $hidden", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Снять фильтр") }
            }
        }
    }
}

@Composable
private fun ReceiverFilterDialog(
    receivers: List<Int>,
    current: Int?,
    tagCount: (Int) -> Int,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var manual by remember { mutableStateOf("") }
    val manualNumber = manual.trim().toIntOrNull()?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Фильтр по приёмнику") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FilterOption("Все приёмники", selected = current == null) { onSelect(null) }
                receivers.forEach { r ->
                    HorizontalDivider()
                    FilterOption("Приёмник $r", "меток: ${tagCount(r)}", selected = current == r) { onSelect(r) }
                }
                if (receivers.isEmpty()) {
                    Text(
                        "Пока ни один приёмник не прислал отчёт. Номер можно ввести вручную:",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    label = { Text("Номер приёмника") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(manualNumber) }, enabled = manualNumber != null) { Text("Применить номер") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun FilterOption(title: String, subtitle: String? = null, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TagCard(t: TagUi, st: UiState, onClick: () -> Unit) {
    val age = st.now - t.lastSeen
    val est = DistanceModel.estimate(t.smoothedRssi, st.environment, st.calibrationFor(st.environment))
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(freshnessColor(age), 14)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Метка ${t.tag}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "приёмник ${t.receiver} · ${formatAge(age)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${formatMeters(est.meters)} м",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (age < 30_000) Palette.Accent else Palette.Idle,
                    )
                    Text("${t.rssi} дБм", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            SignalBar(t.smoothedRssi, height = 10)
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String, action: Pair<String, () -> Unit>?) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Radar, null, Modifier.size(72.dp), tint = Palette.Idle)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = action.second, modifier = Modifier.height(52.dp)) { Text(action.first) }
            }
        }
    }
}
