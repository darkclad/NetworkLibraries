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
        const val PAGE_SIZE = 50
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
                    BrowseMode.SEARCH_RESULTS -> listOf(TITLE, AUTHOR, DATE_ADDED)
                }
            }
        }
    }

    // UI State
    data class LibraryUiState(
        val browseMode: BrowseMode = BrowseMode.ALL_BOOKS,
        val sortOrder: SortOrder = SortOrder.DATE_ADDED_DESC,
        val searchQuery: String = "",
        val selectedAuthorId: Long? = null,
        val selectedSeriesId: Long? = null,
        val selectedGenreId: Long? = null,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        // Pagination for ALL_BOOKS mode
        val currentPage: Int = 0,
        val hasMoreBooks: Boolean = true,
        val isLoadingMore: Boolean = false
    )

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    // Debug: track every browseMode change with stack trace
    init {
        viewModelScope.launch {
            var lastMode: BrowseMode? = null
            _uiState.collect { state ->
                if (state.browseMode != lastMode) {
                    Log.w(TAG, "BROWSE_MODE_CHANGE: $lastMode -> ${state.browseMode}, authorId=${state.selectedAuthorId}, instance=${System.identityHashCode(this@LibraryViewModel)}")
                    lastMode = state.browseMode
                }
            }
        }
    }

    // Paginated books list for ALL_BOOKS mode
    private val _pagedBooks = MutableStateFlow<List<BookWithDetails>>(emptyList())

    // Books list based on current browse mode
    // Only re-trigger when browse-relevant state changes, not pagination fields
    private data class BrowseKey(
        val browseMode: BrowseMode,
        val sortOrder: SortOrder,
        val selectedAuthorId: Long?,
        val selectedSeriesId: Long?,
        val selectedGenreId: Long?
    )

    val books: StateFlow<List<BookWithDetails>> = _uiState
        .map { BrowseKey(it.browseMode, it.sortOrder, it.selectedAuthorId, it.selectedSeriesId, it.selectedGenreId) }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            when (key.browseMode) {
                BrowseMode.ALL_BOOKS -> {
                    // Paginated - data loaded via loadBooks/loadMoreBooks
                    _pagedBooks
                }
                BrowseMode.BY_AUTHOR -> {
                    key.selectedAuthorId?.let { authorId ->
                        bookDao.getBooksForAuthor(authorId).map { books ->
                            applySortOrder(books, key.sortOrder)
                        }
                    } ?: flowOf(emptyList())
                }
                BrowseMode.BY_SERIES -> {
                    key.selectedSeriesId?.let { seriesId ->
                        bookDao.getBooksForSeries(seriesId).map { books ->
                            applySortOrder(books, key.sortOrder)
                        }
                    } ?: flowOf(emptyList())
                }
                BrowseMode.BY_GENRE -> {
                    key.selectedGenreId?.let { genreId ->
                        bookDao.getBooksForGenre(genreId).map { books ->
                            applySortOrder(books, key.sortOrder)
                        }
                    } ?: flowOf(emptyList())
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
        Log.d(TAG, "init: ViewModel created, instance=${System.identityHashCode(this)}")
        viewModelScope.launch {
            searchManager.initialize()

            // Load saved library view mode settings
            val savedBrowseMode = appPreferences.getLibraryBrowseModeOnce()
            val savedSortOrder = appPreferences.getLibrarySortOrderOnce()

            val browseMode = try {
                val mode = BrowseMode.valueOf(savedBrowseMode)
                // Migrate old RECENT mode to ALL_BOOKS
                if (mode.name == "RECENT") BrowseMode.ALL_BOOKS else mode
            } catch (e: Exception) {
                BrowseMode.ALL_BOOKS
            }

            val sortOrder = try {
                SortOrder.valueOf(savedSortOrder)
            } catch (e: Exception) {
                SortOrder.DATE_ADDED_DESC
            }

            // Only apply saved browse mode if no external filter was applied before init
            _uiState.update { current ->
                val effectiveMode = when {
                    current.selectedAuthorId != null -> BrowseMode.BY_AUTHOR
                    current.selectedSeriesId != null -> BrowseMode.BY_SERIES
                    current.selectedGenreId  != null -> BrowseMode.BY_GENRE
                    else -> browseMode
                }
                Log.d(TAG, "init: saved=$browseMode, effective=$effectiveMode, sortOrder=$sortOrder, authorId=${current.selectedAuthorId}, seriesId=${current.selectedSeriesId}")
                current.copy(browseMode = effectiveMode, sortOrder = sortOrder)
            }

            // Load first page for ALL_BOOKS mode
            val finalState = _uiState.value
            Log.d(TAG, "init: finalState browseMode=${finalState.browseMode}, authorId=${finalState.selectedAuthorId}")
            if (finalState.browseMode == BrowseMode.ALL_BOOKS &&
                finalState.selectedAuthorId == null &&
                finalState.selectedSeriesId == null &&
                finalState.selectedGenreId  == null) {
                loadBooks()
            }
        }
    }

    /**
     * Set browse mode
     * Resets selection when switching to a browse-by mode
     */
    fun setBrowseMode(mode: BrowseMode) {
        Log.d(TAG, "setBrowseMode: mode=$mode, instance=${System.identityHashCode(this)}")
        _uiState.update {
            it.copy(
                browseMode = mode,
                selectedAuthorId = null,
                selectedSeriesId = null,
                selectedGenreId = null,
                currentPage = 0,
                hasMoreBooks = true
            )
        }
        // Load first page when switching to ALL_BOOKS
        if (mode == BrowseMode.ALL_BOOKS) {
            loadBooks()
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
        _uiState.update { it.copy(sortOrder = order, currentPage = 0, hasMoreBooks = true) }
        // Reload paginated books with new sort
        if (_uiState.value.browseMode == BrowseMode.ALL_BOOKS) {
            loadBooks()
        }
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
     * Used for filtered views (BY_AUTHOR, BY_SERIES, BY_GENRE)
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
     * Fetch a page of books from the DB using the current sort order
     */
    private suspend fun fetchPage(sortOrder: SortOrder, limit: Int, offset: Int): List<BookWithDetails> {
        return when (sortOrder) {
            SortOrder.TITLE_ASC -> bookDao.getBooksPaged_TitleAsc(limit, offset)
            SortOrder.TITLE_DESC -> bookDao.getBooksPaged_TitleDesc(limit, offset)
            SortOrder.AUTHOR_ASC -> bookDao.getBooksPaged_AuthorAsc(limit, offset)
            SortOrder.AUTHOR_DESC -> bookDao.getBooksPaged_AuthorDesc(limit, offset)
            SortOrder.DATE_ADDED_ASC -> bookDao.getBooksPaged_DateAsc(limit, offset)
            SortOrder.DATE_ADDED_DESC -> bookDao.getBooksPaged_DateDesc(limit, offset)
        }
    }

    /**
     * Load the first page of books
     */
    fun loadBooks() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, currentPage = 0) }
                val sortOrder = _uiState.value.sortOrder
                val books = fetchPage(sortOrder, PAGE_SIZE, 0)
                _pagedBooks.value = books
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentPage = 0,
                        hasMoreBooks = books.size >= PAGE_SIZE
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load books", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to load books: ${e.message}") }
            }
        }
    }

    /**
     * Load more books (next page)
     */
    fun loadMoreBooks() {
        val currentState = _uiState.value
        if (currentState.isLoadingMore || !currentState.hasMoreBooks) {
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoadingMore = true) }
                val nextPage = currentState.currentPage + 1
                val offset = nextPage * PAGE_SIZE
                val moreBooks = fetchPage(currentState.sortOrder, PAGE_SIZE, offset)

                if (moreBooks.isNotEmpty()) {
                    _pagedBooks.value = _pagedBooks.value + moreBooks
                }

                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        currentPage = nextPage,
                        hasMoreBooks = moreBooks.size >= PAGE_SIZE
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load more books", e)
                _uiState.update { it.copy(isLoadingMore = false, errorMessage = "Failed to load more books: ${e.message}") }
            }
        }
    }

    /**
     * Select an author for browsing
     */
    fun selectAuthor(authorId: Long) {
        Log.d(TAG, "selectAuthor: authorId=$authorId, instance=${System.identityHashCode(this)}")
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
            // Only clear search state, don't change browse mode
            // (this gets called on every recomposition with empty search field)
            if (_uiState.value.browseMode == BrowseMode.SEARCH_RESULTS) {
                _uiState.update { it.copy(browseMode = BrowseMode.ALL_BOOKS, searchQuery = "") }
            }
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
    suspend fun deleteBook(bookId: Long, deleteFile: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val book = bookDao.getBookById(bookId) ?: return@withContext false

                if (deleteFile) {
                    try {
                        val uri = Uri.parse(book.filePath)
                        val context = getApplication<Application>()
                        if (uri.scheme == "content") {
                            android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
                        } else {
                            val file = java.io.File(uri.path ?: book.filePath)
                            file.delete()
                        }
                        Log.d(TAG, "Deleted file: ${book.filePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not delete file: ${book.filePath}", e)
                        _uiState.update { it.copy(errorMessage = "Could not delete file: ${e.message}") }
                        return@withContext false
                    }
                }

                searchManager.removeBook(bookId)
                bookDao.delete(book)
                Log.d(TAG, "Deleted book: ${book.title}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete book", e)
                _uiState.update { it.copy(errorMessage = "Failed to delete book: ${e.message}") }
                false
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
