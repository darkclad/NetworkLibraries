package com.example.opdslibrary.data.library

import androidx.room.*

/**
 * Data Access Object for full-text search operations
 */
@Dao
interface BookFtsDao {

    /**
     * Insert or update a book in the search index
     */
    @Query("""
        INSERT OR REPLACE INTO book_search_index (bookId, title, authors, series, description, genres)
        VALUES (:bookId, :title, :authors, :series, :description, :genres)
    """)
    suspend fun upsertBookFts(
        bookId: Long,
        title: String,
        authors: String,
        series: String,
        description: String,
        genres: String
    )

    /**
     * Delete a book from the search index
     */
    @Query("DELETE FROM book_search_index WHERE bookId = :bookId")
    suspend fun deleteBookFts(bookId: Long)

    /**
     * Search for books using FTS4
     * Returns list of book IDs matching the query
     */
    @Query("""
        SELECT bsi.bookId FROM book_search_index bsi
        JOIN book_search_fts ON book_search_fts.rowid = bsi.rowid
        WHERE book_search_fts MATCH :query
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int): List<Long>

    /**
     * Simple search by title (fallback when FTS fails)
     */
    @Query("""
        SELECT bookId FROM book_search_index
        WHERE title LIKE '%' || :query || '%'
           OR authors LIKE '%' || :query || '%'
           OR series LIKE '%' || :query || '%'
        LIMIT :limit
    """)
    suspend fun searchSimple(query: String, limit: Int): List<Long>

    /**
     * Get count of indexed books
     */
    @Query("SELECT COUNT(*) FROM book_search_index")
    suspend fun getIndexedCount(): Int

    /**
     * Clear all indexed data
     */
    @Query("DELETE FROM book_search_index")
    suspend fun clearAll()

    /**
     * Rebuild FTS index (call after bulk insert/delete)
     */
    @Query("INSERT INTO book_search_fts(book_search_fts) VALUES('rebuild')")
    suspend fun rebuildIndex()
}
