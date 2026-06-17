package com.example.trekmesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "breadcrumbs")
data class BreadcrumbEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val timestamp: Long = System.currentTimeMillis()
)
