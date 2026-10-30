package com.botillon.flowscribe.ai

import com.botillon.flowscribe.data.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiClient {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun transcribe(
        secret: String,
        model: String,
        wav: ByteArray,
        systemInstruction: String,
        timeoutMs: Long,
    ): ProviderOutcome {
        val started = System.currentTimeMillis()
        val base64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)

        val payload = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemInstruction) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", "Transcris cet audio de dictée.") })
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", "audio/wav")
                                put("data", base64)
                            })
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                // Gemini 3+ ignore (et rejettent bientôt) temperature/topP : on ne les envoie que si utile.
                if (!model.startsWith("gemini-3") && !model.startsWith("gemini-4")) {
                    put("temperature", 0.0)
                }
                // Budget de sortie calibré sur la durée réelle : la parole consomme ~3 tokens/s.
                put("maxOutputTokens", outputBudget(wav.size))
                put("responseMimeType", "text/plain")
                if (model.startsWith("gemini-2.5")) {
                    put("thinkingConfig", buildJsonObject { put("thinkingBudget", 0) })
                }
            })
            put("safetySettings", buildJsonArray {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT",
                ).forEach { category ->
                    add(buildJsonObject {
                        put("category", category)
                        put("threshold", "BLOCK_NONE")
                    })
                }
            })
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", secret)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val client = Http.clientWithTimeout(timeoutMs)
        return try {
            client.newCall(request).awaitResponse().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = parseError(body).ifBlank { "HTTP ${response.code}" }
                    return ProviderOutcome.failure(
                        provider = Provider.GEMINI,
                        model = model,
                        error = error,
                        httpCode = response.code,
                        retryAfterMs = parseRetryDelay(body),
                        latencyMs = System.currentTimeMillis() - started,
                    )
                }
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return ProviderOutcome.failure(
                        Provider.GEMINI, model, "Réponse Gemini illisible", response.code,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                val block = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
                if (block != null) {
                    return ProviderOutcome.failure(
                        Provider.GEMINI, model, "Requête bloquée par Gemini ($block)", 400,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                }
                val text = root["candidates"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString("")
                    ?.trim()
                    .orEmpty()

                if (text.isBlank()) {
                    ProviderOutcome.failure(
                        Provider.GEMINI, model, "Gemini n'a renvoyé aucun texte", 200,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                } else {
                    val usage = root["usageMetadata"]?.jsonObject
                    val prompt = usage?.get("promptTokenCount")?.jsonPrimitive?.longOrNull ?: 0L
                    val completion = usage?.get("candidatesTokenCount")?.jsonPrimitive?.longOrNull ?: 0L
                    val total = usage?.get("totalTokenCount")?.jsonPrimitive?.longOrNull
                        ?: (prompt + completion)
                    ProviderOutcome.ok(
                        provider = Provider.GEMINI,
                        model = model,
                        text = text,
                        latencyMs = System.currentTimeMillis() - started,
                        promptTokens = prompt,
                        completionTokens = completion,
                        totalTokens = total,
                        audioSeconds = audioSeconds(wav.size),
                    )
                }
            }
        } catch (e: Exception) {
            ProviderOutcome.failure(
                Provider.GEMINI, model, friendlyNetworkError(e), 0,
                latencyMs = System.currentTimeMillis() - started,
                timedOut = e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException,
            )
        }
    }

    suspend fun testKey(secret: String): Result<String> {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
            .header("x-goog-api-key", secret)
            .get()
            .build()
        return try {
            Http.clientWithTimeout(12_000).newCall(request).awaitResponse().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) Result.success("Clé Gemini valide")
                else Result.failure(IllegalStateException(parseError(body).ifBlank { "HTTP ${response.code}" }))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(friendlyNetworkError(e)))
        }
    }

    /**
     * Plafond de sortie volontairement bas : une dictée de 60 s produit ~200 tokens.
     * On garde 4x la marge pour les prompts qui reformatent, sans laisser filer la facture.
     */
    private fun outputBudget(wavBytes: Int): Int {
        val seconds = audioSeconds(wavBytes)
        return (seconds * 8 + 160).toInt().coerceIn(192, 2_048)
    }

    private fun audioSeconds(wavBytes: Int): Double =
        ((wavBytes - 44).coerceAtLeast(0)) / 32_000.0

    private fun parseError(body: String): String {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
    }

    private fun parseRetryDelay(body: String): Long {
        val match = Regex("\"(?:retryDelay|retry_delay)\"\\s*:\\s*\"(\\d+)s\"").find(body)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull()?.times(1000) ?: 30_000
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun friendlyNetworkError(e: Exception): String {
    val raw = e.message.orEmpty()
    return when {
        e is java.net.UnknownHostException -> "Pas de connexion Internet"
        e is java.net.SocketTimeoutException -> "Délai réseau dépassé"
        raw.contains("timeout", ignoreCase = true) -> "Délai réseau dépassé"
        e is java.net.ConnectException -> "Connexion impossible"
        raw.isBlank() -> e.javaClass.simpleName
        else -> raw.take(160)
    }
}

data class ProviderOutcome(
    val ok: Boolean,
    val provider: Provider,
    val model: String,
    val text: String = "",
    val error: String? = null,
    val httpCode: Int = 0,
    val latencyMs: Long = 0,
    val retryAfterMs: Long = 0,
    val timedOut: Boolean = false,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val audioSeconds: Double = 0.0,
    val neurons: Double = 0.0,
    val noSpeechProb: Double = 0.0,
) {
    companion object {
        fun ok(
            provider: Provider,
            model: String,
            text: String,
            latencyMs: Long,
            promptTokens: Long = 0L,
            completionTokens: Long = 0L,
            totalTokens: Long = 0L,
            audioSeconds: Double = 0.0,
            neurons: Double = 0.0,
            noSpeechProb: Double = 0.0,
        ) = ProviderOutcome(
            ok = true,
            provider = provider,
            model = model,
            text = text,
            latencyMs = latencyMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            audioSeconds = audioSeconds,
            neurons = neurons,
            noSpeechProb = noSpeechProb,
        )

        fun failure(
            provider: Provider,
            model: String,
            error: String,
            httpCode: Int,
            latencyMs: Long = 0,
            retryAfterMs: Long = 0,
            timedOut: Boolean = false,
        ) = ProviderOutcome(
            ok = false,
            provider = provider,
            model = model,
            error = error,
            httpCode = httpCode,
            latencyMs = latencyMs,
            retryAfterMs = retryAfterMs,
            timedOut = timedOut,
        )
    }
}
