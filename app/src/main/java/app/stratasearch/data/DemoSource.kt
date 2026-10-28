package app.stratasearch.data

import app.stratasearch.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Демо-режим без оборудования. Генерирует настоящие байты протокола и режет их
 * по 20 байт, как BLE-модуль, — то есть проверяет всю цепочку разбора.
 */
class DemoSource(
    private val scope: CoroutineScope,
    private val onData: (ByteArray) -> Unit,
    /** Азимут планшета: в демо первая метка «стоит» на востоке (90°), сигнал зависит от поворота. */
    private val headingProvider: () -> Double?,
) {
    private var job: Job? = null

    companion object {
        const val RECEIVER = 0x0A1425
        const val RECEIVER_2 = 0x0A2001
        val TAGS = listOf(18004, 17777, 20311)
        const val TARGET_BEARING = 90.0
    }

    fun start() {
        stop()
        job = scope.launch {
            var seq = 0
            var t = 0.0
            while (isActive) {
                t += 1.0
                seq = (seq + 1) and 0xFF
                // Первая метка медленно «приближается и удаляется», остальные стоят на месте.
                val dir = headingProvider()?.let { 8 * cos(Math.toRadians(it - TARGET_BEARING)) } ?: 0.0
                val rssi = listOf(
                    (-72 + 14 * sin(t / 20) + dir).toInt() + Random.nextInt(-2, 3),
                    -86 + Random.nextInt(-3, 4),
                    -62 + Random.nextInt(-2, 3),
                )
                // Третья метка — «редкая лампа»: передаёт раз в 20 с, как настоящие лампы в режиме ожидания.
                val visible = TAGS.indices.filter { it != 2 || t.toInt() % 20 == 0 }
                emit(neighborReport(seq, RECEIVER, visible.map { TAGS[it] to rssi[it] }))
                // Второй приёмник слышит одну свою метку и одну общую.
                emit(neighborReport(seq, RECEIVER_2, listOf(31002 to -68 + Random.nextInt(-3, 4), TAGS[1] to -80 + Random.nextInt(-3, 4))))
                emit(gatewayStatus(seq))
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun emit(frame: ByteArray) {
        frame.toList().chunked(20).forEach { onData(it.toByteArray()) }
    }

    private fun neighborReport(seq: Int, receiver: Int, items: List<Pair<Int, Int>>): ByteArray {
        val body = ByteArray(10 + 4 * items.size)
        body[0] = Protocol.SYNC1.toByte(); body[1] = Protocol.SYNC2.toByte()
        body[2] = Protocol.TYPE_NEIGHBOR_REPORT.toByte(); body[3] = seq.toByte()
        body[4] = 0x27
        Protocol.writeAddress(receiver, false).copyInto(body, 5)
        body[8] = 6
        body[9] = items.size.toByte()
        items.forEachIndexed { k, (addr, rssi) ->
            Protocol.writeAddress(addr, false).copyInto(body, 10 + 4 * k)
            body[13 + 4 * k] = rssi.toByte()
        }
        return Protocol.withChecksum(body)
    }

    private fun gatewayStatus(seq: Int): ByteArray {
        val body = ByteArray(Protocol.GATEWAY_STATUS_LENGTH - 2)
        body[0] = Protocol.SYNC1.toByte(); body[1] = Protocol.SYNC2.toByte()
        body[2] = Protocol.TYPE_GATEWAY_STATUS.toByte(); body[3] = seq.toByte(); body[4] = 6
        body[40] = (0x40 + seq).toByte()
        return Protocol.withChecksum(body)
    }
}
