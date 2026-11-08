package me.kavishdevar.librepods

import android.app.Application
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room3.Room
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.billing.BillingProviderFactory
import me.kavishdevar.librepods.core.utils.GestureFeedback
import me.kavishdevar.librepods.data.heartrate.HeartRatePreferences
import me.kavishdevar.librepods.database.LibrePodsDatabase
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.overlays.ProximityCardWindow
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.repository.AppleRepository
import me.kavishdevar.librepods.repository.HeartRateRepository
import me.kavishdevar.librepods.repository.RecordingRepository
import me.kavishdevar.librepods.repository.WidgetConfigRepository
import me.kavishdevar.librepods.services.LibrePodsAccessibilityService
import me.kavishdevar.librepods.utils.XposedServiceHolder
import me.kavishdevar.librepods.utils.XposedState

class LibrePodsApplication: Application(), XposedServiceHelper.OnServiceListener, DefaultLifecycleObserver {
    lateinit var database: LibrePodsDatabase
        private set

    val appleRepository by lazy { AppleRepository(database.appleDao()) }
    val appDataRepository by lazy { AppDataRepository(database.appSettingsDao(), database.appStateDao()) }
    val widgetConfigRepository by lazy { WidgetConfigRepository(database.widgetConfigDao()) }

    val recordingRepository by lazy { RecordingRepository(applicationContext) }
    val heartRateRepository by lazy { HeartRateRepository(database.heartRateDao()) }

    val heartRatePreferences by lazy {
        HeartRatePreferences(getSharedPreferences(HeartRatePreferences.PREFERENCES_NAME, MODE_PRIVATE))
    }

    val healthConnectClient: HealthConnectClient? by lazy {
        val status = HealthConnectClient.getSdkStatus(this)
        if (status == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(this)
        } else {
            null
        }
    }

    lateinit var islandWindow: IslandWindow
        private set

    lateinit var proximityCardWindow: ProximityCardWindow
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        System.loadLibrary("hiddenapi")

        database = Room.databaseBuilder(
            applicationContext,
            LibrePodsDatabase::class.java,
            "librepods.db"
        ).build()

        XposedServiceHelper.registerListener(this)

        runBlocking(Dispatchers.IO) {
            appDataRepository.awaitInitialized()
        }

        islandWindow = IslandWindow(applicationContext, appDataRepository.settings.value)
        proximityCardWindow = ProximityCardWindow(applicationContext, appDataRepository.settings.value)

        scope.launch {
            LibrePodsAccessibilityService.instance.collect {
                Log.d("LibrePodsApplication", "LibrePodsAccessibilityService instance: $it")
                islandWindow.updateContext(it?: applicationContext)
                proximityCardWindow.updateContext(it?: applicationContext)
            }
        }

        scope.launch {
            appDataRepository.settings.collect {
                islandWindow.updateSettings(it)
                proximityCardWindow.updateSettings(it)
            }
        }

        BillingManager.provider = BillingProviderFactory.create(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        GestureFeedback.init(this)

        super<Application>.onCreate()

    }

    override fun onResume(owner: LifecycleOwner) {
        BillingManager.provider.queryPurchases()
        XposedState.isAvailable = XposedServiceHolder.service != null
        XposedState.bluetoothScopeEnabled = XposedServiceHolder.service?.scope?.contains("com.google.android.bluetooth") == true || XposedServiceHolder.service?.scope?.contains("com.android.bluetooth") == true
    }

    override fun onServiceBind(service: XposedService) {
        XposedServiceHolder.service = service
        XposedState.isAvailable = true
        XposedState.bluetoothScopeEnabled = XposedServiceHolder.service?.scope?.contains("com.google.android.bluetooth") == true || XposedServiceHolder.service?.scope?.contains("com.android.bluetooth") == true
    }

    override fun onServiceDied(p0: XposedService) {
        XposedServiceHolder.service = null
        XposedState.isAvailable = false
    }
}
