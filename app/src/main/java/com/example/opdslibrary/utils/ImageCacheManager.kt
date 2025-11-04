package com.example.opdslibrary.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * Manages image caching with timestamp validation
 * Caches images and checks if they need to be updated based on entry's updated timestamp
 */
class ImageCacheManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val cacheDir: File = File(context.cacheDir, CACHE_DIR_NAME).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "ImageCacheManager"
        private const val PREFS_NAME = "image_cache_prefs"
        private const val CACHE_DIR_NAME = "entry_images"
        private const val KEY_PREFIX_TIMESTAMP = "timestamp_"
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 1000L

        @Volatile
        private var INSTANCE: ImageCacheManager? = null

        fun getInstance(context: Context): ImageCacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ImageCacheManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    /**
     * Get cached image file if it exists and is still valid
     * @param imageUrl The URL of the image
     * @param entryUpdated The updated timestamp from the OPDS entry
     * @return File if cache is valid, null otherwise
     */
    fun getCachedImageIfValid(imageUrl: String, entryUpdated: String?): File? {
        val cacheFile = getCacheFile(imageUrl)

        if (!cacheFile.exists()) {
            Log.d(TAG, "No cache file exists for: $imageUrl")
            return null
        }

        val cachedTimestamp = prefs.getString(getTimestampKey(imageUrl), null)

        // If no entry updated timestamp, use cache if it exists
        if (entryUpdated.isNullOrEmpty()) {
            Log.d(TAG, "No entry timestamp, using cached image: $imageUrl")
            return cacheFile
        }

        // Compare timestamps
        if (cachedTimestamp == null) {
            Log.d(TAG, "No cached timestamp found for: $imageUrl")
            return null
        }

        if (cachedTimestamp == entryUpdated) {
            Log.d(TAG, "Cache is valid (timestamps match): $imageUrl")
            return cacheFile
        }

        // Check if entry is newer
        if (isEntryNewer(entryUpdated, cachedTimestamp)) {
            Log.d(TAG, "Entry is newer than cache, invalidating: $imageUrl")
            // Delete old cache
            cacheFile.delete()
            prefs.edit().remove(getTimestampKey(imageUrl)).apply()
            return null
        }

        Log.d(TAG, "Using cached image (entry not newer): $imageUrl")
        return cacheFile
    }

    /**
     * Download and cache an image with retry logic
     * @param imageUrl The URL to download from
     * @param entryUpdated The updated timestamp from the OPDS entry
     * @return The cached file, or null if download failed after retries
     */
    suspend fun downloadAndCacheImage(imageUrl: String, entryUpdated: String?): File? = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        // Retry logic for transient failures
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "Downloading image (attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS): $imageUrl")

                val request = Request.Builder()
                    .url(imageUrl)
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to download image: HTTP ${response.code} - $imageUrl")
                    return@withContext null
                }

                val cacheFile = getCacheFile(imageUrl)
                val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")

                try {
                    // Download to temp file first
                    response.body?.byteStream()?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Verify the download completed successfully
                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        Log.w(TAG, "Downloaded file is empty or doesn't exist: $imageUrl")
                        tempFile.delete()
                        return@withContext null
                    }

                    // Move temp file to final location
                    if (cacheFile.exists()) {
                        cacheFile.delete()
                    }
                    tempFile.renameTo(cacheFile)

                    // Save timestamp
                    if (!entryUpdated.isNullOrEmpty()) {
                        prefs.edit()
                            .putString(getTimestampKey(imageUrl), entryUpdated)
                            .apply()
                        Log.d(TAG, "Cached image with timestamp: $imageUrl -> $entryUpdated")
                    } else {
                        Log.d(TAG, "Cached image without timestamp: $imageUrl")
                    }

                    return@withContext cacheFile
                } catch (e: Exception) {
                    // Clean up temp file on error
                    tempFile.delete()
                    throw e
                }
            } catch (e: ProtocolException) {
                // Server closed connection prematurely or sent malformed response
                lastException = e
                Log.w(TAG, "Protocol error downloading image (attempt ${attempt + 1}): $imageUrl - ${e.message}")
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1)) // Exponential backoff
                }
            } catch (e: SocketTimeoutException) {
                // Network timeout
                lastException = e
                Log.w(TAG, "Timeout downloading image (attempt ${attempt + 1}): $imageUrl")
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1))
                }
            } catch (e: UnknownHostException) {
                // DNS resolution failed - don't retry
                Log.w(TAG, "Unknown host, skipping cache: $imageUrl - ${e.message}")
                return@withContext null
            } catch (e: IOException) {
                // Other network errors
                lastException = e
                Log.w(TAG, "IO error downloading image (attempt ${attempt + 1}): $imageUrl - ${e.message}")
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1))
                }
            } catch (e: Exception) {
                // Unexpected errors - don't retry
                Log.e(TAG, "Unexpected error downloading image: $imageUrl", e)
                return@withContext null
            }
        }

        // All retries exhausted
        Log.w(TAG, "Failed to download image after $MAX_RETRY_ATTEMPTS attempts: $imageUrl - ${lastException?.message}")
        null
    }

    /**
     * Clear all cached images
     */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Get the cache file for a given URL
     */
    private fun getCacheFile(imageUrl: String): File {
        val filename = generateCacheFileName(imageUrl)
        return File(cacheDir, filename)
    }

    /**
     * Generate a cache filename from URL using MD5 hash
     */
    private fun generateCacheFileName(url: String): String {
        val md5 = MessageDigest.getInstance("MD5")
        val digest = md5.digest(url.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }

        // Try to preserve file extension
        val extension = url.substringAfterLast(".", "").take(5)
        return if (extension.isNotEmpty() && extension.all { it.isLetterOrDigit() }) {
            "$hash.$extension"
        } else {
            hash
        }
    }

    /**
     * Get the SharedPreferences key for a URL's timestamp
     */
    private fun getTimestampKey(url: String): String {
        return KEY_PREFIX_TIMESTAMP + generateCacheFileName(url)
    }

    /**
     * Compare two timestamp strings to determine if entry is newer
     * Supports ISO 8601 format comparisons
     */
    private fun isEntryNewer(entryTimestamp: String, cachedTimestamp: String): Boolean {
        return try {
            // Direct string comparison works for ISO 8601 format (YYYY-MM-DDTHH:mm:ssZ)
            entryTimestamp > cachedTimestamp
        } catch (e: Exception) {
            Log.w(TAG, "Error comparing timestamps, assuming entry is newer", e)
            true
        }
    }
}

/**
 * Coil interceptor that checks our custom cache before loading images
 */
class CachedImageInterceptor(private val context: Context) : Interceptor {

    private val cacheManager by lazy { ImageCacheManager.getInstance(context) }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        // Only intercept HTTP/HTTPS URLs
        if (data is String && (data.startsWith("http://") || data.startsWith("https://"))) {
            // Try to get entry updated timestamp from request parameters
            val entryUpdated = request.parameters.value<String>("entry_updated")

            // Check if we have a valid cached version
            val cachedFile = cacheManager.getCachedImageIfValid(data, entryUpdated)
            if (cachedFile != null) {
                Log.d("CachedImageInterceptor", "Using cached image: $data")
                // Load from cached file
                val cachedRequest = request.newBuilder()
                    .data(cachedFile)
                    .build()
                return chain.proceed(cachedRequest)
            }

            // Proceed with normal loading
            val result = chain.proceed(request)

            // If successful, cache the image
            if (result is SuccessResult) {
                // Download and cache in background (fire and forget)
                CoroutineScope(Dispatchers.IO).launch {
                    cacheManager.downloadAndCacheImage(data, entryUpdated)
                }
            }

            return result
        }

        return chain.proceed(request)
    }
}
