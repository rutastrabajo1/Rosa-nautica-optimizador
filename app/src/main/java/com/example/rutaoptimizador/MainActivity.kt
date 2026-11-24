package com.example.rutaoptimizador

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.location.Geocoder
import android.os.Bundle
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

// Paleta Náutica Profesional
private val NavyDeep = Color(0xFF0B192C)
private val NavyCard = Color(0xFF1E3E62)
private val NauticalTeal = Color(0xFF0EA5E9)
private val NauticalMint = Color(0xFF10B981)
private val GoldCobro = Color(0xFFF59E0B)
private val OrangePickup = Color(0xFFEA580C)
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
                RutaScreen(db)
            }
        }
    }
}

private fun createNumberedMarkerIcon(type: String, stopNumber: Int): BitmapDescriptor {
    val size = 100
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(60, 0, 0, 0)
        style = Paint.Style.FILL
    }

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
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
            val goldBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = GoldCobro.toArgb()
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
            canvas.drawCircle(center + 2f, center + 4f, 36f, shadowPaint)
            canvas.drawCircle(center, center, 36f, circlePaint)
            canvas.drawCircle(center, center, 36f, goldBorderPaint)
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

    fun extractPostalCode(text: String): String? {
        val cpRegex = Regex("""\b(\d{5})\b""")
        return cpRegex.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { it.toIntOrNull() in 1000..99999 }
    }

    suspend fun geocodeAddress(rawAddress: String): LatLng? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale("es", "MX"))
            val postalCode = extractPostalCode(rawAddress)

            var cpCenter: LatLng? = null
            if (postalCode != null) {
                @Suppress("DEPRECATION")
                val cpResults = geocoder.getFromLocationName("$postalCode, México", 1)
                cpCenter = cpResults?.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
            }

            val query = when {
                postalCode != null && !rawAddress.contains("México", ignoreCase = true) ->
                    "$rawAddress, CP $postalCode, México"
                rawAddress.contains("Nuevo León", ignoreCase = true) ||
                        rawAddress.contains("México", ignoreCase = true) ->
                    rawAddress
                else ->
                    "$rawAddress, Nuevo León, México"
            }

            val lowerLeftLat = cpCenter?.let { it.latitude - 0.035 } ?: 25.55
            val lowerLeftLng = cpCenter?.let { it.longitude - 0.035 } ?: -100.48
            val upperRightLat = cpCenter?.let { it.latitude + 0.035 } ?: 25.92
            val upperRightLng = cpCenter?.let { it.longitude + 0.035 } ?: -100.05

            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocationName(
                query,
                5,
                lowerLeftLat,
                lowerLeftLng,
                upperRightLat,
                upperRightLng
            )

            val bestMatch = results?.firstOrNull { addr ->
                val matchesCp = postalCode == null || addr.postalCode == postalCode
                val inBounds = addr.latitude in lowerLeftLat..upperRightLat &&
                        addr.longitude in lowerLeftLng..upperRightLng
                matchesCp && inBounds
            } ?: results?.firstOrNull { addr ->
                addr.latitude in lowerLeftLat..upperRightLat &&
                        addr.longitude in lowerLeftLng..upperRightLng
            }

            bestMatch?.let { LatLng(it.latitude, it.longitude) } ?: cpCenter
        } catch (_: Exception) {
            null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                busy = true
                var processed = 0
                var candidates = 0
                var emptyPhotos = 0
                val ocr = OcrProcessor()
                val startIndex = stops.size

                uris.forEachIndexed { index, uri ->
                    status = "Analizando captura ${index + 1} de ${uris.size}…"
                    try {
                        val text = withContext(Dispatchers.IO) { ocr.read(context, uri) }
                        val found = AddressExtractor.candidates(text).mapIndexed { idx, stop ->
                            val coords = geocodeAddress(stop.address)
                            stop.copy(
                                latitude = coords?.latitude,
                                longitude = coords?.longitude,
                                orderIndex = startIndex + candidates + idx
                            )
                        }
                        if (found.isNotEmpty()) {
                            db.stopDao().upsertAll(found)
                            candidates += found.size
                        } else {
                            emptyPhotos++
                        }
                    } catch (_: Exception) {
                        emptyPhotos++
                    }
                    processed++
                }
                busy = false
                status = "$processed fotos procesadas · $candidates paradas agregadas"
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Vaciar ruta actual", fontWeight = FontWeight.Bold) },
            text = { Text("Se eliminarán las ${stops.size} paradas cargadas. Esta acción no se puede deshacer.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            stops.forEach { db.stopDao().delete(it.id) }
                            selectedIds = emptySet()
                            showMap = false
                            status = "Ruta vaciada"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Vaciar todo")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showFinishDialog) {
        val totalEntregas = stops.count { it.type == "ENTREGA" }
        val totalCobros = stops.count { it.type == "COBRO" }
        val totalRecol = stops.count { it.type == "RECOLECCION" }

        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text("Cierre de Jornada", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Resumen del día:", fontWeight = FontWeight.SemiBold)
                    Text("• Total de paradas: ${stops.size}")
                    Text("• Entregas estándar: $totalEntregas")
                    Text("• Entregas con cobro: $totalCobros")
                    Text("• Recolecciones: $totalRecol")
                    Spacer(Modifier.height(6.dp))
                    Text("¿Confirmas el cierre para dejar limpia la ruta de mañana?")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFinishDialog = false
                        scope.launch {
                            stops.forEach { db.stopDao().delete(it.id) }
                            selectedIds = emptySet()
                            showMap = false
                            status = "Jornada finalizada con éxito"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NauticalMint)
                ) {
                    Text("Finalizar y limpiar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) { Text("Seguir en ruta") }
            }
        )
    }

    if (showAddSheet) {
        AlertDialog(
            onDismissRequest = { showAddSheet = false },
            title = { Text("Agregar parada manual", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = manualAddress,
                        onValueChange = { manualAddress = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Calle, número, colonia y CP") },
                        singleLine = false,
                        maxLines = 3
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = manualType == "ENTREGA",
                            onClick = { manualType = "ENTREGA" },
                            label = { Text("Entrega") }
                        )
                        FilterChip(
                            selected = manualType == "COBRO",
                            onClick = { manualType = "COBRO" },
                            label = { Text("Cobro $") }
                        )
                        FilterChip(
                            selected = manualType == "RECOLECCION",
                            onClick = { manualType = "RECOLECCION" },
                            label = { Text("Recol.") }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualAddress.isNotBlank()) {
                            val cleanAddress = manualAddress.trim()
                            val selectedType = manualType
                            manualAddress = ""
                            showAddSheet = false
                            scope.launch {
                                busy = true
                                status = "Buscando coordenadas…"
                                val coords = geocodeAddress(cleanAddress)
                                db.stopDao().upsert(
                                    Stop(
                                        id = UUID.randomUUID().toString(),
                                        address = cleanAddress,
                                        latitude = coords?.latitude,
                                        longitude = coords?.longitude,
                                        source = "Manual",
                                        verified = true,
                                        orderIndex = stops.size,
                                        type = selectedType
                                    )
                                )
                                if (coords != null) {
                                    cameraPositionState.animate(
                                        CameraUpdateFactory.newLatLngZoom(coords, 15f)
                                    )
                                    status = "Parada agregada al mapa"
                                } else {
                                    status = "Parada agregada (sin GPS exacto)"
                                }
                                busy = false
                            }
                        }
                    }
                ) {
                    Text("Guardar parada")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSheet = false }) { Text("Cancelar") }
            }
        )
    }

    val markers: List<Stop> = stops.filter { it.latitude != null && it.longitude != null }

    Scaffold(
        containerColor = SurfaceBg,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(NavyDeep, NavyCard)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "Rosa Náutica",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            status,
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showAddSheet = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Agregar manual", tint = Color.White)
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Opciones", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Finalizar ruta") },
                                    enabled = stops.isNotEmpty() && !busy,
                                    leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NauticalMint) },
                                    onClick = {
                                        menuExpanded = false
                                        showFinishDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Vaciar ruta") },
                                    enabled = stops.isNotEmpty() && !busy,
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuExpanded = false
                                        showClearDialog = true
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatChip("Total", "${stops.size}", NauticalTeal, Modifier.weight(1f))
                    StatChip("Entregas", "${stops.count { it.type == "ENTREGA" }}", NauticalMint, Modifier.weight(1f))
                    StatChip("Cobros", "${stops.count { it.type == "COBRO" }}", GoldCobro, Modifier.weight(1f))
                    StatChip("Recol.", "${stops.count { it.type == "RECOLECCION" }}", OrangePickup, Modifier.weight(1f))
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = CardWhite, tonalElevation = 8.dp) {
                NavigationBarItem(
                    selected = !showMap,
                    onClick = { showMap = false },
                    icon = { Icon(Icons.Outlined.List, contentDescription = "Lista") },
                    label = { Text("Lista (${stops.size})") }
                )
                NavigationBarItem(
                    selected = showMap,
                    onClick = {
                        if (!hasLocationPermission) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                        showMap = true
                    },
                    icon = { Icon(Icons.Default.LocationOn, contentDescription = "Mapa") },
                    label = { Text("Mapa (${markers.size})") }
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!busy) picker.launch("image/*") },
                containerColor = NauticalTeal,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "Procesando…" else "Cargar Fotos", fontWeight = FontWeight.SemiBold)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = NauticalTeal
                )
            }

            if (selectedIds.isNotEmpty() && !showMap) {
                Surface(
                    color = Color(0xFFFEE2E2),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${selectedIds.size} seleccionadas",
                            color = Color(0xFF991B1B),
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { selectedIds = emptySet() }) {
                                Text("Desmarcar", color = TextDark)
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        val toDelete = stops.filter { it.id in selectedIds }
                                        toDelete.forEach { db.stopDao().delete(it.id) }
                                        val count = toDelete.size
                                        selectedIds = emptySet()
                                        status = "$count paradas eliminadas"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Eliminar")
                            }
                        }
                    }
                }
            }

            if (markers.size >= 2) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Calculando mejor secuencia para ${markers.size} paradas…"
                            val remaining = markers.toMutableList()
                            val ordered = mutableListOf<Stop>()
                            var current = remaining.removeAt(0)
                            ordered.add(current.copy(orderIndex = 0))

                            var step = 1
                            while (remaining.isNotEmpty()) {
                                val nextIndex = remaining.indices.minByOrNull { i ->
                                    val target = remaining[i]
                                    val dLat = (target.latitude ?: 0.0) - (current.latitude ?: 0.0)
                                    val dLng = (target.longitude ?: 0.0) - (current.longitude ?: 0.0)
                                    dLat * dLat + dLng * dLng
                                } ?: 0
                                current = remaining.removeAt(nextIndex)
                                ordered.add(current.copy(orderIndex = step++))
                            }

                            db.stopDao().upsertAll(ordered)
                            showMap = true
                            busy = false
                            status = "Ruta optimizada (${ordered.size} paradas)"
                        }
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NavyDeep),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = NauticalTeal)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Optimizar Ruta (${markers.size} paradas)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }

            if (showMap) {
                LaunchedEffect(markers) {
                    val first = markers.firstOrNull()
                    val lat = first?.latitude
                    val lng = first?.longitude
                    if (lat != null && lng != null) {
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 13f)
                        )
                    }
                }

                GoogleMap(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(
                        isMyLocationEnabled = hasLocationPermission
                    ),
                    uiSettings = MapUiSettings(
                        myLocationButtonEnabled = hasLocationPermission,
                        zoomControlsEnabled = true,
                        compassEnabled = true
                    )
                ) {
                    markers.forEachIndexed { index, stop ->
                        val lat = stop.latitude
                        val lng = stop.longitude
                        if (lat != null && lng != null) {
                            val stopNumber = index + 1
                            val typeLabel = when (stop.type) {
                                "COBRO" -> "Entrega con cobro ($)"
                                "RECOLECCION" -> "Recolección"
                                else -> "Entrega"
                            }
                            Marker(
                                state = rememberUpdatedMarkerState(
                                    position = LatLng(lat, lng)
                                ),
                                title = "Parada #$stopNumber · $typeLabel",
                                snippet = stop.address,
                                icon = createNumberedMarkerIcon(stop.type, stopNumber)
                            )
                        }
                    }
                }
            } else {
                if (stops.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                tint = NauticalTeal,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Sin paradas en tu ruta",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextDark
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Toca el botón \"Cargar Fotos\" abajo para escanear las capturas de tu ruta.",
                                color = TextMuted,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(stops, key = { _, stop -> stop.id }) { index, stop ->
                            val lat = stop.latitude
                            val lng = stop.longitude
                            val hasCoords = lat != null && lng != null
                            val isSelected = stop.id in selectedIds

                            val accentColor = when (stop.type) {
                                "COBRO" -> GoldCobro
                                "RECOLECCION" -> OrangePickup
                                else -> NauticalMint
                            }
                            val typeLabel = when (stop.type) {
                                "COBRO" -> "COBRO $"
                                "RECOLECCION" -> "RECOLECCIÓN"
                                else -> "ENTREGA"
                            }

                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFFE0F2FE) else CardWhite
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Box(
                                        modifier = Modifier
                                            .width(6.dp)
                                            .height(118.dp)
                                            .background(accentColor)
                                    )

                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(12.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(if (stop.type == "RECOLECCION") RoundedCornerShape(8.dp) else CircleShape)
                                                    .background(accentColor)
                                            ) {
                                                Text(
                                                    "${index + 1}",
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                            }

                                            Spacer(Modifier.width(8.dp))

                                            Surface(
                                                color = accentColor.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    typeLabel,
                                                    color = accentColor,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }

                                            Spacer(Modifier.weight(1f))

                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { checked ->
                                                    selectedIds = if (checked) selectedIds + stop.id else selectedIds - stop.id
                                                }
                                            )
                                        }

                                        Text(
                                            stop.address,
                                            color = TextDark,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Spacer(Modifier.height(4.dp))

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = if (hasCoords) "📍 Ubicado" else "⚠️ Sin GPS",
                                                fontSize = 11.sp,
                                                color = if (hasCoords) NauticalMint else GoldCobro,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text("•", color = TextMuted, fontSize = 11.sp)
                                            Text(
                                                text = if (stop.verified) "Verificado" else "Pendiente",
                                                fontSize = 11.sp,
                                                color = TextMuted
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    scope.launch {
                                                        val nextType = when (stop.type) {
                                                            "ENTREGA" -> "COBRO"
                                                            "COBRO" -> "RECOLECCION"
                                                            else -> "ENTREGA"
                                                        }
                                                        db.stopDao().upsert(stop.copy(type = nextType))
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text("Tipo", fontSize = 12.sp)
                                            }

                                            if (!stop.verified) {
                                                TextButton(
                                                    onClick = {
                                                        scope.launch { db.stopDao().upsert(stop.copy(verified = true)) }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                                ) {
                                                    Text("Confirmar", fontSize = 12.sp, color = NauticalMint)
                                                }
                                            }

                                            if (hasCoords) {
                                                TextButton(
                                                    onClick = {
                                                        showMap = true
                                                        scope.launch {
                                                            cameraPositionState.animate(
                                                                CameraUpdateFactory.newLatLngZoom(LatLng(lat!!, lng!!), 16f)
                                                            )
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                                ) {
                                                    Text("Mapa", fontSize = 12.sp, color = NauticalTeal)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(vertical = 6.dp)
    ) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, color = Color(0xFFCBD5E1), fontSize = 10.sp)
    }
}