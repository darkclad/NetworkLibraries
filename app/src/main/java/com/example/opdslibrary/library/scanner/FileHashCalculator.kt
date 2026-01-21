package com.example.opdslibrary.library.scanner

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Utility for calculating file hashes for deduplication
 */
object FileHashCalculator {

    private const val ALGORITHM = "SHA-256"
    private const val BUFFER_SIZE = 8192

    /**
     * Calculate SHA-256 hash of a file
     */
    suspend fun calculateHash(file: File): String = withContext(Dispatchers.IO) {
        file.inputStream().use { stream ->
            calculateHash(stream)
        }
    }

    /**
     * Calculate SHA-256 hash from a URI
     */
    suspend fun calculateHash(uri: Uri, context: Context): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            calculateHash(stream)
        } ?: ""
    }

    /**
     * Calculate SHA-256 hash from an input stream
     */
    suspend fun calculateHash(inputStream: InputStream): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }

        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Calculate hash for first N bytes (faster for large files)
     * Useful for quick duplicate detection
     */
    suspend fun calculatePartialHash(file: File, maxBytes: Long = 1024 * 1024): String =
        withContext(Dispatchers.IO) {
            file.inputStream().use { stream ->
                calculatePartialHash(stream, maxBytes)
            }
        }

    /**
     * Calculate hash for first N bytes from URI
     */
    suspend fun calculatePartialHash(uri: Uri, context: Context, maxBytes: Long = 1024 * 1024): String =
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                calculatePartialHash(stream, maxBytes)
            } ?: ""
        }

    private suspend fun calculatePartialHash(inputStream: InputStream, maxBytes: Long): String =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance(ALGORITHM)
            val buffer = ByteArray(BUFFER_SIZE)
            var totalRead = 0L

            while (totalRead < maxBytes) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                val toProcess = minOf(bytesRead.toLong(), maxBytes - totalRead).toInt()
                digest.update(buffer, 0, toProcess)
                totalRead += toProcess
            }

            digest.digest().joinToString("") { "%02x".format(it) }
        }
}
