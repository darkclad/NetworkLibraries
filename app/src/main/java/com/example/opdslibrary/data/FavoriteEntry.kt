package com.example.opdslibrary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a favorite OPDS entry with its hierarchy path
 */
@Entity(tableName = "favorite_entries")
data class FavoriteEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val catalogId: Long,  // Which catalog this favorite belongs to

    val entryJson: String,  // Serialized OpdsEntry

    val hierarchyPath: String,  // JSON array of breadcrumb titles: ["Root", "Genre", "Sci-Fi"]

    val hierarchyUrls: String,  // JSON array of breadcrumb URLs for reconstruction

    val addedAt: Long = System.currentTimeMillis()
)
