package com.botillon.flowscribe.ai

import android.util.Base64
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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.botillon.flowscribe.ai.Http

/**
 * Cloudflare Workers AI via l'API REST.
 *
 * La clé stockée localement a la forme `accountId|token` : l'identifiant de compte
 * n'est pas secret, le token l'est. Rien n'est codé en dur dans l'application.
 *
 * Le corps envoyé est le WAV brut (Content-Type: audio/wav), pas du JSON :
 * c'est le format accepté par whisper-large-v3-turbo et whisper.
 */
class CloudflareClient {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun transcribe(
        secret: String,
        model: String,
        wav: ByteArray,
        language: String,
        timeoutMs: Long,
    ): ProviderOutcome {
        val started = System.currentTimeMillis()
        val credentials = parseCredentials(secret)
            ?: return ProviderOutcome.failure(
                Provider.CLOUDFLARE,
                model,
                "Clé Cloudflare invalide : format accountId|token attendu",
                400,
            )
        val (accountId, token) = credentials
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"

        val grok = model.contains("grok-stt", ignoreCase = true)
        val requestBody = if (grok) {
            val base64 = Base64.encodeToString(wav, Base64.NO_WRAP)
            val payload = buildJsonObject {
                put("file", "data:audio/wav;base64,$base64")
                if (language != "auto" && language.isNotBlank()) put("language", language)
            }
            payload.toString().toRequestBody(JSON_MEDIA)
        } else {
            wav.toRequestBody(WAV_MEDIA)
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        return try {
            Http.clientWithTimeout(timeoutMs).newCall(request).awaitResponse().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return ProviderOutcome.failure(
                        provider = Provider.CLOUDFLARE,
                        model = model,
                        error = parseError(body).ifBlank { "HTTP ${response.code}" },
                        httpCode = response.code,
                        retryAfterMs = parseRetryAfter(response.header("retry-after")),
                        latencyMs = System.currentTimeMillis() - started,
                    )
                }
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return ProviderOutcome.failure(
                        Provider.CLOUDFLARE, model, "Réponse Cloudflare illisible", response.code,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                if (root["success"]?.jsonPrimitive?.contentOrNull == "false") {
                    return ProviderOutcome.failure(
                        Provider.CLOUDFLARE, model, parseError(body).ifBlank { "Requête Cloudflare refusée" },
                        response.code,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                }
                val result = root["result"]?.jsonObject
                val text = result?.get("text")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isBlank()) {
                    return ProviderOutcome.failure(
                        Provider.CLOUDFLARE, model, "Cloudflare n'a renvoyé aucun texte", 200,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                }
                val duration = result?.get("transcription_info")?.jsonObject
                    ?.get("duration")?.jsonPrimitive?.doubleOrNull
                    ?: result?.get("duration")?.jsonPrimitive?.doubleOrNull
                    ?: audioSeconds(wav.size)
                val usage = result?.get("usage")?.jsonObject
                val neurons = usage?.get("neurons")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.longOrNull ?: 0L
                val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L
                val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L

                ProviderOutcome.ok(
                    provider = Provider.CLOUDFLARE,
                    model = model,
                    text = text,
                    latencyMs = System.currentTimeMillis() - started,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                    audioSeconds = duration,
                    neurons = neurons,
                )
            }
        } catch (e: Exception) {
            ProviderOutcome.failure(
                Provider.CLOUDFLARE, model, friendlyNetworkError(e), 0,
                latencyMs = System.currentTimeMillis() - started,
                timedOut = e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException,
            )
        }
    }

    /**
     * Reformatage du texte STT avec un petit LLM Workers AI.
     */
    suspend fun format(
        secret: String,
        text: String,
        instruction: String,
        timeoutMs: Long = 12_000L,
    ): Result<CloudflareFormatOutcome> {
        val credentials = parseCredentials(secret)
            ?: return Result.failure(IllegalStateException("Format attendu : accountId|token"))
        val (accountId, token) = credentials
        val payload = buildJsonObject {
            put("temperature", 0.1)
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
            .url("https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$FORMAT_MODEL")
            .header("Authorization", "Bearer $token")
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
                val result = root?.get("result")?.jsonObject
                val content = result?.get("response")?.jsonPrimitive?.contentOrNull
                    ?: result?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                val usage = result?.get("usage")?.jsonObject
                val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L
                val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L
                val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.longOrNull
                    ?: (promptTokens + completionTokens)
                val neurons = usage?.get("neurons")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val cleaned = content?.trim().orEmpty()
                if (cleaned.isBlank()) {
                    Result.failure(IllegalStateException("Reformatage vide"))
                } else {
                    Result.success(
                        CloudflareFormatOutcome(
                            text = cleaned,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            totalTokens = totalTokens,
                            neurons = neurons,
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(friendlyNetworkError(e)))
        }
    }

    /**
     * Vérifie les identifiants sans lancer d'inférence payante :
     * lecture du catalogue de modèles du compte.
     */
    suspend fun testKey(secret: String): Result<String> {
        val credentials = parseCredentials(secret)
            ?: return Result.failure(IllegalStateException("Format attendu : accountId|token"))
        val (accountId, token) = credentials
        val request = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/$accountId/ai/models/search?per_page=1")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            Http.clientWithTimeout(12_000).newCall(request).awaitResponse().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success("Clé Cloudflare valide · Workers AI accessible")
                } else {
                    Result.failure(IllegalStateException(parseError(body).ifBlank { "HTTP ${response.code}" }))
                }
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(friendlyNetworkError(e)))
        }
    }

    private fun parseError(body: String): String = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        root["errors"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull().orEmpty()

    private fun parseRetryAfter(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull()
        return if (seconds != null) seconds * 1000 else 30_000L
    }

    private fun audioSeconds(wavBytes: Int): Double =
        ((wavBytes - 44).coerceAtLeast(0)) / 32_000.0

    companion object {
        fun parseCredentials(secret: String): Pair<String, String>? {
            val cleaned = secret.trim()
            val separator = when {
                cleaned.contains('|') -> '|'
                cleaned.contains(':') -> ':'
                else -> return null
            }
            val account = cleaned.substringBefore(separator).trim()
            val token = cleaned.substringAfter(separator).trim()
            if (account.length < 10 || token.length < 20) return null
            return account to token
        }

        private const val FORMAT_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val WAV_MEDIA = "audio/wav".toMediaType()
    }
}

data class CloudflareFormatOutcome(
    val text: String,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val totalTokens: Long = 0L,
    val neurons: Double = 0.0,
)