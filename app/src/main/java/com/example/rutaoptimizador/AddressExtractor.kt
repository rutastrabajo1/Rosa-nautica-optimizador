package com.example.rutaoptimizador

import java.text.Normalizer
import java.util.UUID

/**
 * Extractor conservador: produce candidatos a partir de líneas visibles del OCR.
 * No inventa calles ni números; cada resultado queda pendiente de revisión humana.
 */
object AddressExtractor {
    private val streetHints = listOf("calle", "av", "avenida", "blvd", "boulevard", "carretera", "privada", "camino", "colonia", "parque industrial")
    private val numberRegex = Regex("\\b(?:#?\\s*\\d{1,6}[A-Za-z]?)\\b")

    fun candidates(text: String): List<Stop> {
        val lines = text.lines().map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length >= 10 && it.any(Char::isLetter) }
        val result = mutableListOf<String>()
        lines.forEachIndexed { i, line ->
            val lower = normalize(line)
            val hasStreet = streetHints.any { lower.contains(it) }
            val hasNumber = numberRegex.containsMatchIn(line)
            val next = lines.getOrNull(i + 1).orEmpty()
            val candidate = when {
                hasStreet && hasNumber -> line
                hasStreet && next.isNotBlank() -> "$line, $next"
                else -> null
            }
            if (candidate != null && candidate.length <= 240) result += candidate
        }
        return result.distinctBy { normalize(it) }.map {
            Stop(UUID.randomUUID().toString(), it, source = "OCR", verified = false)
        }
    }

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()
}


==========================================================================================
