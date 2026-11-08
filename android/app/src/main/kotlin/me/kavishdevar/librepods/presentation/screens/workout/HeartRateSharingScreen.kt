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

package me.kavishdevar.librepods.presentation.screens.workout

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.HeartRateBlePeripheralState
import me.kavishdevar.librepods.bluetooth.HeartRateBlePeripheralStatus
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle

/** Opt-in: advertise the AirPods heart rate as a standard BLE Heart Rate Service (0x180D). */
@Composable
fun HeartRateSharingRoute(navigateBack: (() -> Unit)?) {
    val app = LocalContext.current.applicationContext as LibrePodsApplication
    val preferences = app.heartRatePreferences
    val peripheral = app.heartRateBlePeripheral
    val enabled by preferences.bleSharingEnabled.collectAsState()
    val state by peripheral.state.collectAsState()

    val permissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    ) { results ->
        if (results.values.all { it }) {
            if (preferences.bleSharingEnabled.value) {
                // Already on but blocked on permission: start again now that it is granted.
                peripheral.stop()
                peripheral.start()
            } else {
                preferences.setBleSharingEnabled(true)
            }
        }
    }

    StyledScaffold(
        title = stringResource(R.string.heart_rate_sharing),
        navigateBack = navigateBack,
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(topPadding))

            StyledToggle(
                label = stringResource(R.string.heart_rate_sharing_toggle),
                description = stringResource(R.string.heart_rate_sharing_description),
                checked = enabled,
                onCheckedChange = { checked ->
                    if (checked && !permissions.allPermissionsGranted) {
                        permissions.launchMultiplePermissionRequest()
                    } else {
                        preferences.setBleSharingEnabled(checked)
                    }
                },
            )

            if (enabled) {
                StyledListItem(
                    contentText = statusLabel(state),
                    supportingText = listOfNotNull(
                        state.lastError,
                        if (state.status == HeartRateBlePeripheralStatus.PERMISSION_REQUIRED) {
                            stringResource(R.string.heart_rate_sharing_grant)
                        } else null,
                    ).joinToString("\n").ifEmpty { null },
                    onClick = if (state.status == HeartRateBlePeripheralStatus.PERMISSION_REQUIRED) {
                        { permissions.launchMultiplePermissionRequest() }
                    } else null,
                    orientation = StyledListItemOrientation.Vertical,
                )
            }

            Text(
                text = stringResource(R.string.heart_rate_sharing_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun statusLabel(state: HeartRateBlePeripheralState): String = when (state.status) {
    HeartRateBlePeripheralStatus.DISABLED -> stringResource(R.string.heart_rate_sharing_status_off)
    HeartRateBlePeripheralStatus.STARTING -> stringResource(R.string.heart_rate_sharing_status_starting)
    HeartRateBlePeripheralStatus.ADVERTISING -> stringResource(
        R.string.heart_rate_sharing_status_advertising,
        state.connectedDeviceCount,
        state.subscribedDeviceCount,
    )
    HeartRateBlePeripheralStatus.PERMISSION_REQUIRED -> stringResource(R.string.heart_rate_sharing_status_permission)
    HeartRateBlePeripheralStatus.BLUETOOTH_OFF -> stringResource(R.string.heart_rate_sharing_status_bluetooth_off)
    HeartRateBlePeripheralStatus.UNSUPPORTED -> stringResource(R.string.heart_rate_sharing_status_unsupported)
    HeartRateBlePeripheralStatus.ERROR -> stringResource(R.string.heart_rate_sharing_status_error)
}
