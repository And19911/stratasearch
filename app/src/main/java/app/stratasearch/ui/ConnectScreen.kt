package app.stratasearch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.stratasearch.ble.LinkState
import app.stratasearch.data.MainViewModel
import app.stratasearch.data.UiState

@Composable
fun ConnectScreen(st: UiState, vm: MainViewModel, onEnableBluetooth: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!st.bluetoothOn && !st.demo) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BluetoothDisabled, null, tint = Palette.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text("Bluetooth выключен", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = onEnableBluetooth) { Text("Включить") }
                    }
                }
            }
        }

        val connectedOrBusy = st.link is LinkState.Connected || st.link is LinkState.Connecting || st.link is LinkState.Reconnecting
        if (connectedOrBusy) {
            item {
                OutlinedButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Отключиться от приёмника")
                }
            }
        }

        st.lastDevice?.let { (addr, name) ->
            if (!connectedOrBusy) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SectionTitle("Последний приёмник")
                        Text(name, style = MaterialTheme.typography.titleLarge)
                        Text(addr, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.connect(addr, name) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Icon(Icons.Default.Bluetooth, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Подключиться")
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = { if (st.scanning) vm.stopScan() else vm.startScan() },
                enabled = st.bluetoothOn,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (st.scanning) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Идёт поиск… (нажмите, чтобы остановить)")
                } else {
                    Icon(Icons.Default.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Найти приёмники рядом")
                }
            }
        }

        if (st.found.isNotEmpty()) item { SectionTitle("Найдено — нажмите, чтобы подключиться") }
        items(st.found, key = { it.address }) { d ->
            Card(Modifier.fillMaxWidth().clickable { vm.connect(d) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bluetooth, null, tint = Palette.Accent)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(d.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${d.rssi} дБм", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Text(
                "Включите ящик-приёмник и поднесите планшет поближе. Приложение само переподключается, " +
                    "если связь пропала.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("Без оборудования")
                    Text("Демо-режим показывает, как выглядит поиск, на сгенерированных данных. Подходит для обучения.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { if (st.demo) vm.stopDemo() else vm.startDemo() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (st.demo) "Выключить демо-режим" else "Включить демо-режим")
                    }
                }
            }
        }
    }
}
