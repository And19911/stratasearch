package app.stratasearch.data

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Звук детектора движения: короткий «пинг» с затуханием. Чем ближе метка, тем чаще
 * и выше пинг — можно водить антенной, не глядя на экран.
 * [pingProvider] возвращает параметры следующего пинга или null — пауза.
 */
class Beeper(
    private val scope: CoroutineScope,
    private val onPing: () -> Unit,
    private val pingProvider: () -> Ping?,
) {
    /** [audible] = false — пинг только на экране, без звука. */
    data class Ping(val intervalMs: Long, val closeness: Double, val audible: Boolean)

    private var job: Job? = null
    private val tracks = HashMap<Int, AudioTrack>()

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val p = pingProvider()
                if (p == null) {
                    delay(300)
                } else {
                    if (p.audible) play(950 + (p.closeness * 650).toInt())
                    onPing()
                    delay(p.intervalMs)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        tracks.values.forEach { runCatching { it.release() } }
        tracks.clear()
    }

    private fun play(freq: Int) {
        val bucket = freq / 50 * 50
        val t = tracks.getOrPut(bucket) { buildTrack(bucket) ?: return }
        runCatching {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    @Suppress("DEPRECATION")
    private fun buildTrack(freq: Int): AudioTrack? = runCatching {
        val rate = 22050
        val n = rate * 180 / 1000
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val env = exp(-t * 22) * (1 - exp(-t * 900))
            // Основной тон + немного второй гармоники — «электронный» тембр.
            val v = sin(2 * PI * freq * t) + 0.25 * sin(4 * PI * freq * t)
            (v * env * 0.7 * Short.MAX_VALUE).toInt().toShort()
        }
        AudioTrack(
            AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, n * 2, AudioTrack.MODE_STATIC,
        ).also { it.write(pcm, 0, n) }
    }.getOrNull()

    companion object {
        /** Близость 0..1 по RSSI: −95 дБм → 0, −40 дБм → 1. */
        fun closeness(rssi: Double): Double = ((rssi + 95.0) / 55.0).coerceIn(0.0, 1.0)

        /** −95 дБм → 1.5 с между пингами, −40 дБм → 0.15 с. */
        fun intervalForRssi(rssi: Double): Long = (1500 - closeness(rssi) * (1500 - 150)).toLong()
    }
}
