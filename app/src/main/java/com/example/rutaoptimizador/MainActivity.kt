package com.example.rutaoptimizador

import java.util.UUID

object AddressExtractor {

    fun candidates(rawText: String): List<Stop> {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) return emptyList()

        // Patrones de cierre de tarjeta en escáner DHL
        val deliveryGuideRegex = Regex("""\b\d{9,11}\b\s+\d+/\d+""")
        val pickupGuideRegex = Regex("""\b\d{6}\b\s+\d+\s+.*(?:CM|KG|MX|KR|US|CN)""", RegexOption.IGNORE_CASE)
        val pickupTimeWindowRegex = Regex("""\b\d{1,2}:\d{2}\s*-\s*\d{1,2}:\d{2}\b""")
        val codAmountRegex = Regex("""MXN\s*\d+(?:[.,]\d+)?""", RegexOption.IGNORE_CASE)
        val cpRegex = Regex("""\b\d{5}\b""")

        // Filtrar encabezados y barra inferior del escáner
        val ignoredHeaders = listOf(
            "MY ROUTE", "Deliveries", "Bookings", "Payment", "Back to base",
            "Hide", "Inbox", "Scan", "Select", "Map View"
        )

        val cleanLines = lines.filter { line ->
            ignoredHeaders.none { header -> line.equals(header, ignoreCase = true) } &&
                    !line.matches(Regex("""^MYEC-[A-Z0-9]+$""", RegexOption.IGNORE_CASE)) &&
                    !line.matches(Regex("""^\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)$""", RegexOption.IGNORE_CASE)) &&
                    !line.matches(Regex("""^\d{1,2}:\d{2}$"""))
        }

        val blocks = mutableListOf<List<String>>()
        var currentBlock = mutableListOf<String>()

        for (line in cleanLines) {
            currentBlock.add(line)

            val isEndOfStop = deliveryGuideRegex.containsMatchIn(line) ||
                    pickupGuideRegex.containsMatchIn(line) ||
                    (line.matches(Regex("""^\d{9,11}$""")) && currentBlock.size >= 3) ||
                    (line.matches(Regex("""^\d{6}$""")) && currentBlock.size >= 3)

            if (isEndOfStop) {
                blocks.add(currentBlock.toList())
                currentBlock = mutableListOf()
            }
        }

        if (currentBlock.size >= 2 && currentBlock.any { cpRegex.containsMatchIn(it) }) {
            blocks.add(currentBlock.toList())
        }

        val stops = mutableListOf<Stop>()

        for (block in blocks) {
            val fullBlockText = block.joinToString(" ")

            // 1. Determinar tipo de parada
            val isCod = codAmountRegex.containsMatchIn(fullBlockText)
            val isPickup = !isCod && (
                pickupGuideRegex.containsMatchIn(fullBlockText) ||
                (pickupTimeWindowRegex.containsMatchIn(fullBlockText) && !deliveryGuideRegex.containsMatchIn(fullBlockText)) ||
                fullBlockText.contains("CMx", ignoreCase = true) ||
                Regex("""\b\d+\s*KG\b""", RegexOption.IGNORE_CASE).containsMatchIn(fullBlockText)
            )

            val stopType = when {
                isCod -> "COBRO"
                isPickup -> "RECOLECCION"
                else -> "ENTREGA"
            }

            // 2. Limpiar líneas para quedarse con Nombre + Domicilio + CP
            val addressLines = block.filterNot { line ->
                deliveryGuideRegex.containsMatchIn(line) ||
                        pickupGuideRegex.containsMatchIn(line) ||
                        pickupTimeWindowRegex.matches(line) ||
                        codAmountRegex.matches(line) ||
                        line.matches(Regex("""^\d{6,11}$""")) ||
                        line == "-----" ||
                        line.equals("NA", ignoreCase = true)
            }.map { line ->
                // Quitar ventana horaria o monto MXN si quedaron pegados en la misma línea
                line.replace(pickupTimeWindowRegex, "")
                    .replace(codAmountRegex, "")
                    .trim()
            }.filter { it.isNotBlank() }

            if (addressLines.isNotEmpty()) {
                val formattedAddress = addressLines.joinToString(", ")
                stops.add(
                    Stop(
                        id = UUID.randomUUID().toString(),
                        address = formattedAddress,
                        latitude = null,
                        longitude = null,
                        source = "OCR",
                        verified = false,
                        orderIndex = stops.size,
                        type = stopType
                    )
                )
            }
        }

        return stops
    }
}