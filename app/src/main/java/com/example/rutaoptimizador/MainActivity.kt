package com.example.rutaoptimizador

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Paleta Náutica Profesional
private val NavyDeep = Color(0xFF0B192C)
private val NavyCard = Color(0xFF1E3E62)
private val NauticalTeal = Color(0xFF0EA5E9)
private val NauticalMint = Color(0xFF10B981)
private val GoldCobro = Color(0xFFF59E0B)
private val OrangePickup = Color(0xFFEA580C)
private val EndRed = Color(0xFFDC2626)
private val SurfaceBg = Color(0xFFF1F5F9)
private val CardWhite = Color(0xFFFFFFFF)
private val TextDark = Color(0xFF0F172A)
private val TextMuted = Color(0xFF64748B)

object AddressExtractor {

    fun candidates(rawText: String): List<Stop> {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) return emptyList()

        val deliveryGuideRegex = Regex("""\b\d{9,11}\b\s+\d+/\d+""")
        val pickupGuideRegex = Regex("""\b\d{6}\b\s+\d+\s+.*(?:CM|KG|MX|KR|US|CN)""", RegexOption.IGNORE_CASE)
        val pickupTimeWindowRegex = Regex("""\b\d{1,2}:\d{2}\s*-\s*\d{1,2}:\d{2}\b""")
        val codAmountRegex = Regex("""MXN\s*\d+(?:[.,]\d+)?""", RegexOption.IGNORE_CASE)
        val cpRegex = Regex("""\b\d{5}\b""")

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
                deliveryGuideRegex.containsMatchIn(line) ||
                        pickupGuideRegex.containsMatchIn(line) ||
                        pickupTimeWindowRegex.matches(line) ||
                        codAmountRegex.matches(line) ||
                        line.matches(Regex("""^\d{6,11}$""")) ||
                        line == "-----" ||
                        line.equals("NA", ignoreCase = true)
            }.map { line ->
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

@Composable
fun SplashVideoScreen(
    onFinished: () -> Unit
) {
    var hasFinished by remember { mutableStateOf(false) }

    fun finishOnce() {
        if (!hasFinished) {
            hasFinished = true
            onFinished()
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
                VideoView(ctx).apply {
                    val uri = Uri.parse("android.resource://${ctx.packageName}/${R.raw.splash_rosa_nautica}")
                    setVideoURI(uri)
                    setOnPreparedListener { mediaPlayer ->
                        mediaPlayer.isLooping = false
                        start()
                    }
                    setOnCompletionListener {
                        finishOnce()
                    }
                    setOnErrorListener { _, _, _ ->
                        finishOnce()
                        true
                    }
                }
            }
        )
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
                colorScheme = lightColorScheme(
                    primary = NavyDeep,
                    secondary = NauticalTeal,
                    tertiary = NauticalMint,
                    background = SurfaceBg,
                    surface = CardWhite
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

// Optimización con inicio opcional (GPS o parada fija), fin opcional y desenredo 2-Opt
private fun optimizeStopsRoute(
    stopsWithCoords: List<Stop>,
    startFromCurrentGps: Boolean,
    currentLatLng: LatLng?,
    fixedStartId: String?,
    fixedEndId: String?
): List<Stop> {
    if (stopsWithCoords.size <= 1) return stopsWithCoords

    val endStop = fixedEndId?.let { id -> stopsWithCoords.firstOrNull { it.id == id } }
    val startStop = if (!startFromCurrentGps) {
        fixedStartId?.let { id ->
            stopsWithCoords.firstOrNull { it.id == id && it.id != endStop?.id }
        }
    } else null

    val middlePool = stopsWithCoords.filter {
        it.id != startStop?.id && it.id != endStop?.id
    }.toMutableList()

    val route = mutableListOf<Stop>()
    var anchorLat: Double
    var anchorLng: Double

    if (startFromCurrentGps && currentLatLng != null) {
        anchorLat = currentLatLng.latitude
        anchorLng = currentLatLng.longitude
    } else if (startStop != null) {
        route.add(startStop)
        anchorLat = startStop.latitude!!
        anchorLng = startStop.longitude!!
    } else {
        val first = middlePool.removeAt(0)
        route.add(first)
        anchorLat = first.latitude!!
        anchorLng = first.longitude!!
    }

    // 1. Construcción por vecino más cercano desde el punto de partida
    while (middlePool.isNotEmpty()) {
        val nextIdx = middlePool.indices.minByOrNull { i ->
            val candidate = middlePool[i]
            haversineMeters(anchorLat, anchorLng, candidate.latitude!!, candidate.longitude!!)
        } ?: 0
        val chosen = middlePool.removeAt(nextIdx)
        route.add(chosen)
        anchorLat = chosen.latitude!!
        anchorLng = chosen.longitude!!
    }

    if (endStop != null) {
        route.add(endStop)
    }

    // 2. Paso 2-Opt para eliminar cruces y regresos ilógicos en avenidas
    val lockStart = startStop != null
    val lockEnd = endStop != null
    val optFrom = if (lockStart) 1 else 0
    val optTo = if (lockEnd) route.size - 2 else route.size - 1

    if (optTo - optFrom >= 2) {
        var improved = true
        var passes = 0
        while (improved && passes < 15) {
            improved = false
            passes++
            for (i in optFrom until optTo) {
                for (k in i + 1..optTo) {
                    val prevLat = if (i == 0 && startFromCurrentGps && currentLatLng != null) {
                        currentLatLng.latitude
                    } else if (i > 0) {
                        route[i - 1].latitude!!
                    } else null

                    val prevLng = if (i == 0 && startFromCurrentGps && currentLatLng != null) {
                        currentLatLng.longitude
                    } else if (i > 0) {
                        route[i - 1].longitude!!
                    } else null

                    val nextLat = if (k + 1 < route.size) route[k + 1].latitude!! else null
                    val nextLng = if (k + 1 < route.size) route[k + 1].longitude!! else null

                    val currentEdgeCost =
                        (if (prevLat != null && prevLng != null) haversineMeters(prevLat, prevLng, route[i].latitude!!, route[i].longitude!!) else 0.0) +
                        (if (nextLat != null && nextLng != null) haversineMeters(route[k].latitude!!, route[k].longitude!!, nextLat, nextLng) else 0.0)

                    val swappedEdgeCost =
                        (if (prevLat != null && prevLng != null) haversineMeters(prevLat, prevLng, route[k].latitude!!, route[k].longitude!!) else 0.0) +
                        (if (nextLat != null && nextLng != null) haversineMeters(route[i].latitude!!, route[i].longitude!!, nextLat, nextLng) else 0.0)

                    if (swappedEdgeCost + 5.0 < currentEdgeCost) {
                        val sub = route.subList(i, k + 1).reversed()
                        for (offset in sub.indices) {
                            route[i + offset] = sub[offset]
                        }
                        improved = true
                    }
                }
            }
        }
    }

    return route.mapIndexed { idx, stop -> stop.copy(orderIndex = idx) }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RutaScreen(db: AppDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stops by db.stopDao().observeAll().collectAsState(initial = emptyList())
    var status by remember { mutableStateOf("Listo para cargar capturas de ruta") }
    var busy by remember { mutableStateOf(false) }
    var manualAddress by remember { mutableStateOf("") }
    var manualType by remember { mutableStateOf("ENTREGA") }
    var showMap by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    // Control de punto de inicio y parada final
    var startFromGps by remember { mutableStateOf(true) }
    var startStopId by remember { mutableStateOf<String?>(null) }
    var endStopId by remember { mutableStateOf<String?>(null) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val defaultCenter = LatLng(25.7815, -100.1886)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultCenter, 12f)
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

    fun extractPostalCode(text: String): String? {
        val cpRegex = Regex("""\b(\d{5})\b""")
        return cpRegex.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { it.toIntOrNull() in 1000..99999 }
    }

    // Limpia el nombre del cliente pegado por el escáner DHL antes de buscar en el mapa
    fun sanitizeAddressForGeocoding(raw: String): String {
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val filtered = parts.filterNot { part ->
            val hasDigits = part.any { it.isDigit() }
            val isKnownColonyOrCity = listOf(
                "APODACA", "NUEVO LE