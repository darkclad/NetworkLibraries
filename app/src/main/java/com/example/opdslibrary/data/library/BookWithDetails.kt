package com.example.opdslibrary.data.library

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * Book with all related data - authors, series, genres
 */
data class BookWithDetails(
    @Embedded
    val book: Book,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = BookAuthor::class,
            parentColumn = "bookId",
            entityColumn = "authorId"
        )
    )
    val authors: List<Author>,

    @Relation(
        parentColumn = "seriesId",
        entityColumn = "id"
    )
    val series: Series?,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = BookGenre::class,
            parentColumn = "bookId",
            entityColumn = "genreId"
        )
    )
    val genres: List<Genre>
) {
    /**
     * Get primary author display name
     */
    fun getPrimaryAuthorName(): String {
        return authors.firstOrNull()?.getDisplayName() ?: "Unknown Author"
    }

    /**
     * Get all authors as comma-separated string
     */
    fun getAuthorsString(): String {
        return if (authors.isEmpty()) {
            "Unknown Author"
        } else {
            authors.joinToString(", ") { it.getDisplayName() }
        }
    }

    /**
     * Get series info as string (e.g., "Series Name #3")
     */
    fun getSeriesString(): String? {
        return series?.let { s ->
            if (book.seriesNumber != null) {
                "${s.name} #${book.seriesNumber.toInt()}"
            } else {
                s.name
            }
        }
    }

    /**
     * Get genres as comma-separated string
     */
    fun getGenresString(): String {
        return if (genres.isEmpty()) {
            "Unknown"
        } else {
            genres.joinToString(", ") { it.name }
        }
    }
}

/**
 * Author with book count for browsing
 */
data class AuthorWithBookCount(
    @Embedded
    val author: Author,
    val bookCount: Int
)

/**
 * Series with book count for browsing
 */
data class SeriesWithBookCount(
    @Embedded
    val series: Series,
    val bookCount: Int
)

/**
 * Genre with book count for browsing
 */
data class GenreWithBookCount(
    @Embedded
    val genre: Genre,
    val bookCount: Int
)
