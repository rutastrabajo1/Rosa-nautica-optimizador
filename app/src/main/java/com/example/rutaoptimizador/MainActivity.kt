package com.example.rutaoptimizador

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val APP_VERSION = "1.6.7"
private const val PREFS_NAME = "ruta_optimizador_prefs"
private const val KEY_GEMINI_API_KEY = "gemini_api_key"
private const val DEFAULT_GEMINI_API_KEY = ""

// Paleta Náutica Profesional (Rosa Náutica Optimizador)
private val NavyDeep = Color(0xFF041930)
private val NavyPanel = Color(0xFF0B192C)
private val NavyCard = Color(0xFF102A43)
private val NavyBorder = Color(0xFF1E3E62)
private val NauticalTeal = Color(0xFF0EA5E9)
private val NauticalMint = Color(0xFF10B981)
private val GoldCobro = Color(0xFFF59E0B)
private val OrangePickup = Color(0xFFEA580C)
private val EndRed = Color(0xFFDC2626)
private val TextLight = Color(0xFFF8FAFC)
private val TextMutedLight = Color(0xFF94A3B8)

// Centro base en Apodaca, N.L. y radios máximos para evitar saltos fuera de zona
private val ApodacaBaseCenter = LatLng(25.7815, -100.1886)
private const val MaxZoneRadiusMeters = 18000.0
private const val MaxCpRadiusMeters = 4500.0

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

private fun buildGoogleMapsSearchUrl(address: String, lat: Double? = null, lng: Double? = null): String {
    val query = if (lat != null && lng != null) {
        "$lat,$lng"
    } else {
        val normalized = if (
            address.contains("Nuevo León", ignoreCase = true) ||
            address.contains("Nuevo Leon", ignoreCase = true) ||
            address.contains("México", ignoreCase = true) ||
            address.contains("Mexico", ignoreCase = true)
        ) {
            address.trim()
        } else {
            "${address.trim()}, Apodaca, Nuevo León, México"
        }
        Uri.encode(normalized)
    }
    return "https://www.google.com/maps/search/?api=1&query=$query"
}

private fun executeGeminiPost(
    urlString: String,
    payloadString: String,
    apiKeyHeader: String? = null,
    bearerToken: String? = null
): Pair<Int, String> {
    val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 20000
        readTimeout = 60000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        if (!apiKeyHeader.isNullOrBlank()) {
            setRequestProperty("x-goog-api-key", apiKeyHeader)
        }
        if (!bearerToken.isNullOrBlank()) {
            setRequestProperty("Authorization", "Bearer $bearerToken")
        }
    }

    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(payloadString)
        writer.flush()
    }

    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val responseText = if (stream != null) {
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    } else {
        ""
    }
    conn.disconnect()
    return code to responseText
}

private fun parseGeminiApiError(code: Int, responseText: String): String {
    return try {
        val errObj = JSONObject(responseText).optJSONObject("error")
        val msg = errObj?.optString("message", "")?.trim().orEmpty()
        if (msg.isNotEmpty()) "HTTP $code: $msg" else "HTTP $code de Gemini"
    } catch (_: Exception) {
        "HTTP $code de Gemini"
    }
}

private suspend fun orderStopsWithGemini(
    apiKey: String,
    orderedStops: List<Stop>,
    startFromGps: Boolean,
    gps: LatLng?,
    startStopId: String?,
    endStopId: String?
): Result<List<String>> = withContext(Dispatchers.IO) {
    try {
        val cleanKey = apiKey.trim()
        val stopsArray = JSONArray()
        orderedStops.forEachIndexed { idx, s ->
            val obj = JSONObject()
                .put("id", s.id)
                .put("pos", idx + 1)
                .put("address", s.address)
                .put("type", s.type)
            if (s.latitude != null && s.longitude != null) {
                obj.put("lat", s.latitude).put("lng", s.longitude)
            }
            stopsArray.put(obj)
        }

        val configInfo = buildString {
            append("Zona de reparto: Apodaca y Huinalá, Nuevo León, México. ")
            if (startFromGps && gps != null) {
                append("Inicio sugerido cerca de coordenadas GPS (${gps.latitude},${gps.longitude}). ")
            } else if (startStopId != null) {
                append("La parada con id '$startStopId' debe ir al inicio (#1). ")
            }
            if (endStopId != null) {
                append("La parada con id '$endStopId' debe ir al final. ")
            }
            append(
                "Ordena las ${orderedStops.size} paradas con sentido vial y lógico de reparto: agrupa calles, privadas y colonias contiguas en un avance fluido sin saltar de un extremo a otro ni retroceder a colonias ya recorridas. " +
                    "Devuelve EXCLUSIVAMENTE un arreglo JSON de strings con los mismos ${orderedStops.size} id recibidos, sin texto extra ni markdown."
            )
        }

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("text", configInfo))
                            .put(JSONObject().put("text", stopsArray.toString()))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.1)
                    .put("responseMimeType", "application/json")
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            )
        }

        val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
        val payloadStr = payload.toString()

        var (code, responseText) = executeGeminiPost(
            urlString = "$baseUrl?key=${Uri.encode(cleanKey)}",
            payloadString = payloadStr,
            apiKeyHeader = cleanKey
        )

        if ((code == 400 || code == 401 || code == 403) && cleanKey.startsWith("AQ.")) {
            val retry = executeGeminiPost(
                urlString = baseUrl,
                payloadString = payloadStr,
                bearerToken = cleanKey
            )
            if (retry.first in 200..299) {
                code = retry.first
                responseText = retry.second
            }
        }

        if (code !in 200..299) {
            return@withContext Result.failure(Exception(parseGeminiApiError(code, responseText)))
        }

        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates")
        val content = candidates?.optJSONObject(0)?.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val rawJson = parts?.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()

        val cleanedJson = rawJson
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val idArray = JSONArray(cleanedJson)
        val returnedIds = mutableListOf<String>()
        for (i in 0 until idArray.length()) {
            val id = idArray.optString(i, "").trim()
            if (id.isNotEmpty() && id !in returnedIds) returnedIds.add(id)
        }

        val expectedIds = orderedStops.map { it.id }
        val expectedSet = expectedIds.toSet()

        val validOrdered = returnedIds.filter { it in expectedSet }
        if (validOrdered.isEmpty()) {
            return@withContext Result.failure(Exception("Gemini no devolvió IDs válidos"))
        }

        val missingIds = expectedIds.filterNot { it in validOrdered.toSet() }
        val finalIds = validOrdered + missingIds

        if (finalIds.size != orderedStops.size) {
            return@withContext Result.failure(Exception("Recibidos ${returnedIds.size}/${orderedStops.size} IDs"))
        }

        Result.success(finalIds)
    } catch (e: Exception) {
        Result.failure(e)
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
            "MYCG-A", "25 Nov", "Group"
        )

        val explicitCards = rawText.split("---CARD---")
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

        val blocks: List<List<String>> = if (explicitCards.isNotEmpty()) {
            explicitCards
        } else {
            val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
            val fallbackBlocks = mutableListOf<List<String>>()
            var currentBlock = mutableListOf<String>()
            for (line in lines) {
                currentBlock.add(line)
                val isEndOfStop = deliveryGuideRegex.containsMatchIn(line) ||
                    pickupGuideRegex.containsMatchIn(line) ||
                    (line.matches(Regex("""^\d{9,11}$""")) && currentBlock.size >= 3) ||
                    (line.matches(Regex("""^\d{6}$""")) && currentBlock.size >= 3)
                if (isEndOfStop) {
                    fallbackBlocks.add(currentBlock.toList())
                    currentBlock = mutableListOf()
                }
            }
            if (currentBlock.size >= 2 && currentBlock.any { cpRegex.containsMatchIn(it) }) {
                fallbackBlocks.add(currentBlock.toList())
            }
            fallbackBlocks
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

@Composable
fun SplashVideoScreen(
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    var hasFinished by remember { mutableStateOf(false) }
    var isPreparedAndStarted by remember { mutableStateOf(false) }
    var activeSurface by remember { mutableStateOf<Surface?>(null) }

    fun finishOnce() {
        if (!hasFinished) {
            hasFinished = true
            onFinished()
        }
    }

    LaunchedEffect(Unit) {
        delay(8000L)
        finishOnce()
    }

    val mediaPlayer = remember(context) {
        MediaPlayer().apply {
            isLooping = false
            setOnCompletionListener {
                finishOnce()
            }
            setOnErrorListener { _, _, _ ->
                finishOnce()
                true
            }
        }
    }

    DisposableEffect(mediaPlayer) {
        onDispose {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.release()
            } catch (_: Exception) {
            }
            try {
                activeSurface?.release()
            } catch (_: Exception) {
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyDeep),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            if (hasFinished || isPreparedAndStarted) return
                            try {
                                mediaPlayer.reset()
                                val uri = Uri.parse("android.resource://${ctx.packageName}/${R.raw.splash_rosa_nautica}")
                                mediaPlayer.setDataSource(ctx, uri)
                                val outSurface = Surface(surfaceTexture)
                                activeSurface = outSurface
                                mediaPlayer.setSurface(outSurface)
                                mediaPlayer.setOnPreparedListener { mp ->
                                    if (!hasFinished && !isPreparedAndStarted) {
                                        isPreparedAndStarted = true
                                        mp.start()
                                    }
                                }
                                mediaPlayer.prepareAsync()
                            } catch (_: Exception) {
                                finishOnce()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                }
            }
        )
        TextButton(
            onClick = { finishOnce() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Text(
                text = "Omitir",
                color = TextLight.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "ruta-optimizador.db"
        )
            .fallbackToDestructiveMigration()
            .build()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = NauticalTeal,
                    secondary = NauticalMint,
                    tertiary = GoldCobro,
                    background = NavyDeep,
                    surface = NavyPanel,
                    onBackground = TextLight,
                    onSurface = TextLight
                )
            ) {
                var showSplash by remember { mutableStateOf(true) }

                if (showSplash) {
                    SplashVideoScreen(
                        onFinished = { showSplash = false }
                    )
                } else {
                    RutaScreen(db)
                }
            }
        }
    }
}

private fun createNumberedMarkerIcon(
    type: String,
    stopNumber: Int,
    isStart: Boolean = false,
    isEnd: Boolean = false
): BitmapDescriptor {
    val size = 104
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(65, 0, 0, 0)
        style = Paint.Style.FILL
    }

    val borderColor = when {
        isStart -> NauticalTeal.toArgb()
        isEnd -> EndRed.toArgb()
        type == "COBRO" -> GoldCobro.toArgb()
        else -> android.graphics.Color.WHITE
    }

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColor
        style = Paint.Style.STROKE
        strokeWidth = if (isStart || isEnd || type == "COBRO") 7f else 5f
    }

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = if (stopNumber >= 100) 28f else 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    when (type) {
        "RECOLECCION" -> {
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = OrangePickup.toArgb()
                style = Paint.Style.FILL
            }
            val shadowRect = RectF(16f, 20f, size - 12f, size - 12f)
            val rect = RectF(14f, 16f, size - 14f, size - 16f)
            canvas.drawRoundRect(shadowRect, 16f, 16f, shadowPaint)
            canvas.drawRoundRect(rect, 16f, 16f, boxPaint)
            canvas.drawRoundRect(rect, 16f, 16f, borderPaint)
        }
        "COBRO" -> {
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#15803D")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(center + 2f, center + 4f, 36f, shadowPaint)
            canvas.drawCircle(center, center, 36f, circlePaint)
            canvas.drawCircle(center, center, 36f, borderPaint)
        }
        else -> {
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = NauticalMint.toArgb()
                style = Paint.Style.FILL
            }
            canvas.drawCircle(center + 2f, center + 4f, 36f, shadowPaint)
            canvas.drawCircle(center, center, 36f, circlePaint)
            canvas.drawCircle(center, center, 36f, borderPaint)
        }
    }

    val label = if (type == "COBRO") "$$stopNumber" else "$stopNumber"
    val textY = center - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label, center, textY, textPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

private fun optimizeStopsRoute(
    stopsWithCoords: List<Stop>,
    startFromCurrentGps: Boolean,
    currentLatLng: LatLng?,
    fixedStartId: String?,
    fixedEndId: String?,
    respectStreetSequence: Boolean = true
): List<Stop> {
    if (stopsWithCoords.size <= 1) {
        return stopsWithCoords.mapIndexed { index, stop ->
            stop.copy(orderIndex = index)
        }
    }

    val orderedInput = stopsWithCoords.sortedBy { it.orderIndex }

    val endStop = fixedEndId?.let { id ->
        orderedInput.firstOrNull { it.id == id }
    }

    val startStop = if (!startFromCurrentGps) {
        fixedStartId?.let { id ->
            orderedInput.firstOrNull { it.id == id && it.id != endStop?.id }
        }
    } else {
        null
    }

    val remaining = orderedInput.filter {
        it.id != startStop?.id && it.id != endStop?.id
    }.toMutableList()

    fun normalize(value: String): String {
        return java.text.Normalizer.normalize(
            value.lowercase(Locale("es", "MX")),
            java.text.Normalizer.Form.NFD
        )
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun getStreetAndNeighborhood(stop: Stop): Pair<String, String> {
        val parts = stop.address.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val street = parts.getOrNull(0)?.let(::normalize).orEmpty()
        val neighborhood = parts.getOrNull(1)?.let(::normalize).orEmpty()
        return street to neighborhood
    }

    fun houseNumber(stop: Stop): Int? {
        val firstPart = stop.address.split(",").firstOrNull().orEmpty()
        return Regex("""\b\d+[A-Za-z]?\b""")
            .findAll(firstPart)
            .lastOrNull()
            ?.value
            ?.filter { it.isDigit() }
            ?.toIntOrNull()
    }

    fun distanceMeters(a: LatLng, b: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            a.latitude, a.longitude,
            b.latitude, b.longitude,
            results
        )
        return results[0]
    }

    val route = mutableListOf<Stop>()
    var anchorPoint: LatLng? = null

    when {
        startStop != null -> {
            route.add(startStop)
            if (startStop.latitude != null && startStop.longitude != null) {
                anchorPoint = LatLng(startStop.latitude, startStop.longitude)
            }
        }
        startFromCurrentGps && currentLatLng != null -> {
            anchorPoint = currentLatLng
        }
        remaining.isNotEmpty() -> {
            val first = remaining.removeAt(0)
            route.add(first)
            if (first.latitude != null && first.longitude != null) {
                anchorPoint = LatLng(first.latitude, first.longitude)
            }
        }
    }

    if (anchorPoint == null && remaining.isNotEmpty()) {
        val firstWithCoords = remaining.firstOrNull { it.latitude != null && it.longitude != null }
        if (firstWithCoords != null) {
            anchorPoint = LatLng(firstWithCoords.latitude!!, firstWithCoords.longitude!!)
        }
    }

    while (remaining.isNotEmpty()) {
        val currentLoc = anchorPoint
        val currentKey = route.lastOrNull()?.let { getStreetAndNeighborhood(it) }

        val nextStop = if (currentLoc != null) {
            remaining.minByOrNull { cand ->
                val candLoc = if (cand.latitude != null && cand.longitude != null) {
                    LatLng(cand.latitude, cand.longitude)
                } else {
                    null
                }
                val rawDist = if (candLoc != null) {
                    distanceMeters(currentLoc, candLoc).toDouble()
                } else {
                    1_000_000.0
                }

                if (respectStreetSequence && currentKey != null) {
                    val candKey = getStreetAndNeighborhood(cand)
                    val sameStreet = currentKey.first.isNotBlank() && currentKey.first == candKey.first
                    val sameColonia = currentKey.second.isNotBlank() && currentKey.second == candKey.second
                    val numBonus = (houseNumber(cand) ?: 9999) * 0.05

                    when {
                        sameStreet && rawDist <= 1200.0 -> rawDist * 0.25 + numBonus
                        sameColonia && rawDist <= 1500.0 -> rawDist * 0.55 + numBonus
                        else -> rawDist + numBonus
                    }
                } else {
                    rawDist
                }
            } ?: remaining.first()
        } else {
            remaining.first()
        }

        remaining.remove(nextStop)
        route.add(nextStop)

        if (nextStop.latitude != null && nextStop.longitude != null) {
            anchorPoint = LatLng(nextStop.latitude, nextStop.longitude)
        }
    }

    if (endStop != null) {
        route.add(endStop)
    }

    return route.distinctBy { it.id }
        .mapIndexed { index, stop ->
            stop.copy(orderIndex = index)
        }
}

private suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val inputImage = InputImage.fromBitmap(bitmap, 0)

    return suspendCancellableCoroutine { cont ->
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

                val sortedByTop = lineBoxes.sortedWith(
                    compareBy<OcrLineBox> { it.top }.thenBy { it.left }
                )
                val mergedRows = mutableListOf<List<OcrLineBox>>()
                for (item in sortedByTop) {
                    val lastRow = mergedRows.lastOrNull()
                    if (lastRow != null) {
                        val avgHeight = lastRow.map { it.height }.average()
                        val avgCenterY = lastRow.map { it.centerY }.average()
                        if (abs(item.centerY - avgCenterY) <= avgHeight * 0.55) {
                            mergedRows[mergedRows.lastIndex] = (lastRow + item).sortedBy { it.left }
                            continue
                        }
                    }
                    mergedRows.add(listOf(item))
                }

                val imageWidth = bitmap.width.coerceAtLeast(1)
                val leftNumberRegex = Regex("""^\d{1,3}$""")
                val tenDigitGuideRegex = Regex("""\b\d{10}\b(?:\s+\d+/\d+)?""")

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

private fun isVideoUri(context: Context, uri: Uri): Boolean {
    val mime = context.contentResolver.getType(uri).orEmpty()
    if (mime.startsWith("video/", ignoreCase = true)) return true
    val path = uri.toString().lowercase(Locale.ROOT)
    return path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".mkv") || path.endsWith(".avi") || path.endsWith(".3gp")
}

private suspend fun extractOcrTextsFromMediaUri(context: Context, uri: Uri): List<String> =
    withContext(Dispatchers.IO) {
        if (!isVideoUri(context, uri)) {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@withContext emptyList()
            val text = recognizeTextFromBitmap(bitmap)
            bitmap.recycle()
            return@withContext if (text.isNotBlank()) listOf(text) else emptyList()
        }

        val retriever = MediaMetadataRetriever()
        val results = mutableListOf<String>()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1000L)
                ?: 5000L

            val stepMs = 700L
            val maxFrames = 35
            val timestamps = mutableListOf<Long>()
            var pos = 0L
            while (pos <= durationMs && timestamps.size < maxFrames) {
                timestamps.add(pos * 1000L)
                pos += stepMs
            }
            if (timestamps.isEmpty()) timestamps.add(0L)

            for (timeUs in timestamps) {
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val text = recognizeTextFromBitmap(frame)
                    frame.recycle()
                    if (text.isNotBlank()) {
                        results.add(text)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
        results
    }

private fun normalizeStopSignature(address: String): String {
    return java.text.Normalizer.normalize(
        address.lowercase(Locale("es", "MX")),
        java.text.Normalizer.Form.NFD
    )
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

@Composable
private fun SummaryStatCard(
    title: String,
    count: Int,
    accentColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accentColor else NavyBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) NavyBorder.copy(alpha = 0.7f) else NavyCard
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$count",
                color = accentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                color = TextLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StopBadge(type: String, number: Int) {
    when (type) {
        "RECOLECCION" -> {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OrangePickup)
                    .border(1.5.dp, Color.White, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$number",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
        "COBRO" -> {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF15803D))
                    .border(2.dp, GoldCobro, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$$number",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(NauticalMint)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$number",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RutaScreen(db: AppDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stops by db.stopDao().observeAll().collectAsState(initial = emptyList())
    val prefs = remember(context) { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var geminiApiKey by remember {
        mutableStateOf(
            prefs.getString(KEY_GEMINI_API_KEY, null)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_GEMINI_API_KEY
        )
    }
    var geminiKeyInput by remember { mutableStateOf(geminiApiKey) }
    var showGeminiKeyDialog by remember { mutableStateOf(false) }

    var status by remember { mutableStateOf("Listo para cargar fotos o videos de ruta") }
    var busy by remember { mutableStateOf(false) }
    var manualAddress by remember { mutableStateOf("") }
    var manualType by remember { mutableStateOf("ENTREGA") }
    var activeFilter by remember { mutableStateOf<String?>(null) }
    var showMap by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showRouteConfigDialog by remember { mutableStateOf(false) }
    var showPositionDialog by remember { mutableStateOf(false) }
    var targetPositionInput by remember { mutableStateOf("1") }
    var menuExpanded by remember { mutableStateOf(false) }

    val selectedIds = remember { mutableStateListOf<String>() }
    val selectionMode = selectedIds.isNotEmpty()

    var startFromGps by remember { mutableStateOf(true) }
    var keepStreetSequence by remember { mutableStateOf(true) }
    var startStopId by remember { mutableStateOf<String?>(null) }
    var endStopId by remember { mutableStateOf<String?>(null) }

    val deliveryCount = remember(stops) { stops.count { it.type == "ENTREGA" } }
    val pickupCount = remember(stops) { stops.count { it.type == "RECOLECCION" } }
    val codCount = remember(stops) { stops.count { it.type == "COBRO" } }

    val visibleStops = remember(stops, activeFilter) {
        if (activeFilter == null) stops else stops.filter { it.type == activeFilter }
    }

    val hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(ApodacaBaseCenter, 12f)
    }

    fun getLastKnownLatLng(): LatLng? {
        if (!hasLocationPermission) return null
        return try {
            val lm = context.getSystemService(LocationManager::class.java)
            val gpsLoc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = gpsLoc ?: netLoc
            best?.let { LatLng(it.latitude, it.longitude) }
        } catch (_: Exception) {
            null
        }
    }

    fun isWithinZone(point: LatLng, anchor: LatLng, maxMeters: Double): Boolean {
        return haversineMeters(point.latitude, point.longitude, anchor.latitude, anchor.longitude) <= maxMeters
    }

    fun extractPostalCode(text: String): String? {
        val cpRegex = Regex("""\b(\d{5})\b""")
        return cpRegex.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { it.toIntOrNull() in 1000..99999 }
    }

    fun sanitizeAddressForGeocoding(raw: String): String {
        val parts = raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return if (parts.isNotEmpty()) parts.joinToString(", ") else raw.trim()
    }

    fun isSpecificAddressMatch(addr: Address, postalCode: String?): Boolean {
        val hasStreet = !addr.thoroughfare.isNullOrBlank()
        val feature = addr.featureName?.trim().orEmpty()
        val isGenericMunicipalityOrCp = feature.isBlank() ||
            feature.equals("Ciudad Apodaca", ignoreCase = true) ||
            feature.equals("Apodaca", ignoreCase = true) ||
            feature.equals("Nuevo León", ignoreCase = true) ||
            feature.equals("Nuevo Leon", ignoreCase = true) ||
            (postalCode != null && feature == postalCode)
        return hasStreet || !isGenericMunicipalityOrCp
    }

    suspend fun geocodeAddress(rawAddress: String): LatLng? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale("es", "MX"))
            val cleanForSearch = sanitizeAddressForGeocoding(rawAddress)
            val postalCode = extractPostalCode(cleanForSearch) ?: extractPostalCode(rawAddress)
            val zoneAnchor = getLastKnownLatLng() ?: ApodacaBaseCenter

            var cpCenter: LatLng? = null
            if (postalCode != null) {
                @Suppress("DEPRECATION")
                val cpResults = geocoder.getFromLocationName("$postalCode, Apodaca, Nuevo León, México", 3)
                cpCenter = cpResults
                    ?.map { LatLng(it.latitude, it.longitude) }
                    ?.firstOrNull { isWithinZone(it, zoneAnchor, MaxZoneRadiusMeters) }
            }

            val searchAnchor = cpCenter ?: zoneAnchor
            val maxAllowedMeters = if (cpCenter != null) MaxCpRadiusMeters else MaxZoneRadiusMeters

            val query = when {
                postalCode != null && !cleanForSearch.contains("México", ignoreCase = true) ->
                    "$cleanForSearch, CP $postalCode, Apodaca, Nuevo León, México"
                cleanForSearch.contains("Nuevo León", ignoreCase = true) ||
                    cleanForSearch.contains("México", ignoreCase = true) ->
                    cleanForSearch
                else -> "$cleanForSearch, Apodaca, Nuevo León, México"
            }

            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocationName(query, 5)
            results
                ?.firstOrNull { addr ->
                    val pt = LatLng(addr.latitude, addr.longitude)
                    isWithinZone(pt, searchAnchor, maxAllowedMeters) && isSpecificAddressMatch(addr, postalCode)
                }
                ?.let { LatLng(it.latitude, it.longitude) }
        } catch (_: Exception) {
            null
        }
    }

    fun openGoogleMapsForStop(stop: Stop) {
        val mapsUrl = buildGoogleMapsSearchUrl(
            address = stop.address,
            lat = null,
            lng = null
        )
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl))
        context.startActivity(intent)
    }

    fun moveStopInList(stopId: String, delta: Int) {
        if (busy) return
        scope.launch {
            val ordered = stops.sortedBy { it.orderIndex }.toMutableList()
            val currentIdx = ordered.indexOfFirst { it.id == stopId }
            if (currentIdx < 0) return@launch
            val targetIdx = (currentIdx + delta).coerceIn(0, ordered.lastIndex)
            if (targetIdx == currentIdx) return@launch

            val item = ordered.removeAt(currentIdx)
            ordered.add(targetIdx, item)
            val reindexed = ordered.mapIndexed { idx, s -> s.copy(orderIndex = idx) }
            db.stopDao().upsertAll(reindexed)
            status = "Parada movida a posición #${targetIdx + 1}"
        }
    }

    fun moveSelectedToExactPosition(targetNumberOneBased: Int) {
        if (busy || selectedIds.isEmpty() || stops.isEmpty()) return
        scope.launch {
            val ordered = stops.sortedBy { it.orderIndex }.toMutableList()
            val selectedSet = selectedIds.toSet()
            val movingItems = ordered.filter { it.id in selectedSet }
            val remainingItems = ordered.filterNot { it.id in selectedSet }.toMutableList()
            if (movingItems.isEmpty()) return@launch

            val insertIdx = (targetNumberOneBased - 1).coerceIn(0, remainingItems.size)
            for (offset in movingItems.indices) {
                remainingItems.add(insertIdx + offset, movingItems[offset])
            }
            val reindexed = remainingItems.mapIndexed { idx, s -> s.copy(orderIndex = idx) }
            db.stopDao().upsertAll(reindexed)
            val finalPos = insertIdx + 1
            selectedIds.clear()
            status = if (movingItems.size == 1) {
                "Parada asignada al número #$finalPos"
            } else {
                "${movingItems.size} paradas movidas a partir del #$finalPos"
            }
        }
    }

    fun moveSelectedStops(delta: Int) {
        if (busy || selectedIds.isEmpty()) return
        scope.launch {
            val ordered = stops.sortedBy { it.orderIndex }.toMutableList()
            val selectedSet = selectedIds.toSet()

            if (delta < 0) {
                for (i in 1 until ordered.size) {
                    if (ordered[i].id in selectedSet && ordered[i - 1].id !in selectedSet) {
                        val tmp = ordered[i - 1]
                        ordered[i - 1] = ordered[i]
                        ordered[i] = tmp
                    }
                }
            } else if (delta > 0) {
                for (i in ordered.size - 2 downTo 0) {
                    if (ordered[i].id in selectedSet && ordered[i + 1].id !in selectedSet) {
                        val tmp = ordered[i + 1]
                        ordered[i + 1] = ordered[i]
                        ordered[i] = tmp
                    }
                }
            }

            val reindexed = ordered.mapIndexed { idx, s -> s.copy(orderIndex = idx) }
            db.stopDao().upsertAll(reindexed)
            status = if (delta < 0) "Selección subida en la ruta" else "Selección bajada en la ruta"
        }
    }

    suspend fun runOptimization(
        fromGps: Boolean,
        startId: String?,
        endId: String?,
        respectSequence: Boolean
    ) {
        busy = true
        val gps = getLastKnownLatLng()
        val withCoords = stops.filter { it.latitude != null && it.longitude != null }
        if (withCoords.isNotEmpty()) {
            val optimized = optimizeStopsRoute(
                stopsWithCoords = withCoords,
                startFromCurrentGps = fromGps,
                currentLatLng = gps,
                fixedStartId = startId,
                fixedEndId = endId,
                respectStreetSequence = respectSequence
            )
            val withoutCoords = stops.filter { it.latitude == null || it.longitude == null }
                .sortedBy { it.orderIndex }
            val merged = (optimized + withoutCoords).mapIndexed { idx, s -> s.copy(orderIndex = idx) }
            db.stopDao().upsertAll(merged)
            val originLabel = when {
                fromGps && gps != null -> "desde GPS"
                startId != null -> {
                    val idx = stops.indexOfFirst { it.id == startId }
                    if (idx >= 0) "desde #${idx + 1}" else "optimizada"
                }
                else -> "optimizada"
            }
            status = if (respectSequence) {
                "Secuencia conservada y agrupada $originLabel (${optimized.size} con GPS)"
            } else {
                "Ruta optimizada por cercanía $originLabel (${optimized.size} con GPS)"
            }
        } else {
            status = "Sin coordenadas para optimizar"
        }
        busy = false
    }

    fun runGeminiLogicalOrder() {
        if (busy) return
        if (stops.size <= 1) {
            status = "Agrega al menos 2 paradas para ordenar con Gemini"
            return
        }
        if (geminiApiKey.isBlank()) {
            geminiKeyInput = geminiApiKey
            showGeminiKeyDialog = true
            return
        }
        scope.launch {
            busy = true
            status = "Ordenando ${stops.size} paradas con Gemini..."
            val ordered = stops.sortedBy { it.orderIndex }
            val gps = getLastKnownLatLng()
            val result = orderStopsWithGemini(
                apiKey = geminiApiKey,
                orderedStops = ordered,
                startFromGps = startFromGps,
                gps = gps,
                startStopId = startStopId,
                endStopId = endStopId
            )
            result.onSuccess { sortedIds ->
                val byId = ordered.associateBy { it.id }
                val reordered = sortedIds.mapIndexedNotNull { idx, id ->
                    byId[id]?.copy(orderIndex = idx)
                }
                if (reordered.size == ordered.size) {
                    db.stopDao().upsertAll(reordered)
                    status = "Orden lógico aplicado con Gemini (${reordered.size} paradas)"
                } else {
                    status = "No se aplicó cambio: respuesta incompleta (${reordered.size}/${reordered.size})"
                }
            }.onFailure { err ->
                status = "Error Gemini: ${err.message ?: "sin conexión"}"
            }
            busy = false
        }
    }

    fun processMediaUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            busy = true
            status = "Analizando ${uris.size} archivo(s) de foto/video con OCR..."
            var addedCount = 0
            var nextIndex = stops.size
            val seenSignatures = stops.map { normalizeStopSignature(it.address) }.toMutableSet()

            for (uri in uris) {
                val isVid = isVideoUri(context, uri)
                status = if (isVid) {
                    "Extrayendo fotogramas y OCR de video..."
                } else {
                    "Leyendo captura con OCR..."
                }
                val rawTexts = extractOcrTextsFromMediaUri(context, uri)
                for (rawText in rawTexts) {
                    val extracted = AddressExtractor.candidates(rawText)
                    for (candidate in extracted) {
                        val sig = normalizeStopSignature(candidate.address)
                        if (sig.isBlank() || sig in seenSignatures) continue
                        seenSignatures.add(sig)

                        val coords = geocodeAddress(candidate.address)
                        val stopToSave = candidate.copy(
                            latitude = coords?.latitude,
                            longitude = coords?.longitude,
                            verified = coords != null,
                            orderIndex = nextIndex++
                        )
                        db.stopDao().upsert(stopToSave)
                        addedCount++
                    }
                }
            }

            status = if (addedCount > 0) {
                "Se agregaron $addedCount parada(s) desde fotos/videos"
            } else {
                "No se detectaron direcciones nuevas en los archivos"
            }
            busy = false
        }
    }

    val multiMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        processMediaUris(uris)
    }

    val singleMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { processMediaUris(listOf(it)) }
    }

    fun toggleSelection(stopId: String) {
        if (selectedIds.contains(stopId)) {
            selectedIds.remove(stopId)
        } else {
            selectedIds.add(stopId)
        }
    }

    fun groupSelectedStops() {
        if (selectedIds.size < 2) return
        scope.launch {
            busy = true
            val selectedSet = selectedIds.toSet()
            val selectedItems = stops.filter { it.id in selectedSet }
            val remainingItems = stops.filterNot { it.id in selectedSet }

            val anchor = selectedItems.firstOrNull { it.latitude != null && it.longitude != null }
            val orderedGroup = if (anchor != null) {
                val withCoords = selectedItems.filter { it.latitude != null && it.longitude != null }
                    .sortedBy { haversineMeters(anchor.latitude!!, anchor.longitude!!, it.latitude!!, it.longitude!!) }
                val withoutCoords = selectedItems.filter { it.latitude == null || it.longitude == null }
                withCoords + withoutCoords
            } else {
                selectedItems
            }

            val merged = (remainingItems + orderedGroup).mapIndexed { idx, s -> s.copy(orderIndex = idx) }
            db.stopDao().upsertAll(merged)
            selectedIds.clear()
            status = "Grupo de ${orderedGroup.size} parada(s) reunido en secuencia"
            busy = false
        }
    }

    fun deleteSelectedStops() {
        if (selectedIds.isEmpty()) return
        scope.launch {
            busy = true
            val toDelete = selectedIds.toList()
            toDelete.forEach { id -> db.stopDao().delete(id) }
            val reindexed = stops.filterNot { it.id in toDelete }
                .mapIndexed { idx, s -> s.copy(orderIndex = idx) }
            if (reindexed.isNotEmpty()) {
                db.stopDao().upsertAll(reindexed)
            }
            selectedIds.clear()
            status = "Se eliminaron ${toDelete.size} parada(s)"
            busy = false
        }
    }

    Scaffold(
        containerColor = NavyDeep,
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NavyCard,
                        titleContentColor = TextLight,
                        actionIconContentColor = TextLight
                    ),
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar selección")
                        }
                    },
                    title = {
                        Text(
                            text = "${selectedIds.size} sel.",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                val currentIdx = stops.indexOfFirst { it.id == selectedIds.firstOrNull() }
                                targetPositionInput = if (currentIdx >= 0) "${currentIdx + 1}" else "1"
                                showPositionDialog = true
                            }
                        ) {
                            Icon(
                                Icons.Default.FormatListNumbered,
                                contentDescription = null,
                                tint = GoldCobro,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("# Pos", color = GoldCobro, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { moveSelectedStops(-1) }) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Subir parada", tint = NauticalMint)
                        }
                        IconButton(onClick = { moveSelectedStops(1) }) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = "Bajar parada", tint = NauticalTeal)
                        }
                        if (selectedIds.size >= 2) {
                            TextButton(onClick = { groupSelectedStops() }) {
                                Text("Agrupar", color = NauticalTeal, fontWeight = FontWeight.Bold)
                            }
                        }
                        IconButton(onClick = { deleteSelectedStops() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar seleccionadas")
                        }
                    }
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NavyCard,
                        titleContentColor = TextLight,
                        actionIconContentColor = TextLight
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (showMap) "Mapa de Ruta" else "Paradas (${stops.size})",
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = NauticalTeal.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, NauticalTeal)
                            ) {
                                Text(
                                    text = "v$APP_VERSION",
                                    color = NauticalTeal,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showRouteConfigDialog = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "Inicio y fin de ruta")
                        }
                        IconButton(
                            onClick = {
                                multiMediaPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                        ) {
                            Icon(Icons.Default.Image, contentDescription = "Cargar fotos o videos")
                        }
                        IconButton(onClick = { showMap = !showMap }) {
                            Icon(
                                imageVector = if (showMap) Icons.Outlined.List else Icons.Default.Place,
                                contentDescription = "Cambiar vista"
                            )
                        }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Opciones")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            containerColor = NavyCard
                        ) {
                            DropdownMenuItem(
                                text = { Text("Orden lógico (Gemini)", color = GoldCobro, fontWeight = FontWeight.SemiBold) },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = GoldCobro) },
                                onClick = {
                                    menuExpanded = false
                                    runGeminiLogicalOrder()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Configurar API Key Gemini", color = TextLight) },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, tint = GoldCobro) },
                                onClick = {
                                    menuExpanded = false
                                    geminiKeyInput = geminiApiKey
                                    showGeminiKeyDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Inicio y fin de ruta", color = TextLight) },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, tint = NauticalTeal) },
                                onClick = {
                                    menuExpanded = false
                                    showRouteConfigDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Cargar fotos o videos (OCR)", color = TextLight) },
                                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, tint = NauticalTeal) },
                                onClick = {
                                    menuExpanded = false
                                    multiMediaPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Optimizar ruta", color = TextLight) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = NauticalMint) },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        runOptimization(startFromGps, startStopId, endStopId, keepStreetSequence)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Limpiar ruta", color = TextLight) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = EndRed) },
                                onClick = {
                                    menuExpanded = false
                                    showClearDialog = true
                                }
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            multiMediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        },
                        containerColor = NavyCard,
                        contentColor = NauticalTeal
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Fotos o videos OCR")
                    }
                    FloatingActionButton(
                        onClick = { showAddSheet = true },
                        containerColor = NauticalTeal,
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Agregar parada")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NavyDeep)
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryStatCard(
                    title = "Entregas",
                    count = deliveryCount,
                    accentColor = NauticalMint,
                    selected = activeFilter == "ENTREGA",
                    onClick = {
                        activeFilter = if (activeFilter == "ENTREGA") null else "ENTREGA"
                    },
                    modifier = Modifier.weight(1f)
                )
                SummaryStatCard(
                    title = "Recolecciones",
                    count = pickupCount,
                    accentColor = OrangePickup,
                    selected = activeFilter == "RECOLECCION",
                    onClick = {
                        activeFilter = if (activeFilter == "RECOLECCION") null else "RECOLECCION"
                    },
                    modifier = Modifier.weight(1f)
                )
                SummaryStatCard(
                    title = "Cobros",
                    count = codCount,
                    accentColor = GoldCobro,
                    selected = activeFilter == "COBRO",
                    onClick = {
                        activeFilter = if (activeFilter == "COBRO") null else "COBRO"
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .border(1.dp, NavyBorder, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = NavyPanel
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (busy) "Procesando..." else status,
                        modifier = Modifier.weight(1f),
                        color = TextLight,
                        fontSize = 12.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { runGeminiLogicalOrder() }) {
                        Text("Gemini", color = GoldCobro, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { showRouteConfigDialog = true }) {
                        Text("Inicio/Fin", color = NauticalMint, fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = {
                            multiMediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        }
                    ) {
                        Text("+ Captura", color = NauticalTeal, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (showMap) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = hasLocationPermission)
                ) {
                    visibleStops.forEachIndexed { index, stop ->
                        val lat = stop.latitude
                        val lng = stop.longitude
                        val originalNumber = stops.indexOfFirst { it.id == stop.id } + 1
                        if (lat != null && lng != null) {
                            Marker(
                                state = MarkerState(position = LatLng(lat, lng)),
                                title = "#$originalNumber ${stop.address}",
                                snippet = stop.type,
                                icon = createNumberedMarkerIcon(
                                    type = stop.type,
                                    stopNumber = originalNumber,
                                    isStart = index == 0,
                                    isEnd = index == visibleStops.lastIndex && visibleStops.size > 1
                                )
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(visibleStops, key = { _, item -> item.id }) { _, stop ->
                        val routeNumber = stops.indexOfFirst { it.id == stop.id } + 1
                        val isSelected = selectedIds.contains(stop.id)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) NauticalTeal else NavyBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            toggleSelection(stop.id)
                                        } else {
                                            openGoogleMapsForStop(stop)
                                        }
                                    },
                                    onLongClick = {
                                        toggleSelection(stop.id)
                                    }
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) NavyBorder.copy(alpha = 0.65f) else NavyCard
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StopBadge(type = stop.type, number = routeNumber)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stop.address,
                                        color = TextLight,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${stop.type} · ${if (stop.verified) "GPS listo" else "Enlace Google Maps listo"}",
                                        color = TextMutedLight,
                                        fontSize = 12.sp
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                        onClick = { moveStopInList(stop.id, -1) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowUpward,
                                            contentDescription = "Subir en orden",
                                            tint = NauticalMint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { moveStopInList(stop.id, 1) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDownward,
                                            contentDescription = "Bajar en orden",
                                            tint = NauticalTeal,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(onClick = { toggleSelection(stop.id) }) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                        contentDescription = "Seleccionar parada",
                                        tint = if (isSelected) NauticalTeal else TextMutedLight
                                    )
                                }
                                IconButton(onClick = { openGoogleMapsForStop(stop) }) {
                                    Icon(
                                        imageVector = Icons.Default.Navigation,
                                        contentDescription = "Abrir en Google Maps",
                                        tint = NauticalMint
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showGeminiKeyDialog) {
            AlertDialog(
                onDismissRequest = { showGeminiKeyDialog = false },
                containerColor = NavyCard,
                titleContentColor = TextLight,
                textContentColor = TextLight,
                title = { Text("API Key de Gemini", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Pega tu clave de Gemini API para ordenar las paradas por sentido lógico de zona y calles:",
                            color = TextMutedLight,
                            fontSize = 13.sp
                        )
                        OutlinedTextField(
                            value = geminiKeyInput,
                            onValueChange = { geminiKeyInput = it },
                            label = { Text("AQ.Ab8R... / AIza...", color = TextMutedLight) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextLight,
                                unfocusedTextColor = TextLight,
                                focusedBorderColor = GoldCobro,
                                unfocusedBorderColor = NavyBorder
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val trimmed = geminiKeyInput.trim()
                            geminiApiKey = trimmed.ifEmpty { DEFAULT_GEMINI_API_KEY }
                            prefs.edit().putString(KEY_GEMINI_API_KEY, trimmed).apply()
                            showGeminiKeyDialog = false
                            status = if (trimmed.isNotEmpty()) {
                                "API Key de Gemini guardada"
                            } else {
                                "API Key de Gemini borrada"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldCobro)
                    ) {
                        Text("Guardar", color = NavyDeep, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGeminiKeyDialog = false }) {
                        Text("Cancelar", color = TextLight)
                    }
                }
            )
        }

        if (showPositionDialog) {
            AlertDialog(
                onDismissRequest = { showPositionDialog = false },
                containerColor = NavyCard,
                titleContentColor = TextLight,
                textContentColor = TextLight,
                title = { Text("Asignar número de parada", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Escribe el número (1 a ${stops.size.coerceAtLeast(1)}) para mover la selección directo a ese lugar:",
                            color = TextMutedLight,
                            fontSize = 13.sp
                        )
                        OutlinedTextField(
                            value = targetPositionInput,
                            onValueChange = { raw ->
                                targetPositionInput = raw.filter { it.isDigit() }
                            },
                            label = { Text("Número de parada", color = TextMutedLight) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextLight,
                                unfocusedTextColor = TextLight,
                                focusedBorderColor = GoldCobro,
                                unfocusedBorderColor = NavyBorder
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val num = targetPositionInput.toIntOrNull()
                            showPositionDialog = false
                            if (num != null && num >= 1) {
                                moveSelectedToExactPosition(num)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldCobro)
                    ) {
                        Text("Mover al #", color = NavyDeep, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPositionDialog = false }) {
                        Text("Cancelar", color = TextLight)
                    }
                }
            )
        }

        if (showRouteConfigDialog) {
            AlertDialog(
                onDismissRequest = { showRouteConfigDialog = false },
                containerColor = NavyCard,
                titleContentColor = TextLight,
                textContentColor = TextLight,
                title = { Text("Inicio y fin de ruta", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Respetar secuencia de calles", color = NauticalTeal, fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = keepStreetSequence,
                                onCheckedChange = { keepStreetSequence = it }
                            )
                        }

                        HorizontalDivider(color = NavyBorder)
                        Text("Punto de inicio", fontWeight = FontWeight.SemiBold, color = NauticalTeal)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    startFromGps = true
                                    startStopId = null
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = startFromGps,
                                onClick = {
                                    startFromGps = true
                                    startStopId = null
                                }
                            )
                            Text("Mi ubicación actual (GPS)", color = TextLight)
                        }

                        stops.forEachIndexed { idx, stop ->
                            val isChosenStart = !startFromGps && startStopId == stop.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        startFromGps = false
                                        startStopId = stop.id
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isChosenStart,
                                    onClick = {
                                        startFromGps = false
                                        startStopId = stop.id
                                    }
                                )
                                Text(
                                    text = "#${idx + 1} · ${stop.address}",
                                    color = TextLight,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        HorizontalDivider(color = NavyBorder)
                        Text("Parada final (opcional)", fontWeight = FontWeight.SemiBold, color = GoldCobro)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { endStopId = null },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = endStopId == null,
                                onClick = { endStopId = null }
                            )
                            Text("Sin parada final fija", color = TextLight)
                        }

                        stops.forEachIndexed { idx, stop ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { endStopId = stop.id },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = endStopId == stop.id,
                                    onClick = { endStopId = stop.id }
                                )
                                Text(
                                    text = "#${idx + 1} · ${stop.address}",
                                    color = TextLight,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showRouteConfigDialog = false
                            scope.launch {
                                runOptimization(startFromGps, startStopId, endStopId, keepStreetSequence)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NauticalTeal)
                    ) {
                        Text("Optimizar ahora", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRouteConfigDialog = false }) {
                        Text("Cerrar", color = TextLight)
                    }
                }
            )
        }

        if (showAddSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddSheet = false },
                containerColor = NavyCard,
                contentColor = TextLight
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Nueva parada", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextLight)
                    OutlinedTextField(
                        value = manualAddress,
                        onValueChange = { manualAddress = it },
                        label = { Text("Dirección", color = TextMutedLight) },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            focusedBorderColor = NauticalTeal,
                            unfocusedBorderColor = NavyBorder
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ENTREGA", "RECOLECCION", "COBRO").forEach { tipo ->
                            FilterChip(
                                selected = manualType == tipo,
                                onClick = { manualType = tipo },
                                label = { Text(tipo) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NauticalTeal,
                                    selectedLabelColor = Color.White,
                                    containerColor = NavyPanel,
                                    labelColor = TextLight
                                )
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showAddSheet = false
                                singleMediaPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Foto o video")
                        }
                        Button(
                            onClick = {
                                val addr = manualAddress.trim()
                                if (addr.isNotEmpty()) {
                                    scope.launch {
                                        busy = true
                                        val coords = geocodeAddress(addr)
                                        val newStop = Stop(
                                            id = UUID.randomUUID().toString(),
                                            address = addr,
                                            latitude = coords?.latitude,
                                            longitude = coords?.longitude,
                                            source = "MANUAL",
                                            verified = coords != null,
                                            orderIndex = stops.size,
                                            type = manualType
                                        )
                                        db.stopDao().upsert(newStop)
                                        manualAddress = ""
                                        showAddSheet = false
                                        status = "Parada agregada"
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NauticalTeal,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Guardar parada")
                        }
                    }
                }
            }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                containerColor = NavyCard,
                titleContentColor = TextLight,
                textContentColor = TextMutedLight,
                title = { Text("Limpiar paradas") },
                text = { Text("¿Deseas eliminar todas las paradas de la ruta actual?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                stops.forEach { stop -> db.stopDao().delete(stop.id) }
                                selectedIds.clear()
                                activeFilter = null
                                startStopId = null
                                endStopId = null
                                showClearDialog = false
                                status = "Ruta limpiada"
                            }
                        }
                    ) {
                        Text("Eliminar", color = EndRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("Cancelar", color = TextLight)
                    }
                }
            )
        }
    }
}