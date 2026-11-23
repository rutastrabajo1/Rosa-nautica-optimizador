package com.example.rutaoptimizador

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
import androidx.room.Room
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                        val found = AddressExtractor.candidates(text)
                        if (found.isNotEmpty()) {
                            db.stopDao().upsertAll(found)
                            candidates += found.size
                        }
                    } catch (_: Exception) {
                        // La fotografía fallida no detiene el resto; se refleja en el estado final.
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
                OutlinedButton(onClick = { showMap = !showMap }) {
                    Text(if (showMap) "Lista" else "Mapa")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manualAddress,
                    onValueChange = { manualAddress = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Agregar domicilio") }
                )
                Button(onClick = {
                    if (manualAddress.isNotBlank()) {
                        scope.launch {
                            db.stopDao().upsert(
                                Stop(
                                    id = UUID.randomUUID().toString(),
                                    address = manualAddress.trim(),
                                    source = "Manual",
                                    verified = true
                                )
                            )
                            manualAddress = ""
                        }
                    }
                }) {
                    Text("Añadir")
                }
            }
            Text("Entregas: ${stops.size} · pendientes de revisión: ${stops.count { !it.verified }}")
            if (showMap) {
                val markers = stops.filter { it.latitude != null && it.longitude != null }
                GoogleMap(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    markers.forEach { stop ->
                        Marker(
                            state = rememberUpdatedMarkerState(
                                position = LatLng(stop.latitude!!, stop.longitude!!)
                            ),
                            title = stop.address
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(stops, key = { it.id }) { stop ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(stop.address)
                                Text(
                                    text = "${stop.source} · ${if (stop.verified) "Verificado" else "Pendiente de revisión"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row {
                                    TextButton(onClick = {
                                        scope.launch { db.stopDao().upsert(stop.copy(verified = true)) }
                                    }) {
                                        Text("Confirmar")
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
