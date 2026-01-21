package com.example.opdslibrary.library.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Factory for creating appropriate book metadata parsers
 */
object BookParserFactory {

    private const val TAG = "BookParserFactory"

    // Available parsers in priority order
    private val parsers: List<BookMetadataParser> = listOf(
        Fb2ZipParser(),  // FB2.zip first (more specific)
        Fb2Parser(),
        EpubParser(),
        MobiParser(),
        PdfParser()
    )

    /**
     * Get list of all supported file extensions
     */
    fun getSupportedExtensions(): List<String> {
        return parsers.flatMap { it.getSupportedExtensions() }.distinct()
    }

    /**
     * Check if a filename is a supported book format
     */
    fun isSupported(filename: String): Boolean {
        val lower = filename.lowercase()
        return getSupportedExtensions().any { ext ->
            lower.endsWith(".$ext")
        }
    }

    /**
     * Get appropriate parser for a file
     */
    fun getParser(file: File): BookMetadataParser? {
        return parsers.firstOrNull { it.canParse(file) }
    }

    /**
     * Get appropriate parser for a filename
     */
    fun getParser(filename: String): BookMetadataParser? {
        return parsers.firstOrNull { it.canParse(filename) }
    }

    /**
     * Get appropriate parser for a URI
     */
    fun getParser(uri: Uri, context: Context): BookMetadataParser? {
        return parsers.firstOrNull { it.canParse(uri, context) }
    }

    /**
     * Parse a book file and return metadata
     * Falls back to filename parsing if content parsing fails
     */
    suspend fun parseBook(file: File): BookMetadata {
        val parser = getParser(file)
        if (parser != null) {
            val metadata = parser.parse(file)
            if (metadata != null) {
                Log.d(TAG, "Parsed ${file.name} using ${parser.javaClass.simpleName}")
                return metadata
            }
        }

        Log.d(TAG, "Falling back to filename parsing for ${file.name}")
        return FilenameParser.parse(file.name)
    }

    /**
     * Parse a book from URI and return metadata
     * Falls back to filename parsing if content parsing fails
     */
    suspend fun parseBook(uri: Uri, context: Context): BookMetadata {
        val filename = getFilenameFromUri(uri, context)
        val parser = getParser(uri, context)

        if (parser != null) {
            val metadata = parser.parse(uri, context)
            if (metadata != null) {
                Log.d(TAG, "Parsed $filename using ${parser.javaClass.simpleName}")
                return metadata
            }
        }

        Log.d(TAG, "Falling back to filename parsing for $filename")
        return FilenameParser.parse(filename)
    }

    /**
     * Extract cover from a book file
     * Returns null if cover extraction is not supported or fails
     */
    suspend fun extractCover(file: File): ByteArray? {
        val parser = getParser(file) ?: return null
        return try {
            // Create a temp file for cover
            val tempFile = File.createTempFile("cover", ".jpg")
            if (parser.extractCover(file, tempFile)) {
                tempFile.readBytes().also { tempFile.delete() }
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting cover from ${file.name}", e)
            null
        }
    }

    /**
     * Extract cover from a book URI
     * Returns null if cover extraction is not supported or fails
     */
    suspend fun extractCover(uri: Uri, context: Context): ByteArray? {
        val parser = getParser(uri, context) ?: return null
        return try {
            parser.extractCover(uri, context)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting cover from URI", e)
            null
        }
    }

    /**
     * Get the format/type of a book file
     */
    fun getBookFormat(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".fb2.zip") -> "FB2"
            lower.endsWith(".fb2") -> "FB2"
            lower.endsWith(".epub") -> "EPUB"
            lower.endsWith(".pdf") -> "PDF"
            lower.endsWith(".mobi") -> "MOBI"
            lower.endsWith(".azw3") -> "AZW3"
            lower.endsWith(".azw") -> "AZW"
            lower.endsWith(".prc") -> "PRC"
            else -> "Unknown"
        }
    }

    /**
     * Get metadata source identifier for a filename
     */
    fun getMetadataSource(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".fb2.zip") -> "fb2"
            lower.endsWith(".fb2") -> "fb2"
            lower.endsWith(".epub") -> "epub"
            lower.endsWith(".pdf") -> "pdf"
            lower.endsWith(".mobi") -> "mobi"
            lower.endsWith(".azw3") -> "mobi"
            lower.endsWith(".azw") -> "mobi"
            lower.endsWith(".prc") -> "mobi"
            else -> "filename"
        }
    }

    private fun getFilenameFromUri(uri: Uri, context: Context): String {
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
        return uri.lastPathSegment ?: "unknown"
    }
}
