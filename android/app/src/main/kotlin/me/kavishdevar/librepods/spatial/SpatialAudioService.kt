package me.kavishdevar.librepods.spatial

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.activities.MainActivity

/**
 * Foreground anchor for Spatial Audio: while it runs, the Shizuku user service stays bound so
 * rendering continues with the screen off and the app closed. It does no audio work itself and
 * holds no wake lock; the shell service is idle unless a selected app plays to Bluetooth.
 */
class SpatialAudioService : Service() {

    private val controller by lazy { SpatialAudioController.get(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startInForeground()
        controller.attach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OFF -> {
                controller.setEnabled(false)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MODE -> controller.toggleMode()
        }
        if (!controller.enabled.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        controller.attach()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        controller.detach()
        super.onDestroy()
    }

    private fun startInForeground() {
        try {
            startForeground(NOTIFICATION_ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    companion object {
        private const val TAG = "SpatialAudioService"
        private const val CHANNEL = "spatial_audio"
        private const val NOTIFICATION_ID = 4217
        const val ACTION_OFF = "me.kavishdevar.librepods.spatial.OFF"
        const val ACTION_TOGGLE_MODE = "me.kavishdevar.librepods.spatial.TOGGLE_MODE"

        @Volatile
        private var running = false

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, SpatialAudioService::class.java))
            } catch (t: Throwable) {
                // Background start not allowed right now; the app starts it again when opened.
                Log.w(TAG, "could not start: $t")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SpatialAudioService::class.java))
        }

        fun refreshNotification(context: Context) {
            if (!running) return
            try {
                context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(context))
            } catch (_: SecurityException) {
            }
        }

        private fun build(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.spatial_audio), NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    }
                )
            }
            val c = SpatialAudioController.get(context)
            val preset = c.currentPreset().name + if (c.isModified()) " · " + context.getString(R.string.spatial_edited) else ""
            val own = c.mode.value == SpatialAudioPreferences.MODE_OWN
            val state = context.getString(stateText(c.status.value.state, c.shizuku.value))
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            fun action(action: String, req: Int) = PendingIntent.getService(
                context, req, Intent(context, SpatialAudioService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_airpods)
                .setContentTitle(context.getString(R.string.spatial_audio) + " · " + (if (own) "OWN 3D" else "NORMAL"))
                .setContentText("$preset · $state")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, context.getString(if (own) R.string.spatial_switch_normal else R.string.spatial_switch_own), action(ACTION_TOGGLE_MODE, 1))
                .addAction(0, context.getString(R.string.spatial_turn_off), action(ACTION_OFF, 2))
                .build()
        }

        fun stateText(s: SpatialStatus.State, shizuku: SpatialAudioController.ShizukuState): Int = when {
            shizuku == SpatialAudioController.ShizukuState.NOT_RUNNING ||
                shizuku == SpatialAudioController.ShizukuState.NOT_INSTALLED -> R.string.spatial_state_shizuku_off
            shizuku == SpatialAudioController.ShizukuState.NO_PERMISSION ||
                shizuku == SpatialAudioController.ShizukuState.DENIED -> R.string.spatial_state_shizuku_permission
            shizuku == SpatialAudioController.ShizukuState.READY -> R.string.spatial_state_connecting
            else -> when (s) {
                SpatialStatus.State.OFF -> R.string.spatial_state_off
                SpatialStatus.State.NO_APPS -> R.string.spatial_state_no_apps
                SpatialStatus.State.NO_OUTPUT -> R.string.spatial_state_no_output
                SpatialStatus.State.WAITING -> R.string.spatial_state_waiting
                SpatialStatus.State.SILENT -> R.string.spatial_state_silent
                SpatialStatus.State.RENDERING -> R.string.spatial_state_rendering
                SpatialStatus.State.ERROR -> R.string.spatial_state_error
            }
        }
    }
}
