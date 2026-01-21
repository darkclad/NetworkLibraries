package com.example.opdslibrary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Entity for storing recent search queries per catalog
 */
@Entity(
    tableName = "search_history",
    indices = [
        Index("catalogId"),
        Index("searchedAt")
    ]
)
data class SearchHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val catalogId: Long,           // Which catalog this search was performed in
    val query: String,             // The search query
    val searchedAt: Long = System.currentTimeMillis()  // When the search was performed
)

/**
 * DAO for search history operations
 */
@Dao
interface SearchHistoryDao {

    /**
     * Get recent searches for a catalog, ordered by most recent first
     */
    @Query("""
        SELECT DISTINCT query FROM search_history
        WHERE catalogId = :catalogId
        ORDER BY searchedAt DESC
        LIMIT :limit
    """)
    fun getRecentSearches(catalogId: Long, limit: Int = 10): Flow<List<String>>

    /**
     * Get recent searches as a one-shot query
     */
    @Query("""
        SELECT DISTINCT query FROM search_history
        WHERE catalogId = :catalogId
        ORDER BY searchedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentSearchesOnce(catalogId: Long, limit: Int = 10): List<String>

    /**
     * Add a search query to history
     * If the same query exists, update its timestamp
     */
    @Query("""
        INSERT OR REPLACE INTO search_history (id, catalogId, query, searchedAt)
        SELECT
            COALESCE((SELECT id FROM search_history WHERE catalogId = :catalogId AND query = :query), 0),
            :catalogId,
            :query,
            :searchedAt
    """)
    suspend fun addSearch(catalogId: Long, query: String, searchedAt: Long = System.currentTimeMillis())

    /**
     * Insert a search entry
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(searchHistory: SearchHistory)

    /**
     * Delete old search entries (older than specified timestamp)
     */
    @Query("DELETE FROM search_history WHERE searchedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int

    /**
     * Delete all search history for a catalog
     */
    @Query("DELETE FROM search_history WHERE catalogId = :catalogId")
    suspend fun deleteForCatalog(catalogId: Long)

    /**
     * Delete a specific search query
     */
    @Query("DELETE FROM search_history WHERE catalogId = :catalogId AND query = :query")
    suspend fun deleteSearch(catalogId: Long, query: String)

    /**
     * Clear all search history
     */
    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    /**
     * Get count of search history entries
     */
    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun getCount(): Int
}
