package com.botillon.flowscribe.ai

import com.botillon.flowscribe.data.ApiKeyStore
import com.botillon.flowscribe.data.AppSettings
import com.botillon.flowscribe.data.DictionaryEntry
import com.botillon.flowscribe.data.PromptDefaults
import com.botillon.flowscribe.data.PromptPreset
import com.botillon.flowscribe.data.Provider
import com.botillon.flowscribe.text.PunctuationProcessor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

data class EngineInput(
    val wav: ByteArray,
    val settings: AppSettings,
    val prompts: List<PromptPreset>,
    val dictionary: List<DictionaryEntry>,
    val preview: Boolean = false,
    val appName: String = "",
    val appPackage: String = "",
)

data class UsageInfo(
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val audioSeconds: Double = 0.0,
    val neurons: Double = 0.0,
) {
    val hasTokens: Boolean get() = totalTokens > 0
    val hasAny: Boolean get() = hasTokens || audioSeconds > 0.0 || neurons > 0.0

    fun plus(other: UsageInfo) = UsageInfo(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        totalTokens = totalTokens + other.totalTokens,
        audioSeconds = audioSeconds + other.audioSeconds,
        neurons = neurons + other.neurons,
    )
}

data class EngineSuccess(
    val text: String,
    val rawText: String,
    val provider: Provider,
    val model: String,
    val latencyMs: Long,
    val fallbackUsed: Boolean,
    val usage: UsageInfo = UsageInfo(),
    val appName: String = "",
    val appPackage: String = "",
    val promptName: String = "",
)

sealed interface EngineResult {
    data class Success(val data: EngineSuccess) : EngineResult
    data class Failure(val message: String, val timedOut: Boolean = false) : EngineResult
}

/**
 * Orchestrateur multi-fournisseurs :
 *  - rotation des clés (jusqu'à 9 par fournisseur) avec refroidissement,
 *  - plafond quotidien optionnel par clé,
 *  - course « hedged » Gemini / STT : le premier texte valide gagne,
 *  - repli chronométré configurable,
 *  - style adapté à l'application active via {{app}} / {{package}},
 *  - post-traitement typographique et vocabulaire,
 *  - comptage des tokens / secondes audio / neurons par clé.
 */
class TranscriptionEngine(
    private val keyStore: ApiKeyStore,
    private val gemini: GeminiClient = GeminiClient(),
    private val groq: GroqClient = GroqClient(),
    private val cloudflare: CloudflareClient = CloudflareClient(),
) {

    suspend fun transcribe(input: EngineInput): EngineResult {
        val started = System.currentTimeMillis()
        val settings = input.settings
        val limit = settings.dailyKeyRequestLimit
        val primary = settings.primary

        val prompt = choosePrompt(input)
        val appName = if (settings.sendAppContext) input.appName else ""
        val appPackage = if (settings.sendAppContext) input.appPackage else ""
        val systemInstruction = buildInstruction(settings, prompt, input.dictionary, appName, appPackage)
        val vocabulary = buildVocabulary(input.dictionary)

        val order = fallbackOrder(primary)
        val primaryReady = keyStore.usableKeys(primary, limit).isNotEmpty()
        val secondary = order.drop(1).firstOrNull { keyStore.usableKeys(it, limit).isNotEmpty() }
        val secondaryReady = !input.preview && settings.fallbackEnabled && secondary != null

        if (input.preview && !primaryReady) {
            return EngineResult.Failure("Aucune clé ${primary.shortLabel} disponible")
        }
        if (!primaryReady && !secondaryReady) {
            val capped = keyStore.keys(primary).any { it.enabled }
            return EngineResult.Failure(
                if (capped && limit > 0) {
                    "Plafond quotidien atteint pour ${primary.shortLabel} ($limit requêtes/clé)."
                } else {
                    "Ajoute une clé API ${primary.shortLabel} dans l'application pour dicter."
                },
            )
        }

        val effectivePrimary = if (primaryReady) primary else secondary!!
        val effectiveSecondary = if (primaryReady) secondary else null

        val outcome = when {
            input.preview || effectiveSecondary == null -> callProvider(
                provider = effectivePrimary,
                wav = input.wav,
                settings = settings,
                systemInstruction = systemInstruction,
                vocabulary = vocabulary,
                timeoutMs = if (input.preview) 9_000L else 20_000L,
            )
            else -> raceProviders(
                primary = effectivePrimary,
                secondary = effectiveSecondary,
                wav = input.wav,
                settings = settings,
                systemInstruction = systemInstruction,
                vocabulary = vocabulary,
            )
        }

        if (!outcome.ok) {
            return EngineResult.Failure(
                message = outcome.error ?: "Transcription impossible",
                timedOut = outcome.timedOut,
            )
        }

        var text = outcome.text
        var usage = outcome.toUsage()

        // Gemini applique directement le style. Groq / Cloudflare ne sont que
        // des moteurs de transcription : on reformate avec un petit LLM.
        if (!input.preview &&
            outcome.provider != Provider.GEMINI &&
            prompt.id != PromptDefaults.CLEAN_ID &&
            settings.groqFormatting
        ) {
            val formatted = reformat(outcome.provider, text, systemInstruction)
            if (formatted != null) {
                text = formatted.text
                usage = usage.plus(formatted.usage)
            }
        }

        if (!input.preview) {
            text = PunctuationProcessor.process(text, settings, input.dictionary)
        }

        if (text.isBlank()) {
            return EngineResult.Failure("Aucune parole détectée")
        }

        return EngineResult.Success(
            EngineSuccess(
                text = text,
                rawText = outcome.text,
                provider = outcome.provider,
                model = outcome.model,
                latencyMs = System.currentTimeMillis() - started,
                fallbackUsed = outcome.provider != primary,
                usage = usage,
                appName = input.appName,
                appPackage = input.appPackage,
                promptName = prompt.name,
            ),
        )
    }

    /** Course entre le fournisseur principal et le meilleur repli disponible. */
    private suspend fun raceProviders(
        primary: Provider,
        secondary: Provider,
        wav: ByteArray,
        settings: AppSettings,
        systemInstruction: String,
        vocabulary: String,
    ): ProviderOutcome = coroutineScope {
        val primaryDeferred = CompletableDeferred<ProviderOutcome>()
        val secondaryDeferred = CompletableDeferred<ProviderOutcome>()
        val gate = CompletableDeferred<Unit>()
        val primaryResult = AtomicReference<ProviderOutcome?>(null)

        val primaryJob = launch {
            val result = safeCall(primary, wav, settings, systemInstruction, vocabulary, 22_000L)
            primaryResult.set(result)
            primaryDeferred.complete(result)
            if (!result.ok) gate.complete(Unit)
        }

        val gateJob = launch {
            if (settings.hedgeEnabled) {
                withTimeoutOrNull(settings.hedgeDelayMs.coerceIn(250L, 2_000L)) {
                    primaryDeferred.await()
                }
            } else {
                withTimeoutOrNull(settings.fallbackTimeoutMs.coerceIn(1_000L, 8_000L)) {
                    primaryDeferred.await()
                }
            }
            if (primaryResult.get()?.ok != true) gate.complete(Unit)
        }

        val secondaryJob = launch {
            gate.await()
            if (primaryResult.get()?.ok == true) return@launch
            delay(40)
            if (primaryResult.get()?.ok == true) return@launch
            val result = safeCall(secondary, wav, settings, systemInstruction, vocabulary, 22_000L)
            secondaryDeferred.complete(result)
        }

        val first = select {
            primaryDeferred.onAwait { it }
            secondaryDeferred.onAwait { it }
        }

        if (first.ok) {
            primaryJob.cancel()
            gateJob.cancel()
            secondaryJob.cancel()
            return@coroutineScope first
        }

        val second = if (first.provider == primary) {
            if (secondaryDeferred.isCompleted) secondaryDeferred.await()
            else withTimeoutOrNull(24_000L) { secondaryDeferred.await() }
        } else {
            if (primaryDeferred.isCompleted) primaryDeferred.await()
            else withTimeoutOrNull(24_000L) { primaryDeferred.await() }
        }

        when {
            second == null -> first
            second.ok -> second
            second.httpCode in 400..499 && first.httpCode !in 400..499 -> second
            else -> first
        }
    }

    private suspend fun safeCall(
        provider: Provider,
        wav: ByteArray,
        settings: AppSettings,
        systemInstruction: String,
        vocabulary: String,
        timeoutMs: Long,
    ): ProviderOutcome = try {
        callProvider(provider, wav, settings, systemInstruction, vocabulary, timeoutMs)
    } catch (e: Exception) {
        ProviderOutcome.failure(provider, modelFor(provider, settings), friendlyNetworkError(e), 0)
    }

    private suspend fun callProvider(
        provider: Provider,
        wav: ByteArray,
        settings: AppSettings,
        systemInstruction: String,
        vocabulary: String,
        timeoutMs: Long,
    ): ProviderOutcome {
        val keys = keyStore.usableKeys(provider, settings.dailyKeyRequestLimit)
        val model = modelFor(provider, settings)
        if (keys.isEmpty()) {
            return ProviderOutcome.failure(provider, model, "Aucune clé ${provider.shortLabel} disponible", 0)
        }

        var last: ProviderOutcome? = null
        for (key in keys.take(2)) {
            keyStore.touch(provider, key.id)
            val outcome = when (provider) {
                Provider.GEMINI -> gemini.transcribe(
                    secret = key.secret,
                    model = model,
                    wav = wav,
                    systemInstruction = systemInstruction,
                    timeoutMs = timeoutMs,
                )
                Provider.GROQ -> groq.transcribe(
                    secret = key.secret,
                    model = model,
                    wav = wav,
                    language = settings.language,
                    vocabulary = vocabulary,
                    timeoutMs = timeoutMs,
                )
                Provider.CLOUDFLARE -> cloudflare.transcribe(
                    secret = key.secret,
                    model = model,
                    wav = wav,
                    language = settings.language,
                    timeoutMs = timeoutMs,
                )
            }

            if (outcome.ok) {
                keyStore.markSuccess(provider, key.id)
                keyStore.recordUsage(
                    provider = provider,
                    id = key.id,
                    tokens = outcome.totalTokens,
                    audioSeconds = outcome.audioSeconds,
                    neurons = outcome.neurons,
                )
                return outcome
            }

            last = outcome
            val cooldown = cooldownFor(outcome)
            keyStore.markFailure(provider, key.id, cooldown, outcome.error)
            if (outcome.httpCode == 400 || outcome.httpCode == 413) break
        }

        return last ?: ProviderOutcome.failure(provider, model, "Échec ${provider.shortLabel}", 0)
    }

    /**
     * Reformatage d'un texte STT via un petit LLM. On préfère le fournisseur
     * qui vient de transcrire, puis les autres clés disponibles.
     */
    private suspend fun reformat(
        provider: Provider,
        text: String,
        instruction: String,
    ): TextFormat? {
        val order = when (provider) {
            Provider.GROQ -> listOf(Provider.GROQ, Provider.CLOUDFLARE)
            Provider.CLOUDFLARE -> listOf(Provider.CLOUDFLARE, Provider.GROQ)
            Provider.GEMINI -> emptyList()
        }
        for (candidate in order) {
            val key = keyStore.usableKeys(candidate).firstOrNull() ?: continue
            if (candidate == Provider.GROQ) {
                val result = groq.format(key.secret, text, instruction, 10_000L).getOrNull() ?: continue
                if (result.text.isNotBlank()) {
                    keyStore.recordUsage(Provider.GROQ, key.id, result.totalTokens)
                    return TextFormat(
                        result.text,
                        UsageInfo(
                            promptTokens = result.promptTokens,
                            completionTokens = result.completionTokens,
                            totalTokens = result.totalTokens,
                        ),
                    )
                }
            } else {
                val result = cloudflare.format(key.secret, text, instruction, 12_000L).getOrNull() ?: continue
                if (result.text.isNotBlank()) {
                    keyStore.recordUsage(
                        Provider.CLOUDFLARE,
                        key.id,
                        result.totalTokens,
                        neurons = result.neurons,
                    )
                    return TextFormat(
                        result.text,
                        UsageInfo(
                            promptTokens = result.promptTokens,
                            completionTokens = result.completionTokens,
                            totalTokens = result.totalTokens,
                            neurons = result.neurons,
                        ),
                    )
                }
            }
        }
        return null
    }

    private fun cooldownFor(outcome: ProviderOutcome): Long = when {
        outcome.httpCode == 401 || outcome.httpCode == 403 -> 30 * 60_000L
        outcome.httpCode == 429 -> outcome.retryAfterMs.coerceIn(20_000L, 15 * 60_000L)
        outcome.httpCode >= 500 -> 45_000L
        outcome.timedOut -> 8_000L
        outcome.httpCode == 0 -> 10_000L
        else -> 5_000L
    }

    private fun modelFor(provider: Provider, settings: AppSettings): String = when (provider) {
        Provider.GEMINI -> settings.geminiModel.ifBlank { Provider.GEMINI.defaultModel }
        Provider.GROQ -> settings.groqModel.ifBlank { Provider.GROQ.defaultModel }
        Provider.CLOUDFLARE -> settings.cloudflareModel.ifBlank { Provider.CLOUDFLARE.defaultModel }
    }

    suspend fun testKey(provider: Provider, secret: String): Result<String> = when (provider) {
        Provider.GEMINI -> gemini.testKey(secret)
        Provider.GROQ -> groq.testKey(secret)
        Provider.CLOUDFLARE -> cloudflare.testKey(secret)
    }

    private fun choosePrompt(input: EngineInput): PromptPreset {
        if (input.settings.appAwarePrompts && input.appPackage.isNotBlank()) {
            input.prompts.firstOrNull { it.matchesPackage(input.appPackage) }?.let { return it }
        }
        return input.prompts.firstOrNull { it.id == input.settings.activePromptId }
            ?: input.prompts.firstOrNull()
            ?: PromptDefaults.builtIn.first()
    }

    private fun fallbackOrder(primary: Provider): List<Provider> = when (primary) {
        Provider.GEMINI -> listOf(Provider.GEMINI, Provider.GROQ, Provider.CLOUDFLARE)
        Provider.GROQ -> listOf(Provider.GROQ, Provider.CLOUDFLARE, Provider.GEMINI)
        Provider.CLOUDFLARE -> listOf(Provider.CLOUDFLARE, Provider.GEMINI, Provider.GROQ)
    }

    companion object {
        fun buildVocabulary(dictionary: List<DictionaryEntry>): String {
            val terms = dictionary.asSequence()
                .filter { it.enabled && it.term.isNotBlank() }
                .map { it.term.trim() }
                .distinct()
                .take(60)
                .toList()
            if (terms.isEmpty()) return ""
            return "Vocabulaire à respecter : " + terms.joinToString(", ") + "."
        }

        fun buildInstruction(
            settings: AppSettings,
            prompt: PromptPreset,
            dictionary: List<DictionaryEntry>,
            appName: String = "",
            appPackage: String = "",
        ): String = buildString {
            appendLine(applyPlaceholders(prompt.instructions.trim(), settings, appName, appPackage))
            appendLine()
            if (appPackage.isNotBlank()) {
                appendLine("Application active : ${appName.ifBlank { appPackage }} ($appPackage).")
                appendLine("Tiens compte de cette application si les consignes ci-dessus la mentionnent.")
                appendLine()
            }
            if (settings.language == "auto") {
                appendLine("Transcris dans la langue réellement parlée. Ne traduis pas.")
            } else {
                appendLine("Transcris en ${languageLabel(settings.language)}.")
            }
            val vocabulary = buildVocabulary(dictionary)
            if (vocabulary.isNotBlank()) {
                appendLine(vocabulary)
                appendLine("Respecte exactement ces graphies, y compris les majuscules et accents.")
            }
            if (settings.punctuation) {
                appendLine("Ponctue le texte de façon complète. Une intonation montante est une question.")
            }
            appendLine("Réponds uniquement avec le texte final, sans commentaire ni guillemets.")
        }.trim()

        fun applyPlaceholders(
            text: String,
            settings: AppSettings,
            appName: String,
            appPackage: String,
        ): String {
            val date = runCatching {
                SimpleDateFormat("d MMMM yyyy", Locale.FRANCE).format(Date())
            }.getOrDefault("")
            return text
                .replace("{{app}}", appName.ifBlank { appPackage }.ifBlank { "l'application active" })
                .replace("{{package}}", appPackage)
                .replace("{{lang}}", if (settings.language == "auto") "langue parlée" else languageLabel(settings.language))
                .replace("{{date}}", date)
        }

        fun languageLabel(code: String): String = when (code) {
            "fr" -> "français"
            "en" -> "anglais"
            "es" -> "espagnol"
            "de" -> "allemand"
            "it" -> "italien"
            "pt" -> "portugais"
            "nl" -> "néerlandais"
            else -> "français"
        }
    }

    private data class TextFormat(val text: String, val usage: UsageInfo)
}

private fun ProviderOutcome.toUsage() = UsageInfo(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = totalTokens,
    audioSeconds = audioSeconds,
    neurons = neurons,
)
