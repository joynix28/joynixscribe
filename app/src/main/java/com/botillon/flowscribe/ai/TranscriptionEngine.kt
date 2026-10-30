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
import java.util.concurrent.atomic.AtomicReference

data class EngineInput(
    val wav: ByteArray,
    val settings: AppSettings,
    val prompts: List<PromptPreset>,
    val dictionary: List<DictionaryEntry>,
    val preview: Boolean = false,
)

data class EngineSuccess(
    val text: String,
    val rawText: String,
    val provider: Provider,
    val model: String,
    val latencyMs: Long,
    val fallbackUsed: Boolean,
)

sealed interface EngineResult {
    data class Success(val data: EngineSuccess) : EngineResult
    data class Failure(val message: String, val timedOut: Boolean = false) : EngineResult
}

/**
 * Orchestrateur multi-fournisseurs :
 *  - rotation des clés (jusqu'à 9 par fournisseur) avec refroidissement,
 *  - course « hedged » Gemini / Groq : le premier texte valide gagne,
 *  - repli chronométré configurable,
 *  - post-traitement typographique et vocabulaire.
 */
class TranscriptionEngine(
    private val keyStore: ApiKeyStore,
    private val gemini: GeminiClient = GeminiClient(),
    private val groq: GroqClient = GroqClient(),
) {

    suspend fun transcribe(input: EngineInput): EngineResult {
        val started = System.currentTimeMillis()
        val settings = input.settings
        val primary = settings.primary
        val secondary = primary.other()

        val prompt = input.prompts.firstOrNull { it.id == settings.activePromptId }
            ?: input.prompts.firstOrNull()
            ?: PromptDefaults.builtIn.first()

        val systemInstruction = buildInstruction(settings, prompt, input.dictionary)
        val vocabulary = buildVocabulary(input.dictionary)

        val primaryReady = keyStore.usableKeys(primary).isNotEmpty()
        val secondaryReady = !input.preview &&
            settings.fallbackEnabled &&
            keyStore.usableKeys(secondary).isNotEmpty()

        if (input.preview && !primaryReady) {
            return EngineResult.Failure("Aucune clé ${primary.shortLabel} disponible")
        }
        if (!primaryReady && !secondaryReady) {
            return EngineResult.Failure(
                "Ajoute une clé API ${primary.shortLabel} dans l'application pour dicter.",
            )
        }

        val outcome = when {
            input.preview -> callProvider(
                provider = primary,
                wav = input.wav,
                settings = settings,
                systemInstruction = prompt.instructions,
                vocabulary = vocabulary,
                timeoutMs = 9_000L,
            )
            !primaryReady -> callProvider(
                provider = secondary,
                wav = input.wav,
                settings = settings,
                systemInstruction = systemInstruction,
                vocabulary = vocabulary,
                timeoutMs = 20_000L,
            )
            !secondaryReady -> callProvider(
                provider = primary,
                wav = input.wav,
                settings = settings,
                systemInstruction = systemInstruction,
                vocabulary = vocabulary,
                timeoutMs = 20_000L,
            )
            else -> raceProviders(
                primary = primary,
                secondary = secondary,
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
        if (outcome.provider == Provider.GROQ &&
            !input.preview &&
            settings.groqFormatting &&
            prompt.id != PromptDefaults.CLEAN_ID
        ) {
            val key = keyStore.usableKeys(Provider.GROQ).firstOrNull()
            if (key != null) {
                val formatted = groq.format(key.secret, text, systemInstruction, 10_000L).getOrNull()
                if (!formatted.isNullOrBlank()) text = formatted
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
            ),
        )
    }

    /** Course entre les deux fournisseurs. Le premier succès annule l'autre appel. */
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
        val keys = keyStore.usableKeys(provider)
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
            }

            if (outcome.ok) {
                keyStore.markSuccess(provider, key.id)
                return outcome
            }

            last = outcome
            val cooldown = cooldownFor(outcome)
            keyStore.markFailure(provider, key.id, cooldown, outcome.error)
            if (outcome.httpCode == 400 || outcome.httpCode == 413) break
        }

        return last ?: ProviderOutcome.failure(provider, model, "Échec ${provider.shortLabel}", 0)
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
    }

    suspend fun testKey(provider: Provider, secret: String): Result<String> = when (provider) {
        Provider.GEMINI -> gemini.testKey(secret)
        Provider.GROQ -> groq.testKey(secret)
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
        ): String = buildString {
            appendLine(prompt.instructions.trim())
            appendLine()
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
}

private fun Provider.other(): Provider = if (this == Provider.GEMINI) Provider.GROQ else Provider.GEMINI
