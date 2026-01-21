package com.example.opdslibrary.library.parser

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Parser for EPUB format
 * Extracts metadata from OPF file inside the EPUB archive
 */
class EpubParser : BaseBookMetadataParser() {

    companion object {
        private const val TAG = "EpubParser"

        // Dublin Core namespace
        private const val NS_DC = "http://purl.org/dc/elements/1.1/"
        private const val NS_OPF = "http://www.idpf.org/2007/opf"
    }

    override fun getSupportedExtensions(): List<String> = listOf("epub")

    override suspend fun parse(inputStream: InputStream, filename: String): BookMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                parseEpubStream(inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing EPUB: $filename", e)
                null
            }
        }
    }

    override suspend fun parse(file: File): BookMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                parseEpubFile(file)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing EPUB file: ${file.name}", e)
                null
            }
        }
    }

    private fun parseEpubFile(file: File): BookMetadata? {
        return ZipFile(file).use { zipFile ->
            // Find the OPF file location from container.xml
            val opfPath = findOpfPath(zipFile) ?: return@use null

            // Parse the OPF file
            val opfEntry = zipFile.getEntry(opfPath) ?: return@use null
            val metadata = zipFile.getInputStream(opfEntry).use { stream ->
                parseOpf(stream, opfPath)
            }

            // Try to extract cover
            if (metadata != null && metadata.coverData == null) {
                val coverData = extractCoverFromZip(zipFile, opfPath)
                if (coverData != null) {
                    return@use metadata.copy(coverData = coverData)
                }
            }

            metadata
        }
    }

    private fun parseEpubStream(inputStream: InputStream): BookMetadata? {
        val entries = mutableMapOf<String, ByteArray>()

        // Read all entries into memory (EPUBs are usually small)
        ZipInputStream(inputStream).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zipStream.readBytes()
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }

        // Find container.xml
        val containerXml = entries["META-INF/container.xml"] ?: return null

        // Parse container.xml to find OPF path
        val opfPath = parseContainerXml(containerXml.inputStream()) ?: return null

        // Parse OPF
        val opfData = entries[opfPath] ?: return null
        val metadata = parseOpf(opfData.inputStream(), opfPath)

        // Try to extract cover
        if (metadata != null && metadata.coverData == null) {
            val coverHref = findCoverHref(opfData.inputStream())
            if (coverHref != null) {
                val coverPath = resolvePath(opfPath, coverHref)
                val coverData = entries[coverPath]
                if (coverData != null) {
                    return metadata.copy(coverData = coverData)
                }
            }
        }

        return metadata
    }

    private fun findOpfPath(zipFile: ZipFile): String? {
        val containerEntry = zipFile.getEntry("META-INF/container.xml") ?: return null
        return zipFile.getInputStream(containerEntry).use { stream ->
            parseContainerXml(stream)
        }
    }

    private fun parseContainerXml(inputStream: InputStream): String? {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (fullPath != null) {
                    return fullPath
                }
            }
            eventType = parser.next()
        }
        return null
    }

    private fun parseOpf(inputStream: InputStream, opfPath: String): BookMetadata? {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)

        var title: String? = null
        val authors = mutableListOf<AuthorInfo>()
        var language: String? = null
        var year: Int? = null
        var description: String? = null
        var publisher: String? = null
        var isbn: String? = null
        var seriesName: String? = null
        var seriesNumber: Float? = null
        val subjects = mutableListOf<String>()

        var inMetadata = false
        var currentTag: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name

                    if (name == "metadata") {
                        inMetadata = true
                    } else if (inMetadata) {
                        currentTag = name

                        // Check for Calibre series metadata
                        if (name == "meta") {
                            val metaName = parser.getAttributeValue(null, "name")
                            val metaContent = parser.getAttributeValue(null, "content")

                            when (metaName) {
                                "calibre:series" -> seriesName = metaContent
                                "calibre:series_index" -> seriesNumber = metaContent?.toFloatOrNull()
                            }
                        }

                        // Check for role attribute on creator
                        if (name == "creator") {
                            val role = parser.getAttributeValue(NS_OPF, "role")
                                ?: parser.getAttributeValue(null, "role")
                            // Will be used when processing text
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty() && inMetadata) {
                        when (currentTag) {
                            "title" -> title = text
                            "creator" -> {
                                authors.add(AuthorInfo.fromFullName(text))
                            }
                            "language" -> language = text
                            "date" -> {
                                // Try to extract year from date
                                year = extractYear(text)
                            }
                            "description" -> description = cleanHtml(text)
                            "publisher" -> publisher = text
                            "identifier" -> {
                                if (text.startsWith("978") || text.startsWith("979")) {
                                    isbn = text
                                }
                            }
                            "subject" -> subjects.add(text)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "metadata") {
                        inMetadata = false
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }

        if (title.isNullOrBlank()) {
            return null
        }

        return BookMetadata(
            title = title,
            authors = authors.ifEmpty { listOf(AuthorInfo(nickname = "Unknown")) },
            series = seriesName?.let { SeriesInfo(it, seriesNumber) },
            genres = subjects,
            language = language,
            year = year,
            isbn = isbn,
            description = description,
            publisher = publisher
        )
    }

    private fun findCoverHref(opfStream: InputStream): String? {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(opfStream, null)

        var coverId: String? = null
        val manifestItems = mutableMapOf<String, String>()

        var inManifest = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "manifest" -> inManifest = true
                        "item" -> {
                            if (inManifest) {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                val properties = parser.getAttributeValue(null, "properties")

                                if (id != null && href != null) {
                                    manifestItems[id] = href

                                    // EPUB3: cover-image property
                                    if (properties?.contains("cover-image") == true) {
                                        return href
                                    }
                                }
                            }
                        }
                        "meta" -> {
                            // EPUB2: meta name="cover" content="cover-id"
                            val name = parser.getAttributeValue(null, "name")
                            if (name == "cover") {
                                coverId = parser.getAttributeValue(null, "content")
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "manifest") {
                        inManifest = false
                    }
                }
            }
            eventType = parser.next()
        }

        // Look up cover in manifest
        return coverId?.let { manifestItems[it] }
    }

    private fun extractCoverFromZip(zipFile: ZipFile, opfPath: String): ByteArray? {
        val opfEntry = zipFile.getEntry(opfPath) ?: return null

        val coverHref = zipFile.getInputStream(opfEntry).use { stream ->
            findCoverHref(stream)
        } ?: return null

        val coverPath = resolvePath(opfPath, coverHref)
        val coverEntry = zipFile.getEntry(coverPath) ?: return null

        return zipFile.getInputStream(coverEntry).use { stream ->
            stream.readBytes()
        }
    }

    override suspend fun extractCover(uri: Uri, context: Context): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val metadata = parseEpubStream(stream)
                    metadata?.coverData
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting cover from EPUB", e)
                null
            }
        }
    }

    private fun resolvePath(basePath: String, relativePath: String): String {
        val baseDir = basePath.substringBeforeLast("/", "")
        return if (baseDir.isNotEmpty()) {
            "$baseDir/$relativePath"
        } else {
            relativePath
        }
    }

    private fun extractYear(dateStr: String): Int? {
        // Try to extract 4-digit year from various formats
        val yearRegex = Regex("(19|20)\\d{2}")
        return yearRegex.find(dateStr)?.value?.toIntOrNull()
    }

    private fun cleanHtml(text: String): String {
        // Remove HTML tags and decode entities
        return text
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
