package com.example.opdslibrary.library.search

import android.content.Context
import android.util.Log
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.data.library.Author
import com.example.opdslibrary.data.library.Book
import com.example.opdslibrary.data.library.Genre
import com.example.opdslibrary.data.library.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages full-text search index for the book library using Room FTS4
 */
class BookSearchManager(private val context: Context) {

    companion object {
        private const val TAG = "SearchIndexManager"
    }

    private val database by lazy { AppDatabase.getDatabase(context) }
    private val bookFtsDao by lazy { database.bookFtsDao() }

    private var isInitialized = false

    /**
     * Initialize the search index
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        try {
            // FTS table is created automatically by Room migration
            isInitialized = true
            Log.d(TAG, "Search index initialized (using Room FTS4)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize search index", e)
            throw e
        }
    }

    /**
     * Index a book with its metadata
     */
    suspend fun indexBook(
        book: Book,
        authors: List<Author>,
        series: Series?,
        genres: List<Genre>
    ) = withContext(Dispatchers.IO) {
        try {
            val authorNames = authors.joinToString(" ") {
                "${it.getDisplayName()} ${it.sortName}"
            }
            val genreNames = genres.joinToString(" ") { it.name }

            bookFtsDao.upsertBookFts(
                bookId = book.id,
                title = book.title.lowercase(),
                authors = authorNames.lowercase(),
                series = series?.name?.lowercase() ?: "",
                description = book.description?.lowercase() ?: "",
                genres = genreNames.lowercase()
            )

            Log.d(TAG, "Indexed book: ${book.title} (id=${book.id})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to index book: ${book.title}", e)
        }
    }

    /**
     * Index multiple books in batch
     */
    suspend fun indexBooks(
        booksWithMetadata: List<BookIndexData>
    ) = withContext(Dispatchers.IO) {
        try {
            booksWithMetadata.forEach { data ->
                val authorNames = data.authors.joinToString(" ") {
                    "${it.getDisplayName()} ${it.sortName}"
                }
                val genreNames = data.genres.joinToString(" ") { it.name }

                bookFtsDao.upsertBookFts(
                    bookId = data.book.id,
                    title = data.book.title.lowercase(),
                    authors = authorNames.lowercase(),
                    series = data.series?.name?.lowercase() ?: "",
                    description = data.book.description?.lowercase() ?: "",
                    genres = genreNames.lowercase()
                )
            }
            Log.d(TAG, "Batch indexed ${booksWithMetadata.size} books")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to batch index books", e)
        }
    }

    /**
     * Remove a book from the index
     */
    suspend fun removeBook(bookId: Long) = withContext(Dispatchers.IO) {
        try {
            bookFtsDao.deleteBookFts(bookId)
            Log.d(TAG, "Removed book from index: $bookId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove book from index: $bookId", e)
        }
    }

    /**
     * Search for books matching the query
     * @param query Search query string
     * @param limit Maximum number of results
     * @return List of matching book IDs
     */
    suspend fun search(query: String, limit: Int = 50): List<Long> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext emptyList()
        }

        try {
            // Global FTS query — field-specific syntax is unreliable with non-ASCII in FTS4.
            // Include both lowercase and original-case per word to handle legacy index entries
            // that were not stored in lowercase (SQLite simple tokenizer skips non-ASCII casing).
            val terms = query.trim().split("\\s+".toRegex())
            val ftsQuery = terms.joinToString(" ") { word ->
                val lc = word.lowercase()
                if (lc == word) "$lc*" else "$lc* OR $word*"
            }

            Log.d(TAG, "Search '$query' → FTS query: '$ftsQuery'")
            val allDetails = bookFtsDao.searchWithDetails(ftsQuery, limit * 3)

            // Post-filter: only keep results that match in title or authors
            val lcTerms = terms.map { it.lowercase() }
            val details = allDetails.filter { entry ->
                lcTerms.any { term ->
                    entry.title.contains(term, ignoreCase = true) ||
                    entry.authors.contains(term, ignoreCase = true)
                }
            }.take(limit)

            val results = details.map { it.bookId }
            Log.d(TAG, "Search '$query' returned ${results.size} results (${allDetails.size} before title/author filter)")
            details.forEach { entry ->
                val matched = buildList {
                    if (lcTerms.any { entry.title.contains(it, ignoreCase = true) })
                        add("title: \"${entry.title.take(60)}\"")
                    if (entry.authors.isNotEmpty() && lcTerms.any { entry.authors.contains(it, ignoreCase = true) })
                        add("authors: \"${entry.authors.take(60)}\"")
                }
                Log.d(TAG, "  id=${entry.bookId} → ${matched.joinToString(" | ")}")
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for query: $query", e)
            emptyList()
        }
    }

    /**
     * Search by specific field
     */
    suspend fun searchByField(field: String, value: String, limit: Int = 50): List<Long> =
        withContext(Dispatchers.IO) {
            if (value.isBlank()) {
                return@withContext emptyList()
            }

            try {
                val ftsQuery = "$field:${value.trim()}*"
                val results = bookFtsDao.search(ftsQuery, limit)
                results
            } catch (e: Exception) {
                Log.e(TAG, "Field search failed: $field=$value", e)
                emptyList()
            }
        }

    /**
     * Commit pending changes to the index (no-op for FTS, kept for API compatibility)
     */
    suspend fun commit() = withContext(Dispatchers.IO) {
        // No-op - SQLite commits automatically
        Log.d(TAG, "Index commit (no-op for FTS)")
    }

    /**
     * Get the number of documents in the index
     */
    suspend fun getDocumentCount(): Int = withContext(Dispatchers.IO) {
        try {
            bookFtsDao.getIndexedCount()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Clear all documents from the index
     */
    suspend fun clearIndex() = withContext(Dispatchers.IO) {
        try {
            bookFtsDao.clearAll()
            Log.d(TAG, "Index cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear index", e)
        }
    }

    /**
     * Close the index (no-op for FTS, kept for API compatibility)
     */
    suspend fun close() = withContext(Dispatchers.IO) {
        isInitialized = false
        Log.d(TAG, "Index closed (no-op for FTS)")
    }
}

/**
 * Data class for batch indexing
 */
data class BookIndexData(
    val book: Book,
    val authors: List<Author>,
    val series: Series?,
    val genres: List<Genre>
)
