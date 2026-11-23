package com.example.rutaoptimizador

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StopDao {
    @Query("SELECT * FROM stops ORDER BY orderIndex, address") fun observeAll(): Flow<List<Stop>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(stops: List<Stop>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(stop: Stop)
    @Query("DELETE FROM stops WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM stops") suspend fun clear()
}


==========================================================================================
