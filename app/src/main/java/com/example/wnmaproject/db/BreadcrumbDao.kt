package com.example.trekmesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BreadcrumbDao {
    @Insert
    suspend fun insert(breadcrumb: BreadcrumbEntity)

    @Query("SELECT * FROM breadcrumbs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLast(limit: Int): List<BreadcrumbEntity>

    @Query("DELETE FROM breadcrumbs WHERE timestamp < :threshold")
    suspend fun pruneOld(threshold: Long)
}
