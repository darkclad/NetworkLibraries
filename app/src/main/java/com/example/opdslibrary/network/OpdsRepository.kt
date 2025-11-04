package com.example.opdslibrary.network

import android.content.Context
import android.util.Log
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.data.OpdsFeed
import com.example.opdslibrary.data.OpdsFeedCache
import com.example.opdslibrary.data.OpdsFeedResult
import com.example.opdslibrary.data.OpdsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Exception for 401 Unauthorized responses
 */
class UnauthorizedException(message: String) : Exception(message)

/**
 * Exception for 404 Not Found responses
 */
class NotFoundException(message: String) : Exception(message)

/**
 * Repository for fetching OPDS feeds with caching support
 */
class OpdsRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val parser = OpdsParser()
    private val feedCacheDao = AppDatabase.getDatabase(context).feedCacheDao()

    companion object {
        private const val TAG = "OpdsRepository"

        // Date format used in OPDS feeds (ISO 8601)
        private val dateFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        ).apply {
            forEach { it.timeZone = TimeZone.getTimeZone("UTC") }
        }
    }

    /**
     * Fetch an OPDS feed from the given URL with optional authentication and caching
     * @param url URL of the OPDS feed
     * @param username Optional username for authentication
     * @param password Optional password for authentication
     * @param forceRefresh If true, bypass cache and fetch from network
     * @param parentUpdated Update timestamp from parent entry (used for cache validation)
     * @return Result containing OpdsFeedResult with feed data and cache status
     */
    suspend fun fetchFeed(
        url: String,
        username: String? = null,
        password: String? = null,
        forceRefresh: Boolean = false,
        parentUpdated: String? = null
    ): Result<OpdsFeedResult> = withContext(Dispatchers.IO) {
        try {
            // Check cache if not forcing refresh
            if (!forceRefresh) {
                val cachedFeed = getCachedFeedIfValid(url, parentUpdated)
                if (cachedFeed != null) {
                    Log.d(TAG, "Using cached OPDS feed for: $url")
                    return@withContext Result.success(OpdsFeedResult(cachedFeed, fromCache = true))
                }
            }

            Log.d(TAG, "Fetching OPDS feed from network: $url (forceRefresh=$forceRefresh)")

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "opdsLibrary/1.0")

            // Add Basic Authentication if credentials are provided
            if (username != null && password != null) {
                val credentials = okhttp3.Credentials.basic(username, password)
                requestBuilder.addHeader("Authorization", credentials)
                Log.d(TAG, "Adding Basic Auth credentials for user: $username")
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP error: ${response.code} ${response.message}")

                // Handle 401 Unauthorized specifically
                if (response.code == 401) {
                    Log.w(TAG, "401 Unauthorized - authentication required")
                    return@withContext Result.failure(
                        UnauthorizedException("Authentication required")
                    )
                }

                // Handle 404 Not Found specifically
                if (response.code == 404) {
                    Log.w(TAG, "404 Not Found - catalog no longer exists")
                    return@withContext Result.failure(
                        NotFoundException("Catalog not found (404)")
                    )
                }

                return@withContext Result.failure(
                    IOException("Unexpected response code: ${response.code}")
                )
            }

            // Read the response body as string for logging
            val xmlString = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response body"))

            // Log the XML response (truncated if too long)
            Log.d(TAG, "Received XML (${xmlString.length} chars):")
            if (xmlString.length > 5000) {
                Log.d(TAG, xmlString.substring(0, 5000) + "\n... [truncated]")
            } else {
                Log.d(TAG, xmlString)
            }

            // Convert string back to InputStream for parsing
            val feed = parser.parse(xmlString.byteInputStream())
            Log.d(TAG, "Successfully parsed feed: ${feed.title}")

            // Cache the feed
            cacheFeed(url, xmlString, feed.updated)

            Result.success(OpdsFeedResult(feed, fromCache = false))
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching/parsing feed", e)
            Result.failure(e)
        }
    }

    /**
     * Resolve relative URL against base URL
     * Properly handles:
     * - Absolute URLs (http://, https://) - returns as-is
     * - Absolute paths (/path) - resolves against origin (scheme + host)
     * - Relative paths (path) - resolves against base URL directory
     */
    fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            // If it's already an absolute URL, return it
            if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
                Log.d(TAG, "Absolute URL: $relativeUrl")
                return relativeUrl
            }

            // Use OkHttp's HttpUrl to properly resolve the URL
            val base = baseUrl.toHttpUrl()
            val resolved = base.resolve(relativeUrl)

            if (resolved != null) {
                Log.d(TAG, "Resolved: $baseUrl + $relativeUrl = ${resolved.toString()}")
                resolved.toString()
            } else {
                Log.w(TAG, "Could not resolve URL: $relativeUrl against $baseUrl")
                relativeUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving URL: $relativeUrl against $baseUrl", e)
            relativeUrl
        }
    }

    /**
     * Get cached feed if valid (not older than parent update)
     */
    private suspend fun getCachedFeedIfValid(url: String, parentUpdated: String?): OpdsFeed? {
        try {
            val cached = feedCacheDao.getCachedFeed(url) ?: return null

            // If no parent update timestamp provided, use the cached feed
            if (parentUpdated.isNullOrEmpty()) {
                Log.d(TAG, "Cache valid: no parent timestamp for $url")
                return parser.parse(cached.xmlContent.byteInputStream())
            }

            // If no cached timestamp, use the cache anyway
            if (cached.feedUpdated.isNullOrEmpty()) {
                Log.d(TAG, "Cache valid: no cached timestamp for $url")
                return parser.parse(cached.xmlContent.byteInputStream())
            }

            // If parent is newer than cache, invalidate cache
            if (isParentNewer(parentUpdated, cached.feedUpdated)) {
                Log.d(TAG, "Cache invalid: parent is newer for $url")
                feedCacheDao.deleteCachedFeed(url)
                return null
            }

            // Cache is valid
            Log.d(TAG, "Cache valid: using cached feed for $url")
            return parser.parse(cached.xmlContent.byteInputStream())
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cache for $url", e)
            return null
        }
    }

    /**
     * Cache feed XML with update timestamp
     */
    private suspend fun cacheFeed(url: String, xmlContent: String, feedUpdated: String?) {
        try {
            val cache = OpdsFeedCache(
                url = url,
                xmlContent = xmlContent,
                feedUpdated = feedUpdated
            )
            feedCacheDao.insertOrUpdate(cache)
            Log.d(TAG, "Cached feed: $url (updated: $feedUpdated)")
        } catch (e: Exception) {
            Log.e(TAG, "Error caching feed: $url", e)
        }
    }

    /**
     * Check if parent timestamp is newer than cached timestamp
     */
    private fun isParentNewer(parentUpdated: String, cachedUpdated: String): Boolean {
        try {
            val parentDate = parseDate(parentUpdated)
            val cachedDate = parseDate(cachedUpdated)

            if (parentDate != null && cachedDate != null) {
                return parentDate.after(cachedDate)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error comparing dates: parent=$parentUpdated, cached=$cachedUpdated", e)
        }

        // If we can't parse, assume cache is valid to avoid unnecessary refetches
        return false
    }

    /**
     * Parse date string in various OPDS formats
     */
    private fun parseDate(dateString: String): Date? {
        for (format in dateFormats) {
            try {
                return format.parse(dateString)
            } catch (e: Exception) {
                // Try next format
            }
        }
        Log.w(TAG, "Could not parse date: $dateString")
        return null
    }
}
