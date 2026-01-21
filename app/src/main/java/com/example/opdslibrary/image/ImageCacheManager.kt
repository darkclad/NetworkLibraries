package com.example.opdslibrary.image

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manager for caching downloaded images locally
 */
class ImageCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "ImageCacheManager"
        private const val CACHE_DIR = "image_cache"
        private const val CATALOG_ICONS_DIR = "catalog_icons"
        private const val BOOK_COVERS_DIR = "book_covers"
        private const val MAX_CACHE_SIZE_MB = 100
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 30000
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR).apply { mkdirs() }
    }

    private val catalogIconsDir: File by lazy {
        File(cacheDir, CATALOG_ICONS_DIR).apply { mkdirs() }
    }

    private val bookCoversDir: File by lazy {
        File(cacheDir, BOOK_COVERS_DIR).apply { mkdirs() }
    }

    /**
     * Get cached catalog icon path, or null if not cached
     */
    fun getCachedCatalogIcon(catalogId: Long): String? {
        val file = File(catalogIconsDir, "catalog_$catalogId")
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Get cached book cover path, or null if not cached
     */
    fun getCachedBookCover(bookId: Long): String? {
        val file = File(bookCoversDir, "book_$bookId")
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Get cached image by URL hash
     */
    fun getCachedImageByUrl(imageUrl: String): String? {
        val hash = hashUrl(imageUrl)
        val file = File(cacheDir, hash)
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * Download and cache a catalog icon
     * @return Local file path on success, null on failure
     */
    suspend fun downloadCatalogIcon(catalogId: Long, iconUrl: String): String? {
        return downloadImage(iconUrl, File(catalogIconsDir, "catalog_$catalogId"))
    }

    /**
     * Download and cache a book cover
     * @return Local file path on success, null on failure
     */
    suspend fun downloadBookCover(bookId: Long, coverUrl: String): String? {
        return downloadImage(coverUrl, File(bookCoversDir, "book_$bookId"))
    }

    /**
     * Download and cache an image by URL
     * @return Local file path on success, null on failure
     */
    suspend fun downloadImageByUrl(imageUrl: String): String? {
        val hash = hashUrl(imageUrl)
        return downloadImage(imageUrl, File(cacheDir, hash))
    }

    /**
     * Download an image to a specific file
     */
    private suspend fun downloadImage(imageUrl: String, targetFile: File): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading image: $imageUrl")

            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "OPDS Library Android App")

            try {
                connection.connect()
                val responseCode = connection.responseCode

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "HTTP error: $responseCode for $imageUrl")
                    return@withContext null
                }

                // Check content type
                val contentType = connection.contentType
                if (contentType != null && !contentType.startsWith("image/")) {
                    Log.w(TAG, "Not an image: $contentType for $imageUrl")
                    return@withContext null
                }

                // Download to temp file first
                val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Rename to final file
                if (tempFile.exists()) {
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    tempFile.renameTo(targetFile)
                    Log.d(TAG, "Image cached: ${targetFile.absolutePath}")
                    return@withContext targetFile.absolutePath
                }

            } finally {
                connection.disconnect()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to download image: $imageUrl", e)
        }
        null
    }

    /**
     * Delete cached catalog icon
     */
    fun deleteCatalogIcon(catalogId: Long) {
        File(catalogIconsDir, "catalog_$catalogId").delete()
    }

    /**
     * Delete cached book cover
     */
    fun deleteBookCover(bookId: Long) {
        File(bookCoversDir, "book_$bookId").delete()
    }

    /**
     * Clear all cached images
     */
    fun clearCache() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        catalogIconsDir.mkdirs()
        bookCoversDir.mkdirs()
    }

    /**
     * Get current cache size in bytes
     */
    fun getCacheSizeBytes(): Long {
        return cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    /**
     * Cleanup old cache files if cache exceeds max size
     */
    suspend fun cleanupIfNeeded() = withContext(Dispatchers.IO) {
        val maxSizeBytes = MAX_CACHE_SIZE_MB * 1024L * 1024L
        val currentSize = getCacheSizeBytes()

        if (currentSize > maxSizeBytes) {
            Log.d(TAG, "Cache size ${currentSize / 1024 / 1024}MB exceeds limit, cleaning up...")

            // Get all files sorted by last modified (oldest first)
            val files = cacheDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.lastModified() }
                .toList()

            var deletedSize = 0L
            val targetSize = maxSizeBytes * 3 / 4 // Clean to 75% of max

            for (file in files) {
                if (currentSize - deletedSize <= targetSize) break
                val fileSize = file.length()
                if (file.delete()) {
                    deletedSize += fileSize
                    Log.d(TAG, "Deleted: ${file.name}")
                }
            }

            Log.d(TAG, "Cleanup complete, freed ${deletedSize / 1024}KB")
        }
    }

    private fun hashUrl(url: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(url.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
