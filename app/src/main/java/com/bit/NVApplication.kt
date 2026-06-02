package com.bit

import android.app.Application
import android.util.Log
import com.bit.data.AppSettingsDataStore
import com.bit.data.VaultManager
import com.bit.di.AppContainer
import com.bit.plugins.CalculatorPlugin
import com.bit.plugins.DateTimePlugin
import com.bit.plugins.DevUtilsPlugin
import com.bit.plugins.FileManagerPlugin
import com.bit.plugins.NotePadPlugin
import com.bit.plugins.PluginManager
import com.bit.plugins.SystemInfoPlugin
import com.bit.plugins.WebSearchPlugin
import com.bit.repo.RagRepository
import com.bit.tts.TTSManager
import com.bit.worker.DataIntegrityManager
import com.bit.worker.LlmModelWorker
import com.bit.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class NVApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "NVApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application onCreate")

        // Initialize global Crash Reporter
        com.bit.util.CrashReporter.init(applicationContext)

        // Create all notification channels once at startup (API 26+ requirement)
        NotificationChannels.createAllChannels(applicationContext)

        // Initialize app container first
        AppContainer.init(applicationContext, this)

        // Register plugins
        PluginManager.registerPlugin(WebSearchPlugin())
        PluginManager.registerPlugin(CalculatorPlugin())
        PluginManager.registerPlugin(DateTimePlugin())
        PluginManager.registerPlugin(DevUtilsPlugin())
        PluginManager.registerPlugin(FileManagerPlugin(applicationContext))
        PluginManager.registerPlugin(NotePadPlugin())
        PluginManager.registerPlugin(SystemInfoPlugin(applicationContext))
        Log.d(TAG, "Plugins registered: ${PluginManager.registeredPlugins.value.size} plugins")

        // Initialize TTS Manager without auto-loading (loading controlled by settings)
        TTSManager.init(applicationContext, autoLoad = false)
        Log.d(TAG, "TTSManager initialized")

        // Run data integrity check after UMS is ready (deferred to let UI render first)
        appScope.launch {
            delay(2000) // Let Activity.onCreate + first frame complete before scanning
            try {
                if (!VaultManager.isReady.value) {
                    Log.w(TAG, "UMS not ready, skipping integrity check")
                } else {
                    val db = AppContainer.getDatabase()
                    val ragRepository = RagRepository(
                        ragDao = db.ragDao(),
                        context = applicationContext
                    )
                    val manager = DataIntegrityManager(
                        context = applicationContext,
                        modelRepo = VaultManager.modelRepo!!,
                        personaRepo = VaultManager.personaRepo!!,
                        ragDao = db.ragDao(),
                        memoryRepo = VaultManager.memoryRepo!!,
                        ragRepository = ragRepository,
                        appSettings = AppSettingsDataStore(applicationContext)
                    )
                    val report = manager.runFullCheck()
                    Log.i(TAG, "Integrity check: ${report.totalFixes} fixes applied")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Data integrity check failed", e)
            }
        }

        // Conditionally load TTS model based on user setting
        appScope.launch {
            try {
                val settings = AppSettingsDataStore(applicationContext)
                val loadOnStart = settings.loadTTSOnStart.first()
                if (loadOnStart) {
                    val modelDir = TTSManager.getModelDirectory()
                    if (modelDir != null) {
                        val success = TTSManager.loadModel(modelDir)
                        Log.d(TAG, "TTS model auto-loaded on start: $success")
                    }
                } else {
                    Log.d(TAG, "TTS auto-load disabled by user setting")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking TTS auto-load setting", e)
            }
        }

        // Note: Service binding moved to MainActivity to comply with Android 14+ foreground service restrictions
    }
}