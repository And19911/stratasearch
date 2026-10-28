package app.stratasearch

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stratasearch.data.MainViewModel
import app.stratasearch.ui.ConnectScreen
import app.stratasearch.ui.LogScreen
import app.stratasearch.ui.Palette
import app.stratasearch.ui.SettingsScreen
import app.stratasearch.ui.StatusStrip
import app.stratasearch.ui.StrataTheme
import app.stratasearch.ui.TagsScreen
import app.stratasearch.ui.TargetScreen

private enum class Tab(val title: String, val icon: ImageVector) {
    Tags("Метки", Icons.Default.Radar),
    Connect("Приёмник", Icons.Default.Bluetooth),
    Settings("Калибровка", Icons.Default.Tune),
    Log("Журнал", Icons.Default.Description),
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StrataTheme {
                var granted by rememberSaveable { mutableStateOf(hasPermissions()) }
                val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    granted = hasPermissions()
                }
                val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
                LaunchedEffect(Unit) { if (!granted) permLauncher.launch(requiredPermissions) }

                App(
                    vm = vm,
                    permissionsGranted = granted,
                    onRequestPermissions = { permLauncher.launch(requiredPermissions) },
                    onEnableBluetooth = {
                        runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun App(
    vm: MainViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val st by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(if (st.lastDevice == null) Tab.Connect else Tab.Tags) }

    // Автоподключение к последнему приёмнику при запуске.
    LaunchedEffect(permissionsGranted) {
        val last = st.lastDevice
        if (permissionsGranted && last != null && !st.receiving && st.bluetoothOn) vm.connect(last.first, last.second)
    }

    val selected = st.selected
    if (selected != null) {
        BackHandler { vm.select(null) }
        TargetScreen(selected, st, vm)
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            StatusStrip(st)
            if (!permissionsGranted && !st.demo && tab != Tab.Settings && tab != Tab.Log) {
                PermissionRequest(onRequestPermissions, onDemo = vm::startDemo)
                return@Column
            }
            when (tab) {
                Tab.Tags -> TagsScreen(st, vm, onGoConnect = { tab = Tab.Connect })
                Tab.Connect -> ConnectScreen(st, vm, onEnableBluetooth)
                Tab.Settings -> SettingsScreen(st, vm)
                Tab.Log -> LogScreen(st, vm)
            }
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit, onDemo: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Нужен доступ к Bluetooth", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Без него приложение не сможет найти приёмник и получать данные о метках.",
                textAlign = TextAlign.Center,
                color = Palette.TextDim,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRequest, modifier = Modifier.height(52.dp)) { Text("Разрешить") }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = onDemo) { Text("Попробовать в демо-режиме") }
        }
    }
}
