package com.example.opdslibrary.library.parser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Parser for PDF format
 * Extracts basic metadata from PDF info dictionary
 * Falls back to filename parsing if metadata is missing
 */
class PdfParser : BaseBookMetadataParser() {

    companion object {
        private const val TAG = "PdfParser"
    }

    override fun getSupportedExtensions(): List<String> = listOf("pdf")

    override suspend fun parse(inputStream: InputStream, filename: String): BookMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                // Try to extract metadata from PDF
                val metadata = extractPdfMetadata(inputStream)
                if (metadata != null && metadata.title.isNotBlank()) {
                    return@withContext metadata
                }

                // Fall back to filename parsing
                parseFromFilename(filename)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing PDF: $filename", e)
                parseFromFilename(filename)
            }
        }
    }

    private fun extractPdfMetadata(inputStream: InputStream): BookMetadata? {
        // Read first portion of PDF to find info dictionary
        val buffer = ByteArray(8192)
        val bytesRead = inputStream.read(buffer)
        if (bytesRead <= 0) return null

        val content = String(buffer, 0, bytesRead, Charsets.ISO_8859_1)

        // Check PDF signature
        if (!content.startsWith("%PDF")) {
            return null
        }

        // Look for trailer and Info dictionary reference
        // This is a simplified parser - full PDF parsing would require a library
        var title: String? = null
        var author: String? = null
        var subject: String? = null
        var creator: String? = null

        // Try to find /Title
        title = extractPdfString(content, "/Title")
        author = extractPdfString(content, "/Author")
        subject = extractPdfString(content, "/Subject")
        creator = extractPdfString(content, "/Creator")

        if (title.isNullOrBlank()) {
            return null
        }

        val authors = if (!author.isNullOrBlank()) {
            listOf(AuthorInfo.fromFullName(author))
        } else {
            emptyList()
        }

        return BookMetadata(
            title = title,
            authors = authors.ifEmpty { listOf(AuthorInfo(nickname = "Unknown")) },
            description = subject,
            publisher = creator
        )
    }

    private fun extractPdfString(content: String, key: String): String? {
        val keyIndex = content.indexOf(key)
        if (keyIndex < 0) return null

        val afterKey = content.substring(keyIndex + key.length).trimStart()

        // PDF strings can be in parentheses () or hex <>
        return when {
            afterKey.startsWith("(") -> {
                // Literal string
                val endIndex = findClosingParen(afterKey)
                if (endIndex > 0) {
                    decodePdfString(afterKey.substring(1, endIndex))
                } else null
            }
            afterKey.startsWith("<") -> {
                // Hex string
                val endIndex = afterKey.indexOf(">")
                if (endIndex > 0) {
                    decodeHexString(afterKey.substring(1, endIndex))
                } else null
            }
            else -> null
        }
    }

    private fun findClosingParen(s: String): Int {
        var depth = 0
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
                '\\' -> i++ // Skip escaped character
            }
            i++
        }
        return -1
    }

    private fun decodePdfString(s: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    '(' -> result.append('(')
                    ')' -> result.append(')')
                    '\\' -> result.append('\\')
                    else -> result.append(s[i + 1])
                }
                i += 2
            } else {
                result.append(s[i])
                i++
            }
        }
        return result.toString()
    }

    private fun decodeHexString(hex: String): String {
        val cleanHex = hex.replace("\\s".toRegex(), "")
        val bytes = ByteArray(cleanHex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return String(bytes, Charsets.UTF_16BE)
    }

    private fun parseFromFilename(filename: String): BookMetadata {
        // Remove extension
        val name = filename
            .removeSuffix(".pdf")
            .replace("_", " ")
            .replace("-", " ")
            .trim()

        // Try to detect author - title pattern
        val parts = name.split(" - ", limit = 2)
        return if (parts.size == 2) {
            BookMetadata(
                title = parts[1].trim(),
                authors = listOf(AuthorInfo.fromFullName(parts[0].trim()))
            )
        } else {
            BookMetadata(
                title = name,
                authors = listOf(AuthorInfo(nickname = "Unknown"))
            )
        }
    }

    override suspend fun extractCoverFromStream(inputStream: InputStream, filename: String): ByteArray? {
        // PDF cover extraction requires a full PDF library
        // Return null - we don't support cover extraction for PDFs without additional dependencies
        return null
    }
}
