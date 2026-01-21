package com.example.opdslibrary.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opdslibrary.data.AppPreferences
import com.example.opdslibrary.image.ImageCacheManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for Application Settings screen
 */
class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AppSettingsVM"
    }

    private val appPreferences = AppPreferences(application)
    private val imageCacheManager = ImageCacheManager(application)

    // Download folder settings
    val downloadFolderName: StateFlow<String> = appPreferences.downloadFolderName
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "Downloads/Books"
        )

    // Parallel workers setting
    val parallelWorkers: StateFlow<Int> = appPreferences.scanParallelWorkers
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppPreferences.DEFAULT_PARALLEL_WORKERS
        )

    // Maximum parallel workers based on CPU cores
    val maxParallelWorkers: Int = AppPreferences.getMaxParallelWorkers()

    // Format priority setting
    val formatPriority: StateFlow<List<String>> = appPreferences.formatPriority
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppPreferences.DEFAULT_FORMAT_PRIORITY
        )

    // Image cache size
    private val _imageCacheSize = MutableStateFlow(0L)
    val imageCacheSize: StateFlow<Long> = _imageCacheSize.asStateFlow()

    // Error message
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        // Load initial cache size
        viewModelScope.launch {
            _imageCacheSize.value = imageCacheManager.getCacheSizeBytes()
        }
    }

    /**
     * Set download folder for OPDS downloads
     */
    fun setDownloadFolder(uri: Uri, displayName: String) {
        viewModelScope.launch {
            try {
                appPreferences.setDownloadFolder(uri.toString(), displayName)
                Log.d(TAG, "Set download folder: $displayName ($uri)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set download folder", e)
                _errorMessage.value = "Failed to set download folder: ${e.message}"
            }
        }
    }

    /**
     * Set number of parallel workers for scanning
     */
    fun setParallelWorkers(workers: Int) {
        viewModelScope.launch {
            appPreferences.setScanParallelWorkers(workers)
            Log.d(TAG, "Set parallel workers to: $workers")
        }
    }

    /**
     * Set format priority order
     */
    fun setFormatPriority(formats: List<String>) {
        viewModelScope.launch {
            appPreferences.setFormatPriority(formats)
            Log.d(TAG, "Set format priority: $formats")
        }
    }

    /**
     * Reset format priority to default
     */
    fun resetFormatPriority() {
        viewModelScope.launch {
            appPreferences.resetFormatPriority()
            Log.d(TAG, "Reset format priority to default")
        }
    }

    /**
     * Clear image cache
     */
    fun clearImageCache() {
        viewModelScope.launch {
            try {
                imageCacheManager.clearCache()
                _imageCacheSize.value = 0L
                Log.d(TAG, "Image cache cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear image cache", e)
                _errorMessage.value = "Failed to clear cache: ${e.message}"
            }
        }
    }

    /**
     * Refresh cache size
     */
    fun refreshCacheSize() {
        viewModelScope.launch {
            _imageCacheSize.value = imageCacheManager.getCacheSizeBytes()
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
}
