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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private fun createStopMarkerIcon(type: String): BitmapDescriptor {
    val size = 84
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f

    when (type) {
        "RECOLECCION" -> {
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D97706")
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FEF3C7")
                style = Paint.Style.FILL
            }
            val rect = RectF(14f, 18f, size - 14f, size - 14f)
            canvas.drawRoundRect(rect, 10f, 10f, boxPaint)
            canvas.drawRoundRect(rect, 10f, 10f, borderPaint)
            canvas.drawRect(center - 6f, 18f, center + 6f, 38f, tapePaint)
        }
        "COBRO" -> {
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#16A34A")
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 42f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            canvas.drawCircle(center, center, 32f, circlePaint)
            canvas.drawCircle(center, center, 32f, borderPaint)
            val textY = center - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("$", center, textY, textPaint)
        }
        else -> {
            val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#22C55E")
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }
            canvas.drawCircle(center, center, 28f, circlePaint)
            canvas.drawCircle(center, center, 28f, borderPaint)
        }
    }

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

                uris.forEachIndexed { index, uri ->
                    status = "Procesando fotografía ${index + 1} de ${uris.size}…"
                    try {
                        val text = withContext(Dispatchers.IO) { ocr.read(context, uri) }
                        val found = AddressExtractor.candidates(text).mapIndexed { idx, stop ->
                            val coords = geocodeAddress(stop.address)
                            stop.copy(
                                latitude = coords?.latitude,
                                longitude = coords?.longitude,
                                orderIndex = candidates + idx
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                "Entregas: ${stops.size} · En mapa: ${markers.size} · 🟢 ${stops.count { it.type == "ENTREGA" }} · 🟢$ ${stops.count { it.type == "COBRO" }} · 📦 ${stops.count { it.type == "RECOLECCION" }}",
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
                            val typeLabel = when (stop.type) {
                                "COBRO" -> "Entrega con cobro ($)"
                                "RECOLECCION" -> "Recolección"
                                else -> "Entrega"
                            }
                            Marker(
                                state = rememberUpdatedMarkerState(
                                    position = LatLng(lat, lng)
                                ),
                                title = "Parada #${index + 1} · $typeLabel",
                                snippet = stop.address,
                                icon = createStopMarkerIcon(stop.type)
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(stops, key = { it.id }) { stop ->
                        val lat = stop.latitude
                        val lng = stop.longitude
                        val hasCoords = lat != null && lng != null
                        val badge = when (stop.type) {
                            "COBRO" -> "🟢$ Entrega con cobro"
                            "RECOLECCION" -> "📦 Recolección"
                            else -> "🟢 Entrega"
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text("$badge · ${stop.address}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${stop.source} · ${if (stop.verified) "Verificado" else "Pendiente"} · ${if (hasCoords) "Ubicado en mapa" else "Sin GPS"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row {
                                    TextButton(onClick = {
                                        val nextType = when (stop.type) {
                                            "ENTREGA" -> "COBRO"
                                            "COBRO" -> "RECOLECCION"
                                            else -> "ENTREGA"
                                        }
                                        scope.launch { db.stopDao().upsert(stop.copy(type = nextType)) }
                                    }) {
                                        Text("Cambiar tipo")
                                    }
                                    if (!stop.verified) {
                                        TextButton(onClick = {
                                            scope.launch { db.stopDao().upsert(stop.copy(verified = true)) }
                                        }) {
                                            Text("Confirmar")
                                        }
                                    }
                                    if (hasCoords && lat != null && lng != null) {
                                        TextButton(onClick = {
                                            showMap = true
                                            scope.launch {
                                                cameraPositionState.animate(
                                                    CameraUpdateFactory.newLatLngZoom(
                                                        LatLng(lat, lng),
                                                        16f
                                                    )
                                                )
                                            }
                                        }) {
                                            Text("Ver en mapa")
                                        }
                                    }
                                    TextButton(onClick = {
                                        scope.launch { db.stopDao().delete(stop.id) }
                                    }) {
                                        Text("Eliminar")
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