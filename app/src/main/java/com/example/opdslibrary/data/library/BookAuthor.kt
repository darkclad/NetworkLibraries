package com.example.opdslibrary.data.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction table for many-to-many relationship between books and authors
 */
@Entity(
    tableName = "book_authors",
    primaryKeys = ["bookId", "authorId"],
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Author::class,
            parentColumns = ["id"],
            childColumns = ["authorId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("authorId")
    ]
)
data class BookAuthor(
    val bookId: Long,
    val authorId: Long,
    val role: String = ROLE_AUTHOR    // "author", "translator", "editor"
) {
    companion object {
        const val ROLE_AUTHOR = "author"
        const val ROLE_TRANSLATOR = "translator"
        const val ROLE_EDITOR = "editor"
    }
}
