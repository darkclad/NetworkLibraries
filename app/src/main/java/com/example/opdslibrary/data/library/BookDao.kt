package com.example.opdslibrary.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for books
 */
@Dao
interface BookDao {

    // === Query Methods ===

    @Transaction
    @Query("""
        SELECT b.* FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
        ORDER BY b.titleSort ASC
    """)
    fun getAllBooksWithDetailsByTitle(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT b.* FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
        ORDER BY b.titleSort DESC
    """)
    fun getAllBooksWithDetailsByTitleDesc(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT b.* FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
        ORDER BY b.addedAt DESC
    """)
    fun getAllBooksWithDetailsByDateDesc(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT b.* FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
        ORDER BY b.addedAt ASC
    """)
    fun getAllBooksWithDetailsByDate(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT DISTINCT b.* FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        LEFT JOIN book_authors ba ON b.id = ba.bookId
        LEFT JOIN authors a ON ba.authorId = a.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
        ORDER BY a.sortName ASC, b.titleSort ASC
    """)
    fun getAllBooksWithDetailsByAuthor(): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT DISTINCT b.* FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        LEFT JOIN book_authors ba ON b.id = ba.bookId
        LEFT JOIN authors a ON ba.authorId = a.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
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

    @Transaction
    @Query("""
        SELECT b.* FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.seriesId = :seriesId AND (b.scanFolderId IS NULL OR sf.enabled = 1)
        ORDER BY b.seriesNumber ASC
    """)
    fun getBooksForSeries(seriesId: Long): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT b.* FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        INNER JOIN book_authors ba ON b.id = ba.bookId
        WHERE ba.authorId = :authorId AND (b.scanFolderId IS NULL OR sf.enabled = 1)
        ORDER BY b.titleSort ASC
    """)
    fun getBooksForAuthor(authorId: Long): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT b.* FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        INNER JOIN book_genres bg ON b.id = bg.bookId
        WHERE bg.genreId = :genreId AND (b.scanFolderId IS NULL OR sf.enabled = 1)
        ORDER BY b.titleSort ASC
    """)
    fun getBooksForGenre(genreId: Long): Flow<List<BookWithDetails>>

    @Transaction
    @Query("""
        SELECT b.* FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
        ORDER BY b.addedAt DESC LIMIT :limit OFFSET :offset
    """)
    suspend fun getRecentBooksPagedOnce(limit: Int, offset: Int): List<BookWithDetails>

    @Query("""
        SELECT COUNT(*) FROM books b
        LEFT JOIN scan_folders sf ON b.scanFolderId = sf.id
        WHERE b.scanFolderId IS NULL OR sf.enabled = 1
    """)
    fun getBookCount(): Flow<Int>

    // === Search by ID list (for Lucene results) ===

    @Transaction
    @Query("SELECT * FROM books WHERE id IN (:ids)")
    suspend fun getBooksByIds(ids: List<Long>): List<BookWithDetails>

    // === Insert/Update/Delete ===

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: Book): Long

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

    @Query("UPDATE books SET needsReindex = 0, indexedAt = :indexedAt WHERE id = :bookId")
    suspend fun markAsIndexed(bookId: Long, indexedAt: Long)

    // === Cover Updates ===

    @Query("UPDATE books SET coverPath = :coverPath WHERE id = :bookId")
    suspend fun updateCoverPath(bookId: Long, coverPath: String?)

    // === Check Existence ===

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE filePath = :path)")
    suspend fun existsByPath(path: String): Boolean

    // === Duplicate Detection ===

    @Query("""
        SELECT * FROM books
        WHERE fileSize > 0 AND fileSize IN (
            SELECT fileSize FROM books GROUP BY fileSize HAVING COUNT(*) > 1
        )
        ORDER BY fileSize ASC, addedAt ASC
    """)
    suspend fun getDuplicateBooks(): List<Book>

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
            WHEN (SELECT opdsUpdated FROM books WHERE opdsEntryId = :entryId AND catalogId = :catalogId LIMIT 1) IS NULL THEN 1
            WHEN (SELECT opdsUpdated FROM books WHERE opdsEntryId = :entryId AND catalogId = :catalogId LIMIT 1) < :opdsUpdated THEN 1
            ELSE 0
        END
    """)
    suspend fun isBookOutdated(entryId: String, catalogId: Long, opdsUpdated: Long): Int?

    /**
     * Find books by normalized filename (case-insensitive)
     * Uses LIKE to match the filename portion of filePath
     * Used for filename-based OPDS matching
     *
     * @param filename The normalized filename to search for (should be lowercase)
     * @return List of matching books (may include partial matches, filter in code for exact match)
     */
    @Query("SELECT * FROM books WHERE LOWER(filePath) LIKE '%' || LOWER(:filename)")
    suspend fun getBooksByFilenamePattern(filename: String): List<Book>

    /**
     * Find books by title pattern (case-insensitive)
     * Used for title-based OPDS matching
     *
     * @param title The title pattern to search for
     * @return List of matching books
     */
    @Query("SELECT * FROM books WHERE LOWER(title) LIKE '%' || LOWER(:title) || '%'")
    suspend fun getBooksByTitlePattern(title: String): List<Book>

    /**
     * Find books by author name pattern (case-insensitive)
     * Searches through the authors table and returns books by matching authors
     * Matches against firstName, middleName, lastName, nickname, or sortName
     *
     * @param authorName The author name pattern to search for
     * @return List of matching books
     */
    @Query("""
        SELECT DISTINCT b.* FROM books b
        INNER JOIN book_authors ba ON b.id = ba.bookId
        INNER JOIN authors a ON ba.authorId = a.id
        WHERE LOWER(a.firstName) LIKE '%' || LOWER(:authorName) || '%'
           OR LOWER(a.middleName) LIKE '%' || LOWER(:authorName) || '%'
           OR LOWER(a.lastName) LIKE '%' || LOWER(:authorName) || '%'
           OR LOWER(a.nickname) LIKE '%' || LOWER(:authorName) || '%'
           OR LOWER(a.sortName) LIKE '%' || LOWER(:authorName) || '%'
    """)
    suspend fun getBooksByAuthorPattern(authorName: String): List<Book>

    /**
     * Update navigation history for a book
     * Used to enable "View in Catalog" feature for filename-matched books
     *
     * @param bookId The book ID to update
     * @param navigationHistory JSON-serialized navigation history
     */
    @Query("UPDATE books SET opdsNavigationHistory = :navigationHistory WHERE id = :bookId")
    suspend fun updateOpdsNavigationHistory(bookId: Long, navigationHistory: String?)

    /**
     * Clear OPDS info from all books for a specific catalog (DEBUG MODE)
     * Used to test re-matching after refresh
     *
     * @param catalogId The catalog ID
     * @return Number of books updated
     */
    @Query("""
        UPDATE books
        SET opdsEntryId = NULL,
            catalogId = NULL,
            opdsUpdated = NULL,
            opdsRelLinks = NULL,
            opdsNavigationHistory = NULL
        WHERE catalogId = :catalogId
    """)
    suspend fun clearOpdsInfoForCatalog(catalogId: Long): Int
}
