package com.example.rutaoptimizador

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.Geocoder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
            MaterialTheme {
                RutaScreen(db)
            }
        }
    }
}

private fun createNumberedMarkerIcon(type: String, stopNumber: Int): BitmapDescriptor {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = if (stopNumber >= 100) 28f else 34f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    when (type) {
        "RECOLECCION" -> {
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D97706")
                style = Paint.Style.FILL
            }
            val rect = RectF(14f, 16f, size - 14f, size - 16f)
            canvas.drawRoundRect(rect, 14f, 14f, boxPaint)
            canvas.drawRoundRect(rect, 14f, 14f, borderPaint)
        }
        "COBRO" -> {
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#15803D")
                style = Paint.Style.FILL
            }
            val goldBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FACC15")
                style = Paint.Style.STROKE
                strokeWidth = 6f
            }
            canvas.drawCircle(center, center, 36f, circlePaint)
            canvas.drawCircle(center, center, 36f, goldBorderPaint)
        }
        else -> {
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#22C55E")
                style = Paint.Style.FILL
            }
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
    var status by remember { mutableStateOf("Selecciona fotografías para reconocer domicilios.") }
    var busy by remember { mutableStateOf(false) }
    var manualAddress by remember { mutableStateOf("") }
    var manualType by remember { mutableStateOf("ENTREGA") }
    var showMap by remember { mutableStateOf(false) }

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showFinishDialog by remember { mutableStateOf(false) }

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
                    status = "Procesando fotografía ${index + 1} de ${uris.size}…"
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
                status = "Procesadas $processed fotos ($emptyPhotos sin lectura). $candidates domicilios agregados."
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Vaciar ruta") },
            text = { Text("¿Seguro que quieres eliminar todas las paradas (${stops.size}) de la lista?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        scope.launch {
                            stops.forEach { db.stopDao().delete(it) }
                            selectedIds = emptySet()
                            showMap = false
                            status = "Ruta vaciada. Listo para cargar una nueva."
                        }
                    }
                ) {
                    Text("Sí, vaciar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showFinishDialog) {
        val totalEntregas = stops.count { it.type == "ENTREGA" }
        val totalCobros = stops.count { it.type == "COBRO" }
        val totalRecol = stops.count { it.type == "RECOLECCION" }

        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text("Resumen y cierre de ruta") },
            text = {
                Text(
                    "Paradas totales: ${stops.size}\n" +
                    "• Entregas: $totalEntregas\n" +
                    "• Entregas con cobro: $totalCobros\n" +
                    "• Recolecciones: $totalRecol\n\n" +
                    "¿Deseas finalizar la jornada y limpiar la lista para mañana?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFinishDialog = false
                        scope.launch {
                            stops.forEach { db.stopDao().delete(it) }
                            selectedIds = emptySet()
                            showMap = false
                            status = "¡Ruta finalizada! Todo quedó limpio para la siguiente jornada."
                        }
                    }
                ) {
                    Text("Finalizar ruta")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) {
                    Text("Seguir en ruta")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Rosa Náutica Optimizador") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(status, style = MaterialTheme.typography.bodyMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { picker.launch("image/*") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Agregar fotos")
                }

                OutlinedButton(
                    onClick = {
                        if (!showMap && !hasLocationPermission) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                        showMap = !showMap
                    }
                ) {
                    Text(if (showMap) "Ver Lista" else "Ver Mapa")
                }
            }

            if (stops.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Vaciar ruta")
                    }

                    Button(
                        onClick = { showFinishDialog = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("Finalizar ruta")
                    }
                }
            }

            if (selectedIds.isNotEmpty() && !showMap) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val toDelete = stops.filter { it.id in selectedIds }
                                toDelete.forEach { db.stopDao().delete(it) }
                                val count = toDelete.size
                                selectedIds = emptySet()
                                status = "Se eliminaron $count paradas seleccionadas."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Eliminar seleccionadas (${selectedIds.size})")
                    }

                    OutlinedButton(onClick = { selectedIds = emptySet() }) {
                        Text("Desmarcar")
                    }
                }
            }

            val markers: List<Stop> = stops.filter { it.latitude != null && it.longitude != null }
            if (markers.size >= 2) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            status = "Optimizando orden de ${markers.size} paradas…"
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
                            status = "Ruta optimizada con ${ordered.size} paradas en orden."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Optimizar Ruta (${markers.size} paradas)")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manualAddress,
                    onValueChange = { manualAddress = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Agregar domicilio") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        manualType = when (manualType) {
                            "ENTREGA" -> "COBRO"
                            "COBRO" -> "RECOLECCION"
                            else -> "ENTREGA"
                        }
                    }
                ) {
                    Text(
                        when (manualType) {
                            "COBRO" -> "🟢$ Cobro"
                            "RECOLECCION" -> "📦 Recol."
                            else -> "🟢 Entrega"
                        }
                    )
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        if (manualAddress.isNotBlank()) {
                            val cleanAddress = manualAddress.trim()
                            val selectedType = manualType
                            manualAddress = ""
                            scope.launch {
                                busy = true
                                status = "Ubicando domicilio en el mapa…"
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
                                    status = "Domicilio agregado y ubicado en el mapa."
                                } else {
                                    status = "Domicilio agregado (sin coordenadas exactas)."
                                }
                                busy = false
                            }
                        }
                    }
                ) {
                    Text("Añadir")
                }
            }

            Text(
                "Total: ${stops.size} · En mapa: ${markers.size} · 🟢 ${stops.count { it.type == "ENTREGA" }} · 🟢$ ${stops.count { it.type == "COBRO" }} · 📦 ${stops.count { it.type == "RECOLECCION" }}",
                style = MaterialTheme.typography.bodySmall
            )

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
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(stops, key = { _, stop -> stop.id }) { index, stop ->
                        val lat = stop.latitude
                        val lng = stop.longitude
                        val hasCoords = lat != null && lng != null
                        val isSelected = stop.id in selectedIds
                        val badge = when (stop.type) {
                            "COBRO" -> "🟢$ Cobro"
                            "RECOLECCION" -> "📦 Recolección"
                            else -> "🟢 Entrega"
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            selectedIds = if (checked) {
                                                selectedIds + stop.id
                                            } else {
                                                selectedIds - stop.id
                                            }
                                        }
                                    )
                                    Text(
                                        "#${index + 1} · $badge · ${stop.address}",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Text(
                                    text = "${stop.source} · ${if (stop.verified) "Verificado" else "Pendiente"} · ${if (hasCoords) "Ubicado en mapa" else "Sin GPS"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            val nextType = when (stop.type) {
                                                "ENTREGA" -> "COBRO"
                                                "COBRO" -> "RECOLECCION"
                                                else -> "ENTREGA"
                                            }
                                            db.stopDao().upsert(stop.copy(type = nextType))
                                        }
                                    }) { Text("Cambiar tipo") }

                                    if (!stop.verified) {
                                        TextButton(onClick = {
                                            scope.launch { db.stopDao().upsert(stop.copy(verified = true)) }
                                        }) { Text("Confirmar") }
                                    }

                                    if (hasCoords) {
                                        TextButton(onClick = {
                                            showMap = true
                                            scope.launch {
                                                cameraPositionState.animate(
                                                    CameraUpdateFactory.newLatLngZoom(LatLng(lat!!, lng!!), 16f)
                                                )
                                            }
                                        }) { Text("Ver en mapa") }
                                    }

                                    TextButton(onClick = {
                                        scope.launch {
                                            selectedIds = selectedIds - stop.id
                                            db.stopDao().delete(stop)
                                        }
                                    }) { Text("Eliminar") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}