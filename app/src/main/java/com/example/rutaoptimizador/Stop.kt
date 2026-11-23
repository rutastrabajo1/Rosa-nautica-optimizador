package com.example.rutaoptimizador

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stops")
data class Stop(
    @PrimaryKey val id: String,
    val address: String,
    val park: String = "Sin clasificar",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val source: String = "OCR",
    val verified: Boolean = false,
    val orderIndex: Int = 0,
    val type: String = "ENTREGA"
)
