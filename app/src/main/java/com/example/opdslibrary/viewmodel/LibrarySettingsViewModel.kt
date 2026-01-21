package com.example.opdslibrary.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.data.library.ScanFolder
import com.example.opdslibrary.library.scanner.LibraryScanScheduler
import com.example.opdslibrary.library.scanner.LibraryScanWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Data class representing scan progress
 */
data class ScanProgress(
    val isScanning: Boolean = false,
    val folderName: String? = null,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val currentFile: String? = null,
    val status: String? = null
) {
    val progressText: String
        get() = if (totalCount > 0) "$processedCount / $totalCount" else ""

    val progressPercent: Float
        get() = if (totalCount > 0) processedCount.toFloat() / totalCount else 0f
}

/**
 * ViewModel for Library Settings screen
 */
class LibrarySettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "LibrarySettingsVM"
    }

    private val database = AppDatabase.getDatabase(application)
    private val scanFolderDao = database.scanFolderDao()
    private val bookDao = database.bookDao()
    private val authorDao = database.authorDao()
    private val seriesDao = database.seriesDao()
    private val genreDao = database.genreDao()
    private val bookFtsDao = database.bookFtsDao()
    private val scanScheduler = LibraryScanScheduler(application)

    // All scan folders
    val scanFolders: StateFlow<List<ScanFolder>> = scanFolderDao.getAllFolders()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error message
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Scan progress (legacy - for backward compatibility)
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Detailed scan progress
    val scanProgress: StateFlow<ScanProgress> = scanScheduler.observeScanProgress()
        .asFlow()
        .map { workInfos ->
            val runningWork = workInfos.firstOrNull {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }

            if (runningWork != null) {
                val progress = runningWork.progress
                val isRunning = runningWork.state == WorkInfo.State.RUNNING

                // Update legacy scanning state
                _isScanning.value = true

                ScanProgress(
                    isScanning = true,
                    folderName = progress.getString(LibraryScanWorker.KEY_FOLDER_NAME),
                    processedCount = progress.getInt(LibraryScanWorker.KEY_PROCESSED_COUNT, 0),
                    totalCount = progress.getInt(LibraryScanWorker.KEY_TOTAL_COUNT, 0),
                    currentFile = progress.getString(LibraryScanWorker.KEY_CURRENT_FILE),
                    status = progress.getString(LibraryScanWorker.KEY_STATUS)
                )
            } else {
                // Check if any work just completed
                val completedWork = workInfos.any {
                    it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED
                }
                if (completedWork) {
                    _isScanning.value = false
                }
                ScanProgress(isScanning = false)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ScanProgress()
        )

    /**
     * Add a new scan folder
     */
    fun addScanFolder(uri: Uri, displayName: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                // Check if already exists
                val existing = scanFolderDao.getFolderByPath(uri.toString())
                if (existing != null) {
                    _errorMessage.value = "This folder is already added"
                    return@launch
                }

                // Insert new folder
                val folder = ScanFolder(
                    path = uri.toString(),
                    displayName = displayName,
                    enabled = true
                )
                scanFolderDao.insert(folder)

                Log.d(TAG, "Added scan folder: $displayName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add scan folder", e)
                _errorMessage.value = "Failed to add folder: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Remove a scan folder
     */
    fun removeScanFolder(folder: ScanFolder) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                scanFolderDao.delete(folder)
                Log.d(TAG, "Removed scan folder: ${folder.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove scan folder", e)
                _errorMessage.value = "Failed to remove folder: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Toggle folder enabled state
     */
    fun toggleFolderEnabled(folder: ScanFolder) {
        viewModelScope.launch {
            try {
                scanFolderDao.setEnabled(folder.id, !folder.enabled)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle folder", e)
                _errorMessage.value = "Failed to update folder: ${e.message}"
            }
        }
    }

    /**
     * Start scanning all enabled folders
     */
    fun startScan(fullScan: Boolean = false) {
        viewModelScope.launch {
            try {
                _isScanning.value = true
                scanScheduler.scheduleScanAll(fullScan)
                Log.d(TAG, "Started library scan")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start scan", e)
                _errorMessage.value = "Failed to start scan: ${e.message}"
                _isScanning.value = false
            }
        }
    }

    /**
     * Start scanning a specific folder
     */
    fun scanFolder(folder: ScanFolder, fullScan: Boolean = false) {
        viewModelScope.launch {
            try {
                _isScanning.value = true
                scanScheduler.scheduleScanFolder(folder.id, fullScan)
                Log.d(TAG, "Started scanning folder: ${folder.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start folder scan", e)
                _errorMessage.value = "Failed to start scan: ${e.message}"
                _isScanning.value = false
            }
        }
    }

    /**
     * Cancel all scans
     */
    fun cancelScans() {
        scanScheduler.cancelAllScans()
        _isScanning.value = false
        Log.d(TAG, "Cancelled all scans")
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Clear all library data (books, authors, series, genres, search index)
     * Does NOT delete scan folders - only clears the library contents
     */
    fun clearLibrary() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                // Cancel any ongoing scans first
                cancelScans()

                // Clear in the correct order to respect foreign key constraints
                // 1. Clear junction tables first
                authorDao.deleteAllBookAuthors()
                genreDao.deleteAllBookGenres()

                // 2. Clear FTS index
                bookFtsDao.clearAll()

                // 3. Clear main tables
                bookDao.deleteAll()
                authorDao.deleteAll()
                seriesDao.deleteAll()
                genreDao.deleteAll()

                // 4. Reset scan folder file counts and last scan timestamps
                scanFolderDao.resetAllScanStats()

                Log.d(TAG, "Library database cleared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear library", e)
                _errorMessage.value = "Failed to clear library: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
