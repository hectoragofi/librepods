package me.kavishdevar.librepods.spatial

import org.json.JSONArray
import org.json.JSONObject

/** Renderer status sent from the shell user service to the app. */
data class SpatialStatus(
    val state: State = State.OFF,
    val mode: Int = 2,
    /** Selected packages currently playing. */
    val playing: List<String> = emptyList(),
    val error: String? = null,
    /** Average OWN 3D cost per 10 ms block (diagnostics only, -1 = unknown). */
    val cpuMs: Double = -1.0,
    val cpuMaxMs: Double = -1.0,
    val peak: Int = 0,
    val routed: String? = null,
    val agc: Float = 0f,
    /** Service log, only while diagnostics are on. */
    val log: String? = null,
) {
    enum class State {
        /** Feature off. */
        OFF,
        /** On, but no app selected. */
        NO_APPS,
        /** On, but media is not going to Bluetooth: nothing is captured. */
        NO_OUTPUT,
        /** On, idle: no selected app is playing (no policy registered). */
        WAITING,
        /** Capturing, input is silent: render track paused. */
        SILENT,
        RENDERING,
        ERROR,
    }

    /** CPU load of the renderer in percent of real time. */
    val cpuPercent: Double get() = if (cpuMs < 0) -1.0 else cpuMs / 10.0 * 100.0

    fun toJson(): String = JSONObject().apply {
        put("state", state.name)
        put("mode", mode)
        put("playing", JSONArray(playing))
        error?.let { put("error", it) }
        put("cpuMs", cpuMs)
        put("cpuMaxMs", cpuMaxMs)
        put("peak", peak)
        routed?.let { put("routed", it) }
        put("agc", agc.toDouble())
        log?.let { put("log", it) }
    }.toString()

    companion object {
        fun fromJson(json: String): SpatialStatus = try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("playing")
            SpatialStatus(
                state = runCatching { State.valueOf(o.optString("state")) }.getOrDefault(State.ERROR),
                mode = o.optInt("mode", 2),
                playing = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) },
                error = o.optString("error").ifEmpty { null },
                cpuMs = o.optDouble("cpuMs", -1.0),
                cpuMaxMs = o.optDouble("cpuMaxMs", -1.0),
                peak = o.optInt("peak"),
                routed = o.optString("routed").ifEmpty { null },
                agc = o.optDouble("agc", 0.0).toFloat(),
                log = o.optString("log").ifEmpty { null },
            )
        } catch (_: Exception) {
            SpatialStatus(state = State.ERROR, error = "bad status")
        }
    }
}
