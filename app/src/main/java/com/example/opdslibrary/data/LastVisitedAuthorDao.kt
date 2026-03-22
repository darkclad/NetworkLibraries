package com.example.opdslibrary.data

import androidx.room.*

/**
 * Data Access Object for last visited author entries
 */
@Dao
interface LastVisitedAuthorDao {

    @Query("SELECT * FROM last_visited_authors WHERE catalogId = :catalogId ORDER BY visitedAt DESC")
    suspend fun getForCatalog(catalogId: Long): List<LastVisitedAuthor>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LastVisitedAuthor): Long

    @Query("DELETE FROM last_visited_authors WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete oldest entries beyond the limit for a catalog
     */
    @Query("""
        DELETE FROM last_visited_authors WHERE id IN (
            SELECT id FROM last_visited_authors
            WHERE catalogId = :catalogId
            ORDER BY visitedAt DESC
            LIMIT -1 OFFSET :limit
        )
    """)
    suspend fun trimToLimit(catalogId: Long, limit: Int)

    /**
     * Check if author URL already exists for this catalog and update timestamp
     */
    @Query("UPDATE last_visited_authors SET visitedAt = :visitedAt, navigationHistory = :navigationHistory WHERE catalogId = :catalogId AND url = :url")
    suspend fun updateVisitedAt(catalogId: Long, url: String, visitedAt: Long, navigationHistory: String): Int

    @Query("SELECT COUNT(*) FROM last_visited_authors WHERE catalogId = :catalogId")
    suspend fun getCount(catalogId: Long): Int
}
