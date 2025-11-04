package com.example.opdslibrary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for OPDS catalogs
 */
@Dao
interface CatalogDao {
    /**
     * Get all catalogs as a Flow (automatically updates when data changes)
     */
    @Query("SELECT * FROM opds_catalogs ORDER BY addedDate DESC")
    fun getAllCatalogs(): Flow<List<OpdsCatalog>>

    /**
     * Get all catalogs as a one-time list
     */
    @Query("SELECT * FROM opds_catalogs ORDER BY addedDate DESC")
    suspend fun getAllCatalogsOnce(): List<OpdsCatalog>

    /**
     * Get the default catalog
     */
    @Query("SELECT * FROM opds_catalogs WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultCatalog(): OpdsCatalog?

    /**
     * Get a catalog by ID
     */
    @Query("SELECT * FROM opds_catalogs WHERE id = :catalogId")
    suspend fun getCatalogById(catalogId: Long): OpdsCatalog?

    /**
     * Insert a new catalog
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(catalog: OpdsCatalog): Long

    /**
     * Update an existing catalog
     */
    @Update
    suspend fun update(catalog: OpdsCatalog)

    /**
     * Delete a catalog
     */
    @Delete
    suspend fun delete(catalog: OpdsCatalog)

    /**
     * Delete a catalog by ID
     */
    @Query("DELETE FROM opds_catalogs WHERE id = :catalogId")
    suspend fun deleteCatalogById(catalogId: Long)

    /**
     * Delete all catalogs
     */
    @Query("DELETE FROM opds_catalogs")
    suspend fun deleteAll()

    /**
     * Set a catalog as default and unset all others
     */
    @Transaction
    suspend fun setDefaultCatalog(catalogId: Long) {
        // Unset all defaults
        unsetAllDefaults()
        // Set the specified catalog as default
        setDefault(catalogId)
    }

    /**
     * Unset all default catalogs
     */
    @Query("UPDATE opds_catalogs SET isDefault = 0")
    suspend fun unsetAllDefaults()

    /**
     * Set a specific catalog as default
     */
    @Query("UPDATE opds_catalogs SET isDefault = 1 WHERE id = :catalogId")
    suspend fun setDefault(catalogId: Long)

    /**
     * Check if a catalog with the given URL already exists
     */
    @Query("SELECT COUNT(*) FROM opds_catalogs WHERE url = :url")
    suspend fun catalogExists(url: String): Int
}
