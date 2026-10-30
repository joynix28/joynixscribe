package com.botillon.flowscribe.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stockage des clés API chiffré avec la clé maître du Keystore Android.
 * Jusqu'à 9 clés par fournisseur, rotation LRU et refroidissement automatique.
 */
class ApiKeyStore(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "flowscribe_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { error ->
        Log.w(TAG, "Keystore indisponible, stockage de secours: ${error.message}")
        appContext.getSharedPreferences("flowscribe_plain", Context.MODE_PRIVATE)
    }

    fun keys(provider: Provider): List<ApiKeyEntry> = synchronized(lock) {
        read(provider).map { it.countersForToday() }
    }

    fun count(provider: Provider): Int = keys(provider).size

    fun usableKeys(provider: Provider, dailyLimit: Int = 0): List<ApiKeyEntry> {
        if (!provider.requiresKey) {
            return listOf(ApiKeyEntry(id = "no-key", label = "Sans clé", secret = "no-key"))
        }
        return keys(provider)
        .filter {
            it.enabled &&
                !it.isCoolingDown &&
                it.secret.isNotBlank() &&
                (dailyLimit <= 0 || it.requestsToday < dailyLimit)
        }
        .sortedBy { it.lastUsedAt }
    }

    fun add(provider: Provider, label: String, secret: String): Result<ApiKeyEntry> = synchronized(lock) {
        if (!provider.requiresKey) return Result.failure(IllegalArgumentException("Ce fournisseur ne nécessite aucune clé"))
        val cleaned = secret.trim()
        if (cleaned.length < 8) return Result.failure(IllegalArgumentException("Clé trop courte"))
        if (provider == Provider.CLOUDFLARE &&
            (!cleaned.contains('|') || cleaned.substringBefore('|').isBlank())
        ) {
            return Result.failure(
                IllegalArgumentException("Format Cloudflare attendu : accountId|token"),
            )
        }
        val current = read(provider)
        if (current.size >= MAX_KEYS) return Result.failure(IllegalStateException("Maximum $MAX_KEYS clés pour ${provider.label}"))
        if (current.any { it.secret == cleaned }) return Result.failure(IllegalStateException("Cette clé existe déjà"))
        val entry = ApiKeyEntry(label = label.trim().ifBlank { "Clé ${current.size + 1}" }, secret = cleaned)
        write(provider, current + entry)
        Result.success(entry)
    }

    fun update(provider: Provider, id: String, transform: (ApiKeyEntry) -> ApiKeyEntry) = synchronized(lock) {
        write(provider, read(provider).map { if (it.id == id) transform(it) else it })
    }

    fun delete(provider: Provider, id: String) = synchronized(lock) {
        write(provider, read(provider).filterNot { it.id == id })
    }

    fun setEnabled(provider: Provider, id: String, enabled: Boolean) = update(provider, id) { it.copy(enabled = enabled) }

    fun markSuccess(provider: Provider, id: String) = update(provider, id) {
        it.copy(cooldownUntil = 0L, failCount = 0, lastError = null, lastUsedAt = System.currentTimeMillis())
    }

    /**
     * Enregistre la consommation réelle d'une requête réussie.
     * Les tokens peuvent être 0 pour un modèle audio (Groq / Cloudflare) :
     * on garde alors la durée audio et/ou les neurons comme unité.
     */
    fun recordUsage(
        provider: Provider,
        id: String,
        tokens: Long = 0L,
        audioSeconds: Double = 0.0,
        neurons: Double = 0.0,
    ) = update(provider, id) {
        it.record(tokens = tokens, audioSeconds = audioSeconds, neurons = neurons)
    }

    fun markFailure(provider: Provider, id: String, cooldownMs: Long, error: String?) = update(provider, id) {
        it.copy(
            cooldownUntil = System.currentTimeMillis() + cooldownMs,
            failCount = it.failCount + 1,
            lastError = error?.take(180),
        )
    }

    fun touch(provider: Provider, id: String) = update(provider, id) {
        it.copy(lastUsedAt = System.currentTimeMillis())
    }

    private fun read(provider: Provider): List<ApiKeyEntry> {
        val raw = prefs.getString(keyName(provider), null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ApiKeyEntry>>(raw) }.getOrDefault(emptyList())
    }

    private fun write(provider: Provider, list: List<ApiKeyEntry>) {
        prefs.edit().putString(keyName(provider), json.encodeToString(list)).apply()
    }

    private fun keyName(provider: Provider) = "api_keys_${provider.id}"

    companion object {
        const val MAX_KEYS = 9
        private const val TAG = "FlowScribeKeys"
    }
}
