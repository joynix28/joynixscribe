package com.botillon.flowscribe.text

import com.botillon.flowscribe.data.AppSettings
import com.botillon.flowscribe.data.DictionaryEntry
import java.text.Normalizer

/**
 * Post-traitement déterministe, exécuté après l'IA et avant l'insertion :
 *  - commandes de ponctuation dictées (« virgule », « point d'interrogation »…),
 *  - dictionnaire personnel (corrections ciblées),
 *  - typographie française (espaces insécables avant ? ! ; :, guillemets),
 *  - majuscules de début de phrase, nettoyage des espaces et artefacts.
 */
object PunctuationProcessor {

    private val NBSP = '\u00A0'

    private val spokenCommands = listOf(
        Regex("(?iu)\\b(?:point|signe)\\s+d['’]interrogation\\b") to "?",
        Regex("(?iu)\\b(?:point|signe)\\s+d['’]exclamation\\b") to "!",
        Regex("(?iu)\\bpoint\\s+virgule\\b") to ";",
        Regex("(?iu)\\bdeux\\s*-?\\s*points\\b") to ":",
        Regex("(?iu)\\bouvre(?:z)?\\s+la\\s+parenth[eè]se\\b") to "(",
        Regex("(?iu)\\bparenth[eè]se\\s+ouvrante\\b") to "(",
        Regex("(?iu)\\bferme(?:z)?\\s+la\\s+parenth[eè]se\\b") to ")",
        Regex("(?iu)\\bparenth[eè]se\\s+fermante\\b") to ")",
        Regex("(?iu)\\bouvre(?:z)?\\s+les\\s+guillemets\\b") to "« ",
        Regex("(?iu)\\bferme(?:z)?\\s+les\\s+guillemets\\b") to " »",
        Regex("(?iu)\\bnouveau\\s+paragraphe\\b") to "\n\n",
        Regex("(?iu)\\b(?:à\\s+la\\s+ligne|nouvelle\\s+ligne|retour\\s+à\\s+la\\s+ligne)\\b") to "\n",
        Regex("(?iu)\\bpoint\\s+final\\b") to ".",
        Regex("(?iu)\\bvirgule\\b") to ",",
        Regex("(?iu)\\bpoint\\s+d['’]suspension\\b") to "…",
        Regex("(?iu)\\bpoints?\\s+de\\s+suspension\\b") to "…",
        Regex("(?iu)\\b(?:tiret|trait\\s+d['’]union)\\b") to "-",
        Regex("(?iu)\\bunderscore\\b") to "_",
        Regex("(?iu)\\barobase\\b") to "@",
    )

    private val standalonePoint = Regex("(?iu)(?<=[\\p{L}\\p{N}»\\)])\\s+point(?=\\s+(?:[A-ZÀ-ÖØ-Þ]|$)|[.!?]|$)")

    fun process(
        raw: String,
        settings: AppSettings,
        dictionary: List<DictionaryEntry> = emptyList(),
    ): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        text = stripWrapper(text)
        text = applyDictionary(text, dictionary)
        if (settings.spokenCommands) text = applySpokenCommands(text)
        text = cleanup(text, settings.frenchTypography)
        if (!settings.punctuation) {
            return text.trim()
        }
        if (settings.frenchTypography) text = frenchTypography(text)
        if (settings.autoCapitalize) text = capitalizeSentences(text)
        text = finishPunctuation(text)
        return text.trim()
    }

    private fun stripWrapper(text: String): String {
        var result = text
        val pairs = listOf("\"" to "\"", "«" to "»", "“" to "”", "`" to "`", "```" to "```")
        for ((open, close) in pairs) {
            if (result.length > open.length + close.length &&
                result.startsWith(open) && result.endsWith(close)
            ) {
                result = result.removePrefix(open).removeSuffix(close).trim()
            }
        }
        return result
    }

    private fun applyDictionary(text: String, dictionary: List<DictionaryEntry>): String {
        var result = text
        for (entry in dictionary) {
            if (!entry.enabled || entry.term.isBlank()) continue
            val replacement = entry.replacement.ifBlank { entry.term }
            val pattern = runCatching {
                if (entry.caseSensitive) {
                    Regex("(?u)(?<![\\p{L}\\p{N}])" + Regex.escape(entry.term) + "(?![\\p{L}\\p{N}])")
                } else {
                    Regex("(?iu)(?<![\\p{L}\\p{N}])" + Regex.escape(entry.term) + "(?![\\p{L}\\p{N}])")
                }
            }.getOrNull() ?: continue
            result = pattern.replace(result, Regex.escapeReplacement(replacement))
        }
        return result
    }

    private fun applySpokenCommands(text: String): String {
        var result = text
        for ((pattern, replacement) in spokenCommands) {
            result = pattern.replace(result, replacement)
        }
        return result.replace(standalonePoint, ".")
    }

    private fun cleanup(text: String, french: Boolean): String {
        var result = text
        result = result.replace(Regex("[ \\t]+"), " ")
        result = result.replace(Regex(" *\\n *"), "\n")
        result = result.replace(Regex("\\n{3,}"), "\n\n")
        result = result.replace(Regex("\\s+([,.;:!?…])"), "$1")
        result = result.replace(Regex("([,.;:!?…])(?=[\\p{L}\\p{N}])"), "$1 ")
        result = result.replace(Regex("([,;:])\\1+"), "$1")
        result = result.replace(Regex("\\.{2,}(?!\\.)"), "…")
        result = result.replace(Regex("\\?{2,}"), "?")
        result = result.replace(Regex("!{2,}"), "!")
        result = result.replace(Regex("\\?\\s*!"), "?!")
        result = result.replace(Regex("«\\s+"), "« ")
        result = result.replace(Regex("\\s+»"), " »")
        result = result.replace(Regex("\\(\\s+"), "(")
        result = result.replace(Regex("\\s+\\)"), ")")
        result = result.replace(Regex("[ \\t]+\\n"), "\n")
        if (french) {
            result = result.replace(Regex("([\\p{L}\\p{N}»\\)])\\s*([?!;:])"), "$1$NBSP$2")
        }
        return result
    }

    private fun frenchTypography(text: String): String {
        var result = text
        result = result.replace(Regex("\\s*([?!;:])"), "$NBSP$1")
        result = result.replace(Regex("([?!;:])\\s+"), "$1 ")
        result = result.replace(Regex("«\\s*"), "«$NBSP")
        result = result.replace(Regex("\\s*»"), "$NBSP»")
        result = result.replace(Regex("([\\p{L}\\p{N}])$NBSP?([?!;:])"), "$1$NBSP$2")
        return result
    }

    private fun capitalizeSentences(text: String): String {
        val builder = StringBuilder(text.length)
        var capitalizeNext = true
        for (char in text) {
            when {
                capitalizeNext && char.isLetter() -> {
                    builder.append(char.uppercaseChar())
                    capitalizeNext = false
                }
                else -> builder.append(char)
            }
            if (char in ".!?…" || char == '\n') capitalizeNext = true
        }
        return builder.toString()
    }

    private fun finishPunctuation(text: String): String {
        var result = text.trimEnd()
        if (result.isBlank()) return ""
        val last = result.last()
        val endsOpen = last in ",;:(-–—«\""
        if (endsOpen) {
            result = result.dropLast(1).trimEnd()
        }
        val alreadyFinal = result.isNotEmpty() && result.last() in ".!?…»)\""
        if (!alreadyFinal) {
            result += "."
        }
        return result
    }

    /** Utilisé pour les aperçus : pas de point final ajouté, pas de majuscule forcée en fin de flux. */
    fun preview(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    fun foldForComparison(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
