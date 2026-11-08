package me.kavishdevar.librepods.presentation.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import me.kavishdevar.librepods.core.bluetooth.MacAddress

@Serializable
sealed interface Screen: NavKey {
    val showTopBar: Boolean
        get() = true

    @Serializable
    data object Onboarding: Screen {
        override val showTopBar: Boolean = false
    }

    @Serializable
    data object DeviceList: Screen

    @Serializable
    data class AppleScreen(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data class Rename(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data object AppSettings: Screen

    @Serializable
    data class HeadTracking(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data class Accessibility(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data class TransparencyCustomization(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data class HearingAid(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data class HearingAidAdjustments(
        override val macAddress: MacAddress
    ): DeviceScreen

//    @Serializable
//    data object CameraControl: Screen

    @Serializable
    data object OpenSourceLicenses: Screen

    @Serializable
    data class UpdateHearingTest(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data class VersionInfo(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data class HearingProtection(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data object Purchase: Screen

    @Serializable
    data class Equalizer(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data class LongPress(
        override val macAddress: MacAddress,
        val bud: String
    ): DeviceScreen

    @Serializable
    data class CallControl(
        override val macAddress: MacAddress,
        val action: String
    ): DeviceScreen

    @Serializable
    data class MicrophoneSettings(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data object ReleaseNotes: Screen {
        override val showTopBar: Boolean = false
    }

    @Serializable
    data class Recording(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data class Debug(
        override val macAddress: MacAddress
    ): DeviceScreen

    @Serializable
    data object BLESettings: Screen

    @Serializable
    data class HeartRate(
        override val macAddress: MacAddress
    ): DeviceScreen

    // Workout screens are not DeviceScreens so they stay open while the AirPods are disconnected.
    @Serializable
    data object Workout: Screen

    @Serializable
    data object WorkoutHistory: Screen

    @Serializable
    data object HeartRateSharing: Screen

    @Serializable
    data class WorkoutDetail(
        val sessionId: String
    ): Screen

    @Serializable
    data object AppAccessibilitySettings: Screen

    @Serializable
    data object AppAppearance: Screen

    @Serializable
    data object IslandSettings: Screen

    @Serializable
    data object IslandCompactCustomize: Screen

    @Serializable
    data object IslandCompactSizeAndPosition: Screen

    @Serializable
    data object IslandExpandedCustomize: Screen

    @Serializable
    data object IslandExpandedSizeAndPosition: Screen

    @Serializable
    data object AppColorSettings: Screen

    @Serializable
    data object HazeSettings: Screen

    @Serializable
    data object ProxCardSizeAndPosition: Screen

    @Serializable
    data object ProxCardCustomize: Screen

    @Serializable
    data class OverlaySettings(
        override val macAddress: MacAddress
    ): DeviceScreen
    @Serializable
    data class ProxConnectSettings(
        override val macAddress: MacAddress
    ): DeviceScreen
}

@Serializable
sealed interface DeviceScreen : Screen {
    val macAddress: MacAddress
}
