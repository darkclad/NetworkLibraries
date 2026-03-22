package com.example.opdslibrary.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents an OPDS feed (catalog)
 */
@Parcelize
data class OpdsFeed(
    val id: String = "",
    val title: String = "",
    val updated: String = "",
    val author: OpdsAuthor? = null,
    val entries: List<OpdsEntry> = emptyList(),
    val links: List<OpdsLink> = emptyList(),
    val icon: String? = null
) : Parcelable {
    /**
     * Get the next page link for pagination
     */
    fun getNextPageLink(): String? {
        return links.firstOrNull {
            it.rel == "next" ||
            it.rel == "http://opds-spec.org/facet" ||
            it.type?.contains("application/atom+xml") == true && it.rel?.contains("next") == true
        }?.href
    }

    /**
     * Check if there is a next page available
     */
    fun hasNextPage(): Boolean {
        return getNextPageLink() != null
    }

    /**
     * Get the search link if available (OpenSearch)
     * OPDS catalogs use rel="search" with type containing "opensearchdescription"
     * or direct search templates
     */
    fun getSearchLink(): OpdsLink? {
        return links.firstOrNull {
            it.rel == "search" ||
            it.type?.contains("opensearchdescription", ignoreCase = true) == true
        }
    }

    /**
     * Check if this feed supports search
     */
    fun hasSearch(): Boolean {
        return getSearchLink() != null
    }
}

/**
 * Represents an entry in an OPDS feed (book or navigation link)
 */
@Parcelize
data class OpdsEntry(
    val id: String = "",
    val title: String = "",
    val updated: String = "",
    val summary: String? = null,
    val content: String? = null,
    val author: OpdsAuthor? = null,
    val links: List<OpdsLink> = emptyList(),
    val categories: List<OpdsCategory> = emptyList(),
    val published: String? = null,
    val dcLanguage: String? = null,
    val dcPublisher: String? = null,
    val dcIssued: String? = null
) : Parcelable {
    /**
     * Check if this entry is a navigation link (catalog entry)
     */
    fun isNavigation(): Boolean {
        return links.any { it.type?.contains("atom+xml") == true || it.rel == "subsection" }
    }

    /**
     * Check if this entry is an acquisition (book)
     */
    fun isAcquisition(): Boolean {
        return links.any { it.rel?.startsWith("http://opds-spec.org/acquisition") == true }
    }

    /**
     * Get the thumbnail/cover image URL
     */
    fun getThumbnailUrl(): String? {
        return links.firstOrNull {
            it.rel == "http://opds-spec.org/image/thumbnail" ||
            it.rel == "http://opds-spec.org/image"
        }?.href
    }

    /**
     * Get the navigation link URL
     */
    fun getNavigationUrl(): String? {
        return links.firstOrNull {
            it.type?.contains("atom+xml") == true ||
            it.rel == "subsection"
        }?.href
    }

    /**
     * Get acquisition links (download links)
     */
    fun getAcquisitionLinks(): List<OpdsLink> {
        return links.filter { it.rel?.startsWith("http://opds-spec.org/acquisition") == true }
    }

    /**
     * Get HTML links (web pages)
     */
    fun getHtmlLinks(): List<OpdsLink> {
        return links.filter { it.type == "text/html" }
    }

    /**
     * Get related/navigation links that can be opened in OPDS catalog
     * This includes author links, series links, related books, etc.
     * Excludes acquisition links (downloads) and image links
     */
    fun getRelatedLinks(): List<OpdsLink> {
        return links.filter { link ->
            // Include navigation links (atom+xml feeds)
            val isNavigation = link.type?.contains("atom+xml") == true ||
                    link.rel == "subsection" ||
                    link.rel == "related" ||
                    link.rel == "alternate"

            // Exclude acquisition and image links
            val isAcquisition = link.rel?.startsWith("http://opds-spec.org/acquisition") == true
            val isImage = link.rel?.contains("image") == true

            isNavigation && !isAcquisition && !isImage
        }
    }
}

/**
 * Represents a link in an OPDS feed or entry
 */
@Parcelize
data class OpdsLink(
    val href: String = "",
    val type: String? = null,
    val rel: String? = null,
    val title: String? = null
) : Parcelable {
    /**
     * Get a friendly name for the link type
     */
    fun getFriendlyType(): String {
        return when {
            type?.contains("fb2+zip", ignoreCase = true) == true -> "FB2+ZIP"
            type?.contains("fb2.zip", ignoreCase = true) == true -> "FB2+ZIP"
            type?.contains("epub+zip", ignoreCase = true) == true -> "EPUB+ZIP"
            type?.contains("fb2", ignoreCase = true) == true -> "FB2"
            type?.contains("epub", ignoreCase = true) == true -> "EPUB"
            type?.contains("pdf", ignoreCase = true) == true -> "PDF"
            type?.contains("mobi", ignoreCase = true) == true -> "MOBI"
            type?.contains("azw3", ignoreCase = true) == true -> "AZW3"
            type?.contains("djvu", ignoreCase = true) == true -> "DJVU"
            else -> type?.substringAfterLast("/")?.uppercase() ?: "Unknown"
        }
    }
}

/**
 * Represents an author in an OPDS feed or entry
 */
@Parcelize
data class OpdsAuthor(
    val name: String = "",
    val uri: String? = null
) : Parcelable

/**
 * Represents a category/genre for an OPDS entry
 */
@Parcelize
data class OpdsCategory(
    val term: String = "",
    val label: String? = null,
    val scheme: String? = null
) : Parcelable
