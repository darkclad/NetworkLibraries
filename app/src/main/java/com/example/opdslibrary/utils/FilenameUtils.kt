package com.example.opdslibrary.utils

import android.util.Log
import com.example.opdslibrary.data.OpdsEntry
import java.net.URLDecoder

/**
 * Utility functions for filename extraction and normalization
 * Used for matching OPDS catalog entries to local library books by filename only
 */
object FilenameUtils {
    private const val TAG = "FilenameUtils"

    /**
     * Generate the expected filename for an OPDS entry
     * Uses the same logic as download: entry title + format extension
     *
     * @param entry The OPDS entry
     * @param format The selected format (fb2, epub, etc.)
     * @return Expected filename (sanitized title + format)
     */
    fun generateExpectedFilename(entry: OpdsEntry, format: String): String {
        // Sanitize title for use as filename
        val sanitizedTitle = entry.title
            .replace(Regex("[/\\\\:*?\"<>|]"), "_") // Replace invalid filename chars
            .trim()

        val normalizedFormat = format.lowercase()
        val filename = "$sanitizedTitle.$normalizedFormat"

        Log.d(TAG, "generateExpectedFilename: title='${entry.title}', format='$format' → '$filename'")
        return filename.lowercase()
    }

    /**
     * Extract normalized filename from OPDS acquisition link URL
     * Strips path, query parameters, and URL-decodes the result
     * Returns lowercase for case-insensitive matching
     *
     * @param url The OPDS acquisition link URL
     * @return Normalized filename (lowercase, URL-decoded), or null if extraction fails or URL doesn't contain real filename
     *
     * Example: "http://server.com/books/book.fb2?id=123" → "book.fb2"
     * Example: "http://server.com/b/123/fb2" → null (just format code, not filename)
     */
    fun extractFilenameFromUrl(url: String): String? {
        try {
            Log.d(TAG, "extractFilenameFromUrl: URL = '$url'")

            // Extract the path component after last slash
            val pathComponent = url.substringAfterLast("/")
            Log.d(TAG, "  After last '/': '$pathComponent'")
            if (pathComponent.isEmpty()) return null

            // Remove query parameters and fragments
            val cleanPath = pathComponent.substringBefore("?").substringBefore("#")
            Log.d(TAG, "  After removing query/fragment: '$cleanPath'")
            if (cleanPath.isEmpty()) return null

            // URL decode (handles %20, %2F, etc.)
            val decoded = URLDecoder.decode(cleanPath, "UTF-8")
            Log.d(TAG, "  After URL decode: '$decoded'")

            // Check if this looks like just a format extension (no actual filename)
            // Common formats: fb2, epub, mobi, pdf, txt, html, rtf, etc.
            val formatOnlyPattern = Regex("^(fb2|epub|mobi|pdf|txt|html|rtf|azw3|djvu|doc|docx)(\\.zip)?$", RegexOption.IGNORE_CASE)
            if (formatOnlyPattern.matches(decoded)) {
                Log.w(TAG, "  ✗ REJECTED: '$decoded' is just a format code, not a real filename")
                return null
            }

            // Check if it has a file extension (at least one dot with extension)
            if (!decoded.contains(".")) {
                Log.w(TAG, "  ✗ REJECTED: '$decoded' has no file extension, likely not a filename")
                return null
            }

            // Normalize to lowercase for case-insensitive matching
            val result = decoded.lowercase()
            Log.d(TAG, "  ✓ Final result: '$result'")
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract filename from URL: $url", e)
            return null
        }
    }

    /**
     * Normalize a book's file path to just the filename for matching
     * URL-decodes first (to convert %2F to /), then extracts filename
     * Returns lowercase for case-insensitive matching
     *
     * @param filePath Full file path (may be SAF URI or regular file path)
     * @return Normalized filename (lowercase, URL-decoded)
     *
     * Example: "/storage/emulated/0/Books/book.fb2" → "book.fb2"
     * Example: "content://...%2Fbook.fb2" → "book.fb2"
     */
    fun getMimeType(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".fb2.zip") -> "application/zip"
            lower.endsWith(".fb2") -> "application/x-fictionbook+xml"
            lower.endsWith(".epub") -> "application/epub+zip"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".mobi") -> "application/x-mobipocket-ebook"
            lower.endsWith(".azw3") -> "application/vnd.amazon.ebook"
            lower.endsWith(".djvu") -> "image/vnd.djvu"
            lower.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /**
     * Check whether a normalized book filename matches a target filename.
     * Handles the common case where the on-disk file has an author/series prefix
     * prepended to the server-side filename, e.g.:
     *   disk:   "vinterkey_revizor-...-52.ceVWFA.859569.fb2.zip"
     *   target: "revizor-...-52.ceVWFA.859569.fb2.zip"
     * Both exact equality and endsWith (with any separator before the target) are accepted.
     */
    fun normalizedFilenameMatches(normalizedBookFilename: String, targetFilename: String): Boolean {
        if (targetFilename.isEmpty()) return false
        return normalizedBookFilename == targetFilename ||
               normalizedBookFilename.endsWith(targetFilename)
    }

    fun normalizeBookFilename(filePath: String): String {
        // URL decode first to convert %2F to / for proper path splitting
        val decoded = try {
            URLDecoder.decode(filePath, "UTF-8")
        } catch (e: Exception) {
            filePath
        }

        // Extract filename from path (handles both / and \ separators)
        val afterSlash = decoded.substringAfterLast("/")
        val filename = afterSlash.substringAfterLast("\\")

        return filename.lowercase()
    }
}
