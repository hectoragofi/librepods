package me.kavishdevar.librepods.spatial

import android.content.SharedPreferences
import androidx.core.content.edit

/** Persistent Spatial Audio settings (on/off, apps, preset + tweaks, user presets). */
class SpatialAudioPreferences(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit { putBoolean(KEY_ENABLED, v) }

    var mode: Int
        get() = prefs.getInt(KEY_MODE, MODE_OWN)
        set(v) = prefs.edit { putInt(KEY_MODE, v) }

    var packages: Set<String>
        get() = prefs.getStringSet(KEY_PACKAGES, null)?.toSet() ?: DEFAULT_PACKAGES
        set(v) = prefs.edit { putStringSet(KEY_PACKAGES, v) }

    var presetId: String?
        get() = prefs.getString(KEY_PRESET, null)
        set(v) = prefs.edit { putString(KEY_PRESET, v) }

    /** Current values as a partial map over the engine defaults (JSON), null = the preset as is. */
    var tweaks: String?
        get() = prefs.getString(KEY_TWEAKS, null)
        set(v) = prefs.edit { putString(KEY_TWEAKS, v) }

    var userPresets: String?
        get() = prefs.getString(KEY_USER_PRESETS, null)
        set(v) = prefs.edit { putString(KEY_USER_PRESETS, v) }

    var diagnostics: Boolean
        get() = prefs.getBoolean(KEY_DIAGNOSTICS, false)
        set(v) = prefs.edit { putBoolean(KEY_DIAGNOSTICS, v) }

    companion object {
        const val PREFERENCES_NAME = "spatial_audio"
        const val MODE_NORMAL = 0
        const val MODE_OWN = 2
        val DEFAULT_PACKAGES = setOf(
            "com.apple.android.music",
            "com.spotify.music",
            "org.videolan.vlc",
            "com.google.android.apps.youtube.music",
        )
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODE = "mode"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_PRESET = "preset"
        private const val KEY_TWEAKS = "tweaks"
        private const val KEY_USER_PRESETS = "user_presets"
        private const val KEY_DIAGNOSTICS = "diagnostics"
    }
}
