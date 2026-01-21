package com.example.opdslibrary.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction table for many-to-many relationship between books and genres
 */
@Entity(
    tableName = "book_genres",
    primaryKeys = ["bookId", "genreId"],
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Genre::class,
            parentColumns = ["id"],
            childColumns = ["genreId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("genreId")
    ]
)
data class BookGenre(
    val bookId: Long,
    val genreId: Long
)
