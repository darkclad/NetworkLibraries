package com.example.opdslibrary

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.utils.CachedImageInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Custom Application class to configure Coil with image caching
 */
class OpdsApplication : Application(), ImageLoaderFactory {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "OpdsApplication"
        private const val SEARCH_HISTORY_DAYS_TO_KEEP = 30
    }

    override fun onCreate() {
        super.onCreate()

        // Clean up old search history entries on startup
        cleanupOldSearchHistory()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(CachedImageInterceptor(this@OpdsApplication))
            }
            .respectCacheHeaders(false) // We manage our own cache validation
            .build()
    }

    /**
     * Clean up search history entries older than SEARCH_HISTORY_DAYS_TO_KEEP days
     */
    private fun cleanupOldSearchHistory() {
        applicationScope.launch {
            try {
                val database = AppDatabase.getDatabase(this@OpdsApplication)
                val searchHistoryDao = database.searchHistoryDao()

                val cutoffTime = System.currentTimeMillis() -
                    (SEARCH_HISTORY_DAYS_TO_KEEP.toLong() * 24 * 60 * 60 * 1000)
                val deletedCount = searchHistoryDao.deleteOlderThan(cutoffTime)

                if (deletedCount > 0) {
                    Log.d(TAG, "Cleaned up $deletedCount old search history entries (older than $SEARCH_HISTORY_DAYS_TO_KEEP days)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up old search history", e)
            }
        }
    }
}
