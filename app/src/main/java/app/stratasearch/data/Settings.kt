package app.stratasearch.data

import android.content.Context
import app.stratasearch.protocol.CalibrationPoint
import app.stratasearch.protocol.Environment
import org.json.JSONArray
import org.json.JSONObject

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var environment: Environment
        get() = runCatching { Environment.valueOf(prefs.getString("env", null)!!) }.getOrDefault(Environment.MINE_CLEAR)
        set(v) = prefs.edit().putString("env", v.name).apply()

    var soundEnabled: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var lastDeviceAddress: String?
        get() = prefs.getString("last_addr", null)
        set(v) = prefs.edit().putString("last_addr", v).apply()

    var lastDeviceName: String
        get() = prefs.getString("last_name", null) ?: "Приёмник"
        set(v) = prefs.edit().putString("last_name", v).apply()

    fun loadCalibration(): Map<Environment, List<CalibrationPoint>> {
        val json = prefs.getString("calibration", null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            Environment.entries.associateWith { env ->
                val arr = obj.optJSONArray(env.name) ?: JSONArray()
                (0 until arr.length()).map {
                    val p = arr.getJSONObject(it)
                    CalibrationPoint(p.getInt("rssi"), p.getDouble("m"))
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun saveCalibration(cal: Map<Environment, List<CalibrationPoint>>) {
        val obj = JSONObject()
        cal.forEach { (env, pts) ->
            obj.put(env.name, JSONArray().apply {
                pts.forEach { put(JSONObject().put("rssi", it.rssi).put("m", it.meters)) }
            })
        }
        prefs.edit().putString("calibration", obj.toString()).apply()
    }
}
