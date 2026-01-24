package com.example.opdslibrary.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for series
 */
@Dao
interface SeriesDao {

    // === Query Methods ===

    @Query("SELECT * FROM series ORDER BY name ASC")
    fun getAllSeries(): Flow<List<Series>>

    @Query("""
        SELECT s.*, COUNT(b.id) as bookCount
        FROM series s
        INNER JOIN books b ON s.id = b.seriesId
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
        GROUP BY s.id
        HAVING bookCount > 0
        ORDER BY s.name ASC
    """)
    fun getAllSeriesWithBookCount(): Flow<List<SeriesWithBookCount>>

    @Query("SELECT * FROM series WHERE id = :seriesId")
    suspend fun getSeriesById(seriesId: Long): Series?

    @Query("SELECT * FROM series WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Series?

    @Query("SELECT * FROM series WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchSeries(query: String): Flow<List<Series>>

    @Query("""
        SELECT COUNT(DISTINCT s.id) FROM series s
        INNER JOIN books b ON s.id = b.seriesId
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
    """)
    suspend fun getSeriesCountOnce(): Int

    @Query("""
        SELECT COUNT(DISTINCT s.id) FROM series s
        INNER JOIN books b ON s.id = b.seriesId
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
    """)
    fun getSeriesCount(): Flow<Int>

    // === Insert/Update/Delete ===

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(series: Series): Long

    @Update
    suspend fun update(series: Series)

    @Delete
    suspend fun delete(series: Series)

    @Query("DELETE FROM series WHERE id = :seriesId")
    suspend fun deleteById(seriesId: Long)

    // === Find or Create ===

    @Transaction
    suspend fun findOrCreate(name: String): Long {
        val existing = findByName(name)
        return existing?.id ?: insert(Series(name = name))
    }

    // === Cleanup Orphans ===

    @Query("""
        DELETE FROM series WHERE id NOT IN (
            SELECT DISTINCT seriesId FROM books WHERE seriesId IS NOT NULL
        )
    """)
    suspend fun deleteOrphanedSeries(): Int

    @Query("DELETE FROM series")
    suspend fun deleteAll()
}
