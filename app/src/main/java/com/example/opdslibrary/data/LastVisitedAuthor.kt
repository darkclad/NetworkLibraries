package com.example.opdslibrary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a last visited author entry in an OPDS catalog.
 * Keeps up to 15 entries per catalog, removing oldest when limit exceeded.
 */
@Entity(tableName = "last_visited_authors")
data class LastVisitedAuthor(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val catalogId: Long,

    val authorName: String,

    val url: String,  // URL of the author's page

    val feedTitle: String,  // Feed title for display

    val navigationHistory: String = "[]",  // JSON array of navigation history for reconstruction

    val visitedAt: Long = System.currentTimeMillis()
)
