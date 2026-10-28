package app.stratasearch.data

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Звуковой «поисковик»: чем сильнее сигнал, тем чаще писк — можно водить антенной,
 * не глядя на экран. [intervalProvider] возвращает паузу в мс или null (тишина).
 */
class Beeper(private val scope: CoroutineScope, private val intervalProvider: () -> Long?) {
    private var job: Job? = null
    private var tone: ToneGenerator? = null

    fun start() {
        if (job != null) return
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
        job = scope.launch {
            while (isActive) {
                val interval = intervalProvider()
                if (interval == null) {
                    delay(300)
                } else {
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                    delay(interval)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        tone?.release()
        tone = null
    }

    companion object {
        /** RSSI −95 дБм → 1.5 с между писками, −40 дБм → 0.12 с. */
        fun intervalForRssi(rssi: Double): Long {
            val k = ((rssi + 95.0) / 55.0).coerceIn(0.0, 1.0)
            return (1500 - k * (1500 - 120)).toLong()
        }
    }
}
