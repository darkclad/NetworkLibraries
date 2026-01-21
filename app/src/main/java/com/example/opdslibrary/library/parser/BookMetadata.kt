package com.example.opdslibrary.library.parser

/**
 * Unified book metadata model extracted from various formats
 */
data class BookMetadata(
    val title: String,
    val authors: List<AuthorInfo>,
    val series: SeriesInfo? = null,
    val genres: List<String> = emptyList(),
    val language: String? = null,
    val year: Int? = null,
    val isbn: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val coverData: ByteArray? = null    // Embedded cover image data
) {
    /**
     * Get title suitable for sorting (lowercase, without leading articles)
     */
    fun getTitleSort(): String {
        val lower = title.lowercase().trim()
        // Remove leading articles in English and Russian
        val articles = listOf("the ", "a ", "an ")
        for (article in articles) {
            if (lower.startsWith(article)) {
                return lower.removePrefix(article)
            }
        }
        return lower
    }

    /**
     * Get primary author name
     */
    fun getPrimaryAuthorName(): String {
        return authors.firstOrNull()?.getDisplayName() ?: "Unknown Author"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BookMetadata

        if (title != other.title) return false
        if (authors != other.authors) return false
        if (series != other.series) return false
        if (genres != other.genres) return false
        if (language != other.language) return false
        if (year != other.year) return false
        if (isbn != other.isbn) return false
        if (description != other.description) return false
        if (publisher != other.publisher) return false
        if (coverData != null) {
            if (other.coverData == null) return false
            if (!coverData.contentEquals(other.coverData)) return false
        } else if (other.coverData != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + authors.hashCode()
        result = 31 * result + (series?.hashCode() ?: 0)
        result = 31 * result + genres.hashCode()
        result = 31 * result + (language?.hashCode() ?: 0)
        result = 31 * result + (year ?: 0)
        result = 31 * result + (isbn?.hashCode() ?: 0)
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + (publisher?.hashCode() ?: 0)
        result = 31 * result + (coverData?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Author information extracted from book
 */
data class AuthorInfo(
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val nickname: String? = null,
    val role: String = ROLE_AUTHOR
) {
    companion object {
        const val ROLE_AUTHOR = "author"
        const val ROLE_TRANSLATOR = "translator"
        const val ROLE_EDITOR = "editor"

        /**
         * Create AuthorInfo from a full name string
         */
        fun fromFullName(fullName: String, role: String = ROLE_AUTHOR): AuthorInfo {
            val trimmed = fullName.trim()
            if (trimmed.isEmpty()) {
                return AuthorInfo(nickname = "Unknown", role = role)
            }

            val parts = trimmed.split("\\s+".toRegex())
            return when (parts.size) {
                1 -> AuthorInfo(nickname = parts[0], role = role)
                2 -> AuthorInfo(firstName = parts[0], lastName = parts[1], role = role)
                else -> AuthorInfo(
                    firstName = parts[0],
                    middleName = parts.subList(1, parts.size - 1).joinToString(" "),
                    lastName = parts.last(),
                    role = role
                )
            }
        }
    }

    /**
     * Get display name for UI
     */
    fun getDisplayName(): String {
        return when {
            nickname != null && firstName == null && lastName == null -> nickname
            firstName != null && lastName != null -> {
                if (middleName != null) {
                    "$firstName $middleName $lastName"
                } else {
                    "$firstName $lastName"
                }
            }
            lastName != null -> lastName
            firstName != null -> firstName
            nickname != null -> nickname
            else -> "Unknown Author"
        }
    }

    /**
     * Get sort name (LastName, FirstName format)
     */
    fun getSortName(): String {
        return when {
            lastName != null && firstName != null -> "$lastName, $firstName"
            lastName != null -> lastName
            nickname != null -> nickname
            firstName != null -> firstName
            else -> "Unknown"
        }
    }
}

/**
 * Series information
 */
data class SeriesInfo(
    val name: String,
    val number: Float? = null
)
