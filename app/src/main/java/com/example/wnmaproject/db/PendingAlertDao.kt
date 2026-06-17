package com.example.trekmesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingAlertDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: PendingAlertEntity)

    @Query("SELECT * FROM pending_alerts ORDER BY timestamp ASC")
    suspend fun getAll(): List<PendingAlertEntity>

    @Query("DELETE FROM pending_alerts WHERE messageId = :messageId")
    suspend fun delete(messageId: String)
}
