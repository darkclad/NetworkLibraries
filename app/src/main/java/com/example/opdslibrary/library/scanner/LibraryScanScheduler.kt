package com.example.opdslibrary.library.scanner

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Scheduler for library scan operations using WorkManager
 */
class LibraryScanScheduler(private val context: Context) {

    companion object {
        private const val WORK_NAME_SCAN_ALL = "library_scan_all"
        private const val WORK_NAME_SCAN_FOLDER = "library_scan_folder_"
        private const val WORK_NAME_SCAN_FILE = "library_scan_file"
        private const val WORK_TAG_SCAN = "library_scan"
    }

    /**
     * Schedule a scan of all enabled folders
     */
    fun scheduleScanAll(fullScan: Boolean = false) {
        val inputData = workDataOf(
            LibraryScanWorker.KEY_FULL_SCAN to fullScan
        )

        val scanRequest = OneTimeWorkRequestBuilder<LibraryScanWorker>()
            .setInputData(inputData)
            .setConstraints(createConstraints())
            .addTag(WORK_TAG_SCAN)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                WORK_NAME_SCAN_ALL,
                ExistingWorkPolicy.REPLACE,
                scanRequest
            )
    }

    /**
     * Schedule a scan of a specific folder
     */
    fun scheduleScanFolder(folderId: Long, fullScan: Boolean = false) {
        val inputData = workDataOf(
            LibraryScanWorker.KEY_FOLDER_ID to folderId,
            LibraryScanWorker.KEY_FULL_SCAN to fullScan
        )

        val scanRequest = OneTimeWorkRequestBuilder<LibraryScanWorker>()
            .setInputData(inputData)
            .setConstraints(createConstraints())
            .addTag(WORK_TAG_SCAN)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                WORK_NAME_SCAN_FOLDER + folderId,
                ExistingWorkPolicy.REPLACE,
                scanRequest
            )
    }

    /**
     * Schedule processing of a single file (e.g., newly downloaded book)
     * @param fileUri URI of the file to process
     * @param opdsEntryId Optional OPDS entry ID to link the book to its catalog entry
     * @param catalogId Optional catalog ID for OPDS entry linking
     * @param opdsRelLinks Optional JSON string of related OPDS links
     * @param opdsNavigationHistory Optional JSON string of navigation history for "View in Catalog"
     */
    fun scheduleProcessFile(
        fileUri: Uri,
        opdsEntryId: String? = null,
        catalogId: Long? = null,
        opdsRelLinks: String? = null,
        opdsNavigationHistory: String? = null,
        opdsUpdated: Long? = null
    ) {
        val inputDataBuilder = Data.Builder()
            .putString(LibraryScanWorker.KEY_SINGLE_FILE_URI, fileUri.toString())

        if (opdsEntryId != null) {
            inputDataBuilder.putString(LibraryScanWorker.KEY_OPDS_ENTRY_ID, opdsEntryId)
        }
        if (catalogId != null) {
            inputDataBuilder.putLong(LibraryScanWorker.KEY_OPDS_CATALOG_ID, catalogId)
        }
        if (opdsRelLinks != null) {
            inputDataBuilder.putString(LibraryScanWorker.KEY_OPDS_REL_LINKS, opdsRelLinks)
        }
        if (opdsNavigationHistory != null) {
            inputDataBuilder.putString(LibraryScanWorker.KEY_OPDS_NAV_HISTORY, opdsNavigationHistory)
        }
        if (opdsUpdated != null) {
            inputDataBuilder.putLong(LibraryScanWorker.KEY_OPDS_UPDATED, opdsUpdated)
        }

        val scanRequest = OneTimeWorkRequestBuilder<LibraryScanWorker>()
            .setInputData(inputDataBuilder.build())
            .addTag(WORK_TAG_SCAN)
            .build()

        WorkManager.getInstance(context)
            .enqueue(scanRequest)
    }

    /**
     * Cancel all pending scans
     */
    fun cancelAllScans() {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG_SCAN)
    }

    /**
     * Cancel scan for a specific folder
     */
    fun cancelFolderScan(folderId: Long) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(WORK_NAME_SCAN_FOLDER + folderId)
    }

    /**
     * Observe scan progress for all library scans
     */
    fun observeScanProgress(): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosByTagLiveData(WORK_TAG_SCAN)
    }

    /**
     * Observe progress for scanning all folders
     */
    fun observeScanAllProgress(): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(WORK_NAME_SCAN_ALL)
    }

    /**
     * Observe progress for a specific folder scan
     */
    fun observeFolderScanProgress(folderId: Long): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(WORK_NAME_SCAN_FOLDER + folderId)
    }

    /**
     * Check if any scan is currently running
     */
    suspend fun isScanning(): Boolean {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosByTag(WORK_TAG_SCAN)
            .get()

        return workInfos.any {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }
    }

    private fun createConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()
    }
}
