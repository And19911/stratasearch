package app.stratasearch.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun hex(s: String) = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
private fun signed(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

class ProtocolTest {
    // Эталонные пакеты, зашитые в старом приложении.
    private val report1 = hex("8F EA 01 00 27 8A 14 25 06 01 00 46 54 BF C4 25")
    private val report2 = hex("8F EA 01 09 27 8A D3 67 06 02 00 45 70 D8 00 45 A5 A8 95 6E")

    // Пакеты типа 4, склеенные из реального лога logStrataSearch.txt.
    private val status = (0x0A..0x0D).map { seq ->
        val mid = if (seq == 0x0B) "0F" else "0C"
        val body = hex("8F EA 04 %02X 06 00 00 0C 00 00 0C 00 00 0C 00 00 00 00 00 0C 00 00 0C 00 00 00 00 00 00 00 $mid 00 01 04 02 02 01 00 00 00 %02X".format(seq, 0x40 + seq))
        Protocol.withChecksum(body)
    }

    @Test
    fun checksumMatchesRealCapture() {
        // Последние 2 байта из лога: 29 CB, 2E 13, 2D 19, 2F 40
        val tails = listOf("29 CB", "2E 13", "2D 19", "2F 40").map { hex(it).toList() }
        assertEquals(tails, status.map { it.takeLast(2) })
        assertTrue(Protocol.hasValidChecksum(report1))
        assertTrue(Protocol.hasValidChecksum(report2))
    }

    @Test
    fun parsesNeighborReport() {
        val p = assertIs<Packet.NeighborReport>(PacketParser.parse(report2))
        assertEquals(0x0AD367, p.reporter)
        assertEquals(listOf(Neighbor(0x004570, -40), Neighbor(0x0045A5, -88)), p.neighbors)
        val p1 = assertIs<Packet.NeighborReport>(PacketParser.parse(report1))
        assertEquals(660517, p1.reporter)
        assertEquals(listOf(Neighbor(18004, -65)), p1.neighbors)
    }

    @Test
    fun assemblesRealChunkSequenceFromLog() {
        // Точная последовательность BLE-уведомлений из лога (по 20 байт, со сдвигом).
        val chunks = listOf(
            signed(-113, -22, 4, 12, 6, 0, 0, 12, 0, 0, 12, 0, 0, 12, 0, 0, 0, 0, 0, 12),
            signed(0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 12, 0, 1, 4, 2, 2, 1, 0, 0, 0),
            signed(76, 45, 25, -113, -22, 4, 13, 6, 0, 0, 12, 0, 0, 12, 0, 0, 12, 0, 0, 0),
            signed(0, 0, 12, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0, 12, 0, 1, 4, 2, 2, 1),
            signed(0, 0, 0, 77, 47, 64),
        )
        val frames = mutableListOf<ByteArray>()
        val asm = FrameAssembler { frames += it }
        chunks.forEach(asm::feed)
        assertEquals(2, frames.size)
        val seqs = frames.map { assertIs<Packet.GatewayStatus>(PacketParser.parse(it)).sequence }
        assertEquals(listOf(12, 13), seqs)
        assertEquals(0, asm.droppedBytes)
    }

    @Test
    fun anyChunkingYieldsSameFrames() {
        val stream = status.reduce { a, b -> a + b } + report1 + report2 + status[0]
        for (chunk in 1..25) {
            val frames = mutableListOf<ByteArray>()
            val asm = FrameAssembler { frames += it }
            stream.toList().chunked(chunk).forEach { asm.feed(it.toByteArray()) }
            assertEquals(7, frames.size, "chunk=$chunk")
        }
    }

    @Test
    fun recoversAfterLostChunk() {
        val frames = mutableListOf<ByteArray>()
        val asm = FrameAssembler { frames += it }
        // Первый пакет без среднего куска, затем целые.
        asm.feed(status[0].copyOfRange(0, 20))
        asm.feed(status[0].copyOfRange(40, 43))
        asm.feed(report2)
        asm.feed(status[1])
        assertEquals(2, frames.size)
        assertIs<Packet.NeighborReport>(PacketParser.parse(frames[0]))
    }

    @Test
    fun skipsGarbageBetweenFrames() {
        val frames = mutableListOf<ByteArray>()
        val asm = FrameAssembler { frames += it }
        asm.feed(hex("00 11 8F 22 EA") + report1 + hex("FF FF 8F EA 01") + report2)
        assertEquals(2, frames.size)
    }

    @Test
    fun configPacketLayout() {
        val p = Protocol.buildConfigPacket(id = 1, address = 0x1234)
        assertEquals(14, p.size)
        assertEquals("8F EA 05 01 80 12 34 04 00 00 00 0A", p.copyOfRange(0, 12).toHex())
        assertTrue(Protocol.hasValidChecksum(p))
    }

    @Test
    fun distanceFormulasMatchOldApp() {
        assertEquals(11.7, Environment.MINE_CLEAR.formulaMeters(-65), 0.1)
        assertEquals(2.05, Environment.MINE_BLOCKAGE.formulaMeters(-65), 0.05)
        // Без калибровки модель совпадает со старыми формулами.
        val est = DistanceModel.estimate(-70.0, Environment.MINE_CLEAR)
        assertEquals(kotlin.math.exp(-(-70 + 37.319) / 11.26), est.meters, 1e-6)
        assertTrue(est.minMeters < est.meters && est.meters < est.maxMeters)
    }

    @Test
    fun calibrationFitRecoversModel() {
        // Точки строго по модели A = −45, n = 3.
        val truth = PathLossModel(-45.0, 3.0)
        val pts = listOf(1.0, 5.0, 10.0, 20.0, 40.0).map { CalibrationPoint(truth.rssiAt(it).toInt(), it) }
        val m = DistanceModel.fit(Environment.MINE_CLEAR, pts)
        assertEquals(3.0, m.n, 0.15)
        assertEquals(-45.0, m.a, 1.5)
        assertEquals(10.0, m.meters(truth.rssiAt(10.0)), 1.5)
    }

    @Test
    fun singlePointShiftsLevelOnly() {
        val m = DistanceModel.fit(Environment.MINE_CLEAR, listOf(CalibrationPoint(-60, 5.0)))
        assertEquals(Environment.MINE_CLEAR.defaultN, m.n, 1e-9)
        assertEquals(5.0, m.meters(-60.0), 1e-6)
    }

    @Test
    fun outlierDoesNotBreakScale() {
        val truth = PathLossModel(-45.0, 3.0)
        val pts = listOf(1.0, 5.0, 10.0, 20.0).map { CalibrationPoint(truth.rssiAt(it).toInt(), it) } +
            CalibrationPoint(-50, 30.0) // явно неудачная точка
        val d = DistanceModel.fit(Environment.MINE_CLEAR, pts).meters(truth.rssiAt(10.0))
        assertTrue(d in 5.0..20.0, "d=$d")
    }

    @Test
    fun zones() {
        assertEquals(DistanceZone.TOUCH, DistanceZone.of(1.0))
        assertEquals(DistanceZone.NEAR, DistanceZone.of(12.0))
        assertEquals(DistanceZone.FAR, DistanceZone.of(80.0))
    }

    @Test
    fun bearingFromFourStepsWithRareSignal() {
        // Лампа передаёт раз в минуту: 4 замера через 90°, цель на востоке.
        val est = BearingEstimator()
        listOf(0.0 to -78, 90.0 to -64, 180.0 to -77, 270.0 to -82).forEachIndexed { i, (h, r) ->
            est.add(i * 60_000L, h, r)
        }
        val res = est.estimate(4 * 60_000L)!!
        assertTrue(kotlin.math.abs(normalizeDeg(res.bearingDeg - 90)) < 30, "bearing=${res.bearingDeg}")
    }

    @Test
    fun bearingFromSweep() {
        val target = 60.0
        val est = BearingEstimator()
        // Полный оборот за 36 с, сигнал сильнее всего в сторону цели.
        for (t in 0 until 36) {
            val heading = t * 10.0
            val rssi = (-75 + 10 * kotlin.math.cos(Math.toRadians(heading - target))).toInt()
            est.add(t * 1000L, heading, rssi)
        }
        val r = est.estimate(36_000)!!
        assertTrue(kotlin.math.abs(normalizeDeg(r.bearingDeg - target)) < 15, "bearing=${r.bearingDeg}")
        assertTrue(r.coverage > 0.99)
    }

    @Test
    fun noBearingWithoutSweepOrContrast() {
        val est = BearingEstimator()
        for (t in 0 until 10) est.add(t * 1000L, 5.0, -70)   // стоим на месте
        assertEquals(null, est.estimate(10_000))
        val flat = BearingEstimator()
        for (t in 0 until 36) flat.add(t * 1000L, t * 10.0, -70) // сигнал одинаковый со всех сторон
        assertEquals(null, flat.estimate(36_000))
    }

    @Test
    fun relativeDirection() {
        assertEquals(RelativeDirection.AHEAD, RelativeDirection.of(normalizeDeg(350.0 - 10.0)))
        assertEquals(RelativeDirection.RIGHT, RelativeDirection.of(normalizeDeg(100.0 - 10.0)))
        assertEquals(RelativeDirection.LEFT, RelativeDirection.of(normalizeDeg(280.0 - 10.0)))
        assertEquals(RelativeDirection.BEHIND, RelativeDirection.of(normalizeDeg(190.0 - 10.0)))
    }

    @Test
    fun rssiFilterRareSignal() {
        // Раз в минуту: текущее значение — последний замер, а не медиана за 5 минут.
        val f = RssiFilter()
        listOf(-80, -78, -70, -64).forEachIndexed { i, r -> f.add(i * 60_000L, r) }
        assertEquals(-64.0, f.current)
        assertEquals(6.0, f.trendDb!!, 1e-9)
        assertEquals(60_000L, f.typicalIntervalMs)
    }

    @Test
    fun rssiFilterFrequentSignal() {
        val f = RssiFilter(windowMs = 15_000)
        (0 until 30).forEach { i -> f.add(i * 1000L, if (i % 7 == 0) -95 else -70) } // редкие выбросы
        assertEquals(-70.0, f.current)
        assertEquals(1000L, f.typicalIntervalMs)
    }
}
