package app.stratasearch.protocol

/**
 * Собирает кадры из потока байт. BLE-модуль HC-08 режет данные на куски по ~20 байт,
 * поэтому один пакет приходит в 2–3 уведомлениях, а в одном уведомлении может быть
 * хвост прошлого пакета и начало следующего.
 *
 * Кадр принимается только если сошлась контрольная сумма. При мусоре или потерянном
 * куске выполняется ресинхронизация по следующему заголовку 8F EA.
 */
class FrameAssembler(private val onFrame: (ByteArray) -> Unit) {
    private var buf = ByteArray(1024)
    private var size = 0

    var droppedBytes: Long = 0
        private set

    fun feed(chunk: ByteArray) {
        ensureCapacity(size + chunk.size)
        chunk.copyInto(buf, size)
        size += chunk.size
        process()
    }

    fun reset() {
        size = 0
    }

    private sealed interface Result {
        data class Frame(val length: Int) : Result
        data object NeedMore : Result
        data object Invalid : Result
    }

    private fun process() {
        while (true) {
            val start = findSync(0)
            if (start < 0) {
                // Последний байт может быть началом заголовка.
                val keep = if (size > 0 && (buf[size - 1].toInt() and 0xFF) == Protocol.SYNC1) 1 else 0
                discard(size - keep)
                return
            }
            discard(start)
            when (val r = tryParse(0)) {
                is Result.Frame -> {
                    onFrame(buf.copyOfRange(0, r.length))
                    remove(r.length)
                }
                Result.Invalid -> discard(1)
                Result.NeedMore -> {
                    // Если дальше в буфере уже лежит целый корректный кадр — значит,
                    // текущий кадр потерял кусок, и его нужно бросить.
                    val next = findCompleteFrameAfter(1)
                    if (next > 0) {
                        discard(next)
                    } else if (size > Protocol.MAX_FRAME + 4) {
                        discard(1)
                    } else {
                        return
                    }
                }
            }
        }
    }

    private fun findCompleteFrameAfter(from: Int): Int {
        var i = findSync(from)
        while (i >= 0) {
            if (tryParse(i) is Result.Frame) return i
            i = findSync(i + 1)
        }
        return -1
    }

    private fun tryParse(at: Int): Result {
        val avail = size - at
        if (avail < 3) return Result.NeedMore
        val type = buf[at + 2].toInt() and 0xFF

        val expected: Int? = when (type) {
            Protocol.TYPE_NEIGHBOR_REPORT -> {
                if (avail < 10) return Result.NeedMore
                12 + 4 * (buf[at + 9].toInt() and 0xFF)
            }
            Protocol.TYPE_GATEWAY_STATUS -> Protocol.GATEWAY_STATUS_LENGTH
            else -> null
        }
        if (expected != null && expected <= Protocol.MAX_FRAME) {
            if (avail < expected) return Result.NeedMore
            if (checksumOk(at, expected)) return Result.Frame(expected)
            if (type == Protocol.TYPE_NEIGHBOR_REPORT) return Result.Invalid
            // Для типа 4 длина известна только по наблюдениям — пробуем общий поиск ниже.
        }

        // Неизвестная длина: ищем первую позицию, где сходится контрольная сумма.
        val maxLen = minOf(avail, Protocol.MAX_FRAME)
        for (len in Protocol.MIN_FRAME..maxLen) {
            if (checksumOk(at, len)) return Result.Frame(len)
        }
        return if (avail >= Protocol.MAX_FRAME) Result.Invalid else Result.NeedMore
    }

    private fun checksumOk(at: Int, len: Int): Boolean {
        val (a, b) = Protocol.checksum(buf, at, at + len - 2)
        return (buf[at + len - 2].toInt() and 0xFF) == a && (buf[at + len - 1].toInt() and 0xFF) == b
    }

    private fun findSync(from: Int): Int {
        for (i in from until size - 1) {
            if ((buf[i].toInt() and 0xFF) == Protocol.SYNC1 && (buf[i + 1].toInt() and 0xFF) == Protocol.SYNC2) return i
        }
        return -1
    }

    private fun discard(n: Int) {
        if (n <= 0) return
        droppedBytes += n
        remove(n)
    }

    private fun remove(n: Int) {
        buf.copyInto(buf, 0, n, size)
        size -= n
    }

    private fun ensureCapacity(cap: Int) {
        if (cap > buf.size) buf = buf.copyOf(maxOf(cap, buf.size * 2))
    }
}
