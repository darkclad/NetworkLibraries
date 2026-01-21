package com.example.opdslibrary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for favorite entries
 */
@Dao
interface FavoriteDao {
    /**
     * Get all favorites for a catalog
     */
    @Query("SELECT * FROM favorite_entries WHERE catalogId = :catalogId ORDER BY addedAt DESC")
    fun getFavoritesForCatalog(catalogId: Long): Flow<List<FavoriteEntry>>

    /**
     * Get all favorites for a catalog as a one-time list
     */
    @Query("SELECT * FROM favorite_entries WHERE catalogId = :catalogId ORDER BY addedAt DESC")
    suspend fun getFavoritesForCatalogOnce(catalogId: Long): List<FavoriteEntry>

    /**
     * Insert a favorite
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntry): Long

    /**
     * Delete a favorite
     */
    @Delete
    suspend fun delete(favorite: FavoriteEntry)

    /**
     * Delete a favorite by ID
     */
    @Query("DELETE FROM favorite_entries WHERE id = :favoriteId")
    suspend fun deleteFavoriteById(favoriteId: Long)

    /**
     * Delete all favorites for a catalog
     */
    @Query("DELETE FROM favorite_entries WHERE catalogId = :catalogId")
    suspend fun deleteAllForCatalog(catalogId: Long)

    /**
     * Check if an entry is already in favorites (by entry title and hierarchy)
     */
    @Query("SELECT COUNT(*) FROM favorite_entries WHERE catalogId = :catalogId AND entryJson LIKE '%\"title\":\"' || :entryTitle || '\"%'")
    suspend fun isFavorite(catalogId: Long, entryTitle: String): Int

    /**
     * Get count of favorites for a catalog
     */
    @Query("SELECT COUNT(*) FROM favorite_entries WHERE catalogId = :catalogId")
    suspend fun getFavoritesCount(catalogId: Long): Int

    /**
     * Get all favorites across all catalogs (for cleanup operations)
     */
    @Query("SELECT * FROM favorite_entries")
    suspend fun getAllFavorites(): List<FavoriteEntry>

    /**
     * Update hierarchy path and URLs for a favorite entry
     */
    @Query("UPDATE favorite_entries SET hierarchyPath = :hierarchyPath, hierarchyUrls = :hierarchyUrls WHERE id = :id")
    suspend fun updateHierarchy(id: Long, hierarchyPath: String, hierarchyUrls: String)
}
