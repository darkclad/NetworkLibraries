package com.example.opdslibrary.library.parser

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Parser for FB2 (FictionBook) format
 */
class Fb2Parser : BaseBookMetadataParser() {

    companion object {
        private const val TAG = "Fb2Parser"

        // FB2 XML namespaces
        private const val NS_FB2 = "http://www.gribuser.ru/xml/fictionbook/2.0"
        private const val NS_XLINK = "http://www.w3.org/1999/xlink"

        // Debug: crash counter for development
        @Volatile
        private var parseErrorCount = 0

        private const val MAX_ERRORS_BEFORE_CRASH = 3
    }

    override fun getSupportedExtensions(): List<String> = listOf("fb2")

    override suspend fun parse(inputStream: InputStream, filename: String): BookMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                parseFb2(inputStream, filename)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing FB2: $filename", e)

                // DEV: Track parse errors and crash on 3rd error for investigation
                parseErrorCount++
                Log.e(TAG, "Parse error count: $parseErrorCount / $MAX_ERRORS_BEFORE_CRASH")

                if (parseErrorCount >= MAX_ERRORS_BEFORE_CRASH) {
                    Log.e(TAG, "!!! MAX PARSE ERRORS REACHED - CRASHING FOR INVESTIGATION !!!")
                    throw RuntimeException("FB2 Parser: $MAX_ERRORS_BEFORE_CRASH parse failures reached. Last file: $filename", e)
                }

                null
            }
        }
    }

    private fun parseFb2(inputStream: InputStream, filename: String = ""): BookMetadata? {
        // Read all bytes and detect/fix encoding
        val (correctedContent, detectedEncoding) = EncodingDetector.readWithEncodingDetection(inputStream)

        Log.d(TAG, "=== FB2 PARSING ($filename) ===")
        Log.d(TAG, "Detected encoding: $detectedEncoding")

        // Log the header for debugging
        val header = correctedContent.take(1500)
        Log.d(TAG, "=== FB2 HEADER ===")
        Log.d(TAG, header)
        Log.d(TAG, "=== END FB2 HEADER ===")

        val factory = XmlPullParserFactory.newInstance()
        // Disable namespace awareness to handle FB2 files with undeclared prefixes (like l:href)
        // Many FB2 files use l:href without declaring the 'l' namespace, which causes
        // "Undefined Prefix" errors when namespace-aware parsing is enabled
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()

        // Use the encoding-corrected content as UTF-8
        val correctedStream = ByteArrayInputStream(correctedContent.toByteArray(Charsets.UTF_8))
        parser.setInput(correctedStream, "UTF-8")

        var title: String? = null
        val authors = mutableListOf<AuthorInfo>()
        val translators = mutableListOf<AuthorInfo>()
        var seriesName: String? = null
        var seriesNumber: Float? = null
        val genres = mutableListOf<String>()
        var language: String? = null
        var year: Int? = null
        var description: String? = null
        var coverData: ByteArray? = null
        var coverId: String? = null

        // Track binary data for cover extraction
        val binaries = mutableMapOf<String, ByteArray>()

        var inTitleInfo = false
        var inDocumentInfo = false
        var inAuthor = false
        var inTranslator = false
        var inSequence = false
        var inAnnotation = false
        var currentAuthorFirstName: String? = null
        var currentAuthorMiddleName: String? = null
        var currentAuthorLastName: String? = null
        var currentAuthorNickname: String? = null
        var currentBinaryId: String? = null
        var annotationBuilder = StringBuilder()

        // Track current tag for TEXT events
        var currentTag: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tagName = parser.name
                    currentTag = tagName

                    when (tagName) {
                        "title-info" -> inTitleInfo = true
                        "document-info" -> inDocumentInfo = true
                        "author" -> {
                            if (inTitleInfo && !inDocumentInfo) {
                                inAuthor = true
                                currentAuthorFirstName = null
                                currentAuthorMiddleName = null
                                currentAuthorLastName = null
                                currentAuthorNickname = null
                            }
                        }
                        "translator" -> {
                            if (inTitleInfo) {
                                inTranslator = true
                                currentAuthorFirstName = null
                                currentAuthorMiddleName = null
                                currentAuthorLastName = null
                                currentAuthorNickname = null
                            }
                        }
                        "sequence" -> {
                            if (inTitleInfo) {
                                inSequence = true
                                seriesName = parser.getAttributeValue(null, "name")
                                val numStr = parser.getAttributeValue(null, "number")
                                seriesNumber = numStr?.toFloatOrNull()
                            }
                        }
                        "annotation" -> {
                            if (inTitleInfo) {
                                inAnnotation = true
                                annotationBuilder = StringBuilder()
                            }
                        }
                        "coverpage" -> {
                            // Look for image reference
                            // Format: <image l:href="#cover.jpg"/>
                        }
                        "image" -> {
                            if (coverId == null) {
                                // Get cover image reference
                                // Try various attribute formats used in FB2 files:
                                // - xlink:href (standard)
                                // - l:href (common in Russian FB2s)
                                // - href (plain)
                                val href = getImageHref(parser)
                                if (href != null) {
                                    coverId = href.removePrefix("#")
                                }
                            }
                        }
                        "binary" -> {
                            currentBinaryId = parser.getAttributeValue(null, "id")
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        when {
                            // Check annotation first - it can be inside title-info
                            inAnnotation -> {
                                // Collect annotation text from any nested tags (p, strong, etc.)
                                if (annotationBuilder.isNotEmpty()) {
                                    annotationBuilder.append(" ")
                                }
                                annotationBuilder.append(text)
                            }
                            inAuthor -> {
                                when (currentTag) {
                                    "first-name" -> currentAuthorFirstName = text
                                    "middle-name" -> currentAuthorMiddleName = text
                                    "last-name" -> currentAuthorLastName = text
                                    "nickname" -> currentAuthorNickname = text
                                }
                            }
                            inTranslator -> {
                                when (currentTag) {
                                    "first-name" -> currentAuthorFirstName = text
                                    "middle-name" -> currentAuthorMiddleName = text
                                    "last-name" -> currentAuthorLastName = text
                                    "nickname" -> currentAuthorNickname = text
                                }
                            }
                            inTitleInfo && !inDocumentInfo -> {
                                when (currentTag) {
                                    "book-title" -> title = text
                                    "genre" -> genres.add(text)
                                    "lang" -> language = text
                                    "year" -> year = text.toIntOrNull()
                                }
                            }
                            currentBinaryId != null -> {
                                // Decode base64 binary data
                                try {
                                    val decoded = Base64.decode(text, Base64.DEFAULT)
                                    binaries[currentBinaryId!!] = decoded
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to decode binary: $currentBinaryId")
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tagName = parser.name

                    when (tagName) {
                        "title-info" -> inTitleInfo = false
                        "document-info" -> inDocumentInfo = false
                        "author" -> {
                            if (inAuthor) {
                                val author = AuthorInfo(
                                    firstName = currentAuthorFirstName,
                                    middleName = currentAuthorMiddleName,
                                    lastName = currentAuthorLastName,
                                    nickname = currentAuthorNickname,
                                    role = AuthorInfo.ROLE_AUTHOR
                                )
                                if (author.firstName != null || author.lastName != null || author.nickname != null) {
                                    authors.add(author)
                                }
                                inAuthor = false
                            }
                        }
                        "translator" -> {
                            if (inTranslator) {
                                val translator = AuthorInfo(
                                    firstName = currentAuthorFirstName,
                                    middleName = currentAuthorMiddleName,
                                    lastName = currentAuthorLastName,
                                    nickname = currentAuthorNickname,
                                    role = AuthorInfo.ROLE_TRANSLATOR
                                )
                                if (translator.firstName != null || translator.lastName != null || translator.nickname != null) {
                                    translators.add(translator)
                                }
                                inTranslator = false
                            }
                        }
                        "sequence" -> inSequence = false
                        "annotation" -> {
                            if (inAnnotation) {
                                description = annotationBuilder.toString().trim()
                                    .replace("\\s+".toRegex(), " ")
                                inAnnotation = false
                            }
                        }
                        "binary" -> {
                            currentBinaryId = null
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        // Get cover image data
        if (coverId != null && binaries.containsKey(coverId)) {
            coverData = binaries[coverId]
        }

        // Validate we have at least a title
        if (title.isNullOrBlank()) {
            Log.w(TAG, "No title found in FB2")
            return null
        }

        // Debug: Log extracted metadata
        Log.d(TAG, "=== FB2 METADATA EXTRACTED ===")
        Log.d(TAG, "Title: $title")
        Log.d(TAG, "Authors: ${authors.map { "${it.firstName} ${it.lastName} (${it.nickname})" }}")
        Log.d(TAG, "Genres: $genres")
        Log.d(TAG, "Series: $seriesName #$seriesNumber")
        Log.d(TAG, "Language: $language, Year: $year")
        Log.d(TAG, "=== END FB2 METADATA ===")

        // Combine authors and translators
        val allAuthors = authors + translators

        return BookMetadata(
            title = title,
            authors = allAuthors.ifEmpty { listOf(AuthorInfo(nickname = "Unknown")) },
            series = seriesName?.let { SeriesInfo(it, seriesNumber) },
            genres = genres,
            language = language,
            year = year,
            description = description,
            coverData = coverData
        )
    }

    /**
     * Get image href from various attribute formats used in FB2 files
     * Handles: xlink:href, l:href, href
     */
    private fun getImageHref(parser: XmlPullParser): String? {
        // Iterate through all attributes to find href
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i)
            // Match any attribute ending with "href" (handles xlink:href, l:href, href)
            if (attrName.endsWith("href", ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    override suspend fun extractCoverFromStream(inputStream: InputStream, filename: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val metadata = parseFb2(inputStream, filename)
                metadata?.coverData
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting cover from FB2: $filename", e)
                null
            }
        }
    }
}
