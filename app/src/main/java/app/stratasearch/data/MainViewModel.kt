package app.stratasearch.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.stratasearch.ble.BleLink
import app.stratasearch.ble.FoundDevice
import app.stratasearch.ble.LinkState
import app.stratasearch.protocol.CalibrationPoint
import app.stratasearch.protocol.Environment
import app.stratasearch.protocol.FrameAssembler
import app.stratasearch.protocol.Packet
import app.stratasearch.protocol.PacketParser
import app.stratasearch.protocol.RssiFilter
import app.stratasearch.protocol.toHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Метка, которую слышит приёмник. */
data class TagUi(
    val key: String,
    val receiver: Int,
    val tag: Int,
    val rssi: Int,
    val smoothedRssi: Double,
    /** дБм/с; > 0 — сигнал растёт. */
    val slope: Double?,
    val lastSeen: Long,
    val history: List<Pair<Long, Int>>,
)

data class GatewayUi(val lastSeq: Int, val lastSeen: Long, val packets: Long)

data class UiState(
    val link: LinkState = LinkState.Idle,
    val demo: Boolean = false,
    val bluetoothOn: Boolean = true,
    val scanning: Boolean = false,
    val found: List<FoundDevice> = emptyList(),
    val lastDevice: Pair<String, String>? = null,
    val tags: List<TagUi> = emptyList(),
    val gateway: GatewayUi? = null,
    val reportPackets: Long = 0,
    val droppedBytes: Long = 0,
    val selectedKey: String? = null,
    val environment: Environment = Environment.MINE_CLEAR,
    val sound: Boolean = true,
    val calibration: Map<Environment, List<CalibrationPoint>> = emptyMap(),
    val logLines: List<String> = emptyList(),
    val now: Long = System.currentTimeMillis(),
) {
    val receiving: Boolean get() = demo || link is LinkState.Connected
    val selected: TagUi? get() = tags.firstOrNull { it.key == selectedKey }
    fun calibrationFor(env: Environment) = calibration[env].orEmpty()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val sessionLog = SessionLog(app)

    private val _state = MutableStateFlow(
        UiState(
            environment = settings.environment,
            sound = settings.soundEnabled,
            calibration = settings.loadCalibration(),
            lastDevice = settings.lastDeviceAddress?.let { it to settings.lastDeviceName },
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private class Track(val receiver: Int, val tag: Int) {
        val filter = RssiFilter(5)
        val history = ArrayDeque<Pair<Long, Int>>()
        var rssi = 0
        var lastSeen = 0L
    }

    private val tracks = LinkedHashMap<String, Track>()
    private var gatewayPackets = 0L
    private var reportPackets = 0L

    private val assembler = FrameAssembler(::onFrame)

    private val ble = BleLink(
        context = app,
        onState = { s ->
            _state.update { it.copy(link = s) }
            log("Связь: ${describe(s)}")
            if (s is LinkState.Connected) assembler.reset()
        },
        onScanResult = { d ->
            _state.update { st ->
                val list = (st.found.filterNot { it.address == d.address } + d).sortedByDescending { it.rssi }
                st.copy(found = list)
            }
        },
        onScanStopped = { _state.update { it.copy(scanning = false) } },
        onData = ::onBytes,
        onLog = ::log,
    )

    private val demo = DemoSource(viewModelScope, ::onBytes)

    private val beeper = Beeper(viewModelScope) {
        val st = _state.value
        val t = st.selected
        if (!st.sound || t == null || st.now - t.lastSeen > STALE_MS) null
        else Beeper.intervalForRssi(t.smoothedRssi)
    }

    init {
        log("Запуск приложения, журнал: ${sessionLog.file.name}")
        viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(now = System.currentTimeMillis(), bluetoothOn = ble.isBluetoothOn) }
                delay(1000)
            }
        }
    }

    // ---------- Действия из интерфейса ----------

    fun startScan() {
        stopDemo()
        _state.update { it.copy(found = emptyList(), scanning = true) }
        ble.startScan()
    }

    fun stopScan() = ble.stopScan()

    fun connect(device: FoundDevice) = connect(device.address, device.name)

    fun connect(address: String, name: String) {
        stopDemo()
        settings.lastDeviceAddress = address
        settings.lastDeviceName = name
        _state.update { it.copy(lastDevice = address to name) }
        ble.connect(address, name)
    }

    fun disconnect() = ble.disconnect()

    fun startDemo() {
        ble.disconnect()
        assembler.reset()
        _state.update { it.copy(demo = true) }
        log("Демо-режим включён")
        demo.start()
    }

    fun stopDemo() {
        if (!_state.value.demo) return
        demo.stop()
        _state.update { it.copy(demo = false) }
        log("Демо-режим выключен")
    }

    fun select(key: String?) {
        _state.update { it.copy(selectedKey = key) }
        if (key != null) beeper.start() else beeper.stop()
    }

    fun setEnvironment(env: Environment) {
        settings.environment = env
        _state.update { it.copy(environment = env) }
    }

    fun setSound(on: Boolean) {
        settings.soundEnabled = on
        _state.update { it.copy(sound = on) }
    }

    fun addCalibrationPoint(env: Environment, rssi: Int, meters: Double) {
        updateCalibration(env) { pts -> (pts.filterNot { it.rssi == rssi } + CalibrationPoint(rssi, meters)).sortedBy { it.rssi } }
        log("Калибровка ${env.title}: $rssi дБм = $meters м")
    }

    fun removeCalibrationPoint(env: Environment, point: CalibrationPoint) =
        updateCalibration(env) { pts -> pts - point }

    fun clearCalibration(env: Environment) = updateCalibration(env) { emptyList() }

    private fun updateCalibration(env: Environment, f: (List<CalibrationPoint>) -> List<CalibrationPoint>) {
        val cal = _state.value.calibration.toMutableMap()
        cal[env] = f(cal[env].orEmpty())
        settings.saveCalibration(cal)
        _state.update { it.copy(calibration = cal) }
    }

    fun clearTags() {
        tracks.clear()
        publishTracks()
    }

    fun currentLogFile(): File = sessionLog.file

    // ---------- Приём данных ----------

    private fun onBytes(chunk: ByteArray) {
        val before = assembler.droppedBytes
        assembler.feed(chunk)
        val dropped = assembler.droppedBytes - before
        if (dropped > 0) {
            log("Отброшено $dropped байт (обрыв/мусор). Кусок: ${chunk.toHex()}")
            _state.update { it.copy(droppedBytes = assembler.droppedBytes) }
        }
    }

    private fun onFrame(frame: ByteArray) {
        val now = System.currentTimeMillis()
        when (val p = PacketParser.parse(frame)) {
            is Packet.NeighborReport -> {
                reportPackets++
                log("Т1 #${p.sequence} приёмник ${p.reporter}: " +
                    p.neighbors.joinToString { "${it.address}(${it.rssi})" } + " | ${frame.toHex()}")
                for (n in p.neighbors) {
                    val key = "${p.reporter}:${n.address}"
                    val tr = tracks.getOrPut(key) { Track(p.reporter, n.address) }
                    tr.rssi = n.rssi
                    tr.lastSeen = now
                    tr.filter.add(now, n.rssi)
                    tr.history.addLast(now to n.rssi)
                    while (tr.history.isNotEmpty() && now - tr.history.first().first > HISTORY_MS) tr.history.removeFirst()
                }
                publishTracks()
            }
            is Packet.GatewayStatus -> {
                gatewayPackets++
                log("Т4 #${p.sequence} | ${frame.toHex()}")
                _state.update { it.copy(gateway = GatewayUi(p.sequence, now, gatewayPackets)) }
            }
            is Packet.ConfigAck -> log("Подтверждение конфигурации #${p.id} (тип ${p.type})")
            is Packet.Unknown -> log("Неизвестный тип ${p.type} | ${frame.toHex()}")
        }
    }

    private fun publishTracks() {
        val list = tracks.entries.map { (key, t) ->
            TagUi(
                key = key,
                receiver = t.receiver,
                tag = t.tag,
                rssi = t.rssi,
                smoothedRssi = t.filter.smoothed ?: t.rssi.toDouble(),
                slope = t.filter.slopePerSecond,
                lastSeen = t.lastSeen,
                history = t.history.toList(),
            )
        }.sortedByDescending { it.smoothedRssi }
        _state.update { it.copy(tags = list, reportPackets = reportPackets) }
    }

    private fun log(line: String) {
        sessionLog.add(line)
        _state.update { it.copy(logLines = sessionLog.lines) }
    }

    private fun describe(s: LinkState) = when (s) {
        LinkState.Idle -> "отключено"
        is LinkState.Connecting -> "подключение к ${s.name}"
        is LinkState.Connected -> "подключено ${s.name} ${s.address}"
        is LinkState.Reconnecting -> "переподключение, попытка ${s.attempt}"
        is LinkState.Failed -> "ошибка: ${s.message}"
    }

    override fun onCleared() {
        beeper.stop()
        demo.stop()
        ble.release()
    }

    companion object {
        const val STALE_MS = 5_000L
        const val HISTORY_MS = 120_000L
    }
}
