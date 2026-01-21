package com.example.opdslibrary.image

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.opdslibrary.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker for downloading catalog icons and book covers
 */
class ImageDownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ImageDownloadWorker"

        // Input keys
        const val KEY_IMAGE_TYPE = "image_type"
        const val KEY_IMAGE_URL = "image_url"
        const val KEY_CATALOG_ID = "catalog_id"
        const val KEY_BOOK_ID = "book_id"

        // Output keys
        const val KEY_LOCAL_PATH = "local_path"
        const val KEY_SUCCESS = "success"

        // Image types
        const val TYPE_CATALOG_ICON = "catalog_icon"
        const val TYPE_BOOK_COVER = "book_cover"
    }

    private val imageCacheManager = ImageCacheManager(context)
    private val database = AppDatabase.getDatabase(context)
    private val catalogDao = database.catalogDao()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val imageType = inputData.getString(KEY_IMAGE_TYPE) ?: return@withContext Result.failure()
        val imageUrl = inputData.getString(KEY_IMAGE_URL) ?: return@withContext Result.failure()

        try {
            val localPath = when (imageType) {
                TYPE_CATALOG_ICON -> {
                    val catalogId = inputData.getLong(KEY_CATALOG_ID, -1)
                    if (catalogId < 0) {
                        Log.e(TAG, "Invalid catalog ID")
                        return@withContext Result.failure()
                    }
                    downloadCatalogIcon(catalogId, imageUrl)
                }
                TYPE_BOOK_COVER -> {
                    val bookId = inputData.getLong(KEY_BOOK_ID, -1)
                    if (bookId < 0) {
                        Log.e(TAG, "Invalid book ID")
                        return@withContext Result.failure()
                    }
                    downloadBookCover(bookId, imageUrl)
                }
                else -> {
                    Log.e(TAG, "Unknown image type: $imageType")
                    return@withContext Result.failure()
                }
            }

            if (localPath != null) {
                Result.success(workDataOf(
                    KEY_LOCAL_PATH to localPath,
                    KEY_SUCCESS to true
                ))
            } else {
                Result.failure(workDataOf(KEY_SUCCESS to false))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Image download failed", e)
            Result.failure(workDataOf(KEY_SUCCESS to false))
        }
    }

    private suspend fun downloadCatalogIcon(catalogId: Long, iconUrl: String): String? {
        Log.d(TAG, "Downloading catalog icon for catalog $catalogId: $iconUrl")

        val localPath = imageCacheManager.downloadCatalogIcon(catalogId, iconUrl)

        if (localPath != null) {
            // Update catalog with local icon path
            catalogDao.updateIconLocalPath(catalogId, localPath)
            Log.d(TAG, "Catalog icon saved: $localPath")
        }

        return localPath
    }

    private suspend fun downloadBookCover(bookId: Long, coverUrl: String): String? {
        Log.d(TAG, "Downloading book cover for book $bookId: $coverUrl")

        val localPath = imageCacheManager.downloadBookCover(bookId, coverUrl)

        if (localPath != null) {
            // Update book with local cover path
            database.bookDao().updateCoverPath(bookId, localPath)
            Log.d(TAG, "Book cover saved: $localPath")
        }

        return localPath
    }
}
