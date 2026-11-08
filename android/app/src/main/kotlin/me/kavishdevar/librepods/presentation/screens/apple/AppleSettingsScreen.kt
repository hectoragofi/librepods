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

package me.kavishdevar.librepods.presentation.screens.apple

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.debounce
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.format
import me.kavishdevar.librepods.data.app.Design
import me.kavishdevar.librepods.data.app.DesignSystem
import me.kavishdevar.librepods.presentation.components.apple.AboutCard
import me.kavishdevar.librepods.presentation.components.apple.AudioSettings
import me.kavishdevar.librepods.presentation.components.apple.BatteryView
import me.kavishdevar.librepods.presentation.components.apple.CallControlSettings
import me.kavishdevar.librepods.presentation.components.apple.ConnectionSettings
import me.kavishdevar.librepods.presentation.components.apple.HearingHealthSettings
import me.kavishdevar.librepods.presentation.components.apple.NoiseControlSettings
import me.kavishdevar.librepods.presentation.components.apple.PressAndHoldSettings
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.design.LocalDesign
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.utils.LocalDebugMode
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun AppleSettingsRoute(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?,
    navigateToRename: () -> Unit,
    navigateToHearingProtection: () -> Unit,
    navigateToHearingAid: () -> Unit,
    navigateToLeftLongPress: () -> Unit,
    navigateToRightLongPress: () -> Unit,
    navigateToPurchase: () -> Unit,
    navigateToEqualizer: () -> Unit,
    navigateToHeadTracking: () -> Unit,
    navigateToAccessibility: () -> Unit,
    navigateToVersion: () -> Unit,
    navigateToCallControlScreen: (action: String) -> Unit,
    navigateToMicrophoneSettings: () -> Unit,
    navigateToRecordingScreen: () -> Unit,
    navigateToDebugScreen: () -> Unit,
    navigateToHeartRateScreen: () -> Unit,
    navigateToOverlaySettings: () -> Unit,
    navigateToProxConnectSettings: () -> Unit,
    navigateToSpatialAudio: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    AppleSettingsScreen(
        uiState = uiState,

        setControlCommandInt = { id, value -> viewModel.setControlCommand(id, value) },
        setControlCommandBoolean = { id, value -> viewModel.setControlCommand(id, value) },
//            setControlCommandByte = { id, value -> viewModel.setControlCommand(id, value) },
//            setControlCommandValue = { id, value -> viewModel.setControlCommand(id, value) },

        writeATTCharacteristic = viewModel::writeATTCharacteristic,

        updateSettings = viewModel::updateSettings,

//            onAutomaticConnectionChanged = viewModel::setAutomaticConnectionEnabled,
        disconnect = viewModel::disconnect,

        navigateBack = navigateBack,
        navigateToRename = navigateToRename,
        navigateToHearingProtection = navigateToHearingProtection,
        navigateToHearingAid = navigateToHearingAid,
        navigateToLeftLongPress = navigateToLeftLongPress,
        navigateToRightLongPress = navigateToRightLongPress,
        navigateToPurchase = navigateToPurchase,
        navigateToEqualizer = navigateToEqualizer,
        navigateToHeadTracking = navigateToHeadTracking,
        navigateToAccessibility = navigateToAccessibility,
        navigateToVersion = navigateToVersion,
        navigateToCallControlScreen = navigateToCallControlScreen,
        navigateToMicrophoneSettings = navigateToMicrophoneSettings,
        navigateToRecordingScreen = navigateToRecordingScreen,
        navigateToHeartRateScreen = navigateToHeartRateScreen,
        navigateToDebugScreen = navigateToDebugScreen,
        navigateToOverlaySettings = navigateToOverlaySettings,
        navigateToProxConnectSettings = navigateToProxConnectSettings,
        navigateToSpatialAudio = navigateToSpatialAudio
    )
}

@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
@Composable
fun AppleSettingsScreen(
    uiState: AppleUiState,

    setControlCommandInt: (ControlCommandIdentifier, Int) -> Unit,
    setControlCommandBoolean: (ControlCommandIdentifier, Boolean) -> Unit,
//        setControlCommandByte: (ControlCommandIdentifier, Byte) -> Unit,
//        setControlCommandValue: (ControlCommandIdentifier, ByteArray) -> Unit,

    writeATTCharacteristic: (ATTHandle, ByteArray) -> Unit,

    updateSettings: (transform: (AppleSettings) -> AppleSettings) -> Unit,

//    onAutomaticEarDetectionChanged: (Boolean) -> Unit,
//    onAutomaticConnectionChanged: (Boolean) -> Unit,

    disconnect: () -> Unit,

    navigateBack: (() -> Unit)?,
    navigateToRename: () -> Unit,
    navigateToHearingProtection: () -> Unit,
    navigateToHearingAid: () -> Unit,
    navigateToLeftLongPress: () -> Unit,
    navigateToRightLongPress: () -> Unit,
    navigateToPurchase: () -> Unit,
    navigateToEqualizer: () -> Unit,
    navigateToHeadTracking: () -> Unit,
    navigateToAccessibility: () -> Unit,
    navigateToVersion: () -> Unit,
    navigateToCallControlScreen: (action: String) -> Unit,
    navigateToMicrophoneSettings: () -> Unit,
    navigateToRecordingScreen: () -> Unit,
    navigateToHeartRateScreen: () -> Unit,
    navigateToDebugScreen: () -> Unit,
    navigateToOverlaySettings: () -> Unit,
    navigateToProxConnectSettings: () -> Unit,
    navigateToSpatialAudio: () -> Unit = {}
) {
    val state = uiState.state
    val settings = uiState.settings
    val metadata = uiState.metadata

    val spec = AirPodsSpecs.getSpec(metadata.model)

    val baseCapabilities = spec.baseCapabilities

    val design = LocalDesign.current
    val debugMode = LocalDebugMode.current

    StyledScaffold(
        title = uiState.metadata.name,
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        if (debugMode) {
            var offset by remember { mutableStateOf(Offset.Zero) }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.35f)
                    .zIndex(999f)
                    .offset {
                        IntOffset(offset.x.roundToInt(), offset.y.roundToInt())
                    }
                    .background(MaterialTheme.colorScheme.surfaceDim.copy(0.4f))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset += dragAmount
                        }
                    }
            ) {
                Text(
                    text = "[debug]" +
                        "\n${state.aacpPackets.size} aacp packets" +
                        "\nstate: ${state.state}" +
                        "\ncase lid: ${state.caseLidOpen}" +
                        "\nbattery: ${state.battery.format()}" +
                        "\nconnectedDevices: ${state.connectedDevices.joinToString(separator = ",") { it.macAddress.value }}" +
                        "\naudioSource: ${state.audioSource.mac} (${state.audioSource.type})" +
                        "\nprimary: ${state.primaryComponent}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 8.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(8.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item(key = "top_padding") { Spacer(modifier = Modifier.height(topPadding)) }

            item(key = "spacer_battery") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "battery") {
                BatteryView(
                    batteryList = state.battery,
                    primaryImageRes = spec.primaryImageRes,
                    caseImageRes = spec.caseImageRes
                )
            }

            item(key = "spacer_name") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "name") {
                StyledListItem(
                    contentText = stringResource(R.string.name),
                    supportingText = metadata.name,
                    onClick = navigateToRename,
                )
            }

            val hasHearingAidCapability = baseCapabilities.contains(BaseCapability.HEARING_AID)
            val hasPPECapability = baseCapabilities.contains(BaseCapability.PPE)

            if (hasHearingAidCapability || hasPPECapability) {
                if (hasPPECapability || uiState.vendorIdHook) {
                    item(key = "spacer_hearing_health") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
                item(key = "hearing_health") {
                    HearingHealthSettings(
                        hasPPECapability = hasPPECapability,
                        hasHearingAidCapability = hasHearingAidCapability,
                        vendorIdHook = uiState.vendorIdHook,
                        navigateToHearingProtection = navigateToHearingProtection,
                        navigateToHearingAid = navigateToHearingAid
                    )
                }
            }

            if (baseCapabilities.contains(BaseCapability.LISTENING_MODE)) {
                item(key = "spacer_noise") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item(key = "listening_mode") {
                    NoiseControlSettings(
                        modifier = Modifier.height(100.dp),
                        showOffListeningMode = state.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION]?.getOrNull(0)?.toInt() == 1,
                        noiseControlModeValue = state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                        onNoiseControlModeChanged = {
                            setControlCommandInt(
                                ControlCommandIdentifier.LISTENING_MODE, it
                            )
                        },
                        adaptiveStrength = state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.getOrNull(0)?.toInt() ?: 50,
                        onAdaptiveStrengthChanged = {
                            setControlCommandInt(
                                ControlCommandIdentifier.AUTO_ANC_STRENGTH, it
                            )
                        }
                    )
                }

                if (baseCapabilities.contains(BaseCapability.ADAPTIVE_AUDIO)) {
                    item(key = "adaptive_strength") {
                        AnimatedVisibility(
                            visible = state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt() == 4 && design.system == DesignSystem.Material,
                            enter = remember {
                                fadeIn() + slideInVertically()
                            },
                            exit = remember {
                                fadeOut() + slideOutVertically()
                            }
                        ) {
                            val sliderValue = remember {
                                mutableFloatStateOf(
                                    100f - (state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.getOrNull(
                                        0
                                    )?.toFloat() ?: 50f)
                                )
                            }

                            LaunchedEffect(sliderValue) {
                                snapshotFlow { sliderValue.floatValue }
                                    .debounce(100.milliseconds)
                                    .collect { value ->
                                        setControlCommandInt(
                                            ControlCommandIdentifier.AUTO_ANC_STRENGTH,
                                            (100 - value).toInt()
                                        )
                                    }
                            }

                            StyledSlider(
                                value = sliderValue.floatValue,
                                onValueChange = { sliderValue.floatValue = it },
                                valueRange = 0f..100f,
                                snapPoints = listOf(0f, 50f, 100f),
                                startImageVector = LocalIcons.current.SpeakerMin,
                                endImageVector = LocalIcons.current.SpeakerMax,
                                independent = true,
                                description = stringResource(R.string.adaptive_audio_description),
                                enabled = uiState.isPremium
                            )

                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }

            if (metadata.version3.isNotBlank() && metadata.version3.first().digitToInt() >= 8) {
                item(key = "spacer_recording") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item(key = "recording") {
                    StyledListItem(
                        contentText = stringResource(R.string.recorder),
                        supportingText = stringResource(R.string.recorder_description),
                        onClick = navigateToRecordingScreen,
                        orientation = StyledListItemOrientation.Vertical
                    )
                }
            }

            if (baseCapabilities.contains(BaseCapability.HRM)) {
                val showAlertDisabledMessage = state.hrmState != BuddyState.ACTIVE && settings.hrmAlertEnabled
                item(key = "spacer_heart_rate") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item(key = "heart_rate") {
                    StyledListItem(
                        contentText = stringResource(R.string.heart_rate),
                        onClick = navigateToHeartRateScreen,
                        supportingText = if (showAlertDisabledMessage) stringResource(R.string.heart_rate_alerts_disabled_warning) else state.currentHeartRate?.let { "${it.bpm} bpm" },
                        orientation = if (showAlertDisabledMessage) StyledListItemOrientation.Vertical else StyledListItemOrientation.Horizontal
                    )
                }
            }

            item(key = "spacer_spatial_audio") {
                Spacer(modifier = Modifier.height(16.dp))
            }
            item(key = "spatial_audio") {
                val spatialContext = androidx.compose.ui.platform.LocalContext.current
                val spatial = remember { me.kavishdevar.librepods.spatial.SpatialAudioController.get(spatialContext) }
                val spatialOn by spatial.enabled.collectAsState()
                StyledListItem(
                    contentText = stringResource(R.string.spatial_audio),
                    onClick = navigateToSpatialAudio,
                    supportingText = if (spatialOn) spatial.currentPreset().name else null,
                )
            }

            if (baseCapabilities.contains(BaseCapability.STEM_CONFIG)) {
                item(key = "spacer_press_hold") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item(key = "press_hold") {
                    PressAndHoldSettings(
                        leftAction = settings.leftLongPressAction,
                        rightAction = settings.rightLongPressAction,
                        navigateToLeftLongPress = navigateToLeftLongPress,
                        navigateToRightLongPress = navigateToRightLongPress
                    )
                }
            }

            item(key = "spacer_call") {
                Spacer(modifier = Modifier.height(16.dp))
            }
            item(key = "call_control") {
                val bytes =
                    state.controlStates[ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG]?.take(2)?.toByteArray() ?: byteArrayOf(0x00, 0x00)
                val flipped = try {
                    bytes[1] == 0x02.toByte()
                } catch (_: Exception) {
                    false
                }
                CallControlSettings(
                    flipped = flipped,
                    navigateToCallControlScreen = navigateToCallControlScreen
                )
            }

//                if (baseCapabilities.contains(BaseCapability.RAW_GESTURES_CONFIG) && !BuildConfig.PLAY_BUILD) {
//                    item(key = "spacer_camera") { Spacer(modifier = Modifier.height(16.dp)) }
//                    item(key = "camera_control") {
//                        StyledListItem(
//                            to = "camera_control",
//                            contentText = stringResource(R.string.camera_remote),
//                            descriptionRes = stringResource(R.string.camera_control_description),
//                            titleRes = stringResource(R.string.camera_control),
//                            navController = navController
//                        )
//                    }
//                }

            item(key = "upgrade_button") {
                if (!uiState.isPremium) {
                    Spacer(modifier = Modifier.height(28.dp))
                    StyledButton(
                        onClick = navigateToPurchase,
                        backdrop = rememberLayerBackdrop(),
                        modifier = Modifier.fillMaxWidth(),
                        maxScale = 0.05f,
                        surfaceColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            stringResource(R.string.unlock_advanced_features),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item(key = "spacer_audio") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "audio") {
                val adaptiveVolumeCapability = baseCapabilities.contains(BaseCapability.ADAPTIVE_VOLUME)
                val conversationAwarenessCapability = baseCapabilities.contains(BaseCapability.CONVERSATION_AWARENESS)
                val loudSoundReductionCapability = baseCapabilities.contains(BaseCapability.LOUD_SOUND_REDUCTION)

                val adaptiveVolumeChecked = state.controlStates[ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG]?.getOrNull(0) == 0x01.toByte()
                val conversationAwarenessChecked = state.controlStates[ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG]?.getOrNull(0) == 0x01.toByte()

                AudioSettings(
                    adaptiveVolumeCapability = adaptiveVolumeCapability,
                    conversationAwarenessCapability = conversationAwarenessCapability,
                    loudSoundReductionCapability = loudSoundReductionCapability,
                    customEqCapability = metadata.version3.isNotBlank() && metadata.version3.first().digitToInt() >= 9,
                    adaptiveVolumeChecked = adaptiveVolumeChecked,
                    onAdaptiveVolumeCheckedChange = { checked ->
                        setControlCommandBoolean(
                            ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG,
                            checked
                        )
                    },
                    conversationAwarenessChecked = conversationAwarenessChecked && uiState.isPremium,
                    onConversationAwarenessCheckedChange = { checked ->
                        setControlCommandBoolean(
                            ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG,
                            checked
                        )
                    },
                    loudSoundReductionChecked = state.loudSoundReductionEnabled,
                    onLoudSoundReductionCheckedChange = { checked ->
                        writeATTCharacteristic(
                            ATTHandle.LOUD_SOUND_REDUCTION,
                            byteArrayOf(if (checked) 0x01.toByte() else 0x00.toByte())
                        )
                    },
                    navigateToEqualizer = navigateToEqualizer,
                    vendorIdHook = uiState.vendorIdHook,
                    isPremium = uiState.isPremium
                )
            }

            item(key = "spacer_connection") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "connection") {
                ConnectionSettings(
                    automaticEarDetectionEnabled = state.controlStates[ControlCommandIdentifier.EAR_DETECTION_CONFIG]?.getOrNull(0) == 0x01.toByte() || settings.earDetectionEnabled,
                    onAutomaticEarDetectionChanged = { enabled -> setControlCommandBoolean(ControlCommandIdentifier.EAR_DETECTION_CONFIG, enabled); updateSettings { it.copy(earDetectionEnabled = enabled) } },
                    automaticConnectionEnabled = state.controlStates[ControlCommandIdentifier.SMART_ROUTING_MODE]?.getOrNull(0) == 0x01.toByte(),
                    onAutomaticConnectionChanged = { setControlCommandBoolean(ControlCommandIdentifier.SMART_ROUTING_MODE, it) },
                    disconnectWhenNotWearing = settings.disconnectWhenNotWearing,
                    onDisconnectWhenNotWearingChanged = { enabled -> updateSettings { it.copy(disconnectWhenNotWearing = enabled) } },
                    navigateToProxConnectSettings = navigateToProxConnectSettings
                )
            }

            item(key = "spacer_microphone") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "microphoneState") {
                val id = ControlCommandIdentifier.MIC_MODE

                val selectedModeText =
                    when (state.controlStates[id]?.getOrNull(0) ?: 0x00.toByte()) {
                        0x00.toByte() -> stringResource(R.string.microphone_automatic)
                        0x01.toByte() -> stringResource(R.string.microphone_always_right)
                        0x02.toByte() -> stringResource(R.string.microphone_always_left)
                        else -> stringResource(R.string.microphone_automatic)
                    }

                StyledListItem(
                    contentText = stringResource(R.string.microphone_mode),
                    supportingText = selectedModeText,
                    onClick = navigateToMicrophoneSettings
                )
            }

            if (baseCapabilities.contains(BaseCapability.SLEEP_DETECTION)) {
                item(key = "spacer_sleep") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "sleep_detection") {
                    val id = ControlCommandIdentifier.SLEEP_DETECTION_CONFIG
                    StyledToggle(
                        label = stringResource(R.string.sleep_detection),
                        checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                        onCheckedChange = { setControlCommandBoolean(id, it) },
                        enabled = uiState.isPremium
                    )
                }
            }

            if (baseCapabilities.contains(BaseCapability.HEAD_GESTURES)) {
                item(key = "spacer_head_tracking") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "head_tracking") {
                    StyledListItem(
                        contentText = stringResource(R.string.head_gestures),
                        supportingText = if (settings.headGesturesEnabled) stringResource(R.string.on) else stringResource(R.string.off),
                        onClick = navigateToHeadTracking
                    )
                }
            }

            item(key = "spacer_dynamic_end_of_charge") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "dynamic_end_of_charge") {
                StyledToggle(
                    label = stringResource(R.string.optimized_charging),
                    description = stringResource(R.string.optimized_charging_description),
                    checked = state.controlStates[ControlCommandIdentifier.DYNAMIC_END_OF_CHARGE]?.getOrNull(0) == 0x01.toByte(),
                    onCheckedChange = { setControlCommandBoolean(ControlCommandIdentifier.DYNAMIC_END_OF_CHARGE, it) }
                )
            }

            item(key = "spacer_accessibility") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "accessibility") {
                StyledListItem(
                    contentText = stringResource(R.string.accessibility), onClick = navigateToAccessibility
                )
            }

            if (baseCapabilities.contains(BaseCapability.LOUD_SOUND_REDUCTION) && (metadata.version3.isNotBlank() && metadata.version3.first().digitToInt() >= 8)) {
                item(key = "spacer_off_listening") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "off_listening") {
                    val id = ControlCommandIdentifier.ALLOW_OFF_OPTION
                    StyledToggle(
                        label = stringResource(R.string.off_listening_mode),
                        description = stringResource(R.string.off_listening_mode_description),
                        checked = state.controlStates[id]?.getOrNull(0) == 0x01.toByte(),
                        onCheckedChange = { setControlCommandBoolean(id, it) }
                    )
                }
            }

            item(key = "spacer_overlay_settings") { Spacer(modifier = Modifier.height(16.dp)) }
            item(key = "overlay_settings") {
                StyledListItem(
                    contentText = stringResource(R.string.overlay_settings),
                    onClick = navigateToOverlaySettings
                )
            }

            item(key = "spacer_about") { Spacer(modifier = Modifier.height(32.dp)) }
            item(key = "about") {
                AboutCard(
                    modelName = metadata.modelName,
                    actualModel = metadata.modelNumber,
                    serialNumbers = listOf(metadata.serialNumber, metadata.leftSerialNumber, metadata.rightSerialNumber),
                    version = metadata.version3,
                    navigateToVersion = navigateToVersion
                )
            }

            item(key = "spacer_disconnect") { Spacer(modifier = Modifier.height(28.dp)) }
            item(key = "disconnect_button") {
                StyledButton(
                    onClick = disconnect,
                    backdrop = rememberLayerBackdrop(),
                    isInteractive = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                ) {
                    Text(
                        text = stringResource(R.string.disconnect),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item(key = "spacer_debug") { Spacer(modifier = Modifier.height(16.dp)) }

            if (debugMode) {
                item(key = "show_cached_battery") {
                    StyledToggle(
                        label = "show cached battery",
                        checked = settings.cacheDisconnectedComponentBattery,
                        onCheckedChange = { enabled ->
                            updateSettings { it.copy(cacheDisconnectedComponentBattery = enabled) }
                        }
                    )
                }
                item(key = "debug_button") {
                    StyledListItem(
                        contentText = "debug",
                        onClick = navigateToDebugScreen
                    )
                }
            }

            item(key = "bottom_padding") { Spacer(modifier = Modifier.height(bottomPadding)) }
        }
    }
}

@Preview(name = "Apple")
@Composable
fun AppleSettingsScreenPreviewApple() {
    LibrePodsTheme(
        design = Design(
            system = DesignSystem.Apple,
        )
    ) {
        AppleSettingsScreen(
            uiState = AppleUiState(),

            setControlCommandInt = { _, _ -> },
            setControlCommandBoolean = { _, _ -> },
            writeATTCharacteristic = { _, _ -> },

            updateSettings = { _ -> },

            disconnect = {},

            navigateBack = null,
            navigateToRename = {},
            navigateToHearingProtection = {},
            navigateToHearingAid = {},
            navigateToLeftLongPress = {},
            navigateToRightLongPress = {},
            navigateToPurchase = {},
            navigateToEqualizer = {},
            navigateToHeadTracking = {},
            navigateToAccessibility = {},
            navigateToVersion = {},
            navigateToCallControlScreen = {},
            navigateToMicrophoneSettings = {},
            navigateToRecordingScreen = {},
            navigateToHeartRateScreen = {},
            navigateToDebugScreen = {},
            navigateToOverlaySettings = {},
            navigateToProxConnectSettings = {}
        )
    }
}

@Preview(name = "Material")
@Composable
fun AppleSettingsScreenPreviewMaterial() {
    LibrePodsTheme(
        design = Design(
            system = DesignSystem.Material,
        )
    ) {
        AppleSettingsScreen(
            uiState = AppleUiState(),

            setControlCommandInt = { _, _ -> },
            setControlCommandBoolean = { _, _ -> },
            writeATTCharacteristic = { _, _ -> },

            updateSettings = { _ -> },

            disconnect = {},

            navigateBack = null,
            navigateToRename = {},
            navigateToHearingProtection = {},
            navigateToHearingAid = {},
            navigateToLeftLongPress = {},
            navigateToRightLongPress = {},
            navigateToPurchase = {},
            navigateToEqualizer = {},
            navigateToHeadTracking = {},
            navigateToAccessibility = {},
            navigateToVersion = {},
            navigateToCallControlScreen = {},
            navigateToMicrophoneSettings = {},
            navigateToRecordingScreen = {},
            navigateToHeartRateScreen = {},
            navigateToDebugScreen = {},
            navigateToOverlaySettings = {},
            navigateToProxConnectSettings = {}
        )
    }
}
