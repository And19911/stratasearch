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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stratasearch.data.MainViewModel
import app.stratasearch.data.TagUi
import app.stratasearch.data.UiState
import app.stratasearch.protocol.DistanceModel

@Composable
fun TagsScreen(st: UiState, vm: MainViewModel, onGoConnect: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        EnvironmentSelector(st.environment, vm::setEnvironment, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        when {
            !st.receiving -> EmptyState(
                title = "Нет связи с приёмником",
                text = "Подключитесь к ящику-приёмнику по Bluetooth, чтобы увидеть метки.",
                action = "Подключиться" to onGoConnect,
            )
            st.tags.isEmpty() -> EmptyState(
                title = "Меток пока не слышно",
                text = if (st.gateway != null)
                    "Приёмник на связи и присылает служебные пакеты (${st.gateway.packets} шт.). " +
                        "Как только он услышит метку, она появится здесь."
                else "Ждём данных от приёмника…",
                action = null,
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(st.tags, key = { it.key }) { t -> TagCard(t, st, onClick = { vm.select(t.key) }) }
                item {
                    TextButton(onClick = vm::clearTags, modifier = Modifier.fillMaxWidth()) {
                        Text("Очистить список")
                    }
                }
            }
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
                        "≈${formatMeters(est.meters)} м",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (age < 30_000) Palette.Amber else Palette.Idle,
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
