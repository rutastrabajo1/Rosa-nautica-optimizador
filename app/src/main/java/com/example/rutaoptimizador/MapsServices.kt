package com.example.rutaoptimizador

import android.content.Context
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

object MapsServices {
    @Suppress("DEPRECATION")
    suspend fun geocode(context: Context, address: String): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            try {
                val geocoder = Geocoder(context, Locale("es", "MX"))
                if (Build.VERSION.SDK_INT >= 33) {
                    geocoder.getFromLocationName(address, 1) { list ->
                        if (cont.isActive) cont.resume(list.firstOrNull()?.let { it.latitude to it.longitude })
                    }
                } else {
                    val list = geocoder.getFromLocationName(address, 1)
                    if (cont.isActive) cont.resume(list?.firstOrNull()?.let { it.latitude to it.longitude })
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
}


==========================================================================================
