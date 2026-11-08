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

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.workout.HealthConnectSessionExportState
import me.kavishdevar.librepods.data.workout.HeartRateZone
import me.kavishdevar.librepods.data.workout.HeartRateZones
import me.kavishdevar.librepods.data.workout.WorkoutDetail
import me.kavishdevar.librepods.data.workout.WorkoutSampleEntity
import me.kavishdevar.librepods.data.workout.WorkoutSummary
import me.kavishdevar.librepods.export.workout.WorkoutFileExporter
import me.kavishdevar.librepods.health.workout.AndroidWorkoutHealthConnectExporter
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/** Active workout: start/stop, live BPM and zone, duration, avg/max, chart, zone split. */
@Composable
fun WorkoutRoute(
    navigateBack: (() -> Unit)?,
    navigateToHistory: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as LibrePodsApplication
    val repository = app.workoutRepository
    val preferences = app.workoutPreferences
    val workout by repository.activeWorkout.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var backupNotice by remember { mutableStateOf(app.workoutDatabaseBackupNotice()) }

    LaunchedEffect(workout?.summary?.id) {
        while (workout != null) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    WorkoutPage(title = stringResource(R.string.workout), navigateBack = navigateBack) {
        backupNotice?.let { name ->
            StyledListItem(
                contentText = stringResource(R.string.workout_db_backup_notice, name),
                supportingText = stringResource(R.string.workout_db_backup_dismiss),
                onClick = {
                    app.dismissWorkoutDatabaseBackupNotice()
                    backupNotice = null
                },
                orientation = StyledListItemOrientation.Vertical,
            )
        }

        val detail = workout
        if (detail == null) {
            StyledListItem(
                contentText = stringResource(R.string.workout_none_active),
                supportingText = stringResource(R.string.workout_none_active_description),
                orientation = StyledListItemOrientation.Vertical,
            )
            StyledButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        repository.startWorkout()
                        // The heart-rate watchdog starts (and keeps) the stream once this is on.
                        app.heartRatePreferences.setMonitoringEnabled(true)
                    }
                },
            ) { Text(stringResource(R.string.workout_start)) }

            MaxHeartRateSlider(
                initial = preferences.maxHeartRateBpm,
                onChange = { preferences.maxHeartRateBpm = it },
            )
        } else {
            WorkoutSummaryCard(detail, now, live = true)
            if (detail.samples.isEmpty()) {
                Text(
                    stringResource(R.string.workout_no_samples_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            WorkoutChartCard(
                samples = detail.samples,
                startTimeMillis = detail.summary.startTimeEpochMillis,
                endTimeMillis = now,
            )
            ZoneCard(detail.zones, detail.summary.maxHeartRateBpm)
            StyledButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { repository.finishWorkout(detail.summary.id) } },
            ) { Text(stringResource(R.string.workout_finish)) }
        }

        StyledListItem(
            contentText = stringResource(R.string.workout_history),
            supportingText = stringResource(R.string.workout_history_description),
            onClick = navigateToHistory,
        )
    }
}

@Composable
fun WorkoutHistoryRoute(
    navigateBack: (() -> Unit)?,
    navigateToDetail: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as LibrePodsApplication
    val repository = app.workoutRepository
    val history by repository.history.collectAsState(initial = emptyList())

    WorkoutPage(title = stringResource(R.string.workout_history), navigateBack = navigateBack) {
        if (history.isEmpty()) {
            Text(
                stringResource(R.string.workout_history_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        history.forEachIndexed { index, session ->
            HistoryItem(
                session = session,
                index = index,
                count = history.size,
                onClick = { navigateToDetail(session.id) },
            )
        }
    }
}

@Composable
fun WorkoutDetailRoute(
    sessionId: String,
    navigateBack: (() -> Unit)?,
) {
    val context = LocalContext.current
    val app = context.applicationContext as LibrePodsApplication
    val repository = app.workoutRepository
    val detail by repository.workout(sessionId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (AndroidWorkoutHealthConnectExporter.WRITE_EXERCISE_PERMISSION in granted) {
            scope.launch { repository.retryHealthConnectExport(sessionId) }
        }
    }

    WorkoutPage(title = stringResource(R.string.workout_detail), navigateBack = navigateBack) {
        val workout = detail
        if (workout == null) {
            Text(
                stringResource(R.string.workout_not_found),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@WorkoutPage
        }
        Text(
            formatDateTime(workout.summary.startTimeEpochMillis),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        WorkoutSummaryCard(workout, workout.summary.endTimeEpochMillis ?: System.currentTimeMillis(), live = false)
        WorkoutChartCard(
            samples = workout.samples,
            startTimeMillis = workout.summary.startTimeEpochMillis,
            endTimeMillis = workout.summary.endTimeEpochMillis,
        )
        ZoneCard(workout.zones, workout.summary.maxHeartRateBpm)
        HealthConnectSessionItem(
            workout = workout,
            onRequestPermission = {
                permissionLauncher.launch(AndroidWorkoutHealthConnectExporter.REQUIRED_PERMISSIONS)
            },
            onRetry = { scope.launch { repository.retryHealthConnectExport(sessionId) } },
        )
        StyledListItem(
            contentText = stringResource(R.string.workout_export_csv),
            supportingText = stringResource(R.string.workout_export_csv_description),
            onClick = { scope.launch { exportAndShare(context, workout, WorkoutExportFormat.CSV) } },
            index = 0,
            count = 2,
        )
        StyledListItem(
            contentText = stringResource(R.string.workout_export_fit),
            supportingText = stringResource(R.string.workout_export_fit_description),
            onClick = { scope.launch { exportAndShare(context, workout, WorkoutExportFormat.FIT) } },
            index = 1,
            count = 2,
        )
        StyledButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showDeleteConfirmation = true },
        ) { Text(stringResource(R.string.workout_delete), color = MaterialTheme.colorScheme.error) }

        if (showDeleteConfirmation) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmation = false },
                title = { Text(stringResource(R.string.workout_delete_confirm_title)) },
                text = { Text(stringResource(R.string.workout_delete_confirm_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirmation = false
                        scope.launch {
                            if (repository.deleteWorkout(sessionId)) navigateBack?.invoke()
                        }
                    }) { Text(stringResource(R.string.workout_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmation = false }) {
                        Text(stringResource(R.string.cancel_workout_delete))
                    }
                },
            )
        }
    }
}

@Composable
private fun WorkoutPage(
    title: String,
    navigateBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    StyledScaffold(title = title, navigateBack = navigateBack) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(topPadding))
            content()
            Spacer(Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun MaxHeartRateSlider(initial: Int, onChange: (Int) -> Unit) {
    val sliderValue = remember { mutableFloatStateOf(initial.toFloat()) }
    LaunchedEffect(sliderValue) {
        snapshotFlow { sliderValue.floatValue.roundToInt() }
            .debounce(250.milliseconds)
            .collect { onChange(it) }
    }
    StyledSlider(
        label = stringResource(R.string.workout_max_heart_rate),
        value = sliderValue.floatValue,
        onValueChange = { sliderValue.floatValue = it },
        valueRange = HeartRateZones.MIN_CONFIGURABLE_MAX_HEART_RATE_BPM.toFloat()..
            HeartRateZones.MAX_CONFIGURABLE_MAX_HEART_RATE_BPM.toFloat(),
        description = stringResource(R.string.workout_max_heart_rate_description, sliderValue.floatValue.roundToInt()),
        independent = true,
    )
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun WorkoutSummaryCard(detail: WorkoutDetail, nowMillis: Long, live: Boolean) {
    val summary = detail.summary
    val end = summary.endTimeEpochMillis ?: nowMillis
    CardBox {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    summary.latestBpm?.toString() ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(if (live) R.string.workout_current_bpm else R.string.workout_last_bpm),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatDuration((end - summary.startTimeEpochMillis).coerceAtLeast(0L)),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(stringResource(R.string.workout_duration), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (live) {
            summary.latestBpm?.let { bpm ->
                val zone = detail.zones[HeartRateZones.zoneIndex(bpm, summary.maxHeartRateBpm)]
                Text(
                    stringResource(R.string.workout_live_zone, zone.label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(stringResource(R.string.workout_min), summary.minBpm?.toString() ?: "—")
            Stat(stringResource(R.string.workout_avg), summary.avgBpm?.roundToInt()?.toString() ?: "—")
            Stat(stringResource(R.string.workout_max), summary.maxBpm?.toString() ?: "—")
            Stat(stringResource(R.string.workout_samples), summary.sampleCount.toString())
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkoutChartCard(
    samples: List<WorkoutSampleEntity>,
    startTimeMillis: Long? = null,
    endTimeMillis: Long? = null,
) {
    CardBox {
        Text(stringResource(R.string.heart_rate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (samples.size < 2) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.workout_chart_needs_samples), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            HeartRateCanvas(
                samples = samples,
                startTimeMillis = startTimeMillis,
                endTimeMillis = endTimeMillis,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
    }
}

@Composable
private fun HeartRateCanvas(
    samples: List<WorkoutSampleEntity>,
    startTimeMillis: Long? = null,
    endTimeMillis: Long? = null,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val ordered = samples.sortedBy { it.timestampEpochMillis }
        val minTime = minOf(ordered.first().timestampEpochMillis, startTimeMillis ?: ordered.first().timestampEpochMillis)
        val maxTime = maxOf(ordered.last().timestampEpochMillis, endTimeMillis ?: ordered.last().timestampEpochMillis)
            .coerceAtLeast(minTime + 1L)
        val minBpm = (ordered.minOf { it.bpm } - 5).coerceAtLeast(20)
        val maxBpm = (ordered.maxOf { it.bpm } + 5).coerceAtLeast(minBpm + 1)
        repeat(4) { row ->
            val y = size.height * row / 3f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val path = Path()
        var previousTimestamp: Long? = null
        ordered.forEach { sample ->
            val x = ((sample.timestampEpochMillis - minTime).toFloat() / (maxTime - minTime).toFloat()) * size.width
            val y = size.height - ((sample.bpm - minBpm).toFloat() / (maxBpm - minBpm).toFloat()) * size.height
            val previous = previousTimestamp
            // A long gap means no readings were recorded; start a new segment instead of drawing a slope.
            if (previous == null || sample.timestampEpochMillis - previous > HEART_RATE_GRAPH_GAP_MILLIS) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
            previousTimestamp = sample.timestampEpochMillis
        }
        drawPath(path, lineColor, style = Stroke(width = 4f))
    }
}

private const val HEART_RATE_GRAPH_GAP_MILLIS = 4_000L

@Composable
private fun ZoneCard(zones: List<HeartRateZone>, maxHeartRateBpm: Int) {
    val total = zones.sumOf { it.sampleCount }.coerceAtLeast(1)
    CardBox {
        Text(stringResource(R.string.workout_zones), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.workout_zones_max, maxHeartRateBpm),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        zones.forEach { zone ->
            val sharePercent = (zone.sampleCount.toDouble() / total.toDouble() * 100.0).roundToInt()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${zone.label}  ${zoneBpmRange(zone, maxHeartRateBpm)}")
                Text("$sharePercent%", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = { zone.sampleCount.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HistoryItem(session: WorkoutSummary, index: Int, count: Int, onClick: () -> Unit) {
    val duration = (session.endTimeEpochMillis ?: session.startTimeEpochMillis) - session.startTimeEpochMillis
    StyledListItem(
        contentText = formatDateTime(session.startTimeEpochMillis),
        supportingText = stringResource(
            R.string.workout_history_item,
            formatDuration(duration),
            session.avgBpm?.roundToInt()?.toString() ?: "—",
            session.maxBpm?.toString() ?: "—",
        ),
        onClick = onClick,
        index = index,
        count = count,
    )
}

@Composable
private fun HealthConnectSessionItem(
    workout: WorkoutDetail,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
) {
    val state = workout.summary.healthConnectExportState
    val action: (() -> Unit)? = when (state) {
        HealthConnectSessionExportState.PERMISSION_REQUIRED -> onRequestPermission
        HealthConnectSessionExportState.UNAVAILABLE,
        HealthConnectSessionExportState.ERROR,
        HealthConnectSessionExportState.PENDING -> onRetry
        else -> null
    }
    val label = stringResource(healthStateLabel(state))
    StyledListItem(
        contentText = stringResource(R.string.workout_health_connect),
        supportingText = listOfNotNull(
            label,
            workout.summary.healthConnectExportMessage,
            if (action != null) stringResource(R.string.workout_health_connect_tap) else null,
        ).joinToString("\n"),
        onClick = action,
        orientation = StyledListItemOrientation.Vertical,
    )
}

private fun zoneBpmRange(zone: HeartRateZone, maxHeartRateBpm: Int): String {
    fun firstBpmAtPercent(percent: Int): Int = ceil(maxHeartRateBpm * percent / 100.0).toInt()
    return when {
        zone.minimumPercent == null -> "<${firstBpmAtPercent(zone.maximumPercentExclusive!!)} BPM"
        zone.maximumPercentExclusive == null -> "≥${firstBpmAtPercent(zone.minimumPercent)} BPM"
        else -> "${firstBpmAtPercent(zone.minimumPercent)}–${firstBpmAtPercent(zone.maximumPercentExclusive) - 1} BPM"
    }
}

private enum class WorkoutExportFormat { CSV, FIT }

private suspend fun exportAndShare(context: Context, workout: WorkoutDetail, format: WorkoutExportFormat) {
    try {
        val exported = withContext(Dispatchers.IO) {
            when (format) {
                WorkoutExportFormat.CSV -> WorkoutFileExporter.exportCsv(context, workout)
                WorkoutExportFormat.FIT -> WorkoutFileExporter.exportFit(context, workout)
            }
        }
        WorkoutFileExporter.share(context, exported)
    } catch (error: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.workout_export_failed, format.name, error.message ?: error.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

private fun healthStateLabel(state: HealthConnectSessionExportState): Int = when (state) {
    HealthConnectSessionExportState.NOT_FINISHED -> R.string.workout_hc_not_finished
    HealthConnectSessionExportState.PENDING -> R.string.workout_hc_pending
    HealthConnectSessionExportState.EXPORTED -> R.string.workout_hc_exported
    HealthConnectSessionExportState.PERMISSION_REQUIRED -> R.string.workout_hc_permission_required
    HealthConnectSessionExportState.UNAVAILABLE -> R.string.workout_hc_unavailable
    HealthConnectSessionExportState.ERROR -> R.string.workout_hc_error
}
