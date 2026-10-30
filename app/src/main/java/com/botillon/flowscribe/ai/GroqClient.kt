package com.botillon.flowscribe.ai

import com.botillon.flowscribe.data.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client Groq : Whisper large-v3-turbo pour la transcription,
 * Llama 3.1 8B instant pour le reformatage optionnel des prompts personnalisés.
 */
class GroqClient {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun transcribe(
        secret: String,
        model: String,
        wav: ByteArray,
        language: String,
        vocabulary: String,
        timeoutMs: Long,
    ): ProviderOutcome {
        val started = System.currentTimeMillis()
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "flowscribe.wav", wav.toRequestBody(WAV_MEDIA))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("condition_on_previous_text", "false")

        if (language != "auto" && language.isNotBlank()) builder.addFormDataPart("language", language)
        if (vocabulary.isNotBlank()) builder.addFormDataPart("prompt", vocabulary.take(880))

        val request = Request.Builder()
            .url("$BASE_URL/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $secret")
            .post(builder.build())
            .build()

        return try {
            Http.clientWithTimeout(timeoutMs).newCall(request).awaitResponse().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return ProviderOutcome.failure(
                        provider = Provider.GROQ,
                        model = model,
                        error = parseError(body).ifBlank { "HTTP ${response.code}" },
                        httpCode = response.code,
                        retryAfterMs = parseRetryAfter(response.header("retry-after")),
                        latencyMs = System.currentTimeMillis() - started,
                    )
                }
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val text = root?.get("text")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val duration = root?.get("duration")?.jsonPrimitive?.doubleOrNull
                    ?: audioSeconds(wav.size)
                val usage = root?.get("usage")?.jsonObject
                    ?: root?.get("x_groq")?.jsonObject?.get("usage")?.jsonObject
                val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L
                val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L
                val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.longOrNull
                    ?: (promptTokens + completionTokens)

                if (text.isBlank()) {
                    ProviderOutcome.failure(
                        Provider.GROQ, model, "Groq n'a renvoyé aucun texte", 200,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                } else {
                    ProviderOutcome.ok(
                        provider = Provider.GROQ,
                        model = model,
                        text = text,
                        latencyMs = System.currentTimeMillis() - started,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        totalTokens = totalTokens,
                        audioSeconds = duration,
                    )
                }
            }
        } catch (e: Exception) {
            ProviderOutcome.failure(
                Provider.GROQ, model, friendlyNetworkError(e), 0,
                latencyMs = System.currentTimeMillis() - started,
                timedOut = e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException,
            )
        }
    }

    /**
     * Reformatage du texte Whisper (ponctuation, style, prompt personnalisé).
     * Uniquement appelé pour un prompt non standard, jamais en mode dictée fidèle.
     */
    suspend fun format(
        secret: String,
        text: String,
        instruction: String,
        timeoutMs: Long = 12_000L,
    ): Result<GroqFormatOutcome> {
        val payload = buildJsonObject {
            put("model", FORMAT_MODEL)
            put("temperature", 0.1)
            // Une dictée tient largement dans 1200 tokens : pas de génération inutile.
            put("max_tokens", 1200)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", instruction)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", text)
                })
            })
        }

        val request = Request.Builder()
            .url("$BASE_URL/openai/v1/chat/completions")
            .header("Authorization", "Bearer $secret")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return try {
            Http.clientWithTimeout(timeoutMs).newCall(request).awaitResponse().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException(parseError(body).ifBlank { "HTTP ${response.code}" }))
                }
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val content = root?.get("choices")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonObject?.get("content")
                    ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val usage = root?.get("usage")?.jsonObject
                val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L
                val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L
                val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.longOrNull
                    ?: (promptTokens + completionTokens)
                if (content.isBlank()) {
                    Result.failure(IllegalStateException("Reformatage vide"))
                } else {
                    Result.success(
                        GroqFormatOutcome(
                            text = content,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            totalTokens = totalTokens,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(friendlyNetworkError(e)))
        }
    }

    suspend fun testKey(secret: String): Result<String> {
        val request = Request.Builder()
            .url("$BASE_URL/openai/v1/models")
            .header("Authorization", "Bearer $secret")
            .get()
            .build()
        return try {
            Http.clientWithTimeout(12_000).newCall(request).awaitResponse().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val count = runCatching {
                        json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.size
                    }.getOrNull() ?: 0
                    Result.success("Clé Groq valide · $count modèles")
                } else {
                    Result.failure(IllegalStateException(parseError(body).ifBlank { "HTTP ${response.code}" }))
                }
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(friendlyNetworkError(e)))
        }
    }

    private fun parseError(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull().orEmpty()

    private fun parseRetryAfter(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull()
        return if (seconds != null) seconds * 1000 else 20_000L
    }

    private fun audioSeconds(wavBytes: Int): Double =
        ((wavBytes - 44).coerceAtLeast(0)) / 32_000.0

    companion object {
        private const val BASE_URL = "https://api.groq.com"
        private const val FORMAT_MODEL = "llama-3.1-8b-instant"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val WAV_MEDIA = "audio/wav".toMediaType()
    }
}

data class GroqFormatOutcome(
    val text: String,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val totalTokens: Long = 0L,
)
