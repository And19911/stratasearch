package app.stratasearch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

data class FoundDevice(val name: String, val address: String, val rssi: Int)

sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val name: String) : LinkState
    data class Connected(val name: String, val address: String) : LinkState
    /** Связь пропала, приложение само пытается подключиться заново. */
    data class Reconnecting(val name: String, val attempt: Int) : LinkState
    data class Failed(val message: String) : LinkState
}

/**
 * Связь с приёмником через BLE-модуль HC-08 (прозрачный UART: сервис FFE0, характеристика FFE1).
 * Все колбэки вызываются в главном потоке.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val onState: (LinkState) -> Unit,
    private val onScanResult: (FoundDevice) -> Unit,
    private val onScanStopped: () -> Unit,
    private val onData: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_MS = 15_000L
        private const val RECONNECT_DELAY_MS = 2_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? get() = manager?.adapter

    private var gatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var targetName: String = ""
    private var userWantsConnection = false
    private var reconnectAttempt = 0
    private var scanning = false

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ---------- Поиск ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            main.post { onScanResult(FoundDevice(name, result.device.address, result.rssi)) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                onLog("Ошибка поиска BLE: $errorCode")
                onScanStopped()
            }
        }
    }

    private val stopScanRunnable = Runnable { stopScan() }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            onState(LinkState.Failed("Bluetooth выключен"))
            return
        }
        if (scanning) return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            onState(LinkState.Failed("Нет разрешения на Bluetooth"))
            onScanStopped()
            return
        }
        scanning = true
        main.postDelayed(stopScanRunnable, SCAN_MS)
    }

    fun stopScan() {
        main.removeCallbacks(stopScanRunnable)
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        onScanStopped()
    }

    // ---------- Подключение ----------

    fun connect(address: String, name: String) {
        stopScan()
        disconnectInternal()
        targetAddress = address
        targetName = name
        userWantsConnection = true
        reconnectAttempt = 0
        openGatt()
    }

    fun disconnect() {
        userWantsConnection = false
        main.removeCallbacksAndMessages(null)
        disconnectInternal()
        onState(LinkState.Idle)
    }

    private fun disconnectInternal() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private fun openGatt() {
        val address = targetAddress ?: return
        val device: BluetoothDevice = try {
            adapter?.getRemoteDevice(address) ?: return
        } catch (e: IllegalArgumentException) {
            onState(LinkState.Failed("Неверный адрес $address"))
            return
        }
        onState(
            if (reconnectAttempt == 0) LinkState.Connecting(targetName)
            else LinkState.Reconnecting(targetName, reconnectAttempt)
        )
        gatt = try {
            if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            userWantsConnection = false
            onState(LinkState.Failed("Нет разрешения на Bluetooth"))
            null
        }
    }

    private fun scheduleReconnect() {
        if (!userWantsConnection) return
        reconnectAttempt++
        onState(LinkState.Reconnecting(targetName, reconnectAttempt))
        main.postDelayed({ if (userWantsConnection) openGatt() }, RECONNECT_DELAY_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    onLog("Подключено к ${g.device.address}, поиск сервисов")
                    // Больший MTU уменьшает дробление пакетов; HC-08 может отказать — это не страшно.
                    if (!g.requestMtu(185)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onLog("Связь потеряна (status=$status)")
                    disconnectInternal()
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                onLog("MTU = $mtu")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { enableNotifications(g) }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = ch.value ?: return
            val copy = value.copyOf()
            main.post { onData(copy) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            main.post { onData(copy) }
        }
    }

    private fun enableNotifications(g: BluetoothGatt) {
        val ch = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            ?: g.services.flatMap { it.characteristics }.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            }
        if (ch == null) {
            onLog("На устройстве нет UART-характеристики FFE1")
            userWantsConnection = false
            disconnectInternal()
            onState(LinkState.Failed("Это не приёмник (нет сервиса FFE0)"))
            return
        }
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
        reconnectAttempt = 0
        onLog("Подписка на данные ${ch.uuid}")
        onState(LinkState.Connected(targetName, g.device.address))
    }

    fun release() {
        stopScan()
        disconnect()
    }
}
