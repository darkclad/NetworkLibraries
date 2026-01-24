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

    // UI State
    data class LibraryUiState(
        val browseMode: BrowseMode = BrowseMode.ALL_BOOKS,
        val sortOrder: SortOrder = SortOrder.TITLE_ASC,
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
                        bookDao.getBooksForAuthor(authorId)
                    } ?: flowOf(emptyList())
                }
                BrowseMode.BY_SERIES -> {
                    state.selectedSeriesId?.let { seriesId ->
                        bookDao.getBooksForSeries(seriesId)
                    } ?: flowOf(emptyList())
                }
                BrowseMode.BY_GENRE -> {
                    state.selectedGenreId?.let { genreId ->
                        bookDao.getBooksForGenre(genreId)
                    } ?: flowOf(emptyList())
                }
                BrowseMode.RECENT -> {
                    // Return the paginated recent books flow
                    _recentBooks
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
                BrowseMode.ALL_BOOKS
            }

            val sortOrder = try {
                SortOrder.valueOf(savedSortOrder)
            } catch (e: Exception) {
                SortOrder.TITLE_ASC
            }

            _uiState.update { it.copy(browseMode = browseMode, sortOrder = sortOrder) }

            // Load recent books if that's the saved mode
            if (browseMode == BrowseMode.RECENT) {
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
