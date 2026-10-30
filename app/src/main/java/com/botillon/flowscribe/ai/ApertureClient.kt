package com.botillon.flowscribe.ai

import com.botillon.flowscribe.audio.WavEncoder
import com.botillon.flowscribe.data.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Secours sans clé : Aperture Sciences transcrit un fichier audio via
 * multipart/form-data. L'API limite le fichier à environ 1 Mio : on découpe
 * donc le WAV en tranches de ~20 s et on recolle les textes.
 *
 * Deux usages :
 *  - mode normal : après l'arrêt, si les fournisseurs à clé échouent ;
 *  - mode streaming improvisé : chaque segment du streaming est envoyé comme
 *    un petit fichier, ce qui donne un retour au fil de la parole.
 */
class ApertureClient {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun transcribe(
        wav: ByteArray,
        language: String,
        timeoutMs: Long,
    ): ProviderOutcome {
        val started = System.currentTimeMillis()
        val chunks = splitPcm(wav)
        if (chunks.isEmpty()) {
            return ProviderOutcome.failure(
                Provider.APERTURE,
                MODEL,
                "Audio vide",
                400,
                latencyMs = System.currentTimeMillis() - started,
            )
        }

        val texts = mutableListOf<String>()
        for (chunk in chunks) {
            val result = transcribeChunk(chunk, language, timeoutMs)
            if (!result.ok) return result
            if (result.text.isNotBlank()) texts.add(result.text.trim())
        }

        val text = texts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) {
            return ProviderOutcome.failure(
                Provider.APERTURE,
                MODEL,
                "Aperture n'a renvoyé aucun texte",
                200,
                latencyMs = System.currentTimeMillis() - started,
            )
        }

        val pcmBytes = chunks.sumOf { it.size }
        return ProviderOutcome.ok(
            provider = Provider.APERTURE,
            model = MODEL,
            text = text,
            latencyMs = System.currentTimeMillis() - started,
            audioSeconds = pcmBytes / 32_000.0,
        )
    }

    private suspend fun transcribeChunk(
        pcm: ByteArray,
        language: String,
        timeoutMs: Long,
    ): ProviderOutcome {
        val started = System.currentTimeMillis()
        val wav = WavEncoder.encode(pcm)
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "joynixscribe.wav", wav.toRequestBody(WAV_MEDIA))
        if (language != "auto" && language.isNotBlank()) {
            builder.addFormDataPart("language", language)
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .post(builder.build())
            .build()

        return try {
            Http.clientWithTimeout(timeoutMs).newCall(request).awaitResponse().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return ProviderOutcome.failure(
                        Provider.APERTURE,
                        MODEL,
                        parseError(body).ifBlank { "Aperture HTTP ${response.code}" },
                        response.code,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                }
                val text = runCatching {
                    json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.trim().orEmpty()
                if (text.isBlank()) {
                    ProviderOutcome.failure(
                        Provider.APERTURE,
                        MODEL,
                        "Aperture n'a renvoyé aucun texte",
                        200,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                } else {
                    ProviderOutcome.ok(
                        provider = Provider.APERTURE,
                        model = MODEL,
                        text = text,
                        latencyMs = System.currentTimeMillis() - started,
                    )
                }
            }
        } catch (e: Exception) {
            ProviderOutcome.failure(
                Provider.APERTURE,
                MODEL,
                friendlyNetworkError(e),
                0,
                latencyMs = System.currentTimeMillis() - started,
                timedOut = e is java.io.InterruptedIOException || e is java.net.SocketTimeoutException,
            )
        }
    }

    /**
     * Découpe le PCM en tranches sous la limite multipart (~1 Mio). 20 s à
     * 16 kHz mono 16 bits = 640 Kio, ce qui laisse de la marge à l'enveloppe.
     */
    private fun splitPcm(wav: ByteArray): List<ByteArray> {
        val pcm = extractPcm(wav) ?: return emptyList()
        if (pcm.size < 3_200) return emptyList()
        val max = 640 * 1024
        if (pcm.size <= max) return listOf(pcm)
        val out = ArrayList<ByteArray>((pcm.size + max - 1) / max)
        var offset = 0
        while (offset < pcm.size) {
            var end = (offset + max).coerceAtMost(pcm.size)
            if (end < pcm.size) end -= end % 2 // garde des échantillons 16 bits
            if (end <= offset) break
            out.add(pcm.copyOfRange(offset, end))
            offset = end
        }
        return out
    }

    private fun extractPcm(wav: ByteArray): ByteArray? {
        if (wav.size <= 44) return null
        if (wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte()) return wav.copyOfRange(44, wav.size)
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = leInt(wav, pos + 4)
            if (id == "data") {
                val start = pos + 8
                val end = (start + size).coerceAtMost(wav.size)
                return if (end > start) wav.copyOfRange(start, end) else null
            }
            pos += 8 + size + (size % 2)
        }
        return wav.copyOfRange(44, wav.size)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun parseError(body: String): String = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        root["message"]?.jsonPrimitive?.contentOrNull
            ?: root["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull().orEmpty()

    companion object {
        private const val BASE_URL = "https://aperture-sciences.com/api/audio/transcribe"
        private const val MODEL = "parakeet"
        private val WAV_MEDIA = "audio/wav".toMediaType()
    }
}
