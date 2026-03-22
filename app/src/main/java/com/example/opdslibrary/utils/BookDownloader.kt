package com.example.opdslibrary.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.opdslibrary.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Result of a book download operation
 */
sealed class DownloadResult {
    data class Success(
        val fileUri: Uri,
        val fileName: String,
        val fileSize: Long
    ) : DownloadResult()

    data class Error(val message: String) : DownloadResult()
}

/**
 * Handles book downloads with proper filename extraction and SAF support
 */
class BookDownloader(private val context: Context) {

    companion object {
        private const val TAG = "BookDownloader"
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val appPreferences = AppPreferences(context)

    /**
     * Download a book from URL to the configured download folder
     * @param url Download URL
     * @param fallbackFilename Filename to use if server doesn't provide one
     * @param username Optional username for authentication
     * @param password Optional password for authentication
     * @param onProgress Callback for download progress (0-100)
     * @return DownloadResult with file URI or error
     */
    suspend fun downloadBook(
        url: String,
        fallbackFilename: String,
        username: String? = null,
        password: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download: $url")

            // Build request with optional authentication
            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "opdsLibrary/1.0")

            if (username != null && password != null) {
                val credentials = okhttp3.Credentials.basic(username, password)
                requestBuilder.addHeader("Authorization", credentials)
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${response.code}")
                    return@withContext DownloadResult.Error("HTTP ${response.code}: ${response.message}")
                }

                // Extract filename from Content-Disposition header
                val contentDisposition = response.header("Content-Disposition")
                val filename = extractFilenameFromContentDisposition(contentDisposition)
                    ?: fallbackFilename
                Log.d(TAG, "Using filename: $filename (from header: ${contentDisposition != null})")

                // Get content length for progress
                val contentLength = response.body?.contentLength() ?: -1

                // Get download folder
                val downloadFolderUri = appPreferences.getDownloadFolderUriOnce()

                // Save file
                val result = if (downloadFolderUri != null && downloadFolderUri.startsWith("content://")) {
                    // Use SAF to write to the configured folder
                    saveToSafFolder(
                        Uri.parse(downloadFolderUri),
                        filename,
                        response.body?.byteStream(),
                        contentLength,
                        onProgress
                    )
                } else {
                    // Use default Downloads/Books folder
                    saveToDefaultFolder(
                        filename,
                        response.body?.byteStream(),
                        contentLength,
                        onProgress
                    )
                }

                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            DownloadResult.Error(e.message ?: "Download failed")
        }
    }

    /**
     * Extract filename from Content-Disposition header
     */
    private fun extractFilenameFromContentDisposition(contentDisposition: String?): String? {
        if (contentDisposition == null) return null

        Log.d(TAG, "Parsing Content-Disposition: $contentDisposition")

        // Try to extract filename from different formats
        val patterns = listOf(
            // filename*=UTF-8''... (RFC 5987 encoding, preferred)
            Regex("""filename\*\s*=\s*[^']*'[^']*'(.+)""", RegexOption.IGNORE_CASE),
            // filename="..."
            Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
            // filename=... (without quotes)
            Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(contentDisposition)
            if (match != null) {
                var filename = match.groupValues[1].trim()
                // URL decode if needed
                try {
                    filename = java.net.URLDecoder.decode(filename, "UTF-8")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to URL decode filename: $filename")
                }
                Log.d(TAG, "Extracted filename: $filename")
                return filename
            }
        }

        return null
    }

    /**
     * Save file to SAF folder using ContentResolver
     */
    private suspend fun saveToSafFolder(
        folderUri: Uri,
        filename: String,
        inputStream: java.io.InputStream?,
        contentLength: Long,
        onProgress: ((Int) -> Unit)?
    ): DownloadResult {
        if (inputStream == null) {
            return DownloadResult.Error("No data to download")
        }

        try {
            val documentFolder = DocumentFile.fromTreeUri(context, folderUri)
                ?: return DownloadResult.Error("Cannot access download folder")

            // Check if file already exists and delete it
            val existingFile = documentFolder.findFile(filename)
            existingFile?.delete()

            // Determine MIME type
            val mimeType = FilenameUtils.getMimeType(filename)

            // Create new file
            val newFile = documentFolder.createFile(mimeType, filename)
                ?: return DownloadResult.Error("Cannot create file in download folder")

            // Write data
            context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (contentLength > 0 && onProgress != null) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        onProgress(progress)
                    }
                }

                Log.d(TAG, "File saved: ${newFile.uri}, size: $totalBytesRead bytes")
                return DownloadResult.Success(newFile.uri, filename, totalBytesRead)
            }

            return DownloadResult.Error("Failed to write file")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to SAF folder", e)
            return DownloadResult.Error(e.message ?: "Failed to save file")
        }
    }

    /**
     * Save file to default Downloads/Books folder
     */
    private suspend fun saveToDefaultFolder(
        filename: String,
        inputStream: java.io.InputStream?,
        contentLength: Long,
        onProgress: ((Int) -> Unit)?
    ): DownloadResult {
        if (inputStream == null) {
            return DownloadResult.Error("No data to download")
        }

        try {
            val downloadFolder = File(AppPreferences.getDefaultDownloadFolder())
            if (!downloadFolder.exists()) {
                downloadFolder.mkdirs()
            }

            // Generate unique filename if exists
            var targetFile = File(downloadFolder, filename)
            var counter = 1
            val nameWithoutExt = filename.substringBeforeLast(".")
            val extension = filename.substringAfterLast(".", "")

            while (targetFile.exists()) {
                val newName = if (extension.isNotEmpty()) {
                    "${nameWithoutExt}_$counter.$extension"
                } else {
                    "${nameWithoutExt}_$counter"
                }
                targetFile = File(downloadFolder, newName)
                counter++
            }

            // Write data
            targetFile.outputStream().use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    if (contentLength > 0 && onProgress != null) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        onProgress(progress)
                    }
                }

                Log.d(TAG, "File saved: ${targetFile.absolutePath}, size: $totalBytesRead bytes")
                return DownloadResult.Success(Uri.fromFile(targetFile), targetFile.name, totalBytesRead)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to default folder", e)
            return DownloadResult.Error(e.message ?: "Failed to save file")
        }
    }

}
