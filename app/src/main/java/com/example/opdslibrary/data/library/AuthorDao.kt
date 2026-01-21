package com.example.opdslibrary.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for authors
 */
@Dao
interface AuthorDao {

    // === Query Methods ===

    @Query("SELECT * FROM authors ORDER BY sortName ASC")
    fun getAllAuthors(): Flow<List<Author>>

    @Query("""
        SELECT a.*, COUNT(ba.bookId) as bookCount
        FROM authors a
        INNER JOIN book_authors ba ON a.id = ba.authorId
        GROUP BY a.id
        ORDER BY a.sortName ASC
    """)
    fun getAllAuthorsWithBookCount(): Flow<List<AuthorWithBookCount>>

    @Query("SELECT * FROM authors WHERE id = :authorId")
    suspend fun getAuthorById(authorId: Long): Author?

    @Query("SELECT * FROM authors WHERE sortName = :sortName LIMIT 1")
    suspend fun findBySortName(sortName: String): Author?

    @Query("""
        SELECT * FROM authors
        WHERE (lastName = :lastName AND firstName = :firstName)
           OR (nickname = :nickname AND nickname IS NOT NULL)
        LIMIT 1
    """)
    suspend fun findByNameParts(lastName: String?, firstName: String?, nickname: String?): Author?

    @Query("""
        SELECT a.* FROM authors a
        INNER JOIN book_authors ba ON a.id = ba.authorId
        WHERE ba.bookId = :bookId
        ORDER BY ba.role ASC
    """)
    suspend fun getAuthorsForBook(bookId: Long): List<Author>

    @Query("""
        SELECT a.* FROM authors a
        INNER JOIN book_authors ba ON a.id = ba.authorId
        WHERE ba.bookId = :bookId AND ba.role = :role
    """)
    suspend fun getAuthorsForBookByRole(bookId: Long, role: String): List<Author>

    @Query("SELECT COUNT(*) FROM authors")
    suspend fun getAuthorCountOnce(): Int

    @Query("SELECT COUNT(*) FROM authors")
    fun getAuthorCount(): Flow<Int>

    // === Insert/Update/Delete ===

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(author: Author): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(authors: List<Author>): List<Long>

    @Update
    suspend fun update(author: Author)

    @Delete
    suspend fun delete(author: Author)

    @Query("DELETE FROM authors WHERE id = :authorId")
    suspend fun deleteById(authorId: Long)

    // === Book-Author Junction ===

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookAuthor(bookAuthor: BookAuthor)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookAuthors(bookAuthors: List<BookAuthor>)

    @Query("DELETE FROM book_authors WHERE bookId = :bookId")
    suspend fun deleteBookAuthors(bookId: Long)

    @Query("DELETE FROM book_authors WHERE bookId = :bookId AND authorId = :authorId")
    suspend fun deleteBookAuthor(bookId: Long, authorId: Long)

    // === Cleanup Orphans ===

    @Query("""
        DELETE FROM authors WHERE id NOT IN (
            SELECT DISTINCT authorId FROM book_authors
        )
    """)
    suspend fun deleteOrphanedAuthors(): Int

    @Query("DELETE FROM authors")
    suspend fun deleteAll()

    @Query("DELETE FROM book_authors")
    suspend fun deleteAllBookAuthors()
}
