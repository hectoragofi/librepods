package me.kavishdevar.librepods.spatial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** One OWN 3D parameter as described by the native table. */
data class SpatialParam(
    val id: Int,
    val key: String,
    val label: String,
    val group: String,
    val min: Float,
    val max: Float,
    val def: Float,
    val step: Float,
    val rebuild: Boolean,
) {
    val isSwitch: Boolean get() = min == 0f && max == 1f && step == 1f
    fun clamp(v: Float): Float = v.coerceIn(min, max)
}

/** The full parameter table plus key lookup. */
class SpatialParamTable(val params: List<SpatialParam>) {
    private val byKey = params.associateBy { it.key }
    val size: Int get() = params.size
    operator fun get(key: String): SpatialParam? = byKey[key]
    fun id(key: String): Int = byKey[key]?.id ?: -1
    fun defaults(): FloatArray = FloatArray(params.size) { params[it].def }
    fun group(name: String): List<SpatialParam> = params.filter { it.group == name }
}

/**
 * A preset is a partial {key: value} map applied over the engine defaults, the same format as
 * the test app's TuningPanel BUILTIN table and assets/spatial_presets.json.
 */
data class SpatialPreset(
    val id: String,
    val name: String,
    val description: String,
    val params: Map<String, Float>,
    val builtIn: Boolean,
)

object SpatialPresetCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    /** Parses the bundled asset (or any compatible list). Returns the presets and the default id. */
    fun parseAsset(text: String): Pair<List<SpatialPreset>, String?> {
        val root = json.parseToJsonElement(text)
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> root["presets"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val defaultId = ((root as? JsonObject)?.get("default") as? JsonPrimitive)?.content
        val presets = list.mapNotNull { el -> presetFrom(el as? JsonObject ?: return@mapNotNull null, builtIn = true) }
        return presets to defaultId
    }

    fun parseUserPresets(text: String?): List<SpatialPreset> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            (json.parseToJsonElement(text) as? JsonArray)?.mapNotNull { presetFrom(it as? JsonObject ?: return@mapNotNull null, builtIn = false) }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeUserPresets(presets: List<SpatialPreset>): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonArray {
            presets.forEach { p ->
                add(buildJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    put("description", p.description)
                    put("params", paramsObject(p.params))
                })
            }
        }
    )

    private fun presetFrom(o: JsonObject, builtIn: Boolean): SpatialPreset? {
        val name = (o["name"] as? JsonPrimitive)?.content ?: return null
        val id = (o["id"] as? JsonPrimitive)?.content ?: slug(name)
        val description = (o["description"] as? JsonPrimitive)?.content.orEmpty()
        val params = when (val p = o["params"]) {
            is JsonObject -> numbers(p)
            is JsonPrimitive -> if (p.content.isBlank()) emptyMap() else numbers(json.parseToJsonElement(p.content).jsonObject)
            else -> emptyMap()
        }
        return SpatialPreset(id, name, description, params, builtIn)
    }

    private fun numbers(o: JsonObject): Map<String, Float> =
        o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it.toFloat() } }.toMap()

    fun slug(name: String): String =
        name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "preset" }

    /** Partial map over defaults -> full value vector (unknown keys ignored, values clamped). */
    fun apply(table: SpatialParamTable, partial: Map<String, Float>): FloatArray {
        val v = table.defaults()
        for ((k, value) in partial) {
            val p = table[k] ?: continue
            v[p.id] = p.clamp(value)
        }
        return v
    }

    /** Full vector -> only the values that differ from the defaults. */
    fun diff(table: SpatialParamTable, values: FloatArray): Map<String, Float> =
        table.params.filter { it.id < values.size && abs(values[it.id] - it.def) > 1e-4f }
            .associate { it.key to values[it.id] }

    /** Export: every parameter, in the test app's {"own3d":1,"params":{...}} format. */
    fun export(table: SpatialParamTable, values: FloatArray, name: String? = null): String =
        json.encodeToString(JsonElement.serializer(), buildJsonObject {
            put("own3d", 1)
            name?.let { put("name", it) }
            put("params", paramsObject(table.params.filter { it.id < values.size }.associate { it.key to values[it.id] }))
        })

    /**
     * Import: a full export, a {"params":{...}} wrapper or a bare partial {key:value} map.
     * Returns the partial map (keys known to the table only) and the optional name, or null.
     */
    fun import(table: SpatialParamTable, text: String): Pair<Map<String, Float>, String?>? = try {
        val o = json.parseToJsonElement(text.trim()).jsonObject
        val inner = o["params"] as? JsonObject ?: o
        val map = numbers(inner).filterKeys { table[it] != null }
        val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (map.isEmpty() && inner.isNotEmpty() && inner.keys.none { table[it] != null }) null else map to name
    } catch (_: Exception) {
        null
    }

    private fun paramsObject(m: Map<String, Float>): JsonObject = buildJsonObject {
        m.forEach { (k, v) -> put(k, tidy(v)) }
    }

    /** 0.30000001f -> 0.3 so exported JSON stays readable. */
    private fun tidy(v: Float): Double {
        val d = v.toDouble()
        val r = (d * 1000).roundToLong() / 1000.0
        return if (abs(r - d) < 1e-4) r else d
    }
}
