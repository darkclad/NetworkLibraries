package com.example.opdslibrary.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * State of a single download
 */
data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val filename: String? = null,
    val progress: Int = 0,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val fileUri: Uri? = null,
    val error: String? = null,
    val opdsEntryId: String? = null,
    val catalogId: Long? = null,
    val opdsRelLinks: String? = null,
    val opdsNavigationHistory: String? = null
)

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    COMPLETED,
    ERROR
}

/**
 * Singleton manager for tracking book downloads
 */
object DownloadManager {
    private const val TAG = "DownloadManager"
    private const val MAX_COMPLETED_DOWNLOADS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    // Count of active downloads (pending + downloading)
    private val _activeDownloadsCount = MutableStateFlow(0)
    val activeDownloadsCount: StateFlow<Int> = _activeDownloadsCount.asStateFlow()

    // Count of downloads needing attention (active + failed)
    private val _attentionCount = MutableStateFlow(0)
    val attentionCount: StateFlow<Int> = _attentionCount.asStateFlow()

    private var bookDownloader: BookDownloader? = null

    fun initialize(context: Context) {
        if (bookDownloader == null) {
            bookDownloader = BookDownloader(context.applicationContext)
        }
    }

    /**
     * Start a new download
     */
    fun startDownload(
        context: Context,
        title: String,
        url: String,
        fallbackFilename: String,
        alternateUrl: String? = null,
        username: String? = null,
        password: String? = null,
        opdsEntryId: String? = null,
        catalogId: Long? = null,
        opdsRelLinks: String? = null,
        opdsNavigationHistory: String? = null,
        onComplete: ((Uri, String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): String {
        initialize(context)

        val downloadId = UUID.randomUUID().toString()
        val downloadItem = DownloadItem(
            id = downloadId,
            title = title,
            url = url,
            filename = fallbackFilename,
            status = DownloadStatus.PENDING,
            opdsEntryId = opdsEntryId,
            catalogId = catalogId,
            opdsRelLinks = opdsRelLinks,
            opdsNavigationHistory = opdsNavigationHistory
        )

        _downloads.update { it + downloadItem }
        updateActiveCount()

        scope.launch {
            performDownload(
                context = context,
                downloadId = downloadId,
                url = url,
                fallbackFilename = fallbackFilename,
                alternateUrl = alternateUrl,
                username = username,
                password = password,
                onComplete = onComplete,
                onError = onError
            )
        }

        return downloadId
    }

    private suspend fun performDownload(
        context: Context,
        downloadId: String,
        url: String,
        fallbackFilename: String,
        alternateUrl: String?,
        username: String?,
        password: String?,
        onComplete: ((Uri, String) -> Unit)?,
        onError: ((String) -> Unit)?
    ) {
        val downloader = bookDownloader ?: run {
            updateDownloadError(downloadId, "Downloader not initialized")
            onError?.invoke("Downloader not initialized")
            return
        }

        // Update status to downloading
        updateDownloadStatus(downloadId, DownloadStatus.DOWNLOADING)

        Log.d(TAG, "Starting download: $url")

        val result = downloader.downloadBook(
            url = url,
            fallbackFilename = fallbackFilename,
            username = username,
            password = password,
            onProgress = { progress ->
                updateDownloadProgress(downloadId, progress)
            }
        )

        when (result) {
            is DownloadResult.Success -> {
                Log.d(TAG, "Download completed: ${result.fileName}")
                updateDownloadComplete(downloadId, result.fileUri, result.fileName)
                onComplete?.invoke(result.fileUri, result.fileName)
            }
            is DownloadResult.Error -> {
                Log.e(TAG, "Download failed: ${result.message}")

                // Try alternate URL if available
                if (alternateUrl != null) {
                    Log.d(TAG, "Trying alternate URL: $alternateUrl")
                    val retryResult = downloader.downloadBook(
                        url = alternateUrl,
                        fallbackFilename = fallbackFilename,
                        username = username,
                        password = password,
                        onProgress = { progress ->
                            updateDownloadProgress(downloadId, progress)
                        }
                    )

                    when (retryResult) {
                        is DownloadResult.Success -> {
                            Log.d(TAG, "Alternate download completed: ${retryResult.fileName}")
                            updateDownloadComplete(downloadId, retryResult.fileUri, retryResult.fileName)
                            onComplete?.invoke(retryResult.fileUri, retryResult.fileName)
                        }
                        is DownloadResult.Error -> {
                            Log.e(TAG, "Alternate download failed: ${retryResult.message}")
                            updateDownloadError(downloadId, retryResult.message)
                            onError?.invoke(retryResult.message)
                        }
                    }
                } else {
                    updateDownloadError(downloadId, result.message)
                    onError?.invoke(result.message)
                }
            }
        }
    }

    private fun updateDownloadStatus(downloadId: String, status: DownloadStatus) {
        _downloads.update { list ->
            list.map {
                if (it.id == downloadId) it.copy(status = status)
                else it
            }
        }
        updateActiveCount()
    }

    private fun updateDownloadProgress(downloadId: String, progress: Int) {
        _downloads.update { list ->
            list.map {
                if (it.id == downloadId) it.copy(progress = progress, status = DownloadStatus.DOWNLOADING)
                else it
            }
        }
    }

    private fun updateDownloadComplete(downloadId: String, fileUri: Uri, filename: String) {
        _downloads.update { list ->
            list.map {
                if (it.id == downloadId) it.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    fileUri = fileUri,
                    filename = filename
                )
                else it
            }
        }
        updateActiveCount()
        // Auto-cleanup and sort after completion
        autoCleanupCompleted()
    }

    private fun updateDownloadError(downloadId: String, error: String) {
        _downloads.update { list ->
            sortDownloads(list.map {
                if (it.id == downloadId) it.copy(status = DownloadStatus.ERROR, error = error)
                else it
            })
        }
        updateActiveCount()
    }

    private fun updateActiveCount() {
        val downloads = _downloads.value
        _activeDownloadsCount.value = downloads.count {
            it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING
        }
        // Attention count includes active downloads and failed downloads
        _attentionCount.value = downloads.count {
            it.status == DownloadStatus.PENDING ||
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.ERROR
        }
    }

    /**
     * Sort downloads: active first (pending/downloading), then failed, then completed
     */
    private fun sortDownloads(downloads: List<DownloadItem>): List<DownloadItem> {
        return downloads.sortedWith(compareBy { item ->
            when (item.status) {
                DownloadStatus.DOWNLOADING -> 0
                DownloadStatus.PENDING -> 1
                DownloadStatus.ERROR -> 2
                DownloadStatus.COMPLETED -> 3
            }
        })
    }

    /**
     * Auto-cleanup: keep at most MAX_COMPLETED_DOWNLOADS completed downloads
     */
    private fun autoCleanupCompleted() {
        _downloads.update { list ->
            val active = list.filter {
                it.status == DownloadStatus.PENDING ||
                it.status == DownloadStatus.DOWNLOADING
            }
            val failed = list.filter { it.status == DownloadStatus.ERROR }
            val completed = list.filter { it.status == DownloadStatus.COMPLETED }

            // Keep only the most recent completed downloads
            val trimmedCompleted = if (completed.size > MAX_COMPLETED_DOWNLOADS) {
                completed.takeLast(MAX_COMPLETED_DOWNLOADS)
            } else {
                completed
            }

            sortDownloads(active + failed + trimmedCompleted)
        }
    }

    /**
     * Remove a download from the list
     */
    fun removeDownload(downloadId: String) {
        _downloads.update { list ->
            list.filter { it.id != downloadId }
        }
        updateActiveCount()
    }

    /**
     * Clear completed downloads
     */
    fun clearCompleted() {
        _downloads.update { list ->
            list.filter { it.status != DownloadStatus.COMPLETED }
        }
    }

    /**
     * Clear all downloads (including active ones - they will continue but won't be tracked)
     */
    fun clearAll() {
        _downloads.value = emptyList()
        updateActiveCount()
    }

    /**
     * Get download by ID
     */
    fun getDownload(downloadId: String): DownloadItem? {
        return _downloads.value.find { it.id == downloadId }
    }

    /**
     * Check if a URL is currently being downloaded (pending or downloading)
     */
    fun isDownloading(url: String): Boolean {
        return _downloads.value.any {
            it.url == url && (it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING)
        }
    }

    /**
     * Check if any of the given URLs is currently being downloaded
     */
    fun isAnyDownloading(urls: List<String>): Boolean {
        val activeUrls = _downloads.value
            .filter { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
            .map { it.url }
            .toSet()
        return urls.any { it in activeUrls }
    }

    /**
     * Get set of URLs that are currently being downloaded
     */
    fun getDownloadingUrls(): Set<String> {
        return _downloads.value
            .filter { it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING }
            .map { it.url }
            .toSet()
    }

    /**
     * Retry a failed download
     */
    fun retryDownload(
        context: Context,
        downloadId: String,
        onComplete: ((Uri, String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val download = getDownload(downloadId) ?: return
        if (download.status != DownloadStatus.ERROR) return

        initialize(context)

        // Reset status to pending
        _downloads.update { list ->
            list.map {
                if (it.id == downloadId) it.copy(
                    status = DownloadStatus.PENDING,
                    progress = 0,
                    error = null
                )
                else it
            }
        }
        updateActiveCount()

        scope.launch {
            performDownload(
                context = context,
                downloadId = downloadId,
                url = download.url,
                fallbackFilename = download.filename ?: "book_${System.currentTimeMillis()}",
                alternateUrl = null, // Don't use alternate on retry since we already tried it
                username = null,
                password = null,
                onComplete = onComplete,
                onError = onError
            )
        }
    }
}
