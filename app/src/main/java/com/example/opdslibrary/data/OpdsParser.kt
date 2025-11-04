package com.example.opdslibrary.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

/**
 * Parser for OPDS (Open Publication Distribution System) feeds
 * OPDS is based on Atom syndication format
 */
class OpdsParser {

    private val ns: String? = null

    @Throws(XmlPullParserException::class, IOException::class)
    fun parse(inputStream: InputStream): OpdsFeed {
        inputStream.use { stream ->
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)

            // Move to the first tag, skipping any XML declarations or whitespace
            var eventType = parser.eventType
            while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next()
            }

            if (eventType == XmlPullParser.END_DOCUMENT) {
                throw XmlPullParserException("Empty document")
            }

            return readFeed(parser)
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readFeed(parser: XmlPullParser): OpdsFeed {
        val entries = mutableListOf<OpdsEntry>()
        val links = mutableListOf<OpdsLink>()
        var id = ""
        var title = ""
        var updated = ""
        var author: OpdsAuthor? = null
        var icon: String? = null

        // Accept "feed" tag or any tag that ends with "feed" (for namespaced tags)
        if (!parser.name.endsWith("feed")) {
            throw XmlPullParserException("Expected feed element but got: ${parser.name}")
        }

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                continue
            }
            // Get the local name without namespace prefix
            val localName = parser.name.substringAfterLast(':')
            when (localName) {
                "id" -> id = readText(parser, parser.name)
                "title" -> title = readText(parser, parser.name)
                "updated" -> updated = readText(parser, parser.name)
                "author" -> author = readAuthor(parser)
                "icon" -> icon = readText(parser, parser.name)
                "link" -> links.add(readLink(parser))
                "entry" -> entries.add(readEntry(parser))
                else -> skip(parser)
            }
        }
        return OpdsFeed(id, title, updated, author, entries, links, icon)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readEntry(parser: XmlPullParser): OpdsEntry {
        if (!parser.name.endsWith("entry")) {
            throw XmlPullParserException("Expected entry element but got: ${parser.name}")
        }

        var id = ""
        var title = ""
        var updated = ""
        var summary: String? = null
        var content: String? = null
        var author: OpdsAuthor? = null
        val links = mutableListOf<OpdsLink>()
        val categories = mutableListOf<OpdsCategory>()
        var published: String? = null
        var dcLanguage: String? = null
        var dcPublisher: String? = null
        var dcIssued: String? = null

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                continue
            }
            // Get the local name without namespace prefix
            val localName = parser.name.substringAfterLast(':')
            when (localName) {
                "id" -> id = readText(parser, parser.name)
                "title" -> title = readText(parser, parser.name)
                "updated" -> updated = readText(parser, parser.name)
                "summary" -> summary = readText(parser, parser.name)
                "content" -> content = readText(parser, parser.name)
                "author" -> author = readAuthor(parser)
                "link" -> links.add(readLink(parser))
                "category" -> categories.add(readCategory(parser))
                "published" -> published = readText(parser, parser.name)
                "language" -> dcLanguage = readText(parser, parser.name)
                "publisher" -> dcPublisher = readText(parser, parser.name)
                "issued" -> dcIssued = readText(parser, parser.name)
                else -> skip(parser)
            }
        }

        return OpdsEntry(
            id, title, updated, summary, content, author, links,
            categories, published, dcLanguage, dcPublisher, dcIssued
        )
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readLink(parser: XmlPullParser): OpdsLink {
        if (!parser.name.endsWith("link")) {
            throw XmlPullParserException("Expected link element but got: ${parser.name}")
        }

        val href = parser.getAttributeValue(null, "href") ?: ""
        val type = parser.getAttributeValue(null, "type")
        val rel = parser.getAttributeValue(null, "rel")
        val title = parser.getAttributeValue(null, "title")

        // Handle both self-closing and regular tags
        if (parser.isEmptyElementTag) {
            parser.next()
        } else {
            parser.next()
            if (parser.eventType != XmlPullParser.END_TAG) {
                skip(parser)
            }
        }

        return OpdsLink(href, type, rel, title)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readAuthor(parser: XmlPullParser): OpdsAuthor {
        if (!parser.name.endsWith("author")) {
            throw XmlPullParserException("Expected author element but got: ${parser.name}")
        }

        var name = ""
        var uri: String? = null
        var email: String? = null

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) {
                continue
            }
            // Get the local name without namespace prefix
            val localName = parser.name.substringAfterLast(':')
            when (localName) {
                "name" -> name = readText(parser, parser.name)
                "uri" -> uri = readText(parser, parser.name)
                "email" -> email = readText(parser, parser.name)
                else -> skip(parser)
            }
        }
        return OpdsAuthor(name, uri, email)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readCategory(parser: XmlPullParser): OpdsCategory {
        if (!parser.name.endsWith("category")) {
            throw XmlPullParserException("Expected category element but got: ${parser.name}")
        }

        val term = parser.getAttributeValue(null, "term") ?: ""
        val label = parser.getAttributeValue(null, "label")
        val scheme = parser.getAttributeValue(null, "scheme")

        // Handle both self-closing and regular tags
        if (parser.isEmptyElementTag) {
            parser.next()
        } else {
            parser.next()
            if (parser.eventType != XmlPullParser.END_TAG) {
                skip(parser)
            }
        }

        return OpdsCategory(term, label, scheme)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun readText(parser: XmlPullParser, tag: String): String {
        if (parser.eventType != XmlPullParser.START_TAG) {
            throw XmlPullParserException("Expected START_TAG but got: ${parser.eventType}")
        }

        val expectedTag = tag
        var text = ""

        if (parser.next() == XmlPullParser.TEXT) {
            text = parser.text ?: ""
            parser.next()
        }

        // Make sure we're at the end tag, skip any nested elements if needed
        while (parser.eventType != XmlPullParser.END_TAG || parser.name != expectedTag) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                skip(parser)
            } else {
                parser.next()
            }
        }

        return text
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) {
            throw IllegalStateException()
        }
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }
}
