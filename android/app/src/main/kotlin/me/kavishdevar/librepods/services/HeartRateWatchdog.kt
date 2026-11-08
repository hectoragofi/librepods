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

package me.kavishdevar.librepods.services

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class HeartRateRecoveryStatus {
    /** Monitoring is switched off. */
    OFF,
    /** Monitoring is on but the AACP link is not up. */
    WAITING_FOR_AIRPODS,
    /** Monitoring is on but no bud is in an ear, so no samples are expected. */
    NOT_WORN,
    STARTING,
    LIVE,
    /** No sample within the first-sample timeout; the start command was resent. */
    RETRYING,
    /** The resend did not help; the AACP socket is being rebuilt once. */
    REBUILDING,
    /** Retry and rebuild both failed; waits for a manual retry. */
    FAILED,
}

/**
 * Keeps the heart-rate stream alive for one AirPods device while the user has monitoring on.
 *
 * Ported from the earlier HeartRateMonitor. Each attempt waits [FIRST_SAMPLE_TIMEOUT] for a sample;
 * the first miss resends the start command, the second requests one automatic AACP socket rebuild
 * (clean transport, wait, reconnect, re-init) after which the start is sent again. A live test after
 * toggling phone Bluetooth showed the resend alone does not recover the stream, the rebuild does.
 * Monitoring also resumes on its own every time the device reconnects.
 */
class HeartRateWatchdog(
    private val device: AppleDevice,
    private val scope: CoroutineScope,
    private val monitoringEnabled: Flow<Boolean>,
) {
    private data class Gate(
        val enabled: Boolean,
        val connection: ConnectionState,
        val connectionNumber: Int,
        val worn: Boolean,
        val retryRequest: Int,
    )

    private val _status = MutableStateFlow(HeartRateRecoveryStatus.OFF)
    val status: StateFlow<HeartRateRecoveryStatus> = _status.asStateFlow()

    private val retryRequests = MutableStateFlow(0)

    /** One automatic rebuild per failure episode; cleared by a live sample or a manual retry. */
    @Volatile private var rebuildUsed = false
    @Volatile private var rebuildInFlight = false

    private var job: Job? = null
    private var lastSupervisedConnection = -1

    fun start() {
        if (job?.isActive == true) return
        val worn = device.state
            .map { state ->
                // Unknown wear state (no component info yet) counts as worn.
                state.componentState.isEmpty() ||
                    state.componentState.any { it.status == ComponentStatus.IN_EAR }
            }
            .distinctUntilChanged()

        job = scope.launch {
            combine(
                combine(monitoringEnabled, device.connectionState, device.connectionNumber) { e, c, n -> Triple(e, c, n) },
                worn,
                retryRequests,
            ) { (enabled, connection, number), isWorn, retry ->
                Gate(enabled, connection, number, isWorn, retry)
            }
                .distinctUntilChanged()
                // connect() emits CONNECTING, CONNECTED and a new connection number in quick
                // succession; settle so one reconnect is seen as one gate change.
                .debounce(GATE_SETTLE)
                .collectLatest { gate ->
                    when {
                        !gate.enabled -> {
                            rebuildUsed = false
                            setStatus(HeartRateRecoveryStatus.OFF)
                        }
                        gate.connection != ConnectionState.CONNECTED -> {
                            if (!rebuildInFlight) setStatus(HeartRateRecoveryStatus.WAITING_FOR_AIRPODS)
                        }
                        !gate.worn -> setStatus(HeartRateRecoveryStatus.NOT_WORN)
                        else -> supervise(gate.connectionNumber)
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Manual retry from the UI after [HeartRateRecoveryStatus.FAILED]; allows another rebuild. */
    fun retry() {
        rebuildUsed = false
        retryRequests.update { it + 1 }
    }

    private suspend fun supervise(connectionNumber: Int) {
        if (rebuildInFlight) {
            // This is the reconnect our own rebuild produced; keep rebuildUsed so we only rebuild once.
            rebuildInFlight = false
        } else if (connectionNumber != lastSupervisedConnection) {
            // Any other new connection (e.g. after a Bluetooth toggle) starts a new failure episode.
            rebuildUsed = false
        }
        lastSupervisedConnection = connectionNumber

        setStatus(HeartRateRecoveryStatus.STARTING)
        if (device.state.value.hrmState == BuddyState.INACTIVE) {
            // The AACP init sequence runs twice, ~3 s apart, right after connecting.
            delay(INIT_SETTLE)
            sendStart("initial")
        }

        var streamLive = false
        var misses = 0
        while (true) {
            val timeout = if (streamLive) STALL_TIMEOUT else FIRST_SAMPLE_TIMEOUT
            if (awaitNextSample(timeout)) {
                if (!streamLive && misses > 0) Log.i(TAG, "heart-rate refresh succeeded after $misses miss(es)")
                streamLive = true
                misses = 0
                rebuildUsed = false
                setStatus(HeartRateRecoveryStatus.LIVE)
                continue
            }

            val reason = if (streamLive) "stream-stalled" else "first-sample-timeout"
            streamLive = false
            misses++

            if (misses == 1) {
                Log.i(TAG, "heart-rate refresh requested reason=$reason")
                setStatus(HeartRateRecoveryStatus.RETRYING)
                sendStart("retry")
                continue
            }

            Log.w(TAG, "heart-rate refresh failed reason=$reason attempts=${misses - 1}")
            if (!rebuildUsed) {
                rebuildUsed = true
                rebuildInFlight = true
                setStatus(HeartRateRecoveryStatus.REBUILDING)
                Log.i(TAG, "requesting one automatic AACP rebuild for heart-rate recovery")
                // Run outside collectLatest: the reconnect changes the gate and would cancel us.
                scope.launch {
                    val connected = try {
                        device.rebuildAacpTransport(REBUILD_SETTLE)
                    } catch (e: Exception) {
                        Log.e(TAG, "AACP rebuild failed", e)
                        false
                    }
                    Log.i(TAG, "AACP rebuild for heart-rate finished connected=$connected")
                    if (!connected) {
                        // AirPods unreachable; the next normal reconnect resumes monitoring.
                        rebuildInFlight = false
                        setStatus(HeartRateRecoveryStatus.WAITING_FOR_AIRPODS)
                    }
                }
            } else {
                Log.i(TAG, "automatic AACP rebuild already used; waiting for manual retry")
                setStatus(HeartRateRecoveryStatus.FAILED)
            }
            awaitCancellation()
        }
    }

    private fun sendStart(reason: String) {
        val sent = device.startHr()
        Log.d(TAG, "heart-rate start ($reason) sent=$sent")
    }

    private suspend fun awaitNextSample(timeout: Duration): Boolean {
        val before = device.state.value.currentHeartRate?.timestamp
        return withTimeoutOrNull(timeout) {
            device.state.first { state ->
                val timestamp = state.currentHeartRate?.timestamp
                timestamp != null && timestamp != before
            }
        } != null
    }

    private fun setStatus(status: HeartRateRecoveryStatus) {
        _status.value = status
    }

    companion object {
        private const val TAG = "HeartRateWatchdog"
        val FIRST_SAMPLE_TIMEOUT = 8.seconds
        val STALL_TIMEOUT = 10.seconds
        val INIT_SETTLE = 4.seconds
        val REBUILD_SETTLE = 3.seconds
        val GATE_SETTLE = 500.milliseconds
    }
}

/** Lets screens read watchdog state and trigger a retry; the service owns the instances. */
object HeartRateWatchdogs {
    private val watchdogs = MutableStateFlow<Map<MacAddress, HeartRateWatchdog>>(emptyMap())

    fun register(macAddress: MacAddress, watchdog: HeartRateWatchdog) {
        watchdogs.update { it + (macAddress to watchdog) }
    }

    fun unregister(macAddress: MacAddress) {
        watchdogs.update { it - macAddress }
    }

    fun status(macAddress: MacAddress): Flow<HeartRateRecoveryStatus> =
        watchdogs.flatMapLatest { map -> map[macAddress]?.status ?: flowOf(HeartRateRecoveryStatus.OFF) }

    fun retry(macAddress: MacAddress) {
        watchdogs.value[macAddress]?.retry()
    }
}
