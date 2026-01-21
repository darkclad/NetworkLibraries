package com.example.opdslibrary.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Core book entity storing metadata for local library books
 */
@Entity(
    tableName = "books",
    indices = [
        Index("filePath", unique = true),
        Index("fileHash"),
        Index("seriesId"),
        Index("needsReindex"),
        Index("titleSort"),
        Index("opdsEntryId"),
        Index("catalogId")
    ],
    foreignKeys = [
        ForeignKey(
            entity = Series::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // File information
    val filePath: String,              // Current absolute path or SAF URI
    val fileHash: String,              // SHA-256 for deduplication
    val fileSize: Long,
    val fileModified: Long,            // File modification timestamp

    // Core metadata
    val title: String,
    val titleSort: String,             // Lowercase, without articles for sorting
    val lang: String? = null,
    val year: Int? = null,
    val isbn: String? = null,
    val description: String? = null,

    // Series information
    val seriesId: Long? = null,        // FK to series table
    val seriesNumber: Float? = null,   // Position in series (float for "1.5")

    // Metadata tracking
    val metadataSource: String,        // "fb2", "epub", "pdf", "mobi", "filename"
    val indexedAt: Long,               // When Lucene indexed this book
    val needsReindex: Boolean = false,

    // Organization
    val originalPath: String? = null,  // Path before auto-organization
    val coverPath: String? = null,     // Extracted cover image path

    // Tracking
    val addedAt: Long = System.currentTimeMillis(),
    val downloadedViaApp: Boolean = false,
    val catalogId: Long? = null,       // Source OPDS catalog if downloaded
    val opdsEntryId: String? = null,   // OPDS entry ID for linking catalog entry to local book
    val opdsUpdated: Long? = null,     // OPDS entry "updated" timestamp for detecting outdated books
    val opdsRelLinks: String? = null,  // JSON array of related OPDS links (author, series, etc.)
    val opdsNavigationHistory: String? = null  // JSON array of navigation history for "View in Catalog"
) {
    /**
     * Get file extension from path
     */
    fun getFileExtension(): String {
        val path = filePath.lowercase()
        return when {
            path.endsWith(".fb2.zip") -> "fb2.zip"
            path.endsWith(".fb2") -> "fb2"
            path.endsWith(".epub") -> "epub"
            path.endsWith(".pdf") -> "pdf"
            path.endsWith(".mobi") -> "mobi"
            path.endsWith(".azw3") -> "azw3"
            else -> filePath.substringAfterLast(".", "unknown")
        }
    }

    /**
     * Get friendly format name for display
     */
    fun getFormatName(): String {
        return when (getFileExtension()) {
            "fb2.zip" -> "FB2 (ZIP)"
            "fb2" -> "FB2"
            "epub" -> "EPUB"
            "pdf" -> "PDF"
            "mobi" -> "MOBI"
            "azw3" -> "AZW3"
            else -> getFileExtension().uppercase()
        }
    }

    /**
     * Data class for storing OPDS related link info
     */
    data class OpdsRelLink(
        val href: String,
        val title: String?,
        val type: String?,
        val rel: String?
    )

    /**
     * Parse the JSON stored in opdsRelLinks field
     */
    fun parseOpdsRelLinks(): List<OpdsRelLink> {
        if (opdsRelLinks.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<OpdsRelLink>>() {}.type
            Gson().fromJson(opdsRelLinks, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Data class for storing navigation history entry
     */
    data class OpdsNavHistoryEntry(
        val url: String,
        val title: String,
        val updated: String?
    )

    /**
     * Parse the JSON stored in opdsNavigationHistory field
     */
    fun parseOpdsNavigationHistory(): List<OpdsNavHistoryEntry> {
        if (opdsNavigationHistory.isNullOrBlank()) {
            android.util.Log.d("Book", "parseOpdsNavigationHistory: null or blank for book id=$id, title=$title")
            return emptyList()
        }
        return try {
            val type = object : TypeToken<List<OpdsNavHistoryEntry>>() {}.type
            val result: List<OpdsNavHistoryEntry> = Gson().fromJson(opdsNavigationHistory, type) ?: emptyList()
            android.util.Log.d("Book", "parseOpdsNavigationHistory: parsed ${result.size} entries for book id=$id")
            result
        } catch (e: Exception) {
            android.util.Log.e("Book", "parseOpdsNavigationHistory: failed to parse for book id=$id, json=$opdsNavigationHistory", e)
            emptyList()
        }
    }

    companion object {
        /**
         * Convert OPDS links to JSON string for storage
         */
        fun serializeOpdsRelLinks(links: List<OpdsRelLink>): String? {
            if (links.isEmpty()) return null
            return Gson().toJson(links)
        }

        /**
         * Serialize navigation history to JSON string for storage
         */
        fun serializeOpdsNavigationHistory(entries: List<OpdsNavHistoryEntry>): String? {
            if (entries.isEmpty()) return null
            return Gson().toJson(entries)
        }
    }
}
