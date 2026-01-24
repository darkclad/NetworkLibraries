package com.example.opdslibrary.data

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

// Extension property to create DataStore singleton
private val Context.dataStore by preferencesDataStore(name = "app_settings")

/**
 * Application preferences manager using DataStore
 */
class AppPreferences(private val context: Context) {

    companion object {
        // Keys
        private val KEY_SCAN_PARALLEL_WORKERS = intPreferencesKey("scan_parallel_workers")
        private val KEY_DOWNLOAD_FOLDER_URI = stringPreferencesKey("download_folder_uri")
        private val KEY_DOWNLOAD_FOLDER_NAME = stringPreferencesKey("download_folder_name")
        private val KEY_FORMAT_PRIORITY = stringPreferencesKey("format_priority")
        private val KEY_LIBRARY_BROWSE_MODE = stringPreferencesKey("library_browse_mode")
        private val KEY_LIBRARY_SORT_ORDER = stringPreferencesKey("library_sort_order")
        private val KEY_PREFERRED_READER_PACKAGE = stringPreferencesKey("preferred_reader_package")
        private val KEY_PREFERRED_READER_NAME = stringPreferencesKey("preferred_reader_name")

        // Defaults
        const val DEFAULT_PARALLEL_WORKERS = 4

        // Default format priority (comma-separated, highest priority first)
        val DEFAULT_FORMAT_PRIORITY = listOf(
            "fb2.zip", "fb2", "rtf", "pdf", "epub", "mobi", "azw3", "html", "txt"
        )

        /**
         * Get the maximum recommended parallel workers based on CPU cores
         */
        fun getMaxParallelWorkers(): Int {
            val cpuCores = Runtime.getRuntime().availableProcessors()
            // Use CPU cores, but cap at 8 to avoid excessive resource usage
            return cpuCores.coerceIn(1, 8)
        }

        /**
         * Get the default download folder path
         */
        fun getDefaultDownloadFolder(): String {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val booksDir = File(downloadsDir, "Books")
            if (!booksDir.exists()) {
                booksDir.mkdirs()
            }
            return booksDir.absolutePath
        }
    }

    /**
     * Flow of parallel workers setting
     */
    val scanParallelWorkers: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_SCAN_PARALLEL_WORKERS] ?: DEFAULT_PARALLEL_WORKERS
        }

    /**
     * Set number of parallel workers for library scanning
     */
    suspend fun setScanParallelWorkers(workers: Int) {
        val maxWorkers = getMaxParallelWorkers()
        val validWorkers = workers.coerceIn(1, maxWorkers)
        context.dataStore.edit { preferences ->
            preferences[KEY_SCAN_PARALLEL_WORKERS] = validWorkers
        }
    }

    /**
     * Get parallel workers synchronously (for use in Worker)
     */
    suspend fun getScanParallelWorkersOnce(): Int {
        val preferences = context.dataStore.data.first()
        return preferences[KEY_SCAN_PARALLEL_WORKERS] ?: DEFAULT_PARALLEL_WORKERS
    }

    // ==================== Download Folder Settings ====================

    /**
     * Flow of download folder URI (SAF URI or file path)
     */
    val downloadFolderUri: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_DOWNLOAD_FOLDER_URI]
        }

    /**
     * Flow of download folder display name
     */
    val downloadFolderName: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_DOWNLOAD_FOLDER_NAME] ?: "Downloads/Books"
        }

    /**
     * Set download folder
     * @param uri SAF URI or file path
     * @param displayName Human-readable folder name
     */
    suspend fun setDownloadFolder(uri: String, displayName: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DOWNLOAD_FOLDER_URI] = uri
            preferences[KEY_DOWNLOAD_FOLDER_NAME] = displayName
        }
    }

    /**
     * Get download folder URI synchronously
     */
    suspend fun getDownloadFolderUriOnce(): String? {
        val preferences = context.dataStore.data.first()
        return preferences[KEY_DOWNLOAD_FOLDER_URI]
    }

    /**
     * Get download folder display name synchronously
     */
    suspend fun getDownloadFolderNameOnce(): String {
        val preferences = context.dataStore.data.first()
        return preferences[KEY_DOWNLOAD_FOLDER_NAME] ?: "Downloads/Books"
    }

    /**
     * Check if a custom download folder is configured
     */
    suspend fun hasCustomDownloadFolder(): Boolean {
        return getDownloadFolderUriOnce() != null
    }

    // ==================== Format Priority Settings ====================

    /**
     * Flow of format priority list
     */
    val formatPriority: Flow<List<String>> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_FORMAT_PRIORITY]?.split(",") ?: DEFAULT_FORMAT_PRIORITY
        }

    /**
     * Set format priority order
     * @param formats List of format extensions in priority order (highest first)
     */
    suspend fun setFormatPriority(formats: List<String>) {
        context.dataStore.edit { preferences ->
            preferences[KEY_FORMAT_PRIORITY] = formats.joinToString(",")
        }
    }

    /**
     * Get format priority synchronously
     */
    suspend fun getFormatPriorityOnce(): List<String> {
        val preferences = context.dataStore.data.first()
        return preferences[KEY_FORMAT_PRIORITY]?.split(",") ?: DEFAULT_FORMAT_PRIORITY
    }

    /**
     * Reset format priority to default
     */
    suspend fun resetFormatPriority() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_FORMAT_PRIORITY)
        }
    }

    // ==================== Library View Mode Settings ====================

    /**
     * Flow of library browse mode
     */
    val libraryBrowseMode: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_LIBRARY_BROWSE_MODE] ?: "ALL_BOOKS"
        }

    /**
     * Flow of library sort order
     */
    val librarySortOrder: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_LIBRARY_SORT_ORDER] ?: "TITLE_ASC"
        }

    /**
     * Set library browse mode
     */
    suspend fun setLibraryBrowseMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LIBRARY_BROWSE_MODE] = mode
        }
    }

    /**
     * Set library sort order
     */
    suspend fun setLibrarySortOrder(order: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LIBRARY_SORT_ORDER] = order
        }
    }

    /**
     * Get library browse mode synchronously
     */
    suspend fun getLibraryBrowseModeOnce(): String {
        val preferences = context.dataStore.data.first()
        return preferences[KEY_LIBRARY_BROWSE_MODE] ?: "ALL_BOOKS"
    }

    /**
     * Get library sort order synchronously
     */
    suspend fun getLibrarySortOrderOnce(): String {
        val preferences = context.dataStore.data.first()
        return preferences[KEY_LIBRARY_SORT_ORDER] ?: "TITLE_ASC"
    }

    // ==================== Preferred Reader App Settings ====================

    /**
     * Flow of preferred reader app package name (null means use system chooser)
     */
    val preferredReaderPackage: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_PREFERRED_READER_PACKAGE]
        }

    /**
     * Flow of preferred reader app display name
     */
    val preferredReaderName: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_PREFERRED_READER_NAME] ?: "System Default (Ask Every Time)"
        }

    /**
     * Set preferred reader app
     * @param packageName App package name (null to clear)
     * @param displayName Human-readable app name
     */
    suspend fun setPreferredReader(packageName: String?, displayName: String) {
        context.dataStore.edit { preferences ->
            if (packageName != null) {
                preferences[KEY_PREFERRED_READER_PACKAGE] = packageName
                preferences[KEY_PREFERRED_READER_NAME] = displayName
            } else {
                preferences.remove(KEY_PREFERRED_READER_PACKAGE)
                preferences.remove(KEY_PREFERRED_READER_NAME)
            }
        }
    }

    /**
     * Get preferred reader package synchronously
     */
    suspend fun getPreferredReaderPackageOnce(): String? {
        val preferences = context.dataStore.data.first()
        return preferences[KEY_PREFERRED_READER_PACKAGE]
    }

    /**
     * Clear preferred reader (revert to system chooser)
     */
    suspend fun clearPreferredReader() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_PREFERRED_READER_PACKAGE)
            preferences.remove(KEY_PREFERRED_READER_NAME)
        }
    }
}
