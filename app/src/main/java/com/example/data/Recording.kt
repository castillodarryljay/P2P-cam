package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val filePath: String,
    val fileName: String,
    val durationSeconds: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val sizeBytes: Long
)
