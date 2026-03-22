package com.example.opdslibrary.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.data.AppPreferences
import com.example.opdslibrary.data.library.*
import com.example.opdslibrary.library.search.BookSearchManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Library screen
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "LibraryViewModel"
        const val RECENT_PAGE_SIZE = 20
    }

    private val database = AppDatabase.getDatabase(application)
    private val bookDao = database.bookDao()
    private val authorDao = database.authorDao()
    private val seriesDao = database.seriesDao()
    private val genreDao = database.genreDao()
    private val searchManager = BookSearchManager(application)
    private val appPreferences = AppPreferences(application)

    // Browse mode
    enum class BrowseMode {
        ALL_BOOKS,
        BY_AUTHOR,
        BY_SERIES,
        BY_GENRE,
        RECENT,
        SEARCH_RESULTS
    }

    // Sort order
    enum class SortOrder {
        TITLE_ASC,
        TITLE_DESC,
        AUTHOR_ASC,
        AUTHOR_DESC,
        DATE_ADDED_DESC,
        DATE_ADDED_ASC
    }

    // Sort type (for UI)
    enum class SortType {
        TITLE,
        AUTHOR,
        DATE_ADDED;

        fun toSortOrder(ascending: Boolean): SortOrder {
            return when (this) {
                TITLE -> if (ascending) SortOrder.TITLE_ASC else SortOrder.TITLE_DESC
                AUTHOR -> if (ascending) SortOrder.AUTHOR_ASC else SortOrder.AUTHOR_DESC
                DATE_ADDED -> if (ascending) SortOrder.DATE_ADDED_ASC else SortOrder.DATE_ADDED_DESC
            }
        }

        companion object {
            fun fromSortOrder(sortOrder: SortOrder): SortType {
                return when (sortOrder) {
                    SortOrder.TITLE_ASC, SortOrder.TITLE_DESC -> TITLE
                    SortOrder.AUTHOR_ASC, SortOrder.AUTHOR_DESC -> AUTHOR
                    SortOrder.DATE_ADDED_DESC, SortOrder.DATE_ADDED_ASC -> DATE_ADDED
                }
            }

            fun isAscending(sortOrder: SortOrder): Boolean {
                return when (sortOrder) {
                    SortOrder.TITLE_ASC, SortOrder.AUTHOR_ASC, SortOrder.DATE_ADDED_ASC -> true
                    SortOrder.TITLE_DESC, SortOrder.AUTHOR_DESC, SortOrder.DATE_ADDED_DESC -> false
                }
            }

            /**
             * Get available sort types for a given browse mode
             */
            fun getAvailableForMode(mode: BrowseMode): List<SortType> {
                return when (mode) {
                    BrowseMode.ALL_BOOKS -> listOf(TITLE, AUTHOR, DATE_ADDED)
                    BrowseMode.BY_AUTHOR -> listOf(TITLE, DATE_ADDED) // No author sort when already filtered by author
                    BrowseMode.BY_SERIES -> listOf(TITLE, AUTHOR, DATE_ADDED)
                    BrowseMode.BY_GENRE -> listOf(TITLE, AUTHOR, DATE_ADDED)
                    BrowseMode.RECENT -> listOf(TITLE, AUTHOR, DATE_ADDED)
                    BrowseMode.SEARCH_RESULTS -> listOf(TITLE, AUTHOR, DATE_ADDED)
                }
            }
        }
    }

    // UI State
    data class LibraryUiState(
        val browseMode: BrowseMode = BrowseMode.RECENT,
        val sortOrder: SortOrder = SortOrder.DATE_ADDED_DESC,
        val searchQuery: String = "",
        val selectedAuthorId: Long? = null,
        val selectedSeriesId: Long? = null,
        val selectedGenreId: Long? = null,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        // Pagination for RECENT mode
        val recentPage: Int = 0,
        val hasMoreRecentBooks: Boolean = true,
        val isLoadingMoreRecent: Boolean = false
    )

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Recent books list with pagination
    private val _recentBooks = MutableStateFlow<List<BookWithDetails>>(emptyList())
    val recentBooks: StateFlow<List<BookWithDetails>> = _recentBooks.asStateFlow()

    // Books list based on current browse mode
    val books: StateFlow<List<BookWithDetails>> = _uiState
        .flatMapLatest { state ->
            when (state.browseMode) {
                BrowseMode.ALL_BOOKS -> {
                    when (state.sortOrder) {
                        SortOrder.TITLE_ASC -> bookDao.getAllBooksWithDetailsByTitle()
                        SortOrder.TITLE_DESC -> bookDao.getAllBooksWithDetailsByTitleDesc()
                        SortOrder.AUTHOR_ASC -> bookDao.getAllBooksWithDetailsByAuthor()
                        SortOrder.AUTHOR_DESC -> bookDao.getAllBooksWithDetailsByAuthorDesc()
                        SortOrder.DATE_ADDED_DESC -> bookDao.getAllBooksWithDetailsByDateDesc()
                        SortOrder.DATE_ADDED_ASC -> bookDao.getAllBooksWithDetailsByDate()
                    }
                }
                BrowseMode.BY_AUTHOR -> {
                    state.selectedAuthorId?.let { authorId ->
                        bookDao.getBooksForAuthor(authorId).map { books ->
                            applySortOrder(books, state.sortOrder)
                        }
                    } ?: flowOf(emptyList())
                }
                BrowseMode.BY_SERIES -> {
                    state.selectedSeriesId?.let { seriesId ->
                        bookDao.getBooksForSeries(seriesId).map { books ->
                            applySortOrder(books, state.sortOrder)
                        }
                    } ?: flowOf(emptyList())
                }
                BrowseMode.BY_GENRE -> {
                    state.selectedGenreId?.let { genreId ->
                        bookDao.getBooksForGenre(genreId).map { books ->
                            applySortOrder(books, state.sortOrder)
                        }
                    } ?: flowOf(emptyList())
                }
                BrowseMode.RECENT -> {
                    // Return the paginated recent books flow with applied sort
                    _recentBooks.map { books ->
                        applySortOrder(books, state.sortOrder)
                    }
                }
                BrowseMode.SEARCH_RESULTS -> {
                    // Search results are handled separately via searchBooks()
                    flowOf(emptyList())
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Search results (when in search mode)
    private val _searchResults = MutableStateFlow<List<BookWithDetails>>(emptyList())
    val searchResults: StateFlow<List<BookWithDetails>> = _searchResults.asStateFlow()

    // Authors list for browse mode
    val authors: StateFlow<List<AuthorWithBookCount>> = authorDao.getAllAuthorsWithBookCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Series list for browse mode
    val series: StateFlow<List<SeriesWithBookCount>> = seriesDao.getAllSeriesWithBookCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Genres list for browse mode
    val genres: StateFlow<List<GenreWithBookCount>> = genreDao.getAllGenresWithBookCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Search history
    val searchHistory: StateFlow<List<String>> = appPreferences.librarySearchHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Library statistics
    val totalBooks: StateFlow<Int> = bookDao.getBookCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val totalAuthors: StateFlow<Int> = authorDao.getAuthorCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    val totalSeries: StateFlow<Int> = seriesDao.getSeriesCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    init {
        viewModelScope.launch {
            searchManager.initialize()

            // Load saved library view mode settings
            val savedBrowseMode = appPreferences.getLibraryBrowseModeOnce()
            val savedSortOrder = appPreferences.getLibrarySortOrderOnce()

            val browseMode = try {
                BrowseMode.valueOf(savedBrowseMode)
            } catch (e: Exception) {
                BrowseMode.RECENT
            }

            val sortOrder = try {
                SortOrder.valueOf(savedSortOrder)
            } catch (e: Exception) {
                SortOrder.DATE_ADDED_DESC
            }

            // Only apply saved browse mode if no external filter was applied before init
            // completed (e.g. navigating to library/author/{id} calls selectAuthor before
            // this coroutine resumes from IO, so we must not overwrite that selection).
            _uiState.update { current ->
                val effectiveMode = when {
                    current.selectedAuthorId != null -> BrowseMode.BY_AUTHOR
                    current.selectedSeriesId != null -> BrowseMode.BY_SERIES
                    current.selectedGenreId  != null -> BrowseMode.BY_GENRE
                    else -> browseMode
                }
                current.copy(browseMode = effectiveMode, sortOrder = sortOrder)
            }

            // Load recent books only when no filter took priority
            val finalState = _uiState.value
            if (finalState.browseMode == BrowseMode.RECENT &&
                finalState.selectedAuthorId == null &&
                finalState.selectedSeriesId == null &&
                finalState.selectedGenreId  == null) {
                loadRecentBooks()
            }
        }
    }

    /**
     * Set browse mode
     * Resets selection when switching to a browse-by mode
     */
    fun setBrowseMode(mode: BrowseMode) {
        _uiState.update {
            when (mode) {
                BrowseMode.BY_AUTHOR -> it.copy(
                    browseMode = mode,
                    selectedAuthorId = null,
                    selectedSeriesId = null,
                    selectedGenreId = null
                )
                BrowseMode.BY_SERIES -> it.copy(
                    browseMode = mode,
                    selectedAuthorId = null,
                    selectedSeriesId = null,
                    selectedGenreId = null
                )
                BrowseMode.BY_GENRE -> it.copy(
                    browseMode = mode,
                    selectedAuthorId = null,
                    selectedSeriesId = null,
                    selectedGenreId = null
                )
                BrowseMode.RECENT -> it.copy(
                    browseMode = mode,
                    selectedAuthorId = null,
                    selectedSeriesId = null,
                    selectedGenreId = null,
                    recentPage = 0,
                    hasMoreRecentBooks = true
                )
                else -> it.copy(
                    browseMode = mode,
                    selectedAuthorId = null,
                    selectedSeriesId = null,
                    selectedGenreId = null
                )
            }
        }
        // Load recent books when switching to RECENT mode
        if (mode == BrowseMode.RECENT) {
            loadRecentBooks()
        }
        // Save browse mode preference (skip SEARCH_RESULTS as it's transient)
        if (mode != BrowseMode.SEARCH_RESULTS) {
            viewModelScope.launch {
                appPreferences.setLibraryBrowseMode(mode.name)
            }
        }
    }

    /**
     * Set sort order
     */
    fun setSortOrder(order: SortOrder) {
        _uiState.update { it.copy(sortOrder = order) }
        // Save sort order preference
        viewModelScope.launch {
            appPreferences.setLibrarySortOrder(order.name)
        }
    }

    /**
     * Set sort type (Title, Author, or Date) while preserving current direction
     */
    fun setSortType(type: SortType) {
        val currentAscending = SortType.isAscending(_uiState.value.sortOrder)
        val newOrder = type.toSortOrder(currentAscending)
        setSortOrder(newOrder)
    }

    /**
     * Toggle sort direction (ascending/descending) while preserving current type
     */
    fun toggleSortDirection() {
        val currentType = SortType.fromSortOrder(_uiState.value.sortOrder)
        val currentAscending = SortType.isAscending(_uiState.value.sortOrder)
        val newOrder = currentType.toSortOrder(!currentAscending)
        setSortOrder(newOrder)
    }

    /**
     * Get current sort type
     */
    fun getCurrentSortType(): SortType {
        return SortType.fromSortOrder(_uiState.value.sortOrder)
    }

    /**
     * Check if current sort is ascending
     */
    fun isSortAscending(): Boolean {
        return SortType.isAscending(_uiState.value.sortOrder)
    }

    /**
     * Get available sort types for current browse mode
     */
    fun getAvailableSortTypes(): List<SortType> {
        return SortType.getAvailableForMode(_uiState.value.browseMode)
    }

    /**
     * Apply sort order to a list of books
     * Used for filtered views (BY_AUTHOR, BY_SERIES, BY_GENRE, RECENT)
     */
    private fun applySortOrder(books: List<BookWithDetails>, sortOrder: SortOrder): List<BookWithDetails> {
        return when (sortOrder) {
            SortOrder.TITLE_ASC -> books.sortedBy { it.book.titleSort.lowercase() }
            SortOrder.TITLE_DESC -> books.sortedByDescending { it.book.titleSort.lowercase() }
            SortOrder.AUTHOR_ASC -> books.sortedBy {
                it.authors.firstOrNull()?.sortName?.lowercase() ?: ""
            }
            SortOrder.AUTHOR_DESC -> books.sortedByDescending {
                it.authors.firstOrNull()?.sortName?.lowercase() ?: ""
            }
            SortOrder.DATE_ADDED_ASC -> books.sortedBy { it.book.addedAt }
            SortOrder.DATE_ADDED_DESC -> books.sortedByDescending { it.book.addedAt }
        }
    }

    /**
     * Load the first page of recent books
     */
    fun loadRecentBooks() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, recentPage = 0) }
                val books = bookDao.getRecentBooksPagedOnce(RECENT_PAGE_SIZE, 0)
                _recentBooks.value = books
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        recentPage = 0,
                        hasMoreRecentBooks = books.size >= RECENT_PAGE_SIZE
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load recent books", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load recent books: ${e.message}") }
            }
        }
    }

    /**
     * Load more recent books (next page)
     */
    fun loadMoreRecentBooks() {
        val currentState = _uiState.value
        if (currentState.isLoadingMoreRecent || !currentState.hasMoreRecentBooks) {
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoadingMoreRecent = true) }
                val nextPage = currentState.recentPage + 1
                val offset = nextPage * RECENT_PAGE_SIZE
                val moreBooks = bookDao.getRecentBooksPagedOnce(RECENT_PAGE_SIZE, offset)

                if (moreBooks.isNotEmpty()) {
                    _recentBooks.value = _recentBooks.value + moreBooks
                }

                _uiState.update {
                    it.copy(
                        isLoadingMoreRecent = false,
                        recentPage = nextPage,
                        hasMoreRecentBooks = moreBooks.size >= RECENT_PAGE_SIZE
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load more recent books", e)
                _uiState.update { it.copy(isLoadingMoreRecent = false, errorMessage = "Failed to load more books: ${e.message}") }
            }
        }
    }

    /**
     * Select an author for browsing
     */
    fun selectAuthor(authorId: Long) {
        _uiState.update {
            it.copy(
                browseMode = BrowseMode.BY_AUTHOR,
                selectedAuthorId = authorId
            )
        }
    }

    /**
     * Select a series for browsing
     */
    fun selectSeries(seriesId: Long) {
        _uiState.update {
            it.copy(
                browseMode = BrowseMode.BY_SERIES,
                selectedSeriesId = seriesId
            )
        }
    }

    /**
     * Select a genre for browsing
     */
    fun selectGenre(genreId: Long) {
        _uiState.update {
            it.copy(
                browseMode = BrowseMode.BY_GENRE,
                selectedGenreId = genreId
            )
        }
    }

    /**
     * Search books using Lucene full-text search
     */
    fun searchBooks(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(browseMode = BrowseMode.ALL_BOOKS, searchQuery = "") }
            _searchResults.value = emptyList()
            return
        }

        _uiState.update {
            it.copy(
                browseMode = BrowseMode.SEARCH_RESULTS,
                searchQuery = query,
                isLoading = true
            )
        }

        viewModelScope.launch {
            try {
                val bookIds = searchManager.search(query, 100)
                if (bookIds.isNotEmpty()) {
                    val results = bookDao.getBooksByIds(bookIds)
                    _searchResults.value = results
                } else {
                    _searchResults.value = emptyList()
                }
                appPreferences.addLibrarySearchHistory(query)
            } catch (e: Exception) {
                Log.e(TAG, "Search failed", e)
                _uiState.update { it.copy(errorMessage = "Search failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Get book details by ID
     */
    suspend fun getBookDetails(bookId: Long): BookWithDetails? {
        return withContext(Dispatchers.IO) {
            bookDao.getBookWithDetailsById(bookId)
        }
    }

    /**
     * Delete a book from the library
     */
    fun deleteBook(bookId: Long, deleteFile: Boolean = false) {
        viewModelScope.launch {
            try {
                val book = bookDao.getBookById(bookId) ?: return@launch

                if (deleteFile) {
                    // Try to delete the physical file
                    try {
                        val uri = Uri.parse(book.filePath)
                        val context = getApplication<Application>()
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not delete file: ${book.filePath}", e)
                    }
                }

                // Remove from Lucene index
                searchManager.removeBook(bookId)

                // Delete from database
                bookDao.delete(book)

                Log.d(TAG, "Deleted book: ${book.title}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete book", e)
                _uiState.update { it.copy(errorMessage = "Failed to delete book: ${e.message}") }
            }
        }
    }

    /**
     * Rename a book file and update the database
     */
    fun renameBookFile(
        bookId: Long,
        currentPath: String,
        newFileName: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                // Don't allow renaming content:// URIs
                if (currentPath.startsWith("content://")) {
                    withContext(Dispatchers.Main) {
                        onError("Cannot rename files stored via SAF")
                    }
                    return@launch
                }

                val currentFile = java.io.File(currentPath)
                if (!currentFile.exists()) {
                    withContext(Dispatchers.Main) {
                        onError("File not found")
                    }
                    return@launch
                }

                val parentDir = currentFile.parentFile
                val newFile = java.io.File(parentDir, newFileName)

                // Check if target already exists
                if (newFile.exists() && newFile.absolutePath != currentFile.absolutePath) {
                    withContext(Dispatchers.Main) {
                        onError("A file with this name already exists")
                    }
                    return@launch
                }

                // Rename the file
                val success = currentFile.renameTo(newFile)
                if (!success) {
                    withContext(Dispatchers.Main) {
                        onError("Failed to rename file")
                    }
                    return@launch
                }

                // Update the database
                bookDao.updateFilePath(bookId, newFile.absolutePath)
                Log.d(TAG, "Renamed book file: $currentPath -> ${newFile.absolutePath}")

                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename book file", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Clear all books from the library
     */
    fun clearLibrary() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }

                // Clear Lucene index
                searchManager.clearIndex()

                // Clear database tables
                bookDao.deleteAll()
                authorDao.deleteOrphanedAuthors()
                seriesDao.deleteOrphanedSeries()
                genreDao.deleteOrphanedGenres()

                Log.d(TAG, "Library cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear library", e)
                _uiState.update { it.copy(errorMessage = "Failed to clear library: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Remove a single item from search history
     */
    fun removeSearchHistoryItem(query: String) {
        viewModelScope.launch { appPreferences.removeLibrarySearchHistoryItem(query) }
    }

    /**
     * Clear search history
     */
    fun clearSearchHistory() {
        viewModelScope.launch { appPreferences.clearLibrarySearchHistory() }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            searchManager.close()
        }
    }
}
