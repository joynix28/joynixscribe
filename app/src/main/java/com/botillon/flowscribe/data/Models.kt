package com.botillon.flowscribe.data

import kotlinx.serialization.Serializable
import java.util.UUID

enum class Provider(
    val id: String,
    val label: String,
    val shortLabel: String,
    val keyHint: String,
    val docsUrl: String,
    val defaultModel: String,
    val models: List<String>,
) {
    GEMINI(
        id = "gemini",
        label = "Google Gemini",
        shortLabel = "Gemini",
        keyHint = "AIza…",
        docsUrl = "https://aistudio.google.com/app/apikey",
        defaultModel = "gemini-3.5-flash-lite",
        models = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.8-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
        ),
    ),
    GROQ(
        id = "groq",
        label = "Groq Whisper",
        shortLabel = "Groq",
        keyHint = "gsk_…",
        docsUrl = "https://console.groq.com/keys",
        defaultModel = "whisper-large-v3-turbo",
        models = listOf("whisper-large-v3-turbo", "whisper-large-v3"),
    ),
    CLOUDFLARE(
        id = "cloudflare",
        label = "Cloudflare Workers AI",
        shortLabel = "Cloudflare",
        keyHint = "accountId|token",
        docsUrl = "https://developers.cloudflare.com/workers-ai/get-started/rest-api/",
        defaultModel = "@cf/openai/whisper-large-v3-turbo",
        models = listOf(
            "@cf/openai/whisper-large-v3-turbo",
            "@cf/openai/whisper",
            "@cf/deepgram/nova-3",
            "@cf/xai/grok-stt",
        ),
    );

    val isSpeechToText: Boolean get() = this != GEMINI

    companion object {
        fun fromId(id: String?): Provider = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

enum class InsertMode(val label: String) {
    AUTO("Automatique"),
    SET_TEXT("Remplacement de champ"),
    PASTE("Collage"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("Système"),
    DARK("Sombre"),
    LIGHT("Clair"),
}

@Serializable
data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val secret: String = "",
    val enabled: Boolean = true,
    val cooldownUntil: Long = 0L,
    val failCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
    // Compteurs cumulés, persistés localement uniquement.
    val requests: Int = 0,
    val totalTokens: Long = 0L,
    val totalAudioSeconds: Double = 0.0,
    val totalNeurons: Double = 0.0,
    // Compteurs du jour, remis à zéro au changement de date locale (epoch day).
    val usageDay: Long = 0L,
    val requestsToday: Int = 0,
    val tokensToday: Long = 0L,
    val audioSecondsToday: Double = 0.0,
    val neuronsToday: Double = 0.0,
) {
    val isCoolingDown: Boolean get() = cooldownUntil > System.currentTimeMillis()
    val masked: String
        get() = when {
            secret.length <= 8 -> "••••••••"
            else -> secret.take(4) + "…" + secret.takeLast(4)
        }
    val hasUsage: Boolean get() = requests > 0

    /** Aucun compteur ne doit « sauter » un jour sans requête. */
    fun countersForToday(): ApiKeyEntry {
        val today = epochDay()
        return if (usageDay == today) this else copy(
            usageDay = today,
            requestsToday = 0,
            tokensToday = 0L,
            audioSecondsToday = 0.0,
            neuronsToday = 0.0,
        )
    }

    fun record(tokens: Long, audioSeconds: Double, neurons: Double): ApiKeyEntry {
        val today = epochDay()
        val sameDay = usageDay == today
        return copy(
            requests = requests + 1,
            totalTokens = totalTokens + tokens.coerceAtLeast(0L),
            totalAudioSeconds = totalAudioSeconds + audioSeconds.coerceAtLeast(0.0),
            totalNeurons = totalNeurons + neurons.coerceAtLeast(0.0),
            lastUsedAt = System.currentTimeMillis(),
            usageDay = today,
            requestsToday = (if (sameDay) requestsToday else 0) + 1,
            tokensToday = (if (sameDay) tokensToday else 0L) + tokens.coerceAtLeast(0L),
            audioSecondsToday = (if (sameDay) audioSecondsToday else 0.0) + audioSeconds.coerceAtLeast(0.0),
            neuronsToday = (if (sameDay) neuronsToday else 0.0) + neurons.coerceAtLeast(0.0),
        )
    }

    companion object {
        fun epochDay(): Long = System.currentTimeMillis() / 86_400_000L
    }
}

@Serializable
data class PromptPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val instructions: String,
    val builtIn: Boolean = false,
    /** Packages Android auxquels ce style s'applique automatiquement. */
    val appPackages: List<String> = emptyList(),
) {
    fun matchesPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return appPackages.any { it.trim().equals(pkg, ignoreCase = true) }
    }
}

enum class BubbleAccent(val label: String, val base: Long, val deep: Long, val glow: Long) {
    VIOLET("Violet", 0xFF9E86FF, 0xFF4B2FBF, 0xFF7C5CFF),
    MINT("Menthe", 0xFF7DE2D1, 0xFF0E7C6B, 0xFF2ED3B7),
    CORAL("Corail", 0xFFFFA78F, 0xFFC9402B, 0xFFFF6B57),
    AZURE("Azur", 0xFF8EC5FF, 0xFF1E5FB0, 0xFF3D9BFF),
    SUNSET("Coucher", 0xFFFFC27A, 0xFFB3561E, 0xFFFF8A3D),
    GRAPHITE("Graphite", 0xFFC6C2D6, 0xFF3A3848, 0xFF8B86A3),
}

enum class BubbleIcon(val label: String) {
    MIC("Micro"),
    SPARK("Étincelle"),
    WAVE("Onde"),
    DOT("Point"),
}

enum class BubbleGesture(val label: String, val description: String) {
    TAP("Tap", "Un tap pour démarrer, un tap pour arrêter"),
    HOLD("Maintenir", "Maintiens la bulle pour parler, relâche pour envoyer"),
}

@Serializable
data class DictionaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val term: String,
    val replacement: String = "",
    val enabled: Boolean = true,
    val caseSensitive: Boolean = false,
)

@Serializable
data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val provider: String,
    val model: String,
    val latencyMs: Long,
    val at: Long = System.currentTimeMillis(),
    val appPackage: String? = null,
    val injected: Boolean = true,
)

data class AppSettings(
    val primary: Provider = Provider.GEMINI,
    val fallbackEnabled: Boolean = true,
    val fallbackTimeoutMs: Long = 3500L,
    val hedgeEnabled: Boolean = false,
    val hedgeDelayMs: Long = 650L,
    val geminiModel: String = Provider.GEMINI.defaultModel,
    val groqModel: String = Provider.GROQ.defaultModel,
    val cloudflareModel: String = Provider.CLOUDFLARE.defaultModel,
    val language: String = "auto",
    val activePromptId: String = PromptDefaults.CLEAN_ID,
    val punctuation: Boolean = true,
    val spokenCommands: Boolean = true,
    val frenchTypography: Boolean = true,
    val autoCapitalize: Boolean = true,
    val autoSpace: Boolean = true,
    val insertMode: InsertMode = InsertMode.AUTO,
    val livePreview: Boolean = false,
    val previewIntervalMs: Long = 4000L,
    val maxDurationSec: Int = 90,
    val bubbleSizeDp: Int = 60,
    val bubbleOpacity: Float = 1f,
    val bubbleAccent: BubbleAccent = BubbleAccent.VIOLET,
    val bubbleIcon: BubbleIcon = BubbleIcon.MIC,
    val bubbleGesture: BubbleGesture = BubbleGesture.TAP,
    val haptics: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val groqFormatting: Boolean = true,
    /** Envoie le nom de l'app et du package dans les consignes. */
    val sendAppContext: Boolean = true,
    /** Choisit automatiquement un style dont appPackages correspond à l'app active. */
    val appAwarePrompts: Boolean = true,
    /** Transcrit au fil de la parole au lieu d'attendre la fin. */
    val streamingMode: Boolean = false,
    /** 0 = pas de plafond. Sinon, une clé est ignorée après N requêtes dans la journée. */
    val dailyKeyRequestLimit: Int = 0,
    val onboardingDone: Boolean = false,
)

object PromptDefaults {
    const val CLEAN_ID = "clean"

    private val clean = PromptPreset(
        id = CLEAN_ID,
        name = "Dictée fidèle",
        description = "Transcription exacte, ponctuation et majuscules, zéro reformulation.",
        instructions = """
            Transcris l'audio mot pour mot. Ne reformule pas, ne résume pas, ne corrige pas le style.
            Ajoute la ponctuation et les majuscules en te basant sur le sens ET sur l'intonation :
            une intonation montante à la fin d'une phrase est une question, même sans mot interrogatif.
            Utilise « ? » pour les questions, « ! » pour les exclamations, « , » pour les pauses courtes.
            Conserve les hésitations utiles, supprime les « euh » répétés et les faux départs évidents.
            Écris les nombres et unités de façon naturelle (ex. « 3 euros », « 15 h 30 »).
            N'ajoute aucun commentaire, aucun guillemet autour du texte, aucune balise.
        """.trimIndent(),
        builtIn = true,
    )

    private val email = PromptPreset(
        id = "email",
        name = "Email pro",
        description = "Reformule en email professionnel clair, prêt à envoyer.",
        instructions = """
            Transforme la dictée en email professionnel en français.
            Garde le sens, le destinataire implicite et les demandes. Structure : formule d'appel si pertinente,
            corps concis en paragraphes, formule de politesse courte.
            Corrige l'oral, les hésitations et les répétitions. Ton naturel, direct, sans flagornerie.
            Ne mets pas d'objet ni de signature. Réponds uniquement avec le texte final.
        """.trimIndent(),
        builtIn = true,
    )

    private val notes = PromptPreset(
        id = "notes",
        name = "Notes structurées",
        description = "Transforme un flux parlé en notes courtes à puces.",
        instructions = """
            Transforme la dictée en notes structurées en français.
            Utilise des puces « • » et des sous-puces « – » si nécessaire. Une idée par ligne.
            Garde les chiffres, noms, dates et actions. Supprime les hésitations et les digressions.
            Pas d'introduction, pas de conclusion. Réponds uniquement avec les notes.
        """.trimIndent(),
        builtIn = true,
    )

    private val message = PromptPreset(
        id = "message",
        name = "Message court",
        description = "Message instantané naturel, compact, sans lourdeur.",
        instructions = """
            Transforme la dictée en message court et naturel, adapté à une messagerie.
            Garde le ton de la personne. Deux ou trois phrases maximum si possible.
            Corrige les hésitations, garde la ponctuation utile. Pas de formule d'appel.
            Réponds uniquement avec le message.
        """.trimIndent(),
        builtIn = true,
    )

    private val code = PromptPreset(
        id = "code",
        name = "Technique / code",
        description = "Préserve les termes techniques, identifiants et ponctuation de code.",
        instructions = """
            Transcris la dictée en conservant strictement les termes techniques, noms de variables,
            commandes, chemins et identifiants. Ne traduis pas les termes anglais.
            Rends la ponctuation de code explicitement dictée (underscore, tiret, parenthèses, guillemets).
            Ne mets pas de bloc Markdown. Réponds uniquement avec le texte final.
        """.trimIndent(),
        builtIn = true,
    )

    val builtIn: List<PromptPreset> = listOf(clean, email, notes, message, code)

    fun cleanInstructions(): String = clean.instructions
}
