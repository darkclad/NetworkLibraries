package com.example.opdslibrary.data

import androidx.room.*

/**
 * Data Access Object for OPDS feed cache
 */
@Dao
interface OpdsFeedCacheDao {
    /**
     * Get cached feed by URL
     */
    @Query("SELECT * FROM opds_feed_cache WHERE url = :url LIMIT 1")
    suspend fun getCachedFeed(url: String): OpdsFeedCache?

    /**
     * Insert or update cached feed
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(cache: OpdsFeedCache)

    /**
     * Delete cached feed by URL
     */
    @Query("DELETE FROM opds_feed_cache WHERE url = :url")
    suspend fun deleteCachedFeed(url: String)

    /**
     * Delete all cached feeds
     */
    @Query("DELETE FROM opds_feed_cache")
    suspend fun deleteAll()

    /**
     * Delete old cache entries (older than specified timestamp)
     */
    @Query("DELETE FROM opds_feed_cache WHERE cachedAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    /**
     * Check if a feed URL exists in cache
     */
    @Query("SELECT EXISTS(SELECT 1 FROM opds_feed_cache WHERE url = :url)")
    suspend fun isCached(url: String): Boolean
}
