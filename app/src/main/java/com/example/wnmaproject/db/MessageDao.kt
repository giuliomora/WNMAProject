package com.example.trekmesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    // Restituisce i messaggi ordinati per importanza:
    // 1. SOS (più prioritari)
    // 2. INFO (messaggi diretti)
    // 3. BROADCAST (informazioni generali meteo/sentieri)
    @Query("""
        SELECT * FROM messages
        ORDER BY
            CASE type 
                WHEN 'SOS' THEN priority + 10
                WHEN 'INFO' THEN priority + 5
                WHEN 'BROADCAST' THEN priority
                ELSE 0 
            END DESC,
            timestamp ASC
    """)
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT id FROM messages")
    suspend fun getAllIds(): List<String>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET image_path = :imagePath WHERE id = :id")
    suspend fun updateImagePath(id: String, imagePath: String)

    @Query("""
        DELETE FROM messages WHERE status != 'PENDING'
        AND (
            ttl <= 0
            OR (type = 'BROADCAST' AND timestamp < :sixHoursAgo)
            OR (type = 'INFO' AND priority < 3 AND timestamp < :sixHoursAgo)
            OR (type = 'INFO' AND priority >= 3 AND timestamp < :twentyFourHoursAgo)
            OR (type = 'SOS' AND timestamp < :twentyFourHoursAgo)
        )
    """)
    suspend fun deleteExpired(
        sixHoursAgo: Long = System.currentTimeMillis() - 6 * 60 * 60 * 1000L,
        twentyFourHoursAgo: Long = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    )

    @Query("""
        DELETE FROM messages WHERE id NOT IN (
            SELECT id FROM messages ORDER BY timestamp DESC LIMIT :maxSize
        )
    """)
    suspend fun pruneOldest(maxSize: Int)
}
