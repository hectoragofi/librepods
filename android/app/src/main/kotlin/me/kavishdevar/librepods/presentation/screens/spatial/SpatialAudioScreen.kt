package me.kavishdevar.librepods.presentation.screens.spatial

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import local.librepods.spatialshizuku.Own3D
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledBottomSheet
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledInputField
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.spatial.SpatialAudioController
import me.kavishdevar.librepods.spatial.SpatialAudioController.ShizukuState
import me.kavishdevar.librepods.spatial.SpatialAudioPreferences
import me.kavishdevar.librepods.spatial.SpatialAudioService
import me.kavishdevar.librepods.spatial.SpatialParam
import me.kavishdevar.librepods.spatial.SpatialParamTable
import me.kavishdevar.librepods.spatial.SpatialPreset
import me.kavishdevar.librepods.spatial.SpatialStatus
import java.util.Locale
import kotlin.math.roundToInt

private val SPEAKER_KEYS = listOf("fl", "fr", "c", "sl", "sr", "rl", "rr")

@Composable
fun SpatialAudioRoute(
    navigateBack: (() -> Unit)?,
    navigateToApps: () -> Unit,
) {
    val context = LocalContext.current
    val controller = remember { SpatialAudioController.get(context) }
    val enabled by controller.enabled.collectAsState()
    val mode by controller.mode.collectAsState()
    val packages by controller.packages.collectAsState()
    val presetId by controller.presetId.collectAsState()
    val values by controller.values.collectAsState()
    val userPresets by controller.userPresets.collectAsState()
    val shizuku by controller.shizuku.collectAsState()
    val status by controller.status.collectAsState()
    val diagnostics by controller.diagnostics.collectAsState()
    val table = controller.table

    // Shizuku can be started/granted outside the app: refresh whenever the screen resumes.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { controller.refreshShizuku() }
    }

    val backdrop = rememberLayerBackdrop()
    var speakerSheet by rememberSaveable { mutableStateOf<Int?>(null) }
    var nameSheet by remember { mutableStateOf<NameSheet?>(null) }
    var presetActions by remember { mutableStateOf<SpatialPreset?>(null) }
    var importSheet by remember { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    StyledScaffold(
        title = stringResource(R.string.spatial_audio),
        navigateBack = navigateBack,
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .layerBackdrop(backdrop)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(topPadding))

            if (table == null) {
                Notice(stringResource(R.string.spatial_unavailable))
                Spacer(Modifier.height(bottomPadding))
                return@Column
            }

            StyledToggle(
                label = stringResource(R.string.spatial_audio),
                description = stateLine(context, enabled, status, shizuku, packages.isEmpty(), controller),
                checked = enabled,
                header = true,
                onCheckedChange = { on ->
                    controller.setEnabled(on)
                    if (on && shizuku == ShizukuState.NO_PERMISSION) controller.requestShizukuPermission()
                },
            )

            AnimatedVisibility(
                visible = shizuku != ShizukuState.CONNECTED && (enabled || shizuku != ShizukuState.READY),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ShizukuSetup(shizuku, controller)
            }

            ModeSwitch(
                own = mode == SpatialAudioPreferences.MODE_OWN,
                onChange = { own -> controller.setMode(if (own) SpatialAudioPreferences.MODE_OWN else SpatialAudioPreferences.MODE_NORMAL) },
            )

            StyledList(title = stringResource(R.string.spatial_apps), description = stringResource(R.string.spatial_apps_description)) {
                styledListItem(
                    contentText = stringResource(R.string.spatial_apps),
                    supportingText = appSummary(context, packages),
                    onClick = navigateToApps,
                    orientation = StyledListItemOrientation.Vertical,
                    trailingContent = { Chevron() },
                )
                if (enabled && status.playing.isNotEmpty()) {
                    styledListItem(
                        contentText = stringResource(R.string.spatial_now_playing),
                        supportingText = status.playing.joinToString { appLabel(context, it) },
                        orientation = StyledListItemOrientation.Vertical,
                    )
                }
            }

            SectionTitle(stringResource(R.string.spatial_speakers))
            Card {
                SpatialHeadMap(
                    speakers = mapSpeakers(table, values),
                    sub = subSpeaker(table, values),
                    selected = speakerSheet,
                    modifier = Modifier.padding(8.dp),
                    onMove = { i, az, dist ->
                        controller.setParam(table.id("${SPEAKER_KEYS[i]}_az"), az)
                        controller.setParam(table.id("${SPEAKER_KEYS[i]}_dist"), dist)
                    },
                    onTap = { speakerSheet = it },
                )
                Text(
                    stringResource(R.string.spatial_speakers_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }

            SectionTitle(stringResource(R.string.spatial_presets))
            PresetGrid(
                presets = controller.builtInPresets,
                selectedId = presetId,
                modified = controller.isModified(values),
                onSelect = { controller.selectPreset(it.id) },
                onLongPress = {},
            )
            if (userPresets.isNotEmpty()) {
                SectionTitle(stringResource(R.string.spatial_my_presets))
                PresetGrid(
                    presets = userPresets,
                    selectedId = presetId,
                    modified = controller.isModified(values),
                    onSelect = { controller.selectPreset(it.id) },
                    onLongPress = { presetActions = it },
                )
            }
            StyledList {
                styledListItem(contentText = stringResource(R.string.spatial_save_preset), onClick = { nameSheet = NameSheet.Save })
                styledListItem(contentText = stringResource(R.string.spatial_import), onClick = { importSheet = true })
                styledListItem(contentText = stringResource(R.string.spatial_export), onClick = {
                    copy(context, controller.exportCurrent())
                    Toast.makeText(context, R.string.spatial_copied, Toast.LENGTH_SHORT).show()
                })
                styledListItem(contentText = stringResource(R.string.spatial_reset), onClick = { controller.resetToDefault() })
            }

            // ---- Advanced ----
            Disclosure(stringResource(R.string.spatial_advanced), advancedOpen) { advancedOpen = !advancedOpen }
            AnimatedVisibility(visible = advancedOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ParamGroup(stringResource(R.string.spatial_group_upmix), table.group("Upmix"), values, controller::setParam)
                    ParamGroup(stringResource(R.string.spatial_group_tone), table.group("Tone"), values, controller::setParam)
                    ParamGroup(stringResource(R.string.spatial_group_room), table.group("Room"), values, controller::setParam)
                    ParamGroup(stringResource(R.string.spatial_group_global), table.group("Global").filter { it.key != "hrtf" }, values, controller::setParam)
                    table["hrtf"]?.let { p ->
                        HeadModel(p, values, controller::setParam)
                    }
                    StyledList {
                        styledToggle(
                            label = stringResource(R.string.spatial_diagnostics),
                            description = stringResource(R.string.spatial_diagnostics_description),
                            checked = diagnostics,
                            onCheckedChange = controller::setDiagnostics,
                        )
                    }
                    if (diagnostics) Diagnostics(status, shizuku, controller)
                }
            }

            Spacer(Modifier.height(bottomPadding))
        }
    }

    // ---- sheets ----
    StyledBottomSheet(visible = speakerSheet != null, onDismiss = { speakerSheet = null }, backdrop = backdrop) { _, _ ->
        val i = speakerSheet ?: return@StyledBottomSheet
        if (table != null) SpeakerSheet(i, table, values, controller::setParam)
    }

    StyledBottomSheet(visible = nameSheet != null, onDismiss = { nameSheet = null }, backdrop = backdrop) { _, _ ->
        val sheet = nameSheet ?: return@StyledBottomSheet
        NameEditor(
            initial = (sheet as? NameSheet.Rename)?.preset?.name.orEmpty(),
            onDone = { name ->
                when (sheet) {
                    NameSheet.Save -> controller.saveUserPreset(name)
                    is NameSheet.Rename -> controller.renameUserPreset(sheet.preset.id, name)
                }
                nameSheet = null
            },
            onCancel = { nameSheet = null },
        )
    }

    StyledBottomSheet(visible = presetActions != null, onDismiss = { presetActions = null }, backdrop = backdrop) { _, _ ->
        val p = presetActions ?: return@StyledBottomSheet
        Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp))
            StyledList {
                styledListItem(contentText = stringResource(R.string.spatial_rename), onClick = { presetActions = null; nameSheet = NameSheet.Rename(p) })
                styledListItem(contentText = stringResource(R.string.spatial_update_preset), onClick = { controller.updateUserPreset(p.id); presetActions = null })
                styledListItem(contentText = stringResource(R.string.spatial_export), onClick = {
                    if (table != null) copy(context, me.kavishdevar.librepods.spatial.SpatialPresetCodec.export(table, me.kavishdevar.librepods.spatial.SpatialPresetCodec.apply(table, p.params), p.name))
                    Toast.makeText(context, R.string.spatial_copied, Toast.LENGTH_SHORT).show()
                    presetActions = null
                })
                styledListItem(contentText = stringResource(R.string.spatial_delete), onClick = { controller.deleteUserPreset(p.id); presetActions = null })
            }
        }
    }

    StyledBottomSheet(visible = importSheet, onDismiss = { importSheet = false }, backdrop = backdrop) { _, _ ->
        ImportEditor(
            clipboard = remember { clipboardText(context) },
            onApply = { json, name ->
                if (controller.importJson(json, name)) {
                    importSheet = false
                } else {
                    Toast.makeText(context, R.string.spatial_import_invalid, Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { importSheet = false },
        )
    }
}

private sealed interface NameSheet {
    data object Save : NameSheet
    data class Rename(val preset: SpatialPreset) : NameSheet
}

// ---------------- pieces ----------------

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
private fun Notice(text: String) {
    Card {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Chevron() {
    Icon(LocalIcons.current.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
}

@Composable
private fun Disclosure(title: String, open: Boolean, onToggle: () -> Unit) {
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Icon(LocalIcons.current.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).rotate(angle))
    }
}

/** NORMAL vs OWN 3D, a two-segment control with a sliding thumb. */
@Composable
private fun ModeSwitch(own: Boolean, onChange: (Boolean) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.spatial_mode), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(3.dp)
            ) {
                val half = maxWidth / 2
                val x by animateDpAsState(if (own) half else 0.dp, label = "thumb")
                Box(
                    Modifier
                        .offset(x = x)
                        .width(half)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(19.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                    Segment(stringResource(R.string.spatial_mode_normal), !own, Modifier.weight(1f)) { onChange(false) }
                    Segment(stringResource(R.string.spatial_mode_own), own, Modifier.weight(1f)) { onChange(true) }
                }
            }
            Text(stringResource(R.string.spatial_mode_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, label = "seg")
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(19.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ShizukuSetup(state: ShizukuState, controller: SpatialAudioController) {
    val context = LocalContext.current
    val (title, help, action) = when (state) {
        ShizukuState.NOT_INSTALLED -> Triple(R.string.spatial_shizuku_not_installed, R.string.spatial_shizuku_not_installed_help, R.string.spatial_shizuku_install)
        ShizukuState.NOT_RUNNING -> Triple(R.string.spatial_shizuku_not_running, R.string.spatial_shizuku_not_running_help, R.string.spatial_shizuku_open)
        ShizukuState.UNSUPPORTED -> Triple(R.string.spatial_shizuku_not_running, R.string.spatial_shizuku_unsupported, R.string.spatial_shizuku_open)
        ShizukuState.NO_PERMISSION -> Triple(R.string.spatial_shizuku_permission, R.string.spatial_shizuku_permission_help, R.string.spatial_shizuku_grant)
        ShizukuState.DENIED -> Triple(R.string.spatial_shizuku_permission, R.string.spatial_shizuku_denied_help, R.string.spatial_shizuku_open)
        ShizukuState.READY -> Triple(R.string.spatial_shizuku_ready, R.string.spatial_state_connecting, null)
        ShizukuState.CONNECTED -> Triple(R.string.spatial_shizuku_connected, R.string.spatial_state_connecting, null)
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.spatial_shizuku), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(stringResource(title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                StyledButton(
                    onClick = {
                        when (state) {
                            ShizukuState.NOT_INSTALLED -> open(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                            ShizukuState.NO_PERMISSION -> controller.requestShizukuPermission()
                            else -> controller.shizukuLaunchIntent()?.let { open(context, it) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    maxScale = 0.05f,
                    surfaceColor = MaterialTheme.colorScheme.primary,
                ) {
                    Text(stringResource(action), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetGrid(
    presets: List<SpatialPreset>,
    selectedId: String,
    modified: Boolean,
    onSelect: (SpatialPreset) -> Unit,
    onLongPress: (SpatialPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        presets.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p ->
                    val selected = p.id == selectedId
                    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "presetBorder")
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 84.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(2.dp, border, RoundedCornerShape(20.dp))
                            .combinedClickable(onClick = { onSelect(p) }, onLongClick = { onLongPress(p) })
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(LocalIcons.current.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            if (selected && modified) p.description + " · " + stringResource(R.string.spatial_edited) else p.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A group of parameter sliders in one rounded list. */
@Composable
private fun ParamGroup(title: String, params: List<SpatialParam>, values: FloatArray, onChange: (Int, Float) -> Unit) {
    if (params.isEmpty()) return
    StyledList(title = title) {
        params.forEach { p ->
            if (p.isSwitch) {
                styledToggle(label = cleanLabel(p.label), checked = values[p.id] > 0.5f, onCheckedChange = { onChange(p.id, if (it) 1f else 0f) })
            } else {
                styledListItem(
                    content = { ParamHeader(cleanLabel(p.label), format(p, values[p.id])) },
                    supportingContent = { ParamSlider(p, values[p.id], onChange) },
                    orientation = StyledListItemOrientation.Vertical,
                )
            }
        }
    }
}

@Composable
private fun ParamHeader(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ParamSlider(p: SpatialParam, value: Float, onChange: (Int, Float) -> Unit) {
    val steps = ((p.max - p.min) / p.step).roundToInt()
    Slider(
        value = value.coerceIn(p.min, p.max),
        onValueChange = { v -> onChange(p.id, p.min + ((v - p.min) / p.step).roundToInt() * p.step) },
        valueRange = p.min..p.max,
        // Material draws a tick per step; only ask for discrete ticks on short ranges.
        steps = if (steps in 1..12) steps - 1 else 0,
        colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HeadModel(p: SpatialParam, values: FloatArray, onChange: (Int, Float) -> Unit) {
    val current = values[p.id].roundToInt()
    StyledList(title = stringResource(R.string.spatial_group_head)) {
        Own3D.HRTF_NAMES.forEachIndexed { i, name ->
            styledListItem(
                contentText = name,
                onClick = { onChange(p.id, i.toFloat()) },
                trailingContent = if (i == current) {
                    { Icon(LocalIcons.current.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
}

@Composable
private fun SpeakerSheet(index: Int, table: SpatialParamTable, values: FloatArray, onChange: (Int, Float) -> Unit) {
    val isSub = index == SUB_INDEX
    val key = if (isSub) "sub" else SPEAKER_KEYS[index]
    Column(
        Modifier
            .padding(bottom = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (isSub) stringResource(R.string.spatial_sub_band) else speakerName(index),
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (isSub) {
            Text(stringResource(R.string.spatial_sub_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        }
        val sliders = if (isSub) listOf("sub_db" to R.string.spatial_level, "xover_hz" to null)
        else listOf("${key}_az" to R.string.spatial_azimuth, "${key}_el" to R.string.spatial_elevation, "${key}_dist" to R.string.spatial_distance, "${key}_db" to R.string.spatial_level)
        StyledList {
            sliders.forEach { (k, labelRes) ->
                val p = table[k] ?: return@forEach
                styledListItem(
                    content = { ParamHeader(labelRes?.let { stringResource(it) } ?: cleanLabel(p.label), format(p, values[p.id])) },
                    supportingContent = { ParamSlider(p, values[p.id], onChange) },
                    orientation = StyledListItemOrientation.Vertical,
                )
            }
        }
        val toggles = if (isSub) listOf("sub_mute" to R.string.spatial_mute)
        else listOf("${key}_mute" to R.string.spatial_mute, "${key}_solo" to R.string.spatial_solo, "${key}_direct" to R.string.spatial_direct)
        StyledList {
            toggles.forEach { (k, labelRes) ->
                val p = table[k] ?: return@forEach
                styledToggle(label = stringResource(labelRes), checked = values[p.id] > 0.5f, onCheckedChange = { onChange(p.id, if (it) 1f else 0f) })
            }
        }
    }
}

@Composable
private fun NameEditor(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val state = remember { TextFieldState(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.spatial_preset_name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp))
        StyledInputField(state, focus, placeholder = stringResource(R.string.spatial_preset_name))
        SheetButtons(stringResource(R.string.spatial_save), enabled = state.text.isNotBlank(), onConfirm = { onDone(state.text.toString()) }, onCancel = onCancel)
    }
}

@Composable
private fun ImportEditor(clipboard: String?, onApply: (String, String?) -> Unit, onCancel: () -> Unit) {
    val json = remember { TextFieldState() }
    val name = remember { TextFieldState() }
    LaunchedEffect(clipboard) { if (clipboard != null && json.text.isEmpty()) json.setTextAndPlaceCursorAtEnd(clipboard) }
    Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.spatial_import_hint), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.heightIn(max = 220.dp)) {
            StyledInputField(json, remember { FocusRequester() }, placeholder = "{ \"params\": { … } }", singleLine = false)
        }
        StyledInputField(name, remember { FocusRequester() }, placeholder = stringResource(R.string.spatial_import_save_as))
        SheetButtons(stringResource(R.string.spatial_import_apply), enabled = json.text.isNotBlank(), onConfirm = { onApply(json.text.toString(), name.text.toString().ifBlank { null }) }, onCancel = onCancel)
    }
}

@Composable
private fun SheetButtons(confirm: String, enabled: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StyledButton(onClick = onCancel, modifier = Modifier.weight(1f), maxScale = 0.05f) {
            Text(stringResource(R.string.spatial_cancel), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
        }
        StyledButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.weight(1f), maxScale = 0.05f, surfaceColor = MaterialTheme.colorScheme.primary) {
            Text(confirm, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Diagnostics(status: SpatialStatus, shizuku: ShizukuState, controller: SpatialAudioController) {
    var selfTest by remember { mutableStateOf<String?>(null) }
    val cpu = if (status.cpuMs >= 0) String.format(Locale.US, "%.1f%% · %.2f ms per 10 ms block (max %.2f ms)", status.cpuPercent, status.cpuMs, status.cpuMaxMs) else "–"
    StyledList(title = "Diagnostics") {
        styledListItem(contentText = "State", supportingText = "${status.state} · Shizuku $shizuku", orientation = StyledListItemOrientation.Vertical)
        styledListItem(contentText = stringResource(R.string.spatial_cpu), supportingText = cpu, orientation = StyledListItemOrientation.Vertical)
        styledListItem(
            contentText = "Signal",
            supportingText = String.format(Locale.US, "peak %d · agc %.2f · %s", status.peak, status.agc, status.routed ?: "no route"),
            orientation = StyledListItemOrientation.Vertical,
        )
        status.error?.let { styledListItem(contentText = "Last error", supportingText = it, orientation = StyledListItemOrientation.Vertical) }
        styledListItem(contentText = stringResource(R.string.spatial_self_test), supportingText = selfTest, onClick = {
            selfTest = "…"
            controller.selfTest { selfTest = it }
        }, orientation = StyledListItemOrientation.Vertical)
    }
    status.log?.let { log ->
        Card {
            Text(
                log.lines().takeLast(30).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).animateContentSize(),
            )
        }
    }
}

// ---------------- helpers ----------------

private fun mapSpeakers(table: SpatialParamTable, v: FloatArray): List<MapSpeaker> = SPEAKER_KEYS.mapIndexed { i, k ->
    MapSpeaker(
        name = speakerShort(i),
        azimuth = v.at(table, "${k}_az"),
        distance = v.at(table, "${k}_dist"),
        levelDb = v.at(table, "${k}_db"),
        muted = v.at(table, "${k}_mute") > 0.5f,
        solo = v.at(table, "${k}_solo") > 0.5f,
    )
}

private fun subSpeaker(table: SpatialParamTable, v: FloatArray) =
    MapSpeaker("SUB", 0f, 0f, v.at(table, "sub_db"), v.at(table, "sub_mute") > 0.5f, false)

private fun FloatArray.at(table: SpatialParamTable, key: String): Float = table.id(key).let { if (it in indices) this[it] else 0f }

private fun speakerShort(i: Int) = listOf("FL", "FR", "C", "SL", "SR", "RL", "RR")[i]

private fun speakerName(i: Int) = listOf("Front left", "Front right", "Centre", "Side left", "Side right", "Rear left", "Rear right")[i]

/** "Late reverb (dB re direct)" -> "Late reverb"; units go to the value. */
private fun cleanLabel(label: String) = label.substringBefore(" (").trim()

private fun format(p: SpatialParam, v: Float): String {
    val unit = Regex("\\(([^)]*)\\)").find(p.label)?.groupValues?.get(1)?.let { u ->
        when {
            u.startsWith("deg") -> "°"
            u == "x" -> "×"
            u.startsWith("dB") -> " dB"
            else -> " " + u
        }
    }.orEmpty()
    val num = when {
        p.step >= 1f -> String.format(Locale.US, "%.0f", v)
        p.step >= 0.1f -> String.format(Locale.US, "%.1f", v)
        else -> String.format(Locale.US, "%.2f", v)
    }
    return num + unit
}

private fun stateLine(
    context: Context, enabled: Boolean, status: SpatialStatus, shizuku: ShizukuState,
    noApps: Boolean, controller: SpatialAudioController,
): String {
    if (!enabled) return context.getString(R.string.spatial_audio_description)
    if (noApps) return context.getString(R.string.spatial_state_no_apps)
    val state = context.getString(SpatialAudioService.stateText(status.state, shizuku))
    return state + " · " + controller.currentPreset().name
}

private fun appSummary(context: Context, packages: Set<String>): String {
    if (packages.isEmpty()) return context.getString(R.string.spatial_apps_none)
    val installed = packages.filter { isInstalled(context, it) }
    val names = installed.map { appLabel(context, it) }.sorted()
    return names.joinToString().ifEmpty { context.getString(R.string.spatial_apps_none) }
}

internal fun isInstalled(context: Context, pkg: String) = try {
    context.packageManager.getApplicationInfo(pkg, 0); true
} catch (_: Exception) {
    false
}

internal fun appLabel(context: Context, pkg: String): String = try {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) {
    pkg
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("spatial audio", text))
}

private fun clipboardText(context: Context): String? = try {
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.trim().startsWith("{") }
} catch (_: Exception) {
    null
}
