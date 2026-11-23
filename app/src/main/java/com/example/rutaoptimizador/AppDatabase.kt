package com.example.rutaoptimizador

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Stop::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stopDao(): StopDao
}
