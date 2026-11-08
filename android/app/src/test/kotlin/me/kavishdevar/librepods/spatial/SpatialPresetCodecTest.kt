package me.kavishdevar.librepods.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpatialPresetCodecTest {
    private val table = SpatialParamTable(
        listOf(
            SpatialParam(0, "fl_az", "FL azimuth", "Speakers", -180f, 180f, 30f, 1f, true),
            SpatialParam(1, "fl_mute", "FL mute", "Speakers", 0f, 1f, 0f, 1f, false),
            SpatialParam(2, "late_db", "Late", "Room", -40f, -6f, -22f, 0.5f, true),
            SpatialParam(3, "hrtf", "HRTF", "Global", 0f, 5f, 0f, 1f, true),
        )
    )

    @Test
    fun bundledAssetParses() {
        val asset = listOf("src/main/assets/spatial_presets.json", "app/src/main/assets/spatial_presets.json")
            .map(::File).first { it.exists() }
        val (presets, def) = SpatialPresetCodec.parseAsset(asset.readText())
        assertTrue(presets.size >= 5)
        assertEquals("apple-like", def)
        assertTrue(presets.any { it.id == def })
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        val concert = presets.first { it.id == "concert-hall" }
        assertEquals(55f, concert.params["fl_az"])
    }

    @Test
    fun partialAppliesOverDefaultsAndClamps() {
        val v = SpatialPresetCodec.apply(table, mapOf("late_db" to -100f, "hrtf" to 2f, "unknown" to 3f))
        assertEquals(30f, v[0])
        assertEquals(-40f, v[2])
        assertEquals(2f, v[3])
    }

    @Test
    fun diffKeepsOnlyChanges() {
        val v = table.defaults().also { it[2] = -15f }
        assertEquals(mapOf("late_db" to -15f), SpatialPresetCodec.diff(table, v))
    }

    @Test
    fun exportImportRoundTrip() {
        val v = table.defaults().also { it[0] = 45f; it[1] = 1f }
        val json = SpatialPresetCodec.export(table, v, "Mine")
        val (map, name) = SpatialPresetCodec.import(table, json)!!
        assertEquals("Mine", name)
        assertTrue(SpatialPresetCodec.apply(table, map).contentEquals(v))
    }

    @Test
    fun importAcceptsBarePartialMap() {
        val (map, name) = SpatialPresetCodec.import(table, """{"late_db": -17, "rt60": 0.4}""")!!
        assertEquals(mapOf("late_db" to -17f), map)
        assertNull(name)
    }

    @Test
    fun importRejectsGarbage() {
        assertNull(SpatialPresetCodec.import(table, "not json"))
        assertNull(SpatialPresetCodec.import(table, """{"foo": 1}"""))
        assertNotNull(SpatialPresetCodec.import(table, "{}"))
    }

    @Test
    fun userPresetsRoundTrip() {
        val list = listOf(SpatialPreset("user-a", "A", "Custom", mapOf("fl_az" to 50f), false))
        val back = SpatialPresetCodec.parseUserPresets(SpatialPresetCodec.encodeUserPresets(list))
        assertEquals(list, back)
    }
}
