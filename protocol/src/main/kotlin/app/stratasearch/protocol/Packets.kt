package app.stratasearch.protocol

/**
 * Протокол радиошлюза «StrataSearch» (восстановлен из старого приложения и реального лога).
 *
 * Кадр: 8F EA <тип> ... CK_A CK_B
 * Контрольная сумма — Fletcher-8 по всем байтам кадра, включая заголовок.
 */
object Protocol {
    const val SYNC1: Int = 0x8F
    const val SYNC2: Int = 0xEA

    const val TYPE_NEIGHBOR_REPORT = 1
    const val TYPE_GATEWAY_STATUS = 4
    const val TYPE_CONFIG = 5
    val CONFIG_ACK_TYPES = setOf(6, 10, 11)

    /** Длина пакета типа 4, наблюдаемая на реальном шлюзе. */
    const val GATEWAY_STATUS_LENGTH = 43

    const val MAX_FRAME = 256
    const val MIN_FRAME = 6

    /** Адреса >= этого значения старое приложение считало мусором. */
    const val MAX_VALID_ADDRESS = 1_000_000

    fun checksum(data: ByteArray, from: Int = 0, to: Int = data.size): Pair<Int, Int> {
        var a = 0
        var b = 0
        for (i in from until to) {
            a = (a + (data[i].toInt() and 0xFF)) and 0xFF
            b = (b + a) and 0xFF
        }
        return a to b
    }

    fun hasValidChecksum(frame: ByteArray, length: Int = frame.size): Boolean {
        if (length < 4 || length > frame.size) return false
        val (a, b) = checksum(frame, 0, length - 2)
        return (frame[length - 2].toInt() and 0xFF) == a && (frame[length - 1].toInt() and 0xFF) == b
    }

    fun withChecksum(body: ByteArray): ByteArray {
        val (a, b) = checksum(body)
        return body + byteArrayOf(a.toByte(), b.toByte())
    }

    /** 24-битный адрес big-endian; у старшего байта бит 0x80 — служебный флаг. */
    fun readAddress(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0x7F) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)

    fun writeAddress(address: Int, shortFlag: Boolean): ByteArray {
        val hi = ((address shr 16) and 0xFF) or (if (shortFlag) 0x80 else 0)
        return byteArrayOf(hi.toByte(), (address shr 8).toByte(), address.toByte())
    }

    /**
     * Конфигурационный пакет (тип 5). В старом приложении был написан, но не вызывался.
     * Перед ним старое приложение отправляло один байт 0xFF.
     */
    fun buildConfigPacket(id: Int, address: Int?): ByteArray {
        val body = ByteArray(12)
        body[0] = SYNC1.toByte()
        body[1] = SYNC2.toByte()
        body[2] = TYPE_CONFIG.toByte()
        body[3] = id.toByte()
        if (address != null) writeAddress(address, address < 0x10000).copyInto(body, 4)
        body[7] = 4
        body[11] = 10
        return withChecksum(body)
    }
}

data class Neighbor(val address: Int, val rssi: Int)

sealed interface Packet {
    val raw: ByteArray

    /** Тип 1: узел (приёмник) сообщает, каких соседей он слышит и с каким RSSI. */
    class NeighborReport(
        override val raw: ByteArray,
        val sequence: Int,
        val reporter: Int,
        val status: Int,
        val neighbors: List<Neighbor>,
    ) : Packet

    /** Тип 4: периодический служебный пакет шлюза. Назначение полей пока неизвестно. */
    class GatewayStatus(
        override val raw: ByteArray,
        val sequence: Int,
        val payload: ByteArray,
    ) : Packet

    /** Типы 6, 10, 11: подтверждение конфигурационного пакета. */
    class ConfigAck(override val raw: ByteArray, val type: Int, val id: Int) : Packet

    class Unknown(override val raw: ByteArray, val type: Int) : Packet
}

object PacketParser {
    fun parse(frame: ByteArray): Packet {
        val type = frame[2].toInt() and 0xFF
        return when {
            type == Protocol.TYPE_NEIGHBOR_REPORT && frame.size >= 12 -> parseNeighborReport(frame)
            type == Protocol.TYPE_GATEWAY_STATUS -> Packet.GatewayStatus(
                raw = frame,
                sequence = frame[3].toInt() and 0xFF,
                payload = frame.copyOfRange(5, frame.size - 2),
            )
            type in Protocol.CONFIG_ACK_TYPES -> Packet.ConfigAck(frame, type, frame[3].toInt() and 0xFF)
            else -> Packet.Unknown(frame, type)
        }
    }

    private fun parseNeighborReport(frame: ByteArray): Packet {
        val count = frame[9].toInt() and 0xFF
        val neighbors = ArrayList<Neighbor>(count)
        for (k in 0 until count) {
            val off = 10 + 4 * k
            if (off + 4 > frame.size - 2) break
            val address = Protocol.readAddress(frame, off)
            if (address >= Protocol.MAX_VALID_ADDRESS) continue
            neighbors += Neighbor(address, frame[off + 3].toInt())
        }
        return Packet.NeighborReport(
            raw = frame,
            sequence = frame[3].toInt() and 0xFF,
            reporter = Protocol.readAddress(frame, 5),
            status = frame[8].toInt() and 0xFF,
            neighbors = neighbors,
        )
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
