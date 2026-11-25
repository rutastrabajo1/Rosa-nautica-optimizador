private data class OcrLineBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

private data class DetectedCardBox(
    val top: Int,
    val bottom: Int,
    val lines: List<String>
)

private suspend fun recognizeTextFromUri(context: Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val bitmap: Bitmap? = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
        if (bitmap == null) return@withContext ""

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        suspendCancellableCoroutine { cont ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    recognizer.close()
                    if (!cont.isActive) return@addOnSuccessListener

                    val lineBoxes = mutableListOf<OcrLineBox>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox
                            val clean = line.text.trim()
                            if (box != null && clean.isNotBlank()) {
                                lineBoxes.add(
                                    OcrLineBox(
                                        text = clean,
                                        left = box.left,
                                        top = box.top,
                                        right = box.right,
                                        bottom = box.bottom
                                    )
                                )
                            }
                        }
                    }

                    if (lineBoxes.isEmpty()) {
                        cont.resume(visionText.text)
                        return@addOnSuccessListener
                    }

                    // Agrupa elementos en el mismo renglón horizontal
                    val sortedByTop = lineBoxes.sortedWith(
                        compareBy<OcrLineBox> { it.top }.thenBy { it.left }
                    )
                    val mergedRows = mutableListOf<List<OcrLineBox>>()
                    for (item in sortedByTop) {
                        val lastRow = mergedRows.lastOrNull()
                        if (lastRow != null) {
                            val avgHeight = lastRow.map { it.height }.average()
                            val avgCenterY = lastRow.map { it.centerY }.average()
                            if (kotlin.math.abs(item.centerY - avgCenterY) <= avgHeight * 0.55) {
                                mergedRows[mergedRows.lastIndex] = (lastRow + item).sortedBy { it.left }
                                continue
                            }
                        }
                        mergedRows.add(listOf(item))
                    }

                    val imageWidth = bitmap.width.coerceAtLeast(1)
                    val leftNumberRegex = Regex("""^\d{1,3}$""")
                    val tenDigitGuideRegex = Regex("""\b\d{10}\b(?:\s+\d+/\d+)?""")

                    // Identifica cada tarjeta desde su número izquierdo / encabezado
                    // hasta el cierre de los 10 dígitos + 1/1.
                    val cards = mutableListOf<DetectedCardBox>()
                    var pendingLines = mutableListOf<String>()
                    var pendingTop: Int? = null
                    var pendingBottom: Int = 0

                    for (row in mergedRows) {
                        val leftMost = row.first()
                        val hasLeftStopNumber = leftMost.left < imageWidth * 0.25f &&
                            leftNumberRegex.matches(leftMost.text)

                        val contentText = row.filterNot {
                            it.left < imageWidth * 0.22f && leftNumberRegex.matches(it.text)
                        }.joinToString(" ") { it.text }.trim()

                        if (contentText.isBlank()) continue

                        val rowTop = row.minOf { it.top }
                        val rowBottom = row.maxOf { it.bottom }

                        // Si ya habíamos cerrado una tarjeta con "10 dígitos 1/1" y llega
                        // una línea nueva sin número izquierdo, iniciamos el siguiente bloque.
                        if (hasLeftStopNumber && pendingLines.isNotEmpty()) {
                            cards.add(
                                DetectedCardBox(
                                    top = pendingTop ?: rowTop,
                                    bottom = pendingBottom.coerceAtLeast(rowTop),
                                    lines = pendingLines.toList()
                                )
                            )
                            pendingLines = mutableListOf()
                            pendingTop = null
                            pendingBottom = 0
                        }

                        if (pendingTop == null) {
                            pendingTop = rowTop
                        }
                        pendingBottom = maxOf(pendingBottom, rowBottom)
                        pendingLines.add(contentText)

                        // Cierre exacto de la tarjeta cuando aparece la guía de 10 dígitos (ej. 5838424163 1/1)
                        if (tenDigitGuideRegex.containsMatchIn(contentText)) {
                            cards.add(
                                DetectedCardBox(
                                    top = pendingTop ?: rowTop,
                                    bottom = pendingBottom,
                                    lines = pendingLines.toList()
                                )
                            )
                            pendingLines = mutableListOf()
                            pendingTop = null
                            pendingBottom = 0
                        }
                    }

                    if (pendingLines.isNotEmpty()) {
                        cards.add(
                            DetectedCardBox(
                                top = pendingTop ?: 0,
                                bottom = pendingBottom,
                                lines = pendingLines.toList()
                            )
                        )
                    }

                    val structuredText = cards.joinToString("\n---CARD---\n") { card ->
                        card.lines.joinToString("\n")
                    }
                    cont.resume(structuredText)
                }
                .addOnFailureListener {
                    recognizer.close()
                    if (cont.isActive) cont.resume("")
                }
        }
    }

object AddressExtractor {

    fun candidates(rawText: String): List<Stop> {
        if (rawText.isBlank()) return emptyList()

        val deliveryGuideRegex = Regex("""\b\d{9,11}\b(?:\s+\d+/\d+)?""")
        val pickupGuideRegex = Regex("""\b\d{6}\b\s+\d+\s+.*(?:CM|KG|MX|KR|US|CN)""", RegexOption.IGNORE_CASE)
        val pickupTimeWindowRegex = Regex("""\b\d{1,2}:\d{2}\s*-\s*\d{1,2}:\d{2}\b""")
        val codAmountRegex = Regex("""MXN\s*\d+(?:[.,]\d+)?""", RegexOption.IGNORE_CASE)
        val cpRegex = Regex("""\b\d{5}\b""")

        val ignoredHeaders = listOf(
            "MY ROUTE", "Deliveries", "Bookings", "Payment", "Back to base",
            "Hide", "Inbox", "Scan", "Select", "Map View", "LOAD",
            "Shipments", "Pieces", "Invoices", "Unknown", "Load Complete",
            "MYCG-A", "25 Nov"
        )

        val blocks = rawText.split("---CARD---")
            .map { card ->
                card.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .filter { line ->
                        ignoredHeaders.none { header -> line.equals(header, ignoreCase = true) } &&
                            !line.matches(Regex("""^MY[A-Z0-9-]+$""", RegexOption.IGNORE_CASE)) &&
                            !line.matches(Regex("""^\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)$""", RegexOption.IGNORE_CASE)) &&
                            !line.matches(Regex("""^\d{1,2}:\d{2}$"""))
                    }
            }
            .filter { cardLines ->
                cardLines.size >= 2 || cardLines.any { cpRegex.containsMatchIn(it) || deliveryGuideRegex.containsMatchIn(it) }
            }

        val stops = mutableListOf<Stop>()

        for (block in blocks) {
            val fullBlockText = block.joinToString(" ")

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

            // Quita la guía de 10 dígitos + 1/1 y conserva el resto de la tarjeta como dirección
            val addressLines = block.filterNot { line ->
                line.matches(Regex("""^\d{6,11}(?:\s+\d+/\d+)?$""")) ||
                    pickupTimeWindowRegex.matches(line) ||
                    codAmountRegex.matches(line) ||
                    line == "-----" ||
                    line.equals("NA", ignoreCase = true)
            }.map { line ->
                line.replace(Regex("""\b\d{10}\b(?:\s+\d+/\d+)?"""), "")
                    .replace(pickupTimeWindowRegex, "")
                    .replace(codAmountRegex, "")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            }.filter { it.isNotBlank() }
                .distinct()

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