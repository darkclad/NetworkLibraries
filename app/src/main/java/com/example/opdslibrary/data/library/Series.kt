package com.example.opdslibrary.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Series entity for book series
 */
@Entity(
    tableName = "series",
    indices = [
        Index("name", unique = true)
    ]
)
data class Series(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val description: String? = null
)
