package app.stratasearch.protocol

import kotlin.math.exp
import kotlin.math.pow

/** Условия распространения сигнала. Формулы взяты из старого приложения без изменений. */
enum class Environment(val title: String) {
    MINE_CLEAR("Шахта, без завала"),
    MINE_BLOCKAGE("Шахта, завал"),
    MOUNTAINS("Горная местность");

    fun formulaMeters(rssi: Int): Double {
        val d = when (this) {
            MINE_CLEAR, MOUNTAINS -> exp(-(rssi + 37.319) / 11.26)
            MINE_BLOCKAGE -> 10.0.pow((-53.0 - rssi) / 38.57)
        }
        return if (d.isNaN()) 1.0 else d
    }
}

/** Точка калибровки: при таком RSSI измерено такое расстояние. */
data class CalibrationPoint(val rssi: Int, val meters: Double)

data class DistanceEstimate(val meters: Double, val minMeters: Double, val maxMeters: Double, val fromCalibration: Boolean)

object DistanceModel {
    /** Старое приложение показывало диапазон ±2 м. */
    const val SPREAD_METERS = 2.0

    /**
     * Если для режима есть хотя бы две точки калибровки — линейная интерполяция по ним
     * (за пределами таблицы — по крайним отрезкам). Иначе формула.
     */
    fun estimate(rssi: Double, env: Environment, calibration: List<CalibrationPoint> = emptyList()): DistanceEstimate {
        val pts = calibration.distinctBy { it.rssi }.sortedBy { it.rssi }
        val meters: Double
        val fromCal: Boolean
        if (pts.size >= 2) {
            val i = pts.indexOfFirst { it.rssi >= rssi }.let { if (it < 0) pts.size - 1 else it }.coerceAtLeast(1)
            val p0 = pts[i - 1]
            val p1 = pts[i]
            val t = (rssi - p0.rssi) / (p1.rssi - p0.rssi).toDouble()
            meters = (p0.meters + t * (p1.meters - p0.meters)).coerceAtLeast(0.0)
            fromCal = true
        } else {
            val r = rssi.toInt()
            val frac = rssi - r
            meters = env.formulaMeters(r) * (1 - frac) + env.formulaMeters(r + 1) * frac
            fromCal = false
        }
        return DistanceEstimate(
            meters = meters,
            minMeters = (meters - SPREAD_METERS).coerceAtLeast(0.0),
            maxMeters = meters + SPREAD_METERS,
            fromCalibration = fromCal,
        )
    }
}

/**
 * Сглаживание RSSI: медиана последних значений гасит одиночные выбросы,
 * а наклон по времени даёт подсказку «ближе / дальше».
 */
class RssiFilter(private val window: Int = 5) {
    private val values = ArrayDeque<Pair<Long, Int>>()

    fun add(timeMs: Long, rssi: Int) {
        values.addLast(timeMs to rssi)
        while (values.size > window) values.removeFirst()
    }

    val smoothed: Double?
        get() {
            if (values.isEmpty()) return null
            val s = values.map { it.second }.sorted()
            return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }

    /** дБм в секунду; > 0 — сигнал растёт (приближаемся). */
    val slopePerSecond: Double?
        get() {
            if (values.size < 3) return null
            val t0 = values.first().first
            val xs = values.map { (it.first - t0) / 1000.0 }
            val ys = values.map { it.second.toDouble() }
            val mx = xs.average()
            val my = ys.average()
            val den = xs.sumOf { (it - mx) * (it - mx) }
            if (den < 1e-6) return null
            return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
        }
}
