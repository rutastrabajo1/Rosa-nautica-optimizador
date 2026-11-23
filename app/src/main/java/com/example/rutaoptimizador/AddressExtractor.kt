package com.example.rutaoptimizador

import java.text.Normalizer
import java.util.UUID

/**
 * Extractor dinámico sin límite de entregas:
 * - Detecta domicilios con o sin prefijo ("Calle", "Av", etc.).
 * - Anexa automáticamente la línea de Código Postal / Colonia / Municipio si viene debajo.
 * - Clasifica el tipo de servicio: "ENTREGA", "COBRO" o "RECOLECCION".
 */
object AddressExtractor {
    private val streetHints = listOf(
        "calle", "cll", "av", "avenida", "blvd", "boulevard", "carretera", "carr",
        "privada", "priv", "camino", "colonia", "col", "parque industrial", "p.i.",
        "nave", "bodega", "local", "lote", "manzana", "mz", "km", "kilometro",
        "rio", "circuito", "calzada", "prolongacion", "diagonal", "libramiento", "autopista"
    )

    private val noiseHints = listOf(
        "entrega en", "pendiente", "escaneado", "guia", "waybill", "rastreo",
        "paquete", "piezas", "peso", "kg", "lunes", "martes", "miercoles",
        "jueves", "viernes", "sabado", "domingo", "am", "pm", "back to base",
        "bateria", "wifi", "buscar", "ruta", "parada"
    )

    private val cobroHints = listOf(
        "cobro", "por cobrar", "cobrar", "cod", "pago contra entrega", "efectivo", "$"
    )

    private val recoleccionHints = listOf(
        "recoleccion", "recolectar", "pickup", "pick up", "recoger", "devolucion"
    )

    private val numberRegex = Regex("""\b(?:#?\s*\d{1,6}[A-Za-z]?|s/n|sn)\b""", RegexOption.IGNORE_CASE)
    private val cpRegex = Regex("""\b\d{5}\b""")

    private data class ExtractedCandidate(
        val address: String,
        val type: String
    )

    fun candidates(text: String): List<Stop> {
        val lines = text.lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length >= 6 && it.any(Char::isLetter) }

        val result = mutableListOf<ExtractedCandidate>()
        val consumedIndices = mutableSetOf<Int>()

        lines.forEachIndexed { i, line ->
            if (i in consumedIndices) return@forEachIndexed

            val lower = normalize(line)
            if (isPureNoise(lower)) return@forEachIndexed

            val hasStreet = streetHints.any { lower.contains(it) }
            val hasNumber = numberRegex.containsMatchIn(line)
            val hasCp = cpRegex.containsMatchIn(line)

            val words = lower.split(" ").filter { it.length >= 2 }
            val looksLikeDirectAddress = words.size >= 2 && hasNumber && !hasCp && line.length in 8..90

            if (hasStreet || looksLikeDirectAddress) {
                val parts = mutableListOf(line)
                // Contexto cercano (línea anterior y hasta 2 siguientes) para detectar cobro o recolección
                val contextWindow = buildString {
                    append(lines.getOrNull(i - 1).orEmpty()).append(" ")
                    append(line).append(" ")
                }.toMutableList()

                for (offset in 1..2) {
                    val nextIdx = i + offset
                    val nextLine = lines.getOrNull(nextIdx) ?: break
                    val nextLower = normalize(nextLine)

                    contextWindow.addAll((" $nextLine").toList())

                    if (isPureNoise(nextLower)) break

                    val nextHasCp = cpRegex.containsMatchIn(nextLine)
                    val nextHasLocation = nextHasCp ||
                            nextLower.contains("parque industrial") ||
                            nextLower.contains("apodaca") ||
                            nextLower.contains("monterrey") ||
                            nextLower.contains("guadalupe") ||
                            nextLower.contains("escobedo") ||
                            nextLower.contains("san nicolas") ||
                            nextLower.contains("santa catarina") ||
                            nextLower.contains("pesqueria") ||
                            nextLower.contains("nuevo leon") ||
                            nextLower.contains("col ") ||
                            nextLower.contains("col.")

                    val nextIsAnotherStreet = streetHints.any { nextLower.startsWith(it) } &&
                            numberRegex.containsMatchIn(nextLine) && !nextHasCp

                    if (nextHasLocation && !nextIsAnotherStreet) {
                        parts.add(nextLine)
                        consumedIndices.add(nextIdx)
                        if (nextHasCp) break
                    } else if (!hasNumber && !hasCp && offset == 1 && nextLine.length <= 80) {
                        parts.add(nextLine)
                        consumedIndices.add(nextIdx)
                    } else {
                        break
                    }
                }

                val fullCandidate = parts.joinToString(", ").take(240)
                if (fullCandidate.length >= 8) {
                    val stopType = detectStopType(contextWindow.joinToString(""))
                    result.add(ExtractedCandidate(fullCandidate, stopType))
                }
            }
        }

        return result.distinctBy { normalize(it.address) }.map { item ->
            Stop(
                id = UUID.randomUUID().toString(),
                address = item.address,
                source = "OCR",
                verified = false,
                type = item.type
            )
        }
    }

    private fun detectStopType(rawContext: String): String {
        val norm = normalize(rawContext)
        return when {
            recoleccionHints.any { norm.contains(it) } -> "RECOLECCION"
            rawContext.contains("$") || cobroHints.any { it != "$" && norm.contains(it) } -> "COBRO"
            else -> "ENTREGA"
        }
    }

    private fun isPureNoise(normalizedLine: String): Boolean {
        if (normalizedLine.length < 5) return true
        if (normalizedLine.matches(Regex("^[a-z0-9]{12,}$"))) return true
        return noiseHints.any { normalizedLine == it || normalizedLine.startsWith("$it ") }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
