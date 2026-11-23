package com.example.rutaoptimizador

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
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
        ).build()

        setContent {
            MaterialTheme {
                RutaScreen(db)
            }
        }
    }
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

    val monterreyDefault = LatLng(25.6866, -100.3161)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(monterreyDefault, 11.5f)
    }

    suspend fun geocodeAddress(rawAddress: String): LatLng? = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale("es", "MX"))
            val query = if (rawAddress.contains("Nuevo León", ignoreCase = true) ||
                rawAddress.contains("Monterrey", ignoreCase = true) ||
                rawAddress.contains("Apodaca", ignoreCase = true)
            ) {
                rawAddress
            } else {
                "$rawAddress, Nuevo León, México"
            }
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocationName(query, 1)
            results?.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
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
                val ocr = OcrProcessor()
                uris.forEachIndexed { index, uri ->
                    status = "Procesando fotografía ${index + 1} de ${uris.size}…"
                    try {
                        val text = withContext(Dispatchers.IO) { ocr.read(context, uri) }
                        val found = AddressExtractor.candidates(text).map { stop ->
                            val coords = geocodeAddress(stop.address)
                            if (coords != null) {
                                stop.copy(latitude = coords.latitude, longitude = coords.longitude)
                            } else {
                                stop
                            }
                        }
                        if (found.isNotEmpty()) {
                            db.stopDao().upsertAll(found)
                            candidates += found.size
                        }
                    } catch (_: Exception) {
                        // La fotografía fallida no detiene el resto.
                    }
                    processed++
                }
                busy = false
                status = "Procesadas $processed fotografías. $candidates candidatos agregados para revisión."
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { picker.launch("image/*") },
                    enabled = !busy
                ) {
                    Text("Agregar fotografías")
                }

                OutlinedButton(onClick = {
                    if (!showMap && !hasLocationPermission) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                    showMap = !showMap
                }) {
                    Text(if (showMap) "Ver Lista" else "Ver Mapa")
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
                Button(
                    enabled = !busy,
                    onClick = {
                        if (manualAddress.isNotBlank()) {
                            val cleanAddress = manualAddress.trim()
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
                                        verified = true
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

            val markers: List<Stop> = stops.filter { it.latitude != null && it.longitude != null }
            Text(
                "Entregas: ${stops.size} · En mapa: ${markers.size} · Pendientes: ${stops.count { !it.verified }}",
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
                            Marker(
                                state = rememberUpdatedMarkerState(
                                    position = LatLng(lat, lng)
                                ),
                                title = "Parada #${index + 1}",
                                snippet = stop.address,
                                icon = BitmapDescriptorFactory.defaultMarker(
                                    if (stop.verified) BitmapDescriptorFactory.HUE_AZURE
                                    else BitmapDescriptorFactory.HUE_ORANGE
                                )
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
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(stop.address, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${stop.source} · ${if (stop.verified) "Verificado" else "Pendiente"} · ${if (hasCoords) "Ubicado en mapa" else "Sin GPS"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row {
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
