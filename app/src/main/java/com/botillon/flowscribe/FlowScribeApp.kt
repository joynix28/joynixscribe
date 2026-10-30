package com.botillon.flowscribe

import android.app.Application
import com.botillon.flowscribe.ai.TranscriptionEngine
import com.botillon.flowscribe.data.ApiKeyStore
import com.botillon.flowscribe.data.SettingsStore
import com.botillon.flowscribe.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FlowScribeApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsStore: SettingsStore by lazy { SettingsStore(this) }
    val keyStore: ApiKeyStore by lazy { ApiKeyStore(this) }
    val engine: TranscriptionEngine by lazy { TranscriptionEngine(keyStore) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.ensureChannels(this)
        appScope.launch { runCatching { settingsStore.seedIfNeeded() } }
    }

    companion object {
        @Volatile
        lateinit var instance: FlowScribeApp
            private set
    }
}
