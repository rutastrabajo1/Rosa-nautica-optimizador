package com.example.rutaoptimizador

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente directo a Routes API. Divide las paradas en grupos para no exceder
 * el máximo de waypoints de una solicitud. El resultado es una secuencia de
 * segmentos, no una garantía de óptimo global para toda la jornada.
 */
class RoutesClient(private val apiKey: String) {
    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()

    data class Segment(val encodedPolyline: String, val orderedPlaceIds: List<Int>)

    fun optimize(points: List<Pair<Double, Double>>): List<Segment> {
        require(points.size >= 2) { "Agrega al menos origen y destino." }
        require(apiKey.isNotBlank()) { "Configura MAPS_API_KEY en local.properties." }

        // 25 waypoints intermedios como máximo por llamada; se conserva continuidad.
        val output = mutableListOf<Segment>()
        var start = 0
        while (start < points.lastIndex) {
            val end = minOf(start + 26, points.lastIndex)
            val chunk = points.subList(start, end + 1)
            val body = JSONObject().apply {
                put("origin", location(chunk.first()))
                put("destination", location(chunk.last()))
                put("travelMode", "DRIVE")
                put("optimizeWaypointOrder", true)
                val waypoints = JSONArray()
                chunk.drop(1).dropLast(1).forEach { waypoints.put(JSONObject().put("location", location(it))) }
                put("intermediates", waypoints)
            }
            val request = Request.Builder()
                .url("https://routes.googleapis.com/directions/v2:computeRoutes")
                .addHeader("X-Goog-Api-Key", apiKey)
                .addHeader("X-Goog-FieldMask", "routes.polyline.encodedPolyline,routes.optimizedIntermediateWaypointIndex")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Routes API respondió HTTP ${response.code}: ${response.body?.string().orEmpty()}")
                val json = JSONObject(response.body?.string().orEmpty())
                val route = json.optJSONArray("routes")?.optJSONObject(0)
                    ?: throw IllegalStateException("Google no devolvió una ruta para este segmento.")
                val indices = route.optJSONArray("optimizedIntermediateWaypointIndex")
                val order = (0 until (indices?.length() ?: 0)).map { indices!!.getInt(it) + start + 1 }
                output += Segment(route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty(), order)
            }
            start = end
        }
        return output
    }

    private fun location(p: Pair<Double, Double>) = JSONObject().put("latLng",
        JSONObject().put("latitude", p.first).put("longitude", p.second))
}
