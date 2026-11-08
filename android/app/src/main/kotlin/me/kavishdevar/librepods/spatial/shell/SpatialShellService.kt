package me.kavishdevar.librepods.spatial.shell

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import local.librepods.spatialshizuku.Own3D
import me.kavishdevar.librepods.spatial.ISpatialCallback
import me.kavishdevar.librepods.spatial.ISpatialService
import me.kavishdevar.librepods.spatial.SpatialStatus
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku user service (shell, UID 2000). Event driven and idle by default:
 *  - capture (AudioPolicy + render loop) only exists while the feature is enabled, a selected
 *    app is actually playing (AudioPlaybackCallback) and the media output is Bluetooth
 *    (AudioDeviceCallback + the render track's routing listener);
 *  - after [IDLE_STOP_MS] without playback the policy is unregistered;
 *  - inside a session, ~1 s of digital silence pauses the render track.
 * All state lives on one HandlerThread; binder calls just post to it.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class SpatialShellService() : ISpatialService.Stub() {

    private var appContext: Context? = null

    /** Shizuku calls this constructor (API 13+) with the app's package context. */
    constructor(context: Context) : this() {
        appContext = context
    }

    private val thread = HandlerThread("spatial-ctl").apply { start() }
    private val handler = Handler(thread.looper)

    // ---- state (handler thread only) ----
    private var enabled = false
    private var mode = CaptureEngine.MODE_OWN
    private var packages: List<String> = emptyList()
    private var uids = IntArray(0)
    private var params: FloatArray? = null
    private var diagnostics = false
    private var playing = emptySet<Int>()
    private var playbackWatch = false
    private var btOut = false
    /** Set when the render track was routed off Bluetooth; cleared by a device change or a BT media route. */
    private var routeBlocked = false
    private var pkgUids: Map<String, Int> = emptyMap()
    private var engine: CaptureEngine? = null
    private var own: Own3D? = null
    private var silent = true
    private var state = SpatialStatus.State.OFF
    private var lastError: String? = null
    private var lastStats: CaptureEngine.Stats? = null
    private var idleStopPending = false
    private var failures = 0
    private var callback: ISpatialCallback? = null
    private var clientToken: IBinder? = null
    private val log = ArrayDeque<String>()

    private val shell: Context? by lazy {
        try { ShellContext.get() } catch (t: Throwable) { record("shell context failed: $t"); null }
    }
    private val audio: AudioManager? by lazy { shell?.getSystemService(AudioManager::class.java) }
    private val nativeOk: Boolean by lazy {
        try {
            val info = appContext?.applicationInfo
            Own3D.load(info?.nativeLibraryDir, info?.sourceDir, Build.SUPPORTED_ABIS.firstOrNull())
            Own3D.params()
            true
        } catch (t: Throwable) {
            record("native renderer unavailable: $t")
            false
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            onPlayback(configs)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { routeBlocked = false; refreshOutput(); evaluate() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { routeBlocked = false; refreshOutput(); evaluate() }
    }

    private val idleStop = Runnable {
        idleStopPending = false
        if (engine != null && playing.none { it in uids }) {
            record("no playback for ${IDLE_STOP_MS / 1000}s, releasing capture")
            stopEngine()
            evaluate()
        }
    }

    private val retry = Runnable { evaluate() }

    init {
        handler.post {
            record("user service uid=${Process.myUid()} pid=${Process.myPid()}")
            val am = audio
            if (am != null) {
                try {
                    am.registerAudioPlaybackCallback(playbackCallback, handler)
                    playbackWatch = true
                    onPlayback(am.activePlaybackConfigurations)
                } catch (t: Throwable) {
                    record("playback watch unavailable, capturing whenever enabled: $t")
                }
                try { am.registerAudioDeviceCallback(deviceCallback, handler) } catch (t: Throwable) { record("device watch failed: $t") }
                refreshOutput()
            }
        }
    }

    // ---------------- binder API ----------------

    override fun setClient(token: IBinder?, cb: ISpatialCallback?) {
        handler.post {
            clientToken?.let { old -> try { old.unlinkToDeath(clientDeath, 0) } catch (_: Throwable) {} }
            clientToken = token
            callback = cb
            try { token?.linkToDeath(clientDeath, 0) } catch (_: Throwable) { clientGone() }
            push()
        }
    }

    private val clientDeath = IBinder.DeathRecipient { handler.post { clientGone() } }

    private fun clientGone() {
        record("app gone, stopping")
        callback = null
        clientToken = null
        enabled = false
        stopEngine()
        evaluate()
    }

    override fun configure(on: Boolean, m: Int, pkgs: Array<out String>?) {
        handler.post {
            enabled = on
            if (m == CaptureEngine.MODE_NORMAL || m == CaptureEngine.MODE_OWN) mode = m
            engine?.mode = mode
            val list = pkgs?.toList().orEmpty()
            if (list != packages) {
                packages = list
                uids = resolveUids(list)
            }
            failures = 0
            evaluate()
        }
    }

    override fun setParams(values: FloatArray?) {
        if (values == null) return
        handler.post {
            params = values.clone()
            own?.let { o ->
                val n = minOf(values.size, Own3D.params().size)
                for (i in 0 until n) o.setParam(i, values[i])
            }
        }
    }

    override fun setParam(id: Int, value: Float) {
        handler.post {
            val p = params ?: return@post
            if (id < 0 || id >= p.size) return@post
            p[id] = value
            own?.setParam(id, value)
        }
    }

    override fun setDiagnostics(on: Boolean) {
        handler.post {
            diagnostics = on
            engine?.diagnostics = on
            push()
        }
    }

    override fun status(): String {
        var json = ""
        val latch = CountDownLatch(1)
        handler.post { json = statusJson(); latch.countDown() }
        latch.await(1, TimeUnit.SECONDS)
        return json
    }

    override fun selfTest(): String {
        if (!nativeOk) return "native renderer unavailable"
        return try {
            Own3D().use { o ->
                o.setAll(params ?: Own3D.defaults())
                o.selfTest(5.0)
            }
        } catch (t: Throwable) {
            "self-test failed: $t"
        }.also { record(it) }
    }

    override fun destroy() {
        val latch = CountDownLatch(1)
        handler.post {
            enabled = false
            stopEngine()
            try { audio?.unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Throwable) {}
            try { audio?.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Throwable) {}
            own?.close(); own = null
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        System.exit(0)
    }

    // ---------------- state machine ----------------

    private fun evaluate() {
        val nothingSelected = uids.isEmpty()
        val route = if (btOut && engine == null) mediaRouteIsBluetooth() else null
        if (route == true) routeBlocked = false
        val outputOk = btOut && !routeBlocked && route != false
        val anyPlaying = !playbackWatch || playing.any { it in uids }
        val newState = when {
            !enabled -> SpatialStatus.State.OFF
            Process.myUid() != SHELL_UID || shell == null || !nativeOk -> SpatialStatus.State.ERROR
            nothingSelected -> SpatialStatus.State.NO_APPS
            !outputOk -> SpatialStatus.State.NO_OUTPUT
            !anyPlaying && engine == null -> SpatialStatus.State.WAITING
            else -> if (silent) SpatialStatus.State.SILENT else SpatialStatus.State.RENDERING
        }
        when (newState) {
            SpatialStatus.State.OFF, SpatialStatus.State.ERROR,
            SpatialStatus.State.NO_APPS, SpatialStatus.State.NO_OUTPUT,
            SpatialStatus.State.WAITING -> {
                if (newState == SpatialStatus.State.ERROR && lastError == null) {
                    lastError = if (Process.myUid() != SHELL_UID) "Shizuku must run as shell (ADB), not root" else "Renderer unavailable"
                }
                stopEngine()
            }
            else -> {
                if (anyPlaying) cancelIdleStop() else scheduleIdleStop()
                val e = engine
                if (e != null && !e.uids.contentEquals(uids)) stopEngine()
                if (engine == null) startEngine()
            }
        }
        val effective = if (engine == null && newState in RUNNING) SpatialStatus.State.ERROR else newState
        if (effective != state) {
            state = effective
            record("state=$effective")
        }
        push()
    }

    private fun startEngine() {
        val ctx = shell ?: return
        if (own == null) {
            try {
                own = Own3D().also { o -> o.setAll(params ?: Own3D.defaults()) }
            } catch (t: Throwable) {
                lastError = "Renderer failed to load: ${t.message}"
                record(lastError!!)
                return
            }
        }
        silent = true
        val e = CaptureEngine(ctx, uids.clone(), own, handler, engineListener)
        e.mode = mode
        e.diagnostics = diagnostics
        engine = e
        lastError = null
        record("capture start (${packages.joinToString()})")
        e.start { err -> handler.post { onEngineEnded(e, err) } }
    }

    private fun onEngineEnded(e: CaptureEngine, err: Throwable?) {
        if (engine !== e) return
        engine = null
        if (err != null && !e.cancelled) {
            val cause = (err as? java.lang.reflect.InvocationTargetException)?.targetException ?: err
            lastError = cause.toString()
            record("capture failed: $cause")
            Log.e(TAG, "capture failed", cause)
            failures++
            state = SpatialStatus.State.ERROR
            push()
            if (failures <= MAX_RETRIES) handler.postDelayed(retry, RETRY_MS * failures)
            return
        }
        evaluate()
    }

    private fun stopEngine() {
        cancelIdleStop()
        handler.removeCallbacks(retry)
        val e = engine ?: return
        engine = null
        e.stop(1500)
        silent = true
        lastStats = null
        record("capture stopped")
    }

    private fun scheduleIdleStop() {
        if (idleStopPending) return
        idleStopPending = true
        handler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun cancelIdleStop() {
        if (!idleStopPending) return
        idleStopPending = false
        handler.removeCallbacks(idleStop)
    }

    private val engineListener = object : CaptureEngine.Listener {
        override fun onSilenceChanged(silent: Boolean) {
            handler.post {
                this@SpatialShellService.silent = silent
                evaluate()
            }
        }

        override fun onRouteLost(device: String) {
            handler.post {
                record("output moved to $device, pausing")
                routeBlocked = true
                stopEngine()
                refreshOutput()
                evaluate()
            }
        }

        override fun onStats(stats: CaptureEngine.Stats) {
            handler.post {
                lastStats = stats
                push()
            }
        }

        override fun log(line: String) {
            handler.post { record(line) }
        }
    }

    private fun onPlayback(configs: List<AudioPlaybackConfiguration>) {
        val now = HashSet<Int>()
        for (c in configs) {
            try {
                val st = c.javaClass.getMethod("getPlayerState").invoke(c) as Int
                if (st != PLAYER_STATE_STARTED) continue
                val uid = c.javaClass.getMethod("getClientUid").invoke(c) as Int
                if (uid >= 0 && uid != SHELL_UID) now += uid
            } catch (_: Throwable) {
            }
        }
        if (now != playing) {
            playing = now
            evaluate()
        }
    }

    private fun refreshOutput() {
        val am = audio ?: return
        val bt = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { CaptureEngine.isBluetooth(it.type) }
        } catch (_: Throwable) {
            false
        }
        if (bt != btOut) {
            btOut = bt
            record(if (bt) "bluetooth output available" else "no bluetooth output")
            if (!bt) stopEngine()
            evaluate()
        }
    }

    private fun resolveUids(pkgs: List<String>): IntArray {
        val pm = shell?.packageManager ?: return IntArray(0)
        pkgUids = pkgs.mapNotNull { p ->
            try { p to pm.getApplicationInfo(p, 0).uid } catch (_: Throwable) { null }
        }.toMap()
        return pkgUids.values.distinct().toIntArray()
    }

    /**
     * Where USAGE_MEDIA is routed right now (hidden getAudioDevicesForAttributes, needs
     * MODIFY_AUDIO_ROUTING which shell has). Null when the call is unavailable.
     */
    private fun mediaRouteIsBluetooth(): Boolean? {
        val am = audio ?: return null
        return try {
            val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
            val list = AudioManager::class.java.getMethod("getAudioDevicesForAttributes", android.media.AudioAttributes::class.java)
                .invoke(am, attrs) as List<*>
            val first = list.firstOrNull() ?: return null
            val type = first.javaClass.getMethod("getType").invoke(first) as Int
            // AudioDeviceAttributes.getType() returns AudioDeviceInfo types.
            CaptureEngine.isBluetooth(type)
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------- status ----------------

    private fun statusJson(): String {
        val s = lastStats
        return SpatialStatus(
            state = state,
            mode = mode,
            playing = pkgUids.filterValues { it in playing }.keys.toList(),
            error = lastError,
            cpuMs = s?.avgMsPerBlock ?: -1.0,
            cpuMaxMs = s?.maxMs ?: -1.0,
            peak = s?.peak ?: 0,
            routed = s?.routed,
            agc = s?.agc ?: 0f,
            log = if (diagnostics) synchronized(log) { log.joinToString("\n") } else null,
        ).toJson()
    }

    private fun push() {
        val cb = callback ?: return
        try { cb.onStatus(statusJson()) } catch (_: Throwable) {}
    }

    private fun record(line: String) {
        val stamped = String.format(Locale.US, "%.1f %s", SystemClock.elapsedRealtime() / 1000.0, line)
        synchronized(log) {
            log.addLast(stamped)
            while (log.size > 80) log.removeFirst()
        }
        Log.i(TAG, line)
    }

    companion object {
        private const val TAG = "SpatialShell"
        private const val SHELL_UID = 2000
        private const val PLAYER_STATE_STARTED = 2
        private const val IDLE_STOP_MS = 8000L
        private const val RETRY_MS = 4000L
        private const val MAX_RETRIES = 3
        private val RUNNING = setOf(SpatialStatus.State.RENDERING, SpatialStatus.State.SILENT)
    }
}
