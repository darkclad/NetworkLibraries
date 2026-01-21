package com.example.opdslibrary.library.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Parser for FB2 files inside ZIP archives (*.fb2.zip)
 */
class Fb2ZipParser : BaseBookMetadataParser() {

    companion object {
        private const val TAG = "Fb2ZipParser"
    }

    private val fb2Parser = Fb2Parser()

    override fun getSupportedExtensions(): List<String> = listOf("fb2.zip", "zip")

    override fun canParse(filename: String): Boolean {
        val lower = filename.lowercase()
        // Specifically handle .fb2.zip files
        // For generic .zip, we'd need to peek inside
        return lower.endsWith(".fb2.zip")
    }

    override suspend fun parse(inputStream: InputStream, filename: String): BookMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                parseFb2Zip(inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing FB2.ZIP: $filename", e)
                null
            }
        }
    }

    override suspend fun parse(file: File): BookMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                java.util.zip.ZipFile(file).use { zipFile ->
                    val fb2Entry = zipFile.entries().asSequence()
                        .firstOrNull { it.name.lowercase().endsWith(".fb2") }

                    if (fb2Entry != null) {
                        zipFile.getInputStream(fb2Entry).use { stream ->
                            fb2Parser.parse(stream, fb2Entry.name)
                        }
                    } else {
                        Log.w(TAG, "No FB2 file found in ZIP: ${file.name}")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing FB2.ZIP file: ${file.name}", e)
                null
            }
        }
    }

    override suspend fun parse(uri: Uri, context: Context): BookMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    parseFb2Zip(stream)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing FB2.ZIP from URI", e)
                null
            }
        }
    }

    private suspend fun parseFb2Zip(inputStream: InputStream): BookMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.lowercase().endsWith(".fb2")) {
                            // Found FB2 file - parse it without closing the stream
                            val result = fb2Parser.parse(NonClosingInputStream(zipStream), entry.name)
                            zipStream.closeEntry()
                            return@withContext result
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                    Log.w(TAG, "No FB2 file found in ZIP archive")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading ZIP stream", e)
                null
            }
        }
    }

    override suspend fun extractCoverFromStream(inputStream: InputStream, filename: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.lowercase().endsWith(".fb2")) {
                            val metadata = fb2Parser.parse(NonClosingInputStream(zipStream), entry.name)
                            zipStream.closeEntry()
                            return@withContext metadata?.coverData
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting cover from FB2.ZIP", e)
                null
            }
        }
    }

    override suspend fun extractCover(uri: Uri, context: Context): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    extractCoverFromStream(stream, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting cover from FB2.ZIP URI", e)
                null
            }
        }
    }

    /**
     * Wrapper that prevents closing the underlying stream
     * Used when reading from ZipInputStream
     */
    private class NonClosingInputStream(private val wrapped: InputStream) : InputStream() {
        override fun read(): Int = wrapped.read()
        override fun read(b: ByteArray): Int = wrapped.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = wrapped.read(b, off, len)
        override fun available(): Int = wrapped.available()
        override fun close() {
            // Don't close the underlying stream
        }
    }
}
