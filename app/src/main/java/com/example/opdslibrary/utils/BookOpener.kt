package com.example.opdslibrary.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Utility class for opening books with the preferred reader app
 */
object BookOpener {

    private const val TAG = "BookOpener"
    private const val CACHE_DIR = "books"

    /**
     * Opens a book file with the specified reader app, or shows system picker if none specified.
     *
     * @param context Android context
     * @param filePath Path to the book file (can be content:// URI or regular path)
     * @param preferredPackage Package name of preferred reader app (null for system picker)
     * @param onError Optional callback when opening fails
     */
    fun openBook(
        context: Context,
        filePath: String,
        preferredPackage: String?,
        onError: ((String) -> Unit)? = null
    ) {
        Log.d(TAG, "=== openBook called ===")
        Log.d(TAG, "  filePath: $filePath")
        Log.d(TAG, "  preferredPackage: $preferredPackage")
        Log.d(TAG, "  isContentUri: ${filePath.startsWith("content://")}")

        try {
            Log.d(TAG, "Getting shareable URI...")
            val fileUri = getShareableUri(context, filePath)
            Log.d(TAG, "  shareableUri: $fileUri")

            val mimeType = getMimeTypeFromExtension(filePath)
            Log.d(TAG, "  mimeType: $mimeType")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (preferredPackage != null) {
                // Try to open with preferred app
                intent.setPackage(preferredPackage)
                try {
                    context.startActivity(intent)
                    Log.d(TAG, "Opened book with preferred reader: $preferredPackage")
                    return
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "Preferred reader not found, falling back to system picker: $preferredPackage")
                    // Preferred app not found, fall back to system picker
                    intent.setPackage(null)
                }
            }

            // Use system app picker (allows "Always" / "Just once" options)
            context.startActivity(intent)
            Log.d(TAG, "Opened book with system picker")

        } catch (e: Exception) {
            Log.e(TAG, "=== EXCEPTION in openBook ===")
            Log.e(TAG, "  filePath: $filePath")
            Log.e(TAG, "  exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "  exception message: ${e.message}")
            Log.e(TAG, "  stack trace:", e)

            val errorMessage = when (e) {
                is ActivityNotFoundException -> "No app found to open this book"
                is SecurityException -> "Permission denied to access file"
                is IllegalArgumentException -> "File path not supported: ${e.message}"
                else -> "Failed to open book: ${e.message}"
            }
            Log.e(TAG, "  user error message: $errorMessage")

            onError?.invoke(errorMessage)
                ?: Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Get a shareable URI for the file.
     * For SAF content:// URIs, copies to cache and returns FileProvider URI.
     * For file:// URIs and regular file paths, returns FileProvider URI directly.
     */
    private fun getShareableUri(context: Context, filePath: String): Uri {
        Log.d(TAG, "getShareableUri: filePath=$filePath")

        return when {
            filePath.startsWith("content://") -> {
                Log.d(TAG, "  -> SAF content URI detected, copying to cache")
                copyToCache(context, Uri.parse(filePath), filePath)
            }
            filePath.startsWith("file://") -> {
                // Strip file:// scheme to get actual path
                val actualPath = Uri.parse(filePath).path ?: filePath.removePrefix("file://")
                Log.d(TAG, "  -> file:// URI detected, extracted path: $actualPath")
                getFileProviderUri(context, actualPath)
            }
            else -> {
                Log.d(TAG, "  -> Regular file path detected")
                getFileProviderUri(context, filePath)
            }
        }
    }

    /**
     * Get FileProvider URI for a file path.
     */
    private fun getFileProviderUri(context: Context, filePath: String): Uri {
        val file = File(filePath)
        Log.d(TAG, "  -> File exists: ${file.exists()}, canRead: ${file.canRead()}, length: ${file.length()}")
        Log.d(TAG, "  -> Absolute path: ${file.absolutePath}")

        try {
            val authority = "${context.packageName}.provider"
            Log.d(TAG, "  -> FileProvider authority: $authority")
            val uri = FileProvider.getUriForFile(context, authority, file)
            Log.d(TAG, "  -> FileProvider URI: $uri")
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "  -> FileProvider.getUriForFile FAILED", e)
            throw e
        }
    }

    /**
     * Copy a content:// URI file to cache and return a FileProvider URI.
     * This is necessary because SAF URIs can't be shared with other apps directly.
     */
    private fun copyToCache(context: Context, sourceUri: Uri, originalPath: String): Uri {
        Log.d(TAG, "copyToCache: sourceUri=$sourceUri")

        // Extract filename from the original path or URI
        val filename = extractFilename(originalPath, sourceUri)
        Log.d(TAG, "  -> extracted filename: $filename")

        // Create cache directory
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        Log.d(TAG, "  -> cacheDir: ${cacheDir.absolutePath}")
        if (!cacheDir.exists()) {
            val created = cacheDir.mkdirs()
            Log.d(TAG, "  -> created cacheDir: $created")
        }

        // Create target file in cache
        val cacheFile = File(cacheDir, filename)
        Log.d(TAG, "  -> target cacheFile: ${cacheFile.absolutePath}")

        // Copy content to cache
        Log.d(TAG, "  -> opening input stream from sourceUri...")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            Log.d(TAG, "  -> input stream opened, copying to cache file...")
            cacheFile.outputStream().use { output ->
                val bytesCopied = input.copyTo(output)
                Log.d(TAG, "  -> copied $bytesCopied bytes")
            }
        } ?: run {
            Log.e(TAG, "  -> FAILED: Cannot open input stream for sourceUri")
            throw IllegalStateException("Cannot read source file: $sourceUri")
        }

        Log.d(TAG, "  -> cache file size: ${cacheFile.length()} bytes, exists: ${cacheFile.exists()}")

        // Return FileProvider URI
        val authority = "${context.packageName}.provider"
        Log.d(TAG, "  -> getting FileProvider URI with authority: $authority")
        val uri = FileProvider.getUriForFile(context, authority, cacheFile)
        Log.d(TAG, "  -> FileProvider URI: $uri")
        return uri
    }

    /**
     * Extract filename from path or URI
     */
    private fun extractFilename(path: String, uri: Uri): String {
        // Try to get filename from the path
        val pathFilename = path.substringAfterLast('/')
            .substringAfterLast("%2F") // URL-encoded slash

        if (pathFilename.isNotEmpty() && pathFilename.contains('.')) {
            // URL decode if needed
            return try {
                java.net.URLDecoder.decode(pathFilename, "UTF-8")
            } catch (e: Exception) {
                pathFilename
            }
        }

        // Fallback: generate a name from hash
        return "book_${path.hashCode().toString(16)}.book"
    }

    /**
     * Gets MIME type based on file extension
     */
    private fun getMimeTypeFromExtension(filePath: String): String {
        val lowerPath = filePath.lowercase()

        // Check for compound extensions first
        if (lowerPath.endsWith(".fb2.zip")) {
            return "application/x-fictionbook+xml"
        }

        val extension = lowerPath.substringAfterLast('.')
        return when (extension) {
            "epub" -> "application/epub+zip"
            "pdf" -> "application/pdf"
            "fb2" -> "application/x-fictionbook+xml"
            "mobi", "azw", "azw3" -> "application/x-mobipocket-ebook"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "rtf" -> "application/rtf"
            "zip" -> "application/zip"
            "djvu", "djv" -> "image/vnd.djvu"
            "cbz" -> "application/vnd.comicbook+zip"
            "cbr" -> "application/vnd.comicbook-rar"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }
    }

    /**
     * Clear cached book files (call periodically to free space)
     */
    fun clearCache(context: Context) {
        val cacheDir = File(context.cacheDir, CACHE_DIR)
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            Log.d(TAG, "Book cache cleared")
        }
    }
}
