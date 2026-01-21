package com.example.opdslibrary.library.parser

import android.util.Log

/**
 * Fallback parser that extracts metadata from filenames
 * Used when file content parsing fails or is not supported
 */
object FilenameParser {

    private const val TAG = "FilenameParser"

    /**
     * Common filename patterns:
     * - "Author - Title.ext"
     * - "Author_Title.ext"
     * - "Author_Title_SeriesName_Number.ext"
     * - "Title (Author).ext"
     * - "Title.ext"
     */
    fun parse(filename: String): BookMetadata {
        Log.d(TAG, "Parsing filename: $filename")

        // Remove extension
        val nameWithoutExt = removeExtension(filename)
            .replace("_", " ")
            .trim()

        // Try different patterns

        // Pattern 1: "Author - Title"
        if (nameWithoutExt.contains(" - ")) {
            val parts = nameWithoutExt.split(" - ", limit = 2)
            if (parts.size == 2) {
                return BookMetadata(
                    title = parts[1].trim(),
                    authors = listOf(AuthorInfo.fromFullName(parts[0].trim()))
                )
            }
        }

        // Pattern 2: "Title (Author)"
        val parenPattern = Regex("^(.+)\\s*\\(([^)]+)\\)\\s*$")
        parenPattern.find(nameWithoutExt)?.let { match ->
            val title = match.groupValues[1].trim()
            val author = match.groupValues[2].trim()
            if (title.isNotBlank() && author.isNotBlank()) {
                return BookMetadata(
                    title = title,
                    authors = listOf(AuthorInfo.fromFullName(author))
                )
            }
        }

        // Pattern 3: "Author_ShortTitle_SeriesInfo"
        // Common in library dumps like Flibusta
        val parts = nameWithoutExt.split("_").filter { it.isNotBlank() }
        if (parts.size >= 2) {
            // First part often contains author last name
            val possibleAuthor = parts[0].trim()
            val possibleTitle = parts.drop(1).joinToString(" ").trim()

            // Check if first part looks like an author name (capitalized words)
            if (possibleAuthor.firstOrNull()?.isUpperCase() == true && !possibleAuthor.contains(" ")) {
                return BookMetadata(
                    title = possibleTitle,
                    authors = listOf(AuthorInfo.fromFullName(possibleAuthor))
                )
            }
        }

        // Fallback: use entire filename as title
        return BookMetadata(
            title = nameWithoutExt,
            authors = listOf(AuthorInfo(nickname = "Unknown"))
        )
    }

    /**
     * Extract series info from filename if present
     * Patterns like "01 - Title" or "Title #3"
     */
    fun extractSeriesInfo(filename: String): SeriesInfo? {
        val nameWithoutExt = removeExtension(filename)

        // Pattern: "01 - Title" or "01. Title"
        val numberPrefixPattern = Regex("^(\\d+)\\s*[-.]\\s*(.+)")
        numberPrefixPattern.find(nameWithoutExt)?.let { match ->
            val number = match.groupValues[1].toFloatOrNull()
            if (number != null) {
                // We found a number prefix, but we don't know the series name
                // Return with just the number
                return SeriesInfo(name = "Unknown Series", number = number)
            }
        }

        // Pattern: "Title #3" or "Title Vol.3"
        val suffixPattern = Regex("(.+)\\s*(?:#|Vol\\.?|Book\\s*)\\s*(\\d+)\\s*$", RegexOption.IGNORE_CASE)
        suffixPattern.find(nameWithoutExt)?.let { match ->
            val number = match.groupValues[2].toFloatOrNull()
            if (number != null) {
                return SeriesInfo(name = "Unknown Series", number = number)
            }
        }

        return null
    }

    private fun removeExtension(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".fb2.zip") -> filename.dropLast(8)
            lower.endsWith(".epub") -> filename.dropLast(5)
            lower.endsWith(".mobi") -> filename.dropLast(5)
            lower.endsWith(".fb2") -> filename.dropLast(4)
            lower.endsWith(".pdf") -> filename.dropLast(4)
            lower.endsWith(".azw3") -> filename.dropLast(5)
            lower.endsWith(".azw") -> filename.dropLast(4)
            lower.endsWith(".prc") -> filename.dropLast(4)
            lower.endsWith(".zip") -> filename.dropLast(4)
            else -> filename.substringBeforeLast(".")
        }
    }
}
