package com.example.opdslibrary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents an OPDS catalog stored in the local database
 */
@Entity(tableName = "opds_catalogs")
data class OpdsCatalog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The URL of the OPDS catalog */
    val url: String,

    /** User-provided custom name that overrides the OPDS catalog name */
    val customName: String? = null,

    /** Original name from the OPDS feed */
    val opdsName: String? = null,

    /** URL to the catalog icon */
    val iconUrl: String? = null,

    /** Local file path to the cached catalog icon */
    val iconLocalPath: String? = null,

    /** Timestamp when the icon was last updated from OPDS feed (ISO 8601 format) */
    val iconUpdated: String? = null,

    /** Whether this is the default catalog */
    val isDefault: Boolean = false,

    /** Alternate URL to use when primary URL times out */
    val alternateUrl: String? = null,

    /** Timestamp when the catalog was added */
    val addedDate: Long = System.currentTimeMillis()
) {
    /**
     * Get the display name - prefers custom name over OPDS name
     */
    fun getDisplayName(): String {
        return customName ?: opdsName ?: "Unnamed Catalog"
    }
}
