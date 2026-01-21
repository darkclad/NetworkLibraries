package com.example.opdslibrary.library.parser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for MOBI/PRC/AZW3 formats
 * Extracts metadata from EXTH header
 */
class MobiParser : BaseBookMetadataParser() {

    companion object {
        private const val TAG = "MobiParser"

        // EXTH record types
        private const val EXTH_AUTHOR = 100
        private const val EXTH_PUBLISHER = 101
        private const val EXTH_DESCRIPTION = 103
        private const val EXTH_ISBN = 104
        private const val EXTH_SUBJECT = 105
        private const val EXTH_PUBLISHING_DATE = 106
        private const val EXTH_CONTRIBUTOR = 108
        private const val EXTH_LANGUAGE = 524
        private const val EXTH_TITLE = 503

        // Magic identifiers
        private val MOBI_MAGIC = byteArrayOf('M'.code.toByte(), 'O'.code.toByte(), 'B'.code.toByte(), 'I'.code.toByte())
    }

    override fun getSupportedExtensions(): List<String> = listOf("mobi", "prc", "azw", "azw3")

    override suspend fun parse(inputStream: InputStream, filename: String): BookMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                parseMobi(inputStream, filename)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing MOBI: $filename", e)
                parseFromFilename(filename)
            }
        }
    }

    private fun parseMobi(inputStream: InputStream, filename: String): BookMetadata? {
        // Read entire file (MOBI files are usually small enough)
        val data = inputStream.readBytes()
        if (data.size < 78) {
            Log.w(TAG, "File too small for MOBI format")
            return parseFromFilename(filename)
        }

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Parse PalmDOC header (first 78 bytes)
        // Name is at offset 0, 32 bytes
        val palmName = String(data.sliceArray(0..31), Charsets.ISO_8859_1).trim('\u0000')

        // Number of records at offset 76
        buffer.position(76)
        val numRecords = buffer.short.toInt() and 0xFFFF

        if (numRecords == 0) {
            return parseFromFilename(filename)
        }

        // Record info list starts at offset 78
        // Each record info is 8 bytes: offset (4) + attributes (1) + uniqueID (3)
        buffer.position(78)
        val record0Offset = buffer.int

        // Go to record 0
        if (record0Offset >= data.size) {
            return parseFromFilename(filename)
        }

        buffer.position(record0Offset)

        // Check for PalmDOC header (first 16 bytes of record 0)
        // Compression (2) + unused (2) + text length (4) + record count (2) + record size (2) + encryption (2) + unknown (2)
        buffer.position(record0Offset + 16)

        // Check for MOBI header
        if (record0Offset + 20 + 4 > data.size) {
            return parseFromFilename(filename)
        }

        // MOBI identifier should be at offset 16 in record 0
        val mobiCheck = data.sliceArray(record0Offset + 16 until record0Offset + 20)
        if (!mobiCheck.contentEquals(MOBI_MAGIC)) {
            Log.w(TAG, "MOBI magic not found")
            return parseFromFilename(filename)
        }

        // Parse MOBI header
        buffer.position(record0Offset + 20)
        val headerLength = buffer.int

        // Full name offset and length at MOBI header offset 84 and 88
        if (record0Offset + 16 + 92 > data.size) {
            return parseFromFilename(filename)
        }

        buffer.position(record0Offset + 16 + 84)
        val fullNameOffset = buffer.int
        val fullNameLength = buffer.int

        var title: String? = null
        if (fullNameOffset > 0 && fullNameLength > 0 &&
            record0Offset + fullNameOffset + fullNameLength <= data.size) {
            title = String(
                data.sliceArray(record0Offset + fullNameOffset until record0Offset + fullNameOffset + fullNameLength),
                Charsets.UTF_8
            ).trim('\u0000')
        }

        // Check for EXTH header
        // EXTH flag is at MOBI header offset 128, bit 6
        buffer.position(record0Offset + 16 + 128)
        val exthFlags = buffer.int
        val hasExth = (exthFlags and 0x40) != 0

        var author: String? = null
        var publisher: String? = null
        var description: String? = null
        var isbn: String? = null
        val subjects = mutableListOf<String>()
        var publishDate: String? = null
        var language: String? = null

        if (hasExth) {
            // EXTH header follows MOBI header
            val exthOffset = record0Offset + 16 + headerLength

            if (exthOffset + 12 <= data.size) {
                // Check EXTH identifier
                val exthId = String(data.sliceArray(exthOffset until exthOffset + 4), Charsets.ISO_8859_1)
                if (exthId == "EXTH") {
                    buffer.position(exthOffset + 4)
                    val exthHeaderLength = buffer.int
                    val recordCount = buffer.int

                    var offset = exthOffset + 12
                    repeat(recordCount) {
                        if (offset + 8 > data.size) return@repeat

                        buffer.position(offset)
                        val recordType = buffer.int
                        val recordLength = buffer.int

                        val dataLength = recordLength - 8
                        if (dataLength > 0 && offset + 8 + dataLength <= data.size) {
                            val recordData = String(
                                data.sliceArray(offset + 8 until offset + 8 + dataLength),
                                Charsets.UTF_8
                            ).trim('\u0000')

                            when (recordType) {
                                EXTH_AUTHOR -> author = recordData
                                EXTH_PUBLISHER -> publisher = recordData
                                EXTH_DESCRIPTION -> description = recordData
                                EXTH_ISBN -> isbn = recordData
                                EXTH_SUBJECT -> subjects.add(recordData)
                                EXTH_PUBLISHING_DATE -> publishDate = recordData
                                EXTH_TITLE -> if (title.isNullOrBlank()) title = recordData
                                EXTH_LANGUAGE -> language = recordData
                            }
                        }

                        offset += recordLength
                    }
                }
            }
        }

        // Fall back to Palm name if no title found
        if (title.isNullOrBlank()) {
            title = palmName
        }

        if (title.isNullOrBlank()) {
            return parseFromFilename(filename)
        }

        val authors = if (!author.isNullOrBlank()) {
            listOf(AuthorInfo.fromFullName(author))
        } else {
            listOf(AuthorInfo(nickname = "Unknown"))
        }

        val year = publishDate?.let { extractYear(it) }

        return BookMetadata(
            title = title,
            authors = authors,
            genres = subjects,
            language = language,
            year = year,
            isbn = isbn,
            description = description,
            publisher = publisher
        )
    }

    private fun extractYear(dateStr: String): Int? {
        val yearRegex = Regex("(19|20)\\d{2}")
        return yearRegex.find(dateStr)?.value?.toIntOrNull()
    }

    private fun parseFromFilename(filename: String): BookMetadata {
        // Remove extension
        val name = filename
            .removeSuffix(".mobi")
            .removeSuffix(".prc")
            .removeSuffix(".azw")
            .removeSuffix(".azw3")
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
        // MOBI cover extraction is complex - requires parsing EXTH for cover offset
        // Return null for now - can be implemented with additional work
        return null
    }
}
