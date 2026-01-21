package com.example.opdslibrary.data.library

import androidx.room.*

/**
 * FTS4 virtual table for full-text search on books
 */
@Entity(tableName = "book_fts")
@Fts4(contentEntity = Book::class)
data class BookFts(
    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String?
)

/**
 * Separate table for additional searchable fields not in Book entity
 */
@Entity(
    tableName = "book_search_index",
    indices = [Index("bookId", unique = true)]
)
data class BookSearchIndex(
    @PrimaryKey
    val bookId: Long,

    val title: String,
    val authors: String,       // Combined author names for search
    val series: String,        // Series name
    val description: String,   // Book description
    val genres: String         // Combined genre names
)

/**
 * FTS4 virtual table for the search index
 */
@Entity(tableName = "book_search_fts")
@Fts4(contentEntity = BookSearchIndex::class)
data class BookSearchFts(
    val title: String,
    val authors: String,
    val series: String,
    val description: String,
    val genres: String
)
