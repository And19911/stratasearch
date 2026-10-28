package app.stratasearch.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Азимут, куда смотрит антенна, по компасу планшета.
 * Планшет держат вместе с антенной: лёжа — верхним краем экрана к антенне,
 * стоя — экраном к себе (тогда направление = куда смотрит задняя камера).
 */
class HeadingSensor(context: Context, private val onHeading: (Double) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val r = FloatArray(9)
    private var smoothX = 0.0
    private var smoothY = 0.0
    private var lastEmit = 0L

    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // Столбцы матрицы — оси устройства в мире (X — восток, Y — север, Z — вверх).
        @Suppress("DEPRECATION")
        val (sx, sy) = when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 1.0 to 0.0
            Surface.ROTATION_180 -> 0.0 to -1.0
            Surface.ROTATION_270 -> -1.0 to 0.0
            else -> 0.0 to 1.0
        }
        // «Верх экрана» в мировых координатах.
        val upE = r[0] * sx + r[1] * sy
        val upN = r[3] * sx + r[4] * sy
        val upZ = r[6] * sx + r[7] * sy
        // «Из задней стороны» (−Z устройства).
        val backE = -r[2].toDouble()
        val backN = -r[5].toDouble()
        val backZ = -r[8].toDouble()
        // Берём ось, которая ближе к горизонтали.
        val (e, n) = if (abs(upZ) < abs(backZ)) upE.toDouble() to upN.toDouble() else backE to backN
        val a = atan2(e, n)
        smoothX = 0.85 * smoothX + 0.15 * cos(a)
        smoothY = 0.85 * smoothY + 0.15 * sin(a)
        val now = System.currentTimeMillis()
        if (now - lastEmit >= 80) {
            lastEmit = now
            onHeading((Math.toDegrees(atan2(smoothY, smoothX)) + 360) % 360)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
