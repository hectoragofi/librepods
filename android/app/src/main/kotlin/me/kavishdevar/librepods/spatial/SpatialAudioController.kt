package me.kavishdevar.librepods.spatial

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import local.librepods.spatialshizuku.Own3D
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.spatial.shell.SpatialShellService
import rikka.shizuku.Shizuku

/**
 * App-side owner of the Spatial Audio feature: settings, presets, Shizuku state and the binding
 * to the shell user service. The binding only exists while [SpatialAudioService] (foreground)
 * is attached, i.e. while the feature is on.
 */
class SpatialAudioController private constructor(private val context: Context) {

    enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, UNSUPPORTED, NO_PERMISSION, DENIED, READY, CONNECTED }

    private val prefs = SpatialAudioPreferences(context.getSharedPreferences(SpatialAudioPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE))
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Native parameter table; null when libown3d could not be loaded in the app process. */
    val table: SpatialParamTable? = try {
        Own3D.load(context.applicationInfo.nativeLibraryDir, context.applicationInfo.sourceDir, Build.SUPPORTED_ABIS.firstOrNull())
        SpatialParamTable(Own3D.params().map { SpatialParam(it.id, it.key, it.label, it.group, it.min, it.max, it.def, it.step, it.rebuild) })
    } catch (t: Throwable) {
        Log.e(TAG, "OWN 3D unavailable", t)
        null
    }

    val builtInPresets: List<SpatialPreset>
    val defaultPresetId: String

    init {
        val (list, def) = try {
            context.assets.open(PRESETS_ASSET).bufferedReader().use { SpatialPresetCodec.parseAsset(it.readText()) }
        } catch (t: Throwable) {
            Log.e(TAG, "presets asset unreadable", t)
            emptyList<SpatialPreset>() to null
        }
        builtInPresets = list.ifEmpty { listOf(SpatialPreset("default", "Default", "Engine defaults.", emptyMap(), true)) }
        defaultPresetId = def?.takeIf { id -> builtInPresets.any { it.id == id } } ?: builtInPresets.first().id
    }

    private val _enabled = MutableStateFlow(prefs.enabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _mode = MutableStateFlow(prefs.mode)
    val mode: StateFlow<Int> = _mode.asStateFlow()

    private val _packages = MutableStateFlow(prefs.packages)
    val packages: StateFlow<Set<String>> = _packages.asStateFlow()

    private val _userPresets = MutableStateFlow(SpatialPresetCodec.parseUserPresets(prefs.userPresets))
    val userPresets: StateFlow<List<SpatialPreset>> = _userPresets.asStateFlow()

    private val _presetId = MutableStateFlow(prefs.presetId ?: defaultPresetId)
    val presetId: StateFlow<String> = _presetId.asStateFlow()

    private val _values = MutableStateFlow(initialValues())
    /** Current OWN 3D parameter vector (new array on every change). */
    val values: StateFlow<FloatArray> = _values.asStateFlow()

    private val _diagnostics = MutableStateFlow(prefs.diagnostics)
    val diagnostics: StateFlow<Boolean> = _diagnostics.asStateFlow()

    private val _shizuku = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val shizuku: StateFlow<ShizukuState> = _shizuku.asStateFlow()

    private val _status = MutableStateFlow(SpatialStatus())
    val status: StateFlow<SpatialStatus> = _status.asStateFlow()

    // ---------------- presets ----------------

    fun allPresets(): List<SpatialPreset> = builtInPresets + _userPresets.value

    fun preset(id: String?): SpatialPreset? = allPresets().firstOrNull { it.id == id }

    fun currentPreset(): SpatialPreset = preset(_presetId.value) ?: builtInPresets.first { it.id == defaultPresetId }

    /** True when the live values differ from the selected preset. */
    fun isModified(values: FloatArray = _values.value): Boolean {
        val t = table ?: return false
        return !SpatialPresetCodec.apply(t, currentPreset().params).contentEquals(values)
    }

    private fun initialValues(): FloatArray {
        val t = table ?: return FloatArray(0)
        val tweaks = prefs.tweaks?.let { parseMap(it) }
        return SpatialPresetCodec.apply(t, tweaks ?: (preset(prefs.presetId ?: defaultPresetId)?.params ?: emptyMap()))
    }

    fun selectPreset(id: String) {
        val t = table ?: return
        val p = preset(id) ?: return
        _presetId.value = p.id
        prefs.presetId = p.id
        setAll(SpatialPresetCodec.apply(t, p.params))
    }

    fun resetToDefault() = selectPreset(defaultPresetId)

    /** Saves the live values as a new user preset and selects it. */
    fun saveUserPreset(name: String, description: String = ""): SpatialPreset? {
        val t = table ?: return null
        val clean = name.trim().ifEmpty { return null }
        var id = "user-" + SpatialPresetCodec.slug(clean)
        var n = 2
        while (allPresets().any { it.id == id }) id = "user-" + SpatialPresetCodec.slug(clean) + "-" + n++
        val p = SpatialPreset(id, clean, description.ifBlank { "Custom preset" }, SpatialPresetCodec.diff(t, _values.value), false)
        storeUserPresets(_userPresets.value + p)
        _presetId.value = p.id
        prefs.presetId = p.id
        persistValuesNow()
        return p
    }

    fun renameUserPreset(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        storeUserPresets(_userPresets.value.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Overwrites a user preset with the live values. */
    fun updateUserPreset(id: String) {
        val t = table ?: return
        storeUserPresets(_userPresets.value.map { if (it.id == id) it.copy(params = SpatialPresetCodec.diff(t, _values.value)) else it })
    }

    fun deleteUserPreset(id: String) {
        storeUserPresets(_userPresets.value.filterNot { it.id == id })
        if (_presetId.value == id) {
            _presetId.value = defaultPresetId
            prefs.presetId = defaultPresetId
        }
    }

    private fun storeUserPresets(list: List<SpatialPreset>) {
        _userPresets.value = list
        prefs.userPresets = SpatialPresetCodec.encodeUserPresets(list)
        SpatialAudioService.refreshNotification(context)
    }

    fun exportCurrent(): String {
        val t = table ?: return "{}"
        return SpatialPresetCodec.export(t, _values.value, currentPreset().name)
    }

    /** Applies pasted JSON live; returns false when it is not a valid preset. */
    fun importJson(text: String, saveAs: String? = null): Boolean {
        val t = table ?: return false
        val (map, name) = SpatialPresetCodec.import(t, text) ?: return false
        setAll(SpatialPresetCodec.apply(t, map))
        val label = saveAs?.takeIf { it.isNotBlank() } ?: name
        if (label != null) saveUserPreset(label, "Imported")
        return true
    }

    // ---------------- live parameters ----------------

    fun setParam(id: Int, value: Float) {
        val t = table ?: return
        if (id !in 0 until t.size) return
        val v = t.params[id].clamp(value)
        val cur = _values.value
        if (cur[id] == v) return
        _values.value = cur.copyOf().also { it[id] = v }
        try { service?.setParam(id, v) } catch (_: Throwable) {}
        persistValuesSoon()
    }

    private fun setAll(v: FloatArray) {
        _values.value = v
        try { service?.setParams(v) } catch (_: Throwable) {}
        persistValuesNow()
    }

    private var persistJob: Job? = null
    private fun persistValuesSoon() {
        persistJob?.cancel()
        persistJob = scope.launch { delay(400); persistValuesNow() }
    }

    private fun persistValuesNow() {
        persistJob?.cancel()
        val t = table ?: return
        prefs.tweaks = encodeMap(SpatialPresetCodec.diff(t, _values.value))
        SpatialAudioService.refreshNotification(context)
    }

    // ---------------- on/off, mode, apps ----------------

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.enabled = on
        if (on) {
            SpatialAudioService.start(context)
            pushConfig()
        } else {
            pushConfig()
            SpatialAudioService.stop(context)
        }
    }

    fun setMode(m: Int) {
        _mode.value = m
        prefs.mode = m
        pushConfig()
        SpatialAudioService.refreshNotification(context)
    }

    fun toggleMode() = setMode(if (_mode.value == SpatialAudioPreferences.MODE_OWN) SpatialAudioPreferences.MODE_NORMAL else SpatialAudioPreferences.MODE_OWN)

    fun setPackages(p: Set<String>) {
        _packages.value = p
        prefs.packages = p
        pushConfig()
    }

    fun setDiagnostics(on: Boolean) {
        _diagnostics.value = on
        prefs.diagnostics = on
        try { service?.setDiagnostics(on) } catch (_: Throwable) {}
    }

    /** Starts the foreground service again after an app/process restart when the feature is on. */
    fun ensureRunningIfEnabled() {
        if (_enabled.value) SpatialAudioService.start(context)
    }

    fun selfTest(onResult: (String) -> Unit) {
        val s = service ?: return onResult("Renderer not connected")
        Thread({
            val r = try { s.selfTest() } catch (t: Throwable) { t.toString() }
            main.post { onResult(r) }
        }, "spatial-selftest").start()
    }

    // ---------------- Shizuku ----------------

    private var attached = false
    private var binding = false
    private var service: ISpatialService? = null
    private val clientToken = Binder()

    private val callback = object : ISpatialCallback.Stub() {
        override fun onStatus(json: String?) {
            val s = SpatialStatus.fromJson(json ?: return)
            main.post {
                val changed = s.state != _status.value.state
                _status.value = s
                if (changed) SpatialAudioService.refreshNotification(context)
            }
        }
    }

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, SpatialShellService::class.java.name))
            .processNameSuffix("spatial")
            .daemon(false)
            .debuggable(BuildConfig.DEBUG)
            .version(SHELL_SERVICE_VERSION + BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder == null || !binder.pingBinder()) return
            val s = ISpatialService.Stub.asInterface(binder)
            service = s
            try {
                s.setClient(clientToken, callback)
                s.setDiagnostics(_diagnostics.value)
                s.setParams(_values.value)
                s.configure(_enabled.value, _mode.value, _packages.value.toTypedArray())
            } catch (t: Throwable) {
                Log.w(TAG, "configure failed", t)
            }
            refreshShizuku()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            _status.value = SpatialStatus(state = SpatialStatus.State.ERROR, error = "Renderer stopped")
            refreshShizuku()
            // Shizuku restarts the binder on its own; rebind if it is still alive.
            main.postDelayed({ if (attached) bindIfPossible() }, 1500)
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { main.post { refreshShizuku(); if (attached) bindIfPossible() } }
    private val binderDead = Shizuku.OnBinderDeadListener {
        main.post {
            service = null
            binding = false
            refreshShizuku()
            SpatialAudioService.refreshNotification(context)
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ ->
        main.post { refreshShizuku(); if (attached) bindIfPossible() }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refreshShizuku()
    }

    fun refreshShizuku() {
        val before = _shizuku.value
        _shizuku.value = when {
            service != null -> ShizukuState.CONNECTED
            !Shizuku.pingBinder() -> if (isShizukuInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
            Shizuku.isPreV11() -> ShizukuState.UNSUPPORTED
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.DENIED
            else -> ShizukuState.NO_PERMISSION
        }
        if (_shizuku.value != before) SpatialAudioService.refreshNotification(context)
    }

    fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST)
        } catch (t: Throwable) {
            Log.w(TAG, "permission request failed", t)
        }
        refreshShizuku()
    }

    fun shizukuLaunchIntent(): Intent? = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    /** Called by [SpatialAudioService] when it starts. */
    internal fun attach() {
        attached = true
        bindIfPossible()
    }

    /** Called by [SpatialAudioService] when it stops: unbinding kills the shell process. */
    internal fun detach() {
        attached = false
        val s = service
        service = null
        binding = false
        try { s?.configure(false, _mode.value, emptyArray()) } catch (_: Throwable) {}
        try {
            if (Shizuku.pingBinder()) Shizuku.unbindUserService(serviceArgs, connection, true)
        } catch (_: Throwable) {}
        _status.value = SpatialStatus()
        refreshShizuku()
    }

    private fun bindIfPossible() {
        refreshShizuku()
        if (!attached || service != null || binding) return
        if (_shizuku.value != ShizukuState.READY) return
        binding = true
        try {
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (t: Throwable) {
            binding = false
            Log.w(TAG, "bind failed", t)
        }
    }

    private fun pushConfig() {
        try { service?.configure(_enabled.value, _mode.value, _packages.value.toTypedArray()) } catch (_: Throwable) {}
    }

    // ---------------- helpers ----------------

    private fun parseMap(text: String): Map<String, Float>? = try {
        (Json.parseToJsonElement(text) as? JsonObject)?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.doubleOrNull?.let { k to it.toFloat() }
        }?.toMap()
    } catch (_: Exception) {
        null
    }

    private fun encodeMap(m: Map<String, Float>): String =
        Json.encodeToString(JsonElement.serializer(), buildJsonObject { m.forEach { (k, v) -> put(k, v.toDouble()) } })

    companion object {
        private const val TAG = "SpatialAudio"
        const val PRESETS_ASSET = "spatial_presets.json"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST = 4217
        private const val SHELL_SERVICE_VERSION = 100000

        @Volatile
        private var instance: SpatialAudioController? = null

        fun get(context: Context): SpatialAudioController =
            instance ?: synchronized(this) {
                instance ?: SpatialAudioController(context.applicationContext).also { instance = it }
            }
    }
}
