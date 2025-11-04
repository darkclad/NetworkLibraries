package com.example.opdslibrary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Database entity for caching OPDS feed XML responses
 */
@Entity(tableName = "opds_feed_cache")
data class OpdsFeedCache(
    @PrimaryKey
    val url: String,
    val xmlContent: String,
    val feedUpdated: String?,
    val cachedAt: Long = System.currentTimeMillis()
)
