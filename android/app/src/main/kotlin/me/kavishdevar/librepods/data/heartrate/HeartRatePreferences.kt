/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.data.heartrate

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide heart-rate switches. Backed by the "settings" SharedPreferences with the same keys
 * the earlier heart-rate build used, so values survive installing this build over it.
 */
class HeartRatePreferences(private val sharedPreferences: SharedPreferences) {
    private val _monitoringEnabled = MutableStateFlow(
        sharedPreferences.getBoolean(KEY_MONITORING_ENABLED, false)
    )

    /** True while the user wants heart rate streamed; drives automatic start and recovery. */
    val monitoringEnabled: StateFlow<Boolean> = _monitoringEnabled.asStateFlow()

    private val _bleSharingEnabled = MutableStateFlow(
        sharedPreferences.getBoolean(KEY_BLE_PERIPHERAL_ENABLED, false)
    )

    /** Opt-in: expose heart rate as a standard BLE Heart Rate Service (0x180D). */
    val bleSharingEnabled: StateFlow<Boolean> = _bleSharingEnabled.asStateFlow()

    fun setMonitoringEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_MONITORING_ENABLED, enabled) }
        _monitoringEnabled.value = enabled
    }

    fun setBleSharingEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_BLE_PERIPHERAL_ENABLED, enabled) }
        _bleSharingEnabled.value = enabled
    }

    companion object {
        const val PREFERENCES_NAME = "settings"
        private const val KEY_MONITORING_ENABLED = "heart_rate_monitoring_enabled"
        private const val KEY_BLE_PERIPHERAL_ENABLED = "heart_rate_ble_peripheral_enabled"
    }
}
