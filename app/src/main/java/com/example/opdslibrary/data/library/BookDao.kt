package com.example.opdslibrary.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for books
 */
@Dao
interface BookDao {

    // === Query Methods ===

    @Query("SELECT * FROM books ORDER BY titleSort ASC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun getAllBooksRecentFirst(): Flow<List<Book>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY titleSort ASC")
    fun getAllBooksWithDetails(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY titleSort ASC")
    fun getAllBooksWithDetailsByTitle(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY titleSort DESC")
    fun getAllBooksWithDetailsByTitleDesc(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun getAllBooksWithDetailsRecentFirst(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun getAllBooksWithDetailsByDateDesc(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY addedAt ASC")
    fun getAllBooksWithDetailsByDate(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT DISTINCT b.* FROM books b
        LEFT JOIN book_authors ba ON b.id = ba.bookId
        LEFT JOIN authors a ON ba.authorId = a.id
        ORDER BY a.sortName ASC, b.titleSort ASC
    """)
    fun getAllBooksWithDetailsByAuthor(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT DISTINCT b.* FROM books b
        LEFT JOIN book_authors ba ON b.id = ba.bookId
        LEFT JOIN authors a ON ba.authorId = a.id
        ORDER BY a.sortName DESC, b.titleSort ASC
    """)
    fun getAllBooksWithDetailsByAuthorDesc(): Flow<List<BookWithDetails>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookById(bookId: Long): Book?

    @Transaction
    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookWithDetailsById(bookId: Long): BookWithDetails?

    @Query("SELECT * FROM books WHERE filePath = :path")
    suspend fun getBookByPath(path: String): Book?

    @Query("SELECT * FROM books WHERE fileHash = :hash")
    suspend fun getBooksByHash(hash: String): List<Book>

    @Query("SELECT * FROM books WHERE needsReindex = 1")
    suspend fun getBooksNeedingReindex(): List<Book>

    @Query("SELECT * FROM books WHERE seriesId = :seriesId ORDER BY seriesNumber ASC")
    fun getBooksBySeries(seriesId: Long): Flow<List<Book>>

    @Transaction
    @Query("SELECT * FROM books WHERE seriesId = :seriesId ORDER BY seriesNumber ASC")
    fun getBooksWithDetailsBySeries(seriesId: Long): Flow<List<BookWithDetails>>

    @Transaction
    @Query("SELECT * FROM books WHERE seriesId = :seriesId ORDER BY seriesNumber ASC")
    fun getBooksForSeries(seriesId: Long): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT b.* FROM books b
        INNER JOIN book_authors ba ON b.id = ba.bookId
        WHERE ba.authorId = :authorId
        ORDER BY b.titleSort ASC
    """)
    fun getBooksForAuthor(authorId: Long): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT b.* FROM books b
        INNER JOIN book_genres bg ON b.id = bg.bookId
        WHERE bg.genreId = :genreId
        ORDER BY b.titleSort ASC
    """)
    fun getBooksForGenre(genreId: Long): Flow<List<BookWithDetails>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY addedAt DESC LIMIT :limit")
    fun getRecentBooks(limit: Int): Flow<List<BookWithDetails>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY addedAt DESC LIMIT :limit OFFSET :offset")
    fun getRecentBooksPaged(limit: Int, offset: Int): Flow<List<BookWithDetails>>

    @Transaction
    @Query("SELECT * FROM books ORDER BY addedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getRecentBooksPagedOnce(limit: Int, offset: Int): List<BookWithDetails>

    @Query("SELECT * FROM books WHERE downloadedViaApp = 1 ORDER BY addedAt DESC")
    fun getDownloadedBooks(): Flow<List<Book>>

    @Query("SELECT COUNT(*) FROM books")
    suspend fun getBookCountOnce(): Int

    @Query("SELECT COUNT(*) FROM books")
    fun getBookCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM books WHERE downloadedViaApp = 1")
    suspend fun getDownloadedBookCount(): Int

    // === Search by ID list (for Lucene results) ===

    @Transaction
    @Query("SELECT * FROM books WHERE id IN (:ids)")
    suspend fun getBooksByIds(ids: List<Long>): List<BookWithDetails>

    // === Insert/Update/Delete ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<Book>): List<Long>

    @Update
    suspend fun update(book: Book)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: Long)

    @Delete
    suspend fun delete(book: Book)

    @Query("DELETE FROM books")
    suspend fun deleteAll()

    // === Path Updates ===

    @Query("UPDATE books SET filePath = :newPath WHERE id = :bookId")
    suspend fun updateFilePath(bookId: Long, newPath: String)

    @Query("UPDATE books SET filePath = :newPath, originalPath = :originalPath WHERE id = :bookId")
    suspend fun updateFilePathWithOriginal(bookId: Long, newPath: String, originalPath: String)

    // === Reindex Flags ===

    @Query("UPDATE books SET needsReindex = 1 WHERE id = :bookId")
    suspend fun markForReindex(bookId: Long)

    @Query("UPDATE books SET needsReindex = 0, indexedAt = :indexedAt WHERE id = :bookId")
    suspend fun markAsIndexed(bookId: Long, indexedAt: Long)

    @Query("UPDATE books SET needsReindex = 1")
    suspend fun markAllForReindex()

    // === Cover Updates ===

    @Query("UPDATE books SET coverPath = :coverPath WHERE id = :bookId")
    suspend fun updateCoverPath(bookId: Long, coverPath: String?)

    // === Check Existence ===

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE filePath = :path)")
    suspend fun existsByPath(path: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE fileHash = :hash)")
    suspend fun existsByHash(hash: String): Boolean

    // === OPDS Entry ID Queries ===

    @Query("SELECT * FROM books WHERE opdsEntryId = :entryId AND catalogId = :catalogId LIMIT 1")
    suspend fun getBookByOpdsEntry(entryId: String, catalogId: Long): Book?

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE opdsEntryId = :entryId AND catalogId = :catalogId)")
    suspend fun existsByOpdsEntry(entryId: String, catalogId: Long): Boolean

    @Transaction
    @Query("SELECT * FROM books WHERE opdsEntryId = :entryId AND catalogId = :catalogId LIMIT 1")
    suspend fun getBookWithDetailsByOpdsEntry(entryId: String, catalogId: Long): BookWithDetails?

    @Query("UPDATE books SET opdsEntryId = :entryId, catalogId = :catalogId WHERE id = :bookId")
    suspend fun updateOpdsEntryId(bookId: Long, entryId: String, catalogId: Long)

    @Query("UPDATE books SET opdsUpdated = :opdsUpdated WHERE id = :bookId")
    suspend fun updateOpdsUpdated(bookId: Long, opdsUpdated: Long)

    @Query("UPDATE books SET opdsRelLinks = :relLinks WHERE id = :bookId")
    suspend fun updateOpdsRelLinks(bookId: Long, relLinks: String?)

    @Query("UPDATE books SET opdsRelLinks = :relLinks WHERE opdsEntryId = :entryId AND catalogId = :catalogId")
    suspend fun updateOpdsRelLinksByEntry(entryId: String, catalogId: Long, relLinks: String?)

    /**
     * Check if a book exists and is outdated compared to OPDS entry
     * Returns: null if book doesn't exist, true if outdated, false if current
     */
    @Query("""
        SELECT CASE
            WHEN NOT EXISTS(SELECT 1 FROM books WHERE opdsEntryId = :entryId AND catalogId = :catalogId) THEN NULL
            WHEN (SELECT opdsUpdated FROM books WHERE opdsEntryId = :entryId AND catalogId = :catalogId) IS NULL THEN 1
            WHEN (SELECT opdsUpdated FROM books WHERE opdsEntryId = :entryId AND catalogId = :catalogId) < :opdsUpdated THEN 1
            ELSE 0
        END
    """)
    suspend fun isBookOutdated(entryId: String, catalogId: Long, opdsUpdated: Long): Int?
}
