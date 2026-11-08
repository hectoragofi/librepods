package me.kavishdevar.librepods.presentation.screens.spatial

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import me.kavishdevar.librepods.spatial.SpatialAudioController
import me.kavishdevar.librepods.spatial.SpatialAudioPreferences

private data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?, val media: Boolean)

/** Pick the apps whose audio is captured and spatialized. */
@Composable
fun SpatialAudioAppsRoute(navigateBack: (() -> Unit)?) {
    val context = LocalContext.current
    val controller = remember { SpatialAudioController.get(context) }
    val selected by controller.packages.collectAsState()
    var showAll by rememberSaveable { mutableStateOf(false) }
    val apps by produceState<List<AppEntry>?>(null, showAll) {
        value = withContext(Dispatchers.IO) { loadApps(context, showAll, controller.packages.value) }
    }

    StyledScaffold(title = stringResource(R.string.spatial_apps), navigateBack = navigateBack) { topPadding, bottomPadding ->
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(topPadding))
            StyledToggle(
                label = stringResource(R.string.spatial_apps_show_all),
                checked = showAll,
                onCheckedChange = { showAll = it },
            )
            val list = apps
            if (list != null) {
                StyledList(description = stringResource(R.string.spatial_apps_description)) {
                    list.forEach { app ->
                        styledToggle(
                            label = app.label,
                            checked = app.pkg in selected,
                            onCheckedChange = { on ->
                                controller.setPackages(if (on) selected + app.pkg else selected - app.pkg)
                            },
                        )
                    }
                }
            } else {
                Text("…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(bottomPadding))
        }
    }
}

@Suppress("unused")
@Composable
private fun AppIcon(icon: ImageBitmap?) {
    if (icon != null) Image(icon, null, Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)))
}

/**
 * Media apps = apps exposing a MediaBrowserService or a media-button receiver, plus the defaults
 * and anything already selected. With [all], every launchable app.
 */
private fun loadApps(context: Context, all: Boolean, selected: Set<String>): List<AppEntry> {
    val pm = context.packageManager
    val media = HashSet<String>()
    try {
        pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), PackageManager.MATCH_ALL)
            .mapTo(media) { it.serviceInfo.packageName }
        pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), PackageManager.MATCH_ALL)
            .mapTo(media) { it.activityInfo.packageName }
    } catch (_: Exception) {
    }
    media += SpatialAudioPreferences.DEFAULT_PACKAGES
    val candidates = HashSet<String>(media)
    candidates += selected
    if (all) {
        try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapTo(candidates) { it.activityInfo.packageName }
        } catch (_: Exception) {
        }
    }
    candidates -= context.packageName
    return candidates.mapNotNull { pkg ->
        try {
            val info = pm.getApplicationInfo(pkg, 0)
            val icon = try { pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }
            AppEntry(pkg, pm.getApplicationLabel(info).toString(), icon, pkg in media)
        } catch (_: Exception) {
            null // not installed
        }
    }.sortedWith(compareByDescending<AppEntry> { it.pkg in selected }.thenBy { it.label.lowercase() })
}
