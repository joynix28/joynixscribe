package com.botillon.flowscribe.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.botillon.flowscribe.JoynixScribeApp
import com.botillon.flowscribe.Permissions
import com.botillon.flowscribe.data.ApiKeyEntry
import com.botillon.flowscribe.data.AppSettings
import com.botillon.flowscribe.data.BubbleAccent
import com.botillon.flowscribe.data.BubbleGesture
import com.botillon.flowscribe.data.BubbleIcon
import com.botillon.flowscribe.data.DictionaryEntry
import com.botillon.flowscribe.data.HistoryEntry
import com.botillon.flowscribe.data.InsertMode
import com.botillon.flowscribe.data.PromptDefaults
import com.botillon.flowscribe.data.PromptPreset
import com.botillon.flowscribe.data.Provider
import com.botillon.flowscribe.data.ThemeMode
import com.botillon.flowscribe.service.FloatingBubbleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class PermissionState(
    val microphone: Boolean = false,
    val overlay: Boolean = false,
    val accessibility: Boolean = false,
    val notifications: Boolean = false,
    val battery: Boolean = false,
) {
    val configured: Int
        get() = listOf(microphone, overlay, accessibility).count { it }

    val ready: Boolean
        get() = microphone && overlay
}

data class KeyProbe(
    val running: Boolean = false,
    val ok: Boolean = false,
    val message: String? = null,
)

data class UiMessage(val id: Long = System.nanoTime(), val text: String)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JoynixScribeApp
    private val store = app.settingsStore
    private val keyStore = app.keyStore
    private val engine = app.engine

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val ready: StateFlow<Boolean> = store.settings
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val prompts: StateFlow<List<PromptPreset>> = store.prompts
        .stateIn(viewModelScope, SharingStarted.Eagerly, PromptDefaults.builtIn)

    val dictionary: StateFlow<List<DictionaryEntry>> = store.dictionary
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val history: StateFlow<List<HistoryEntry>> = store.history
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val bubbleRunning: StateFlow<Boolean> = FloatingBubbleService.running

    private val _permissions = MutableStateFlow(PermissionState())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _keys = MutableStateFlow<Map<Provider, List<ApiKeyEntry>>>(emptyMap())
    val keys: StateFlow<Map<Provider, List<ApiKeyEntry>>> = _keys.asStateFlow()

    private val _probes = MutableStateFlow<Map<String, KeyProbe>>(emptyMap())
    val probes: StateFlow<Map<String, KeyProbe>> = _probes.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    init {
        refreshKeys()
        refreshPermissions()
    }

    // ------------------------------------------------------------- permissions

    fun refreshPermissions() {
        val context = getApplication<Application>()
        _permissions.value = PermissionState(
            microphone = Permissions.hasMicrophone(context),
            overlay = Permissions.canDrawOverlays(context),
            accessibility = Permissions.isAccessibilityEnabled(context),
            notifications = Permissions.notificationsEnabled(context),
            battery = Permissions.ignoresBatteryOptimizations(context),
        )
    }

    fun refreshAll() {
        refreshPermissions()
        refreshKeys()
    }

    // ------------------------------------------------------------------ bubble

    fun toggleBubble() {
        val context = getApplication<Application>()
        if (FloatingBubbleService.isRunning) {
            runCatching {
                context.startService(
                    Intent(context, FloatingBubbleService::class.java)
                        .setAction(FloatingBubbleService.ACTION_STOP),
                )
            }
            return
        }
        if (!Permissions.hasMicrophone(context)) {
            notify("Autorise le microphone avant de lancer la bulle.")
            return
        }
        if (!Permissions.canDrawOverlays(context)) {
            notify("Autorise les fenêtres flottantes pour afficher la bulle.")
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingBubbleService::class.java),
            )
        }.onFailure {
            notify("Lancement impossible : ${it.message ?: "erreur inconnue"}")
        }
    }

    // -------------------------------------------------------------------- keys

    fun refreshKeys() {
        _keys.value = Provider.entries.associateWith { keyStore.keys(it) }
    }

    fun addKey(provider: Provider, label: String, secret: String): Result<ApiKeyEntry> {
        val result = keyStore.add(provider, label, secret)
        result.onSuccess {
            refreshKeys()
            notify("Clé ${provider.shortLabel} ajoutée.")
        }.onFailure {
            notify(it.message ?: "Ajout impossible")
        }
        return result
    }

    fun deleteKey(provider: Provider, id: String) {
        keyStore.delete(provider, id)
        refreshKeys()
        notify("Clé supprimée.")
    }

    fun setKeyEnabled(provider: Provider, id: String, enabled: Boolean) {
        keyStore.setEnabled(provider, id, enabled)
        refreshKeys()
    }

    fun testKey(provider: Provider, entry: ApiKeyEntry) {
        if (_probes.value[entry.id]?.running == true) return
        _probes.value = _probes.value + (entry.id to KeyProbe(running = true))
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { engine.testKey(provider, entry.secret) }
                    .getOrElse { Result.failure(it) }
            }
            val probe = outcome.fold(
                onSuccess = { KeyProbe(ok = true, message = it) },
                onFailure = { KeyProbe(ok = false, message = it.message ?: "Clé refusée") },
            )
            _probes.value = _probes.value + (entry.id to probe)
            refreshKeys()
        }
    }

    fun probeFor(id: String): KeyProbe? = _probes.value[id]

    // ----------------------------------------------------------------- prompts

    fun selectPrompt(id: String) = launchStore { setActivePrompt(id) }

    fun createPrompt(
        name: String,
        description: String,
        instructions: String,
        appPackages: List<String> = emptyList(),
    ) {
        val prompt = PromptPreset(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            description = description.trim(),
            instructions = instructions.trim(),
            appPackages = appPackages,
        )
        launchStore { addPrompt(prompt) }
        notify("Style « ${prompt.name} » créé.")
    }

    fun updatePrompt(prompt: PromptPreset) {
        launchStore { updatePrompt(prompt) }
        notify("Style enregistré.")
    }

    fun duplicatePrompt(prompt: PromptPreset) {
        val copy = prompt.copy(
            id = UUID.randomUUID().toString(),
            name = "${prompt.name} (copie)",
            builtIn = false,
        )
        launchStore { addPrompt(copy) }
        notify("Style dupliqué.")
    }

    fun deletePrompt(id: String) {
        viewModelScope.launch {
            val current = settings.value.activePromptId
            if (current == id) store.setActivePrompt(PromptDefaults.CLEAN_ID)
            store.deletePrompt(id)
        }
        notify("Style supprimé.")
    }

    fun resetPrompts() {
        launchStore { resetPrompts() }
        notify("Styles d'origine restaurés.")
    }

    // -------------------------------------------------------------- dictionary

    fun addDictionaryEntry(term: String, replacement: String, caseSensitive: Boolean) {
        val entry = DictionaryEntry(
            term = term.trim(),
            replacement = replacement.trim(),
            caseSensitive = caseSensitive,
        )
        if (entry.term.isBlank()) {
            notify("Le terme ne peut pas être vide.")
            return
        }
        launchStore { addDictionaryEntry(entry) }
        notify("« ${entry.term} » ajouté au dictionnaire.")
    }

    fun updateDictionaryEntry(entry: DictionaryEntry) = launchStore { updateDictionaryEntry(entry) }

    fun deleteDictionaryEntry(id: String) {
        launchStore { deleteDictionaryEntry(id) }
        notify("Terme supprimé.")
    }

    // ---------------------------------------------------------------- settings

    fun setPrimary(provider: Provider) = launchStore { setPrimary(provider) }
    fun setFallbackEnabled(value: Boolean) = launchStore { setFallbackEnabled(value) }
    fun setFallbackTimeout(ms: Long) = launchStore { setFallbackTimeout(ms) }
    fun setHedgeEnabled(value: Boolean) = launchStore { setHedgeEnabled(value) }
    fun setHedgeDelay(ms: Long) = launchStore { setHedgeDelay(ms) }
    fun setGeminiModel(value: String) = launchStore { setGeminiModel(value) }
    fun setGroqModel(value: String) = launchStore { setGroqModel(value) }
    fun setCloudflareModel(value: String) = launchStore { setCloudflareModel(value) }
    fun setLanguage(value: String) = launchStore { setLanguage(value) }
    fun setPunctuation(value: Boolean) = launchStore { setPunctuation(value) }
    fun setSpokenCommands(value: Boolean) = launchStore { setSpokenCommands(value) }
    fun setFrenchTypography(value: Boolean) = launchStore { setFrenchTypography(value) }
    fun setAutoCapitalize(value: Boolean) = launchStore { setAutoCapitalize(value) }
    fun setAutoSpace(value: Boolean) = launchStore { setAutoSpace(value) }
    fun setInsertMode(value: InsertMode) = launchStore { setInsertMode(value) }
    fun setLivePreview(value: Boolean) = launchStore { setLivePreview(value) }
    fun setPreviewInterval(ms: Long) = launchStore { setPreviewInterval(ms) }
    fun setMaxDuration(sec: Int) = launchStore { setMaxDuration(sec) }
    fun setBubbleSize(dp: Int) = launchStore { setBubbleSize(dp) }
    fun setBubbleOpacity(value: Float) = launchStore { setBubbleOpacity(value) }
    fun setBubbleAccent(value: BubbleAccent) = launchStore { setBubbleAccent(value) }
    fun setBubbleIcon(value: BubbleIcon) = launchStore { setBubbleIcon(value) }
    fun setBubbleGesture(value: BubbleGesture) = launchStore { setBubbleGesture(value) }
    fun setHaptics(value: Boolean) = launchStore { setHaptics(value) }
    fun setTheme(value: ThemeMode) = launchStore { setTheme(value) }
    fun setDynamicColor(value: Boolean) = launchStore { setDynamicColor(value) }
    fun setGroqFormatting(value: Boolean) = launchStore { setGroqFormatting(value) }
    fun setSendAppContext(value: Boolean) = launchStore { setSendAppContext(value) }
    fun setAppAwarePrompts(value: Boolean) = launchStore { setAppAwarePrompts(value) }
    fun setStreamingMode(value: Boolean) = launchStore { setStreamingMode(value) }
    fun setApertureFallbackEnabled(value: Boolean) = launchStore { setApertureFallbackEnabled(value) }
    fun setApertureStreamingEnabled(value: Boolean) = launchStore { setApertureStreamingEnabled(value) }
    fun setDailyKeyRequestLimit(value: Int) = launchStore { setDailyKeyRequestLimit(value) }

    fun completeOnboarding() = launchStore { setOnboardingDone(true) }

    /** Un seul appel : modèles économiques, pas d'aperçu, pas de double requête. */
    fun applyEcoPreset() {
        viewModelScope.launch {
            store.setGeminiModel(Provider.GEMINI.defaultModel)
            store.setGroqModel(Provider.GROQ.defaultModel)
            store.setLivePreview(false)
            store.setHedgeEnabled(false)
            store.setFallbackEnabled(true)
            store.setFallbackTimeout(3500)
            store.setMaxDuration(90)
            store.setPrimary(Provider.GEMINI)
            notify("Mode économie activé : un seul appel par dictée.")
        }
    }

    // -------------------------------------------------------------------- data

    fun exportJson(): String = store.exportConfig(
        settings.value,
        prompts.value,
        dictionary.value,
    )

    fun clearHistory() {
        launchStore { clearHistory() }
        notify("Historique effacé.")
    }

    // ------------------------------------------------------------------ helpers

    private fun launchStore(block: suspend com.botillon.flowscribe.data.SettingsStore.() -> Unit) {
        viewModelScope.launch { store.block() }
    }

    fun notify(text: String) {
        _message.value = UiMessage(text = text)
    }

    fun consumeMessage(id: Long) {
        if (_message.value?.id == id) _message.value = null
    }
}
