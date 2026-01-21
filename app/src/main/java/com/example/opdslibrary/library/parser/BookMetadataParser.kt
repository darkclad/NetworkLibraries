package com.example.opdslibrary.library.parser

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * Interface for book metadata parsers
 */
interface BookMetadataParser {

    /**
     * Check if this parser can handle the given file
     */
    fun canParse(file: File): Boolean

    /**
     * Check if this parser can handle the file at the given URI
     */
    fun canParse(uri: Uri, context: Context): Boolean

    /**
     * Check if this parser can handle a file with the given name
     */
    fun canParse(filename: String): Boolean

    /**
     * Parse metadata from a file
     * @return BookMetadata or null if parsing failed
     */
    suspend fun parse(file: File): BookMetadata?

    /**
     * Parse metadata from a URI
     * @return BookMetadata or null if parsing failed
     */
    suspend fun parse(uri: Uri, context: Context): BookMetadata?

    /**
     * Parse metadata from an input stream
     * @return BookMetadata or null if parsing failed
     */
    suspend fun parse(inputStream: InputStream, filename: String): BookMetadata?

    /**
     * Extract cover image from a file
     * @return true if cover was extracted successfully
     */
    suspend fun extractCover(file: File, outputPath: File): Boolean

    /**
     * Extract cover image from a URI
     * @return cover image data or null
     */
    suspend fun extractCover(uri: Uri, context: Context): ByteArray?

    /**
     * Get supported file extensions for this parser
     */
    fun getSupportedExtensions(): List<String>
}

/**
 * Base class with common functionality for parsers
 */
abstract class BaseBookMetadataParser : BookMetadataParser {

    override fun canParse(file: File): Boolean {
        return canParse(file.name)
    }

    override fun canParse(uri: Uri, context: Context): Boolean {
        val filename = getFilenameFromUri(uri, context)
        return canParse(filename)
    }

    override fun canParse(filename: String): Boolean {
        val lower = filename.lowercase()
        return getSupportedExtensions().any { ext ->
            lower.endsWith(".$ext")
        }
    }

    override suspend fun parse(file: File): BookMetadata? {
        return try {
            file.inputStream().use { stream ->
                parse(stream, file.name)
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun parse(uri: Uri, context: Context): BookMetadata? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val filename = getFilenameFromUri(uri, context)
                parse(stream, filename)
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun extractCover(file: File, outputPath: File): Boolean {
        return try {
            val coverData = file.inputStream().use { stream ->
                extractCoverFromStream(stream, file.name)
            }
            if (coverData != null) {
                outputPath.writeBytes(coverData)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun extractCover(uri: Uri, context: Context): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val filename = getFilenameFromUri(uri, context)
                extractCoverFromStream(stream, filename)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract cover from input stream - to be implemented by subclasses
     */
    protected open suspend fun extractCoverFromStream(inputStream: InputStream, filename: String): ByteArray? {
        return null
    }

    /**
     * Get filename from URI
     */
    protected fun getFilenameFromUri(uri: Uri, context: Context): String {
        // Try to get display name from content resolver
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        }
        // Fallback to last path segment
        return uri.lastPathSegment ?: "unknown"
    }
}
