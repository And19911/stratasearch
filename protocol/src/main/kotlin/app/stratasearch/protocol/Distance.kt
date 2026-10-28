package app.stratasearch.protocol

import kotlin.math.log10
import kotlin.math.pow

/**
 * Условия распространения сигнала.
 *
 * Формулы старого приложения — это модель затухания RSSI = A − 10·n·lg(d):
 *  - без завала: d = e^(−(RSSI + 37.319) / 11.26)  →  A = −37.3, n = 2.59
 *  - с завалом:  d = 10^((−53 − RSSI) / 38.57)     →  A = −53.0, n = 3.86
 * Здесь они записаны в этом виде, чтобы калибровка могла подстраивать A и n.
 */
enum class Environment(val title: String, val defaultA: Double, val defaultN: Double) {
    MINE_CLEAR("Шахта, без завала", -37.319, 11.26 * kotlin.math.ln(10.0) / 10),
    MINE_BLOCKAGE("Шахта, завал", -53.0, 3.857),
    MOUNTAINS("Горная местность", -37.319, 11.26 * kotlin.math.ln(10.0) / 10);

    fun formulaMeters(rssi: Int): Double = PathLossModel(defaultA, defaultN).meters(rssi.toDouble())
}

/** Точка калибровки: при таком RSSI (усреднённом) измерено такое расстояние. */
data class CalibrationPoint(val rssi: Int, val meters: Double)

/** RSSI = a − 10·n·lg(d). [sigmaDb] — типичный разброс сигнала вокруг модели. */
data class PathLossModel(val a: Double, val n: Double, val sigmaDb: Double = DistanceModel.DEFAULT_SIGMA_DB) {
    fun meters(rssi: Double): Double = 10.0.pow((a - rssi) / (10 * n))
    fun rssiAt(meters: Double): Double = a - 10 * n * log10(meters)
}

enum class DistanceZone(val title: String, val upToMeters: Double) {
    TOUCH("ВПЛОТНУЮ", 2.0),
    CLOSE("БЛИЗКО", 5.0),
    NEAR("РЯДОМ", 15.0),
    MID("СРЕДНЕ", 30.0),
    FAR("ДАЛЕКО", Double.MAX_VALUE);

    companion object {
        fun of(meters: Double): DistanceZone = entries.first { meters < it.upToMeters }
    }
}

data class DistanceEstimate(
    val meters: Double,
    val minMeters: Double,
    val maxMeters: Double,
    val fromCalibration: Boolean,
    val model: PathLossModel,
) {
    val zone: DistanceZone get() = DistanceZone.of(meters)
}

object DistanceModel {
    /** Разброс RSSI в выработке (отражения, порода, люди) — ±4 дБ считаем нормой. */
    const val DEFAULT_SIGMA_DB = 4.0
    private const val MIN_N = 1.5
    private const val MAX_N = 6.0

    /**
     * Модель для режима по точкам калибровки:
     *  - нет точек — заводская формула;
     *  - одна точка (или все на одном расстоянии) — сдвигаем уровень A, наклон n заводской;
     *  - два расстояния — прямая через них;
     *  - три и больше — устойчивая подгонка (медианы), одна неудачная точка шкалу не ломает.
     */
    fun fit(env: Environment, points: List<CalibrationPoint>): PathLossModel {
        val pts = points.filter { it.meters > 0.05 }
        if (pts.isEmpty()) return PathLossModel(env.defaultA, env.defaultN)

        val xs = pts.map { log10(it.meters) }
        val ys = pts.map { it.rssi.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }

        val distinctX = xs.map { (it * 100).toInt() }.distinct().size
        val n: Double = when {
            distinctX >= 3 -> {
                // Тейл–Сен: медиана наклонов по всем парам точек — устойчива к выбросам.
                val slopes = ArrayList<Double>()
                for (i in xs.indices) for (j in i + 1 until xs.size) {
                    val dx = xs[j] - xs[i]
                    if (kotlin.math.abs(dx) > 0.05) slopes += (ys[j] - ys[i]) / dx
                }
                (-slopes.sorted()[slopes.size / 2] / 10).coerceIn(MIN_N, MAX_N)
            }
            pts.size >= 2 && sxx > 0.02 -> {
                val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
                (-(sxy / sxx) / 10).coerceIn(MIN_N, MAX_N)
            }
            else -> env.defaultN
        }
        // Уровень A — медиана по точкам, тоже устойчива к одной неудачной точке.
        val levels = xs.indices.map { ys[it] + 10 * n * xs[it] }.sorted()
        val a = if (levels.size % 2 == 1) levels[levels.size / 2] else (levels[levels.size / 2 - 1] + levels[levels.size / 2]) / 2
        val sigma = if (pts.size >= 3) {
            // Разброс по медиане отклонений (MAD), чтобы выброс не раздувал диапазон.
            val res = pts.map { kotlin.math.abs(it.rssi - (a - 10 * n * log10(it.meters))) }.sorted()
            (1.4826 * res[res.size / 2]).coerceIn(2.0, 10.0)
        } else DEFAULT_SIGMA_DB
        return PathLossModel(a, n, sigma)
    }

    fun estimate(rssi: Double, env: Environment, calibration: List<CalibrationPoint> = emptyList()): DistanceEstimate {
        val m = fit(env, calibration)
        return DistanceEstimate(
            meters = m.meters(rssi),
            // Сигнал сильнее на σ — метка ближе, слабее — дальше.
            minMeters = m.meters(rssi + m.sigmaDb),
            maxMeters = m.meters(rssi - m.sigmaDb),
            fromCalibration = calibration.isNotEmpty(),
            model = m,
        )
    }
}

/**
 * Сглаживание RSSI по времени, а не по числу измерений: лампа может передавать
 * и раз в секунду, и раз в минуту.
 *  - [current] — медиана измерений за последние [windowMs] (при редком сигнале — просто последнее);
 *  - [trendDb] — насколько текущий сигнал сильнее предыдущего (> 0 — ближе);
 *  - [typicalIntervalMs] — как часто лампа передаёт (медиана промежутков).
 */
class RssiFilter(private val windowMs: Long = 15_000, private val maxTrendGapMs: Long = 180_000) {
    private val values = ArrayDeque<Pair<Long, Int>>()

    fun add(timeMs: Long, rssi: Int) {
        values.addLast(timeMs to rssi)
        while (values.size > 64) values.removeFirst()
    }

    private fun median(xs: List<Int>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    val current: Double?
        get() {
            val last = values.lastOrNull() ?: return null
            return median(values.filter { it.first >= last.first - windowMs }.map { it.second })
        }

    val trendDb: Double?
        get() {
            val last = values.lastOrNull() ?: return null
            val cur = current ?: return null
            val prev = values.lastOrNull { it.first < last.first - windowMs } ?: return null
            if (last.first - prev.first > maxTrendGapMs) return null
            // Предыдущая «порция» — всё, что в окне перед prev.
            val prevMedian = median(values.filter { it.first in (prev.first - windowMs)..prev.first }.map { it.second })
            return cur - prevMedian
        }

    val typicalIntervalMs: Long?
        get() {
            if (values.size < 2) return null
            val gaps = values.zipWithNext { a, b -> b.first - a.first }.filter { it > 0 }.takeLast(8)
            if (gaps.isEmpty()) return null
            return gaps.sorted()[gaps.size / 2]
        }
}
