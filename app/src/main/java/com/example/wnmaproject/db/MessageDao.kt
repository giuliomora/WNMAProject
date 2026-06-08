package com.example.trekmesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT id FROM messages")
    suspend fun getAllIds(): List<String>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM messages WHERE ttl <= 0")
    suspend fun deleteExpired()

    @Query(
        """DELETE FROM messages WHERE id NOT IN (
            SELECT id FROM messages ORDER BY timestamp DESC LIMIT :maxSize
        )"""
    )
    suspend fun pruneOldest(maxSize: Int)
}
