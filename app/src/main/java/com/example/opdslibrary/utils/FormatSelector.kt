package com.example.opdslibrary.utils

import android.util.Log
import com.example.opdslibrary.data.OpdsLink
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Utility for selecting the best download format based on priority
 */
object FormatSelector {

    private const val TAG = "FormatSelector"

    // OPDS uses Atom date format (ISO 8601)
    private val DATE_FORMATS = arrayOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd"
    )

    /**
     * Parse OPDS updated timestamp to milliseconds since epoch
     * @param updated The updated string from OPDS entry
     * @return Milliseconds since epoch, or null if parsing fails
     */
    fun parseOpdsTimestamp(updated: String?): Long? {
        if (updated.isNullOrBlank()) return null

        for (format in DATE_FORMATS) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val date = sdf.parse(updated)
                if (date != null) {
                    return date.time
                }
            } catch (e: Exception) {
                // Try next format
            }
        }
        Log.w(TAG, "Failed to parse OPDS timestamp: $updated")
        return null
    }

    /**
     * Check if a link is an acquisition link (for downloading books)
     */
    private fun OpdsLink.isAcquisitionLink(): Boolean {
        return rel?.startsWith("http://opds-spec.org/acquisition") == true
    }

    /**
     * Select the best acquisition link based on format priority
     * @param links List of links from OPDS entry
     * @param formatPriority List of format extensions in priority order (highest first)
     * @return The best matching link, or null if no acquisition links
     */
    fun selectBestLink(links: List<OpdsLink>, formatPriority: List<String>): OpdsLink? {
        val acquisitionLinks = links.filter { it.isAcquisitionLink() }
        if (acquisitionLinks.isEmpty()) return null

        // Score each link based on format priority
        val scoredLinks = acquisitionLinks.map { link ->
            val score = getFormatScore(link, formatPriority)
            link to score
        }

        // Return the link with the highest score (lowest index = highest priority)
        return scoredLinks.minByOrNull { it.second }?.first
    }

    /**
     * Get the priority score for a link (lower is better)
     */
    private fun getFormatScore(link: OpdsLink, formatPriority: List<String>): Int {
        val linkType = link.type?.lowercase() ?: ""
        val href = link.href.lowercase()

        // Check each format in priority order
        for ((index, format) in formatPriority.withIndex()) {
            val formatLower = format.lowercase()

            // Check MIME type
            if (linkType.contains(formatLower.replace(".", ""))) {
                return index
            }

            // Check file extension in URL
            if (href.endsWith(".$formatLower") || href.contains(".$formatLower?") || href.contains(".$formatLower&")) {
                return index
            }

            // Special handling for fb2.zip
            if (formatLower == "fb2.zip" && (linkType.contains("fb2") && linkType.contains("zip"))) {
                return index
            }
        }

        // Unknown format - return a high score
        return formatPriority.size + 100
    }

    /**
     * Get a user-friendly format name from a link
     */
    fun getFormatName(link: OpdsLink): String {
        val type = link.type?.lowercase() ?: ""
        val href = link.href.lowercase()

        return when {
            type.contains("fb2") && type.contains("zip") -> "FB2.ZIP"
            href.endsWith(".fb2.zip") -> "FB2.ZIP"
            type.contains("fb2") || href.endsWith(".fb2") -> "FB2"
            type.contains("epub") || href.endsWith(".epub") -> "EPUB"
            type.contains("pdf") || href.endsWith(".pdf") -> "PDF"
            type.contains("mobi") || href.endsWith(".mobi") -> "MOBI"
            type.contains("azw") || href.endsWith(".azw3") -> "AZW3"
            type.contains("rtf") || href.endsWith(".rtf") -> "RTF"
            type.contains("html") || href.endsWith(".html") || href.endsWith(".htm") -> "HTML"
            type.contains("text/plain") || href.endsWith(".txt") -> "TXT"
            else -> link.getFriendlyType()
        }
    }
}
