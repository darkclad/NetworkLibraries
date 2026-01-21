package com.example.opdslibrary.image

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Scheduler for image download operations using WorkManager
 */
class ImageDownloadScheduler(private val context: Context) {

    companion object {
        private const val WORK_TAG_IMAGE = "image_download"
        private const val WORK_NAME_CATALOG_ICON = "catalog_icon_"
        private const val WORK_NAME_BOOK_COVER = "book_cover_"
        private const val WORK_NAME_CLEANUP = "image_cache_cleanup"
    }

    /**
     * Schedule download of a catalog icon
     */
    fun scheduleCatalogIconDownload(catalogId: Long, iconUrl: String) {
        val inputData = workDataOf(
            ImageDownloadWorker.KEY_IMAGE_TYPE to ImageDownloadWorker.TYPE_CATALOG_ICON,
            ImageDownloadWorker.KEY_CATALOG_ID to catalogId,
            ImageDownloadWorker.KEY_IMAGE_URL to iconUrl
        )

        val downloadRequest = OneTimeWorkRequestBuilder<ImageDownloadWorker>()
            .setInputData(inputData)
            .setConstraints(createConstraints())
            .addTag(WORK_TAG_IMAGE)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                WORK_NAME_CATALOG_ICON + catalogId,
                ExistingWorkPolicy.KEEP, // Don't restart if already running
                downloadRequest
            )
    }

    /**
     * Schedule download of a book cover
     */
    fun scheduleBookCoverDownload(bookId: Long, coverUrl: String) {
        val inputData = workDataOf(
            ImageDownloadWorker.KEY_IMAGE_TYPE to ImageDownloadWorker.TYPE_BOOK_COVER,
            ImageDownloadWorker.KEY_BOOK_ID to bookId,
            ImageDownloadWorker.KEY_IMAGE_URL to coverUrl
        )

        val downloadRequest = OneTimeWorkRequestBuilder<ImageDownloadWorker>()
            .setInputData(inputData)
            .setConstraints(createConstraints())
            .addTag(WORK_TAG_IMAGE)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                WORK_NAME_BOOK_COVER + bookId,
                ExistingWorkPolicy.KEEP,
                downloadRequest
            )
    }

    /**
     * Schedule periodic cache cleanup
     */
    fun schedulePeriodicCleanup() {
        val cleanupRequest = PeriodicWorkRequestBuilder<ImageCacheCleanupWorker>(
            1, TimeUnit.DAYS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresDeviceIdle(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                WORK_NAME_CLEANUP,
                ExistingPeriodicWorkPolicy.KEEP,
                cleanupRequest
            )
    }

    /**
     * Cancel pending catalog icon download
     */
    fun cancelCatalogIconDownload(catalogId: Long) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(WORK_NAME_CATALOG_ICON + catalogId)
    }

    /**
     * Cancel pending book cover download
     */
    fun cancelBookCoverDownload(bookId: Long) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(WORK_NAME_BOOK_COVER + bookId)
    }

    /**
     * Cancel all pending image downloads
     */
    fun cancelAllDownloads() {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG_IMAGE)
    }

    private fun createConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}

/**
 * Worker for cleaning up old cached images
 */
class ImageCacheCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cacheManager = ImageCacheManager(applicationContext)
        cacheManager.cleanupIfNeeded()
        return Result.success()
    }
}
