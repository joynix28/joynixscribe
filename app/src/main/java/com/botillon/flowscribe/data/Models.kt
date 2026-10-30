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
    );

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
) {
    val isCoolingDown: Boolean get() = cooldownUntil > System.currentTimeMillis()
    val masked: String
        get() = when {
            secret.length <= 8 -> "••••••••"
            else -> secret.take(4) + "…" + secret.takeLast(4)
        }
}

@Serializable
data class PromptPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val instructions: String,
    val builtIn: Boolean = false,
)

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
    val haptics: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val groqFormatting: Boolean = true,
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
