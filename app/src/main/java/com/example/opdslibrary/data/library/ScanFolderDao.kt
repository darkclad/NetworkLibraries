package com.example.opdslibrary.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for scan folders
 */
@Dao
interface ScanFolderDao {

    // === Query Methods ===

    @Query("SELECT * FROM scan_folders ORDER BY displayName ASC")
    fun getAllFolders(): Flow<List<ScanFolder>>

    @Query("SELECT * FROM scan_folders WHERE enabled = 1")
    suspend fun getEnabledFoldersOnce(): List<ScanFolder>

    @Query("SELECT * FROM scan_folders WHERE id = :folderId")
    suspend fun getFolderById(folderId: Long): ScanFolder?

    @Query("SELECT * FROM scan_folders WHERE path = :path")
    suspend fun getFolderByPath(path: String): ScanFolder?

    @Query("SELECT COUNT(*) FROM scan_folders")
    suspend fun getFolderCount(): Int

    @Query("SELECT COUNT(*) FROM scan_folders WHERE enabled = 1")
    suspend fun getEnabledFolderCount(): Int

    // === Insert/Update/Delete ===

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: ScanFolder): Long

    @Update
    suspend fun update(folder: ScanFolder)

    @Delete
    suspend fun delete(folder: ScanFolder)

    @Query("DELETE FROM scan_folders WHERE id = :folderId")
    suspend fun deleteById(folderId: Long)

    // === Toggle Enable/Disable ===

    @Query("UPDATE scan_folders SET enabled = :enabled WHERE id = :folderId")
    suspend fun setEnabled(folderId: Long, enabled: Boolean)

    @Query("UPDATE scan_folders SET enabled = NOT enabled WHERE id = :folderId")
    suspend fun toggleEnabled(folderId: Long)

    // === Update Scan Stats ===

    @Query("UPDATE scan_folders SET lastScan = :lastScan, fileCount = :fileCount WHERE id = :folderId")
    suspend fun updateScanStats(folderId: Long, lastScan: Long, fileCount: Int)

    @Query("UPDATE scan_folders SET lastScan = NULL, fileCount = 0 WHERE id = :folderId")
    suspend fun resetScanStats(folderId: Long)

    @Query("UPDATE scan_folders SET lastScan = NULL, fileCount = 0")
    suspend fun resetAllScanStats()

    // === Check Existence ===

    @Query("SELECT EXISTS(SELECT 1 FROM scan_folders WHERE path = :path)")
    suspend fun existsByPath(path: String): Boolean
}
