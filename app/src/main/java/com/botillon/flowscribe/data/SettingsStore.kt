package com.botillon.flowscribe.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "flowscribe_settings")

class SettingsStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val prefs: Flow<Preferences> = context.dataStore.data.catch { emit(emptyPreferences()) }

    val settings: Flow<AppSettings> = prefs.map { p ->
        AppSettings(
            primary = Provider.fromId(p[Keys.PRIMARY]),
            fallbackEnabled = p[Keys.FALLBACK] ?: true,
            fallbackTimeoutMs = (p[Keys.FALLBACK_TIMEOUT] ?: 3500L).coerceIn(1000L, 8000L),
            hedgeEnabled = p[Keys.HEDGE] ?: false,
            hedgeDelayMs = (p[Keys.HEDGE_DELAY] ?: 650L).coerceIn(250L, 2000L),
            geminiModel = p[Keys.GEMINI_MODEL] ?: Provider.GEMINI.defaultModel,
            groqModel = p[Keys.GROQ_MODEL] ?: Provider.GROQ.defaultModel,
            cloudflareModel = p[Keys.CLOUDFLARE_MODEL] ?: Provider.CLOUDFLARE.defaultModel,
            language = p[Keys.LANGUAGE] ?: "auto",
            activePromptId = p[Keys.ACTIVE_PROMPT] ?: PromptDefaults.CLEAN_ID,
            punctuation = p[Keys.PUNCTUATION] ?: true,
            spokenCommands = p[Keys.SPOKEN_COMMANDS] ?: true,
            frenchTypography = p[Keys.FRENCH_TYPO] ?: true,
            autoCapitalize = p[Keys.AUTO_CAPITALIZE] ?: true,
            autoSpace = p[Keys.AUTO_SPACE] ?: true,
            insertMode = runCatching { InsertMode.valueOf(p[Keys.INSERT_MODE] ?: "AUTO") }.getOrDefault(InsertMode.AUTO),
            livePreview = p[Keys.LIVE_PREVIEW] ?: false,
            previewIntervalMs = (p[Keys.PREVIEW_INTERVAL] ?: 4000L).coerceIn(1500L, 6000L),
            apertureFallbackEnabled = p[Keys.APERTURE_FALLBACK] ?: true,
            apertureStreamingEnabled = p[Keys.APERTURE_STREAMING] ?: true,
            maxDurationSec = (p[Keys.MAX_DURATION] ?: 90).coerceIn(30, 600),
            bubbleSizeDp = (p[Keys.BUBBLE_SIZE] ?: 60).coerceIn(46, 80),
            bubbleOpacity = (p[Keys.BUBBLE_OPACITY] ?: 1f).coerceIn(0.55f, 1f),
            bubbleAccent = runCatching {
                BubbleAccent.valueOf(p[Keys.BUBBLE_ACCENT] ?: "VIOLET")
            }.getOrDefault(BubbleAccent.VIOLET),
            bubbleIcon = runCatching {
                BubbleIcon.valueOf(p[Keys.BUBBLE_ICON] ?: "MIC")
            }.getOrDefault(BubbleIcon.MIC),
            bubbleGesture = runCatching {
                BubbleGesture.valueOf(p[Keys.BUBBLE_GESTURE] ?: "TAP")
            }.getOrDefault(BubbleGesture.TAP),
            haptics = p[Keys.HAPTICS] ?: true,
            theme = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            groqFormatting = p[Keys.GROQ_FORMATTING] ?: true,
            sendAppContext = p[Keys.SEND_APP_CONTEXT] ?: true,
            appAwarePrompts = p[Keys.APP_AWARE_PROMPTS] ?: true,
            streamingMode = p[Keys.STREAMING_MODE] ?: false,
            dailyKeyRequestLimit = (p[Keys.DAILY_KEY_LIMIT] ?: 0).coerceIn(0, 500),
            onboardingDone = p[Keys.ONBOARDING] ?: false,
        )
    }

    val prompts: Flow<List<PromptPreset>> = prefs.map { p ->
        val raw = p[Keys.PROMPTS]
        if (raw.isNullOrBlank()) PromptDefaults.builtIn
        else runCatching { json.decodeFromString<List<PromptPreset>>(raw) }.getOrDefault(PromptDefaults.builtIn)
            .ifEmpty { PromptDefaults.builtIn }
    }

    val dictionary: Flow<List<DictionaryEntry>> = prefs.map { p ->
        val raw = p[Keys.DICTIONARY]
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<DictionaryEntry>>(raw) }.getOrDefault(emptyList())
    }

    val history: Flow<List<HistoryEntry>> = prefs.map { p ->
        val raw = p[Keys.HISTORY]
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<HistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun seedIfNeeded() {
        context.dataStore.edit { p ->
            if (p[Keys.PROMPTS] == null) p[Keys.PROMPTS] = json.encodeToString(PromptDefaults.builtIn)
            if (p[Keys.DICTIONARY] == null) p[Keys.DICTIONARY] = "[]"
            if (p[Keys.HISTORY] == null) p[Keys.HISTORY] = "[]"
        }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setPrimary(value: Provider) = edit { it[Keys.PRIMARY] = value.id }
    suspend fun setFallbackEnabled(value: Boolean) = edit { it[Keys.FALLBACK] = value }
    suspend fun setFallbackTimeout(ms: Long) = edit { it[Keys.FALLBACK_TIMEOUT] = ms.coerceIn(1000L, 8000L) }
    suspend fun setHedgeEnabled(value: Boolean) = edit { it[Keys.HEDGE] = value }
    suspend fun setHedgeDelay(ms: Long) = edit { it[Keys.HEDGE_DELAY] = ms.coerceIn(250L, 2000L) }
    suspend fun setGeminiModel(value: String) = edit { it[Keys.GEMINI_MODEL] = value }
    suspend fun setGroqModel(value: String) = edit { it[Keys.GROQ_MODEL] = value }
    suspend fun setCloudflareModel(value: String) = edit { it[Keys.CLOUDFLARE_MODEL] = value }
    suspend fun setLanguage(value: String) = edit { it[Keys.LANGUAGE] = value }
    suspend fun setActivePrompt(id: String) = edit { it[Keys.ACTIVE_PROMPT] = id }
    suspend fun setPunctuation(value: Boolean) = edit { it[Keys.PUNCTUATION] = value }
    suspend fun setSpokenCommands(value: Boolean) = edit { it[Keys.SPOKEN_COMMANDS] = value }
    suspend fun setFrenchTypography(value: Boolean) = edit { it[Keys.FRENCH_TYPO] = value }
    suspend fun setAutoCapitalize(value: Boolean) = edit { it[Keys.AUTO_CAPITALIZE] = value }
    suspend fun setAutoSpace(value: Boolean) = edit { it[Keys.AUTO_SPACE] = value }
    suspend fun setInsertMode(value: InsertMode) = edit { it[Keys.INSERT_MODE] = value.name }
    suspend fun setLivePreview(value: Boolean) = edit { it[Keys.LIVE_PREVIEW] = value }
    suspend fun setPreviewInterval(ms: Long) = edit { it[Keys.PREVIEW_INTERVAL] = ms.coerceIn(1500L, 6000L) }
    suspend fun setMaxDuration(sec: Int) = edit { it[Keys.MAX_DURATION] = sec.coerceIn(30, 600) }
    suspend fun setBubbleSize(dp: Int) = edit { it[Keys.BUBBLE_SIZE] = dp.coerceIn(46, 80) }
    suspend fun setBubbleOpacity(value: Float) = edit { it[Keys.BUBBLE_OPACITY] = value.coerceIn(0.55f, 1f) }
    suspend fun setBubbleAccent(value: BubbleAccent) = edit { it[Keys.BUBBLE_ACCENT] = value.name }
    suspend fun setBubbleIcon(value: BubbleIcon) = edit { it[Keys.BUBBLE_ICON] = value.name }
    suspend fun setBubbleGesture(value: BubbleGesture) = edit { it[Keys.BUBBLE_GESTURE] = value.name }
    suspend fun setHaptics(value: Boolean) = edit { it[Keys.HAPTICS] = value }
    suspend fun setTheme(value: ThemeMode) = edit { it[Keys.THEME] = value.name }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = value }
    suspend fun setGroqFormatting(value: Boolean) = edit { it[Keys.GROQ_FORMATTING] = value }
    suspend fun setSendAppContext(value: Boolean) = edit { it[Keys.SEND_APP_CONTEXT] = value }
    suspend fun setAppAwarePrompts(value: Boolean) = edit { it[Keys.APP_AWARE_PROMPTS] = value }
    suspend fun setStreamingMode(value: Boolean) = edit { it[Keys.STREAMING_MODE] = value }
    suspend fun setApertureFallbackEnabled(value: Boolean) = edit { it[Keys.APERTURE_FALLBACK] = value }
    suspend fun setApertureStreamingEnabled(value: Boolean) = edit { it[Keys.APERTURE_STREAMING] = value }
    suspend fun setDailyKeyRequestLimit(value: Int) = edit { it[Keys.DAILY_KEY_LIMIT] = value.coerceIn(0, 500) }
    suspend fun setOnboardingDone(value: Boolean) = edit { it[Keys.ONBOARDING] = value }

    suspend fun addPrompt(prompt: PromptPreset) = edit { p ->
        val list = decodePrompts(p).toMutableList()
        list.add(prompt)
        p[Keys.PROMPTS] = json.encodeToString(list)
    }

    suspend fun updatePrompt(prompt: PromptPreset) = edit { p ->
        val list = decodePrompts(p).map { if (it.id == prompt.id) prompt.copy(builtIn = it.builtIn) else it }
        p[Keys.PROMPTS] = json.encodeToString(list)
    }

    suspend fun deletePrompt(id: String) = edit { p ->
        val list = decodePrompts(p).filterNot { it.id == id && !it.builtIn }
        p[Keys.PROMPTS] = json.encodeToString(list)
    }

    suspend fun resetPrompts() = edit { p ->
        p[Keys.PROMPTS] = json.encodeToString(PromptDefaults.builtIn)
        p[Keys.ACTIVE_PROMPT] = PromptDefaults.CLEAN_ID
    }

    suspend fun addDictionaryEntry(entry: DictionaryEntry) = edit { p ->
        val list = decodeDictionary(p).toMutableList()
        list.add(entry)
        p[Keys.DICTIONARY] = json.encodeToString(list)
    }

    suspend fun updateDictionaryEntry(entry: DictionaryEntry) = edit { p ->
        val list = decodeDictionary(p).map { if (it.id == entry.id) entry else it }
        p[Keys.DICTIONARY] = json.encodeToString(list)
    }

    suspend fun deleteDictionaryEntry(id: String) = edit { p ->
        p[Keys.DICTIONARY] = json.encodeToString(decodeDictionary(p).filterNot { it.id == id })
    }

    suspend fun addHistory(entry: HistoryEntry) = edit { p ->
        val list = decodeHistory(p).toMutableList()
        list.add(0, entry)
        p[Keys.HISTORY] = json.encodeToString(list.take(120))
    }

    suspend fun clearHistory() = edit { p -> p[Keys.HISTORY] = "[]" }

    fun exportConfig(settings: AppSettings, prompts: List<PromptPreset>, dictionary: List<DictionaryEntry>): String {
        val payload = buildString {
            appendLine("{")
            appendLine("  \"app\": \"JoynixScribe\",")
            appendLine("  \"version\": 1,")
            appendLine("  \"settings\": {")
            appendLine("    \"primary\": \"${settings.primary.id}\",")
            appendLine("    \"fallbackEnabled\": ${settings.fallbackEnabled},")
            appendLine("    \"fallbackTimeoutMs\": ${settings.fallbackTimeoutMs},")
            appendLine("    \"hedgeEnabled\": ${settings.hedgeEnabled},")
            appendLine("    \"hedgeDelayMs\": ${settings.hedgeDelayMs},")
            appendLine("    \"geminiModel\": \"${settings.geminiModel}\",")
            appendLine("    \"groqModel\": \"${settings.groqModel}\",")
            appendLine("    \"cloudflareModel\": \"${settings.cloudflareModel}\",")
            appendLine("    \"language\": \"${settings.language}\",")
            appendLine("    \"appAwarePrompts\": ${settings.appAwarePrompts},")
            appendLine("    \"sendAppContext\": ${settings.sendAppContext},")
            appendLine("    \"apertureFallbackEnabled\": ${settings.apertureFallbackEnabled},")
            appendLine("    \"apertureStreamingEnabled\": ${settings.apertureStreamingEnabled},")
            appendLine("    \"bubbleGesture\": \"${settings.bubbleGesture.name}\"," )
            appendLine("    \"punctuation\": ${settings.punctuation},")
            appendLine("    \"spokenCommands\": ${settings.spokenCommands},")
            appendLine("    \"frenchTypography\": ${settings.frenchTypography},")
            appendLine("    \"insertMode\": \"${settings.insertMode.name}\"")
            appendLine("  },")
            appendLine("  \"prompts\": ${json.encodeToString(prompts)},")
            appendLine("  \"dictionary\": ${json.encodeToString(dictionary)}")
            appendLine("}")
        }
        return payload
    }

    private fun decodePrompts(p: Preferences): List<PromptPreset> {
        val raw = p[Keys.PROMPTS] ?: return PromptDefaults.builtIn
        return runCatching { json.decodeFromString<List<PromptPreset>>(raw) }.getOrDefault(PromptDefaults.builtIn)
    }

    private fun decodeDictionary(p: Preferences): List<DictionaryEntry> {
        val raw = p[Keys.DICTIONARY] ?: return emptyList()
        return runCatching { json.decodeFromString<List<DictionaryEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun decodeHistory(p: Preferences): List<HistoryEntry> {
        val raw = p[Keys.HISTORY] ?: return emptyList()
        return runCatching { json.decodeFromString<List<HistoryEntry>>(raw) }.getOrDefault(emptyList())
    }

    private object Keys {
        val PRIMARY = stringPreferencesKey("primary_provider")
        val FALLBACK = booleanPreferencesKey("fallback_enabled")
        val FALLBACK_TIMEOUT = longPreferencesKey("fallback_timeout")
        val HEDGE = booleanPreferencesKey("hedge_enabled")
        val HEDGE_DELAY = longPreferencesKey("hedge_delay")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val CLOUDFLARE_MODEL = stringPreferencesKey("cloudflare_model")
        val LANGUAGE = stringPreferencesKey("language")
        val ACTIVE_PROMPT = stringPreferencesKey("active_prompt")
        val PUNCTUATION = booleanPreferencesKey("punctuation")
        val SPOKEN_COMMANDS = booleanPreferencesKey("spoken_commands")
        val FRENCH_TYPO = booleanPreferencesKey("french_typography")
        val AUTO_CAPITALIZE = booleanPreferencesKey("auto_capitalize")
        val AUTO_SPACE = booleanPreferencesKey("auto_space")
        val INSERT_MODE = stringPreferencesKey("insert_mode")
        val LIVE_PREVIEW = booleanPreferencesKey("live_preview")
        val PREVIEW_INTERVAL = longPreferencesKey("preview_interval")
        val MAX_DURATION = intPreferencesKey("max_duration")
        val BUBBLE_SIZE = intPreferencesKey("bubble_size")
        val BUBBLE_OPACITY = floatPreferencesKey("bubble_opacity")
        val BUBBLE_ACCENT = stringPreferencesKey("bubble_accent")
        val BUBBLE_ICON = stringPreferencesKey("bubble_icon")
        val BUBBLE_GESTURE = stringPreferencesKey("bubble_gesture")
        val HAPTICS = booleanPreferencesKey("haptics")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val GROQ_FORMATTING = booleanPreferencesKey("groq_formatting")
        val SEND_APP_CONTEXT = booleanPreferencesKey("send_app_context")
        val APP_AWARE_PROMPTS = booleanPreferencesKey("app_aware_prompts")
        val STREAMING_MODE = booleanPreferencesKey("streaming_mode")
        val APERTURE_FALLBACK = booleanPreferencesKey("aperture_fallback")
        val APERTURE_STREAMING = booleanPreferencesKey("aperture_streaming")
        val DAILY_KEY_LIMIT = intPreferencesKey("daily_key_limit")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val PROMPTS = stringPreferencesKey("prompts_v1")
        val DICTIONARY = stringPreferencesKey("dictionary_v1")
        val HISTORY = stringPreferencesKey("history_v1")
    }
}
