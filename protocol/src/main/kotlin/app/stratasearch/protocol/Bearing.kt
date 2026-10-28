package app.stratasearch.protocol

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Куда повернуться относительно того, куда сейчас смотрит антенна. */
enum class RelativeDirection(val title: String) {
    AHEAD("ПРЯМО"),
    RIGHT("СПРАВА"),
    BEHIND("СЗАДИ"),
    LEFT("СЛЕВА");

    companion object {
        /** [relativeDeg] в диапазоне −180…180, положительный — по часовой (вправо). */
        fun of(relativeDeg: Double): RelativeDirection = when {
            abs(relativeDeg) <= 45 -> AHEAD
            relativeDeg in 45.0..135.0 -> RIGHT
            relativeDeg in -135.0..-45.0 -> LEFT
            else -> BEHIND
        }
    }
}

data class BearingResult(
    /** Азимут на цель, 0 = север, по часовой. */
    val bearingDeg: Double,
    /** Насколько направление на цель сильнее остальных, дБ. */
    val contrastDb: Double,
    /** Какая доля круга уже «прослушана», 0..1. */
    val coverage: Double,
)

fun normalizeDeg(d: Double): Double {
    var x = d % 360.0
    if (x > 180) x -= 360
    if (x <= -180) x += 360
    return x
}

/**
 * Пеленг по направленной антенне: спасатель медленно поворачивается, каждое измерение RSSI
 * привязывается к азимуту антенны (по компасу планшета). Направление, где сигнал сильнее
 * всего, — направление на метку.
 */
class BearingEstimator(
    private val sectors: Int = 12,
    private val maxAgeMs: Long = 90_000,
    private val minCoverage: Double = 0.5,
    private val minContrastDb: Double = 3.0,
) {
    private data class Sample(val time: Long, val heading: Double, val rssi: Int)

    private val samples = ArrayDeque<Sample>()

    fun add(timeMs: Long, headingDeg: Double, rssi: Int) {
        samples.addLast(Sample(timeMs, (headingDeg % 360 + 360) % 360, rssi))
        while (samples.size > 400) samples.removeFirst()
    }

    fun clear() = samples.clear()

    /** Доля секторов, где есть хотя бы одно свежее измерение. */
    fun coverage(nowMs: Long): Double = sectorMeans(nowMs).count { it != null }.toDouble() / sectors

    private fun sectorMeans(nowMs: Long): Array<Double?> {
        while (samples.isNotEmpty() && nowMs - samples.first().time > maxAgeMs) samples.removeFirst()
        val width = 360.0 / sectors
        val sum = DoubleArray(sectors)
        val cnt = IntArray(sectors)
        for (s in samples) {
            val i = ((s.heading + width / 2) / width).toInt() % sectors
            sum[i] += s.rssi
            cnt[i]++
        }
        return Array(sectors) { if (cnt[it] > 0) sum[it] / cnt[it] else null }
    }

    fun estimate(nowMs: Long): BearingResult? {
        val means = sectorMeans(nowMs)
        val filled = means.indices.filter { means[it] != null }
        val coverage = filled.size.toDouble() / sectors
        if (coverage < minCoverage) return null

        // Сглаживаем по соседним секторам — одиночный выброс не уводит пеленг.
        val score = DoubleArray(sectors) { i ->
            val c = means[i] ?: return@DoubleArray Double.NEGATIVE_INFINITY
            val l = means[(i - 1 + sectors) % sectors] ?: c
            val r = means[(i + 1) % sectors] ?: c
            0.5 * c + 0.25 * l + 0.25 * r
        }
        val best = filled.maxBy { score[it] }
        val others = filled.filter { it != best }.map { means[it]!! }.sorted()
        if (others.isEmpty()) return null
        val median = others[others.size / 2]
        val contrast = means[best]!! - median
        if (contrast < minContrastDb) return null

        // Уточняем азимут: средневзвешенное по мощности лучшего сектора и соседей.
        val width = 360.0 / sectors
        var x = 0.0
        var y = 0.0
        for (k in -1..1) {
            val i = (best + k + sectors) % sectors
            val m = means[i] ?: continue
            val w = 10.0.pow(m / 10.0)
            val a = Math.toRadians(i * width)
            x += w * cos(a)
            y += w * sin(a)
        }
        val bearing = (Math.toDegrees(atan2(y, x)) + 360) % 360
        return BearingResult(bearing, contrast, coverage)
    }
}
