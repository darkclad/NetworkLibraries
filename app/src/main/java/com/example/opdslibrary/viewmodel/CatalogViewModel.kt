package com.example.opdslibrary.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.data.FavoriteEntry
import com.example.opdslibrary.data.OpdsLink
import com.example.opdslibrary.data.OpdsCatalog
import com.example.opdslibrary.data.OpdsFeed
import com.example.opdslibrary.data.OpdsEntry
import com.example.opdslibrary.network.NotFoundException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.opdslibrary.network.OpdsRepository
import com.example.opdslibrary.network.UnauthorizedException
import com.example.opdslibrary.utils.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI State for the catalog screen
 */
sealed class CatalogUiState {
    object Initial : CatalogUiState()
    object Loading : CatalogUiState()
    data class Success(
        val feed: OpdsFeed,
        val baseUrl: String,
        val isLoadingMore: Boolean = false,
        val hasNextPage: Boolean = false,
        val catalogIcon: String? = null
    ) : CatalogUiState()
    data class Error(val message: String) : CatalogUiState()
}

/**
 * Authentication state
 */
data class AuthState(
    val isRequired: Boolean = false,
    val url: String = "",
    val attemptCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * Catalog not found state (404)
 */
data class CatalogNotFoundState(
    val catalogId: Long,
    val catalogName: String,
    val url: String
)

/**
 * ViewModel for managing OPDS catalog browsing
 */
class CatalogViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = OpdsRepository(application.applicationContext)
    private val database = AppDatabase.getDatabase(application)
    private val catalogDao = database.catalogDao()
    private val favoriteDao = database.favoriteDao()
    private val gson = Gson()

    private val _uiState = MutableStateFlow<CatalogUiState>(CatalogUiState.Initial)
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _catalogTitle = MutableStateFlow("OPDS Library")
    val catalogTitle: StateFlow<String> = _catalogTitle.asStateFlow()

    private val _currentPageTitle = MutableStateFlow("")
    val currentPageTitle: StateFlow<String> = _currentPageTitle.asStateFlow()

    private val _canNavigateBack = MutableStateFlow(false)
    val canNavigateBack: StateFlow<Boolean> = _canNavigateBack.asStateFlow()

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _isNetworkActive = MutableStateFlow(false)
    val isNetworkActive: StateFlow<Boolean> = _isNetworkActive.asStateFlow()

    private val _catalogNotFoundState = MutableStateFlow<CatalogNotFoundState?>(null)
    val catalogNotFoundState: StateFlow<CatalogNotFoundState?> = _catalogNotFoundState.asStateFlow()

    private val _shouldExitCatalog = MutableStateFlow(false)
    val shouldExitCatalog: StateFlow<Boolean> = _shouldExitCatalog.asStateFlow()

    private val _isCurrentFeedFromCache = MutableStateFlow(false)
    val isCurrentFeedFromCache: StateFlow<Boolean> = _isCurrentFeedFromCache.asStateFlow()

    private val _scrollToEntryIndex = MutableStateFlow(-1)
    val scrollToEntryIndex: StateFlow<Int> = _scrollToEntryIndex.asStateFlow()

    private val _isBrowsingFavorites = MutableStateFlow(false)
    val isBrowsingFavorites: StateFlow<Boolean> = _isBrowsingFavorites.asStateFlow()

    // Store the catalog base URL when browsing favorites (for resolving relative links)
    private var favoritesCatalogBaseUrl: String? = null

    // Current book being viewed (null means showing feed)
    private val _currentBook = MutableStateFlow<OpdsEntry?>(null)
    val currentBook: StateFlow<OpdsEntry?> = _currentBook.asStateFlow()

    // Index of the book in the feed (for scroll restoration)
    private var currentBookIndex: Int = -1

    // Navigation history with entry indices for scroll restoration
    private val navigationHistory = mutableListOf<NavigationHistoryEntry>()

    // Breadcrumb trail for current navigation path (for favorites hierarchy)
    private val breadcrumbTitles = mutableListOf<String>()
    private val breadcrumbUrls = mutableListOf<String>()

    private var savedCatalogName: String = "" // Name from saved catalog data
    private var catalogName: String = "" // Name of the root catalog (from OPDS feed)
    private var rootUrl: String = "" // URL of the root catalog
    private var currentCatalogId: Long? = null // ID of the current catalog from database

    // Pagination state
    private var currentFeed: OpdsFeed? = null
    private var currentBaseUrl: String = ""
    private var nextPageUrl: String? = null
    private var accumulatedEntries = mutableListOf<OpdsEntry>()
    private var isLoadingMore = false
    private var catalogIcon: String? = null

    // Authentication credentials (session-based)
    private var storedUsername: String? = null
    private var storedPassword: String? = null

    companion object {
        private const val TAG = "CatalogViewModel"
        private const val FAVORITES_URL_PREFIX = "internal://favorites"
    }

    /**
     * Represents a navigation history entry
     * @param url The URL of the feed
     * @param title The title of the feed
     * @param updated The update timestamp from the feed
     * @param scrollToIndex The index of the entry that was clicked to navigate here (for back navigation)
     */
    private data class NavigationHistoryEntry(
        val url: String,
        val title: String,
        val updated: String?,
        val scrollToIndex: Int = -1,
        // For book details entries
        val bookEntry: OpdsEntry? = null,
        val feedEntryIndex: Int = -1
    )

    private var initialized = false

    /**
     * Initialize the catalog with a specific URL
     * Should be called once when the ViewModel is created
     */
    fun initializeCatalog(url: String) {
        if (!initialized) {
            initialized = true
            loadFeed(url)
        }
    }

    /**
     * Initialize the catalog by loading from database using catalog ID
     * Should be called once when the ViewModel is created
     */
    fun initializeCatalogById(catalogId: Long) {
        if (!initialized) {
            initialized = true
            currentCatalogId = catalogId
            viewModelScope.launch {
                try {
                    val catalog = catalogDao.getCatalogById(catalogId)
                    if (catalog != null) {
                        savedCatalogName = catalog.getDisplayName()
                        rootUrl = catalog.url
                        loadFeed(catalog.url)
                    } else {
                        _uiState.value = CatalogUiState.Error("Catalog not found")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load catalog from database", e)
                    _uiState.value = CatalogUiState.Error("Failed to load catalog: ${e.message}")
                }
            }
        }
    }

    /**
     * Update the title and navigation state based on current history
     */
    private fun updateNavigationState() {
        Log.d(TAG, "=== updateNavigationState called ===")
        Log.d(TAG, "Full history BEFORE update:")
        navigationHistory.forEachIndexed { index, entry ->
            Log.d(TAG, "  [$index] ${entry.title} -> ${entry.url} (updated: ${entry.updated}, scrollTo: ${entry.scrollToIndex})")
        }

        val currentPage = navigationHistory.lastOrNull()?.title ?: ""
        Log.d(TAG, "Current page from history: '$currentPage'")

        // Set catalog title (use saved name if available, otherwise use OPDS feed name)
        val newCatalogTitle = if (savedCatalogName.isNotEmpty()) {
            savedCatalogName
        } else {
            catalogName.ifEmpty { "OPDS Library" }
        }
        _catalogTitle.value = newCatalogTitle
        Log.d(TAG, "Set catalog title to: '$newCatalogTitle' (saved: $savedCatalogName, opds: $catalogName)")

        // Set current page title (empty if at root, otherwise the page title)
        val newPageTitle = if (currentPage == catalogName || currentPage.isEmpty()) {
            Log.d(TAG, "At root: currentPage='$currentPage' == catalogName='$catalogName'")
            "" // At root, don't show subtitle
        } else {
            Log.d(TAG, "Not at root: currentPage='$currentPage' != catalogName='$catalogName'")
            currentPage
        }
        _currentPageTitle.value = newPageTitle
        Log.d(TAG, "Set page title to: '$newPageTitle'")

        // Can navigate back if: 1) viewing a book, OR 2) history has more than one entry
        _canNavigateBack.value = _currentBook.value != null || navigationHistory.size > 1
        Log.d(TAG, "Can navigate back: ${_canNavigateBack.value} (history size: ${navigationHistory.size}, viewing book: ${_currentBook.value != null})")
        Log.d(TAG, "=== updateNavigationState complete ===")
    }

    /**
     * Load an OPDS feed from the given URL
     */
    fun loadFeed(url: String, addToHistory: Boolean = true, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            Log.d(TAG, "loadFeed: url=$url, addToHistory=$addToHistory, forceRefresh=$forceRefresh")

            // Handle favorites URLs specially
            if (url.startsWith(FAVORITES_URL_PREFIX)) {
                loadFavoritesFeed(url, addToHistory)
                return@launch
            }

            _isNetworkActive.value = true
            _uiState.value = CatalogUiState.Loading
            _currentUrl.value = url
            _isBrowsingFavorites.value = false
            favoritesCatalogBaseUrl = null  // Clear stored base URL when exiting favorites

            // Reset pagination when loading a new feed (not loading more)
            accumulatedEntries.clear()

            // Get parent update timestamp for cache validation (from previous feed in history)
            val parentUpdated = if (navigationHistory.isNotEmpty()) {
                navigationHistory.last().updated
            } else {
                null
            }

            repository.fetchFeed(url, storedUsername, storedPassword, forceRefresh, parentUpdated).fold(
                onSuccess = { result ->
                    val feed = result.feed
                    val fromCache = result.fromCache
                    Log.d(TAG, "Feed loaded successfully: ${feed.title} (updated: ${feed.updated}, fromCache: $fromCache)")

                    // Track cache status
                    _isCurrentFeedFromCache.value = fromCache

                    if (addToHistory) {
                        // Add to history without scroll index for now (will be set when navigating from an entry)
                        navigationHistory.add(NavigationHistoryEntry(url, feed.title, feed.updated))
                        Log.d(TAG, "Added to history: ${feed.title} (updated: ${feed.updated})")

                        // Update breadcrumbs for favorites hierarchy tracking
                        breadcrumbTitles.add(feed.title)
                        breadcrumbUrls.add(url)

                        // Store catalog name and root URL from the first feed loaded
                        if (catalogName.isEmpty()) {
                            catalogName = feed.title
                            rootUrl = url
                            Log.d(TAG, "Set catalog name: $catalogName, root URL: $rootUrl")
                        }
                    }
                    updateNavigationState()

                    // Inject Favorites entry at the top of the feed (only at root level)
                    val feedWithFavorites = if (navigationHistory.size == 1) {
                        val favoritesEntry = OpdsEntry(
                            title = "⭐ Favorites",
                            id = "favorites_root",
                            links = listOf(
                                OpdsLink(
                                    href = FAVORITES_URL_PREFIX,
                                    type = "application/atom+xml;profile=opds-catalog",
                                    rel = "subsection"
                                )
                            ),
                            updated = "",
                            content = "",
                            summary = "Your saved favorites",
                            author = null,
                            categories = emptyList()
                        )
                        val entriesWithFavorites = listOf(favoritesEntry) + feed.entries
                        feed.copy(entries = entriesWithFavorites)
                    } else {
                        feed
                    }

                    // Store feed and pagination info
                    currentFeed = feedWithFavorites
                    currentBaseUrl = url
                    accumulatedEntries.addAll(feed.entries)
                    nextPageUrl = feed.getNextPageLink()?.let { nextLink ->
                        if (nextLink.startsWith("http://") || nextLink.startsWith("https://")) {
                            nextLink
                        } else {
                            repository.resolveUrl(url, nextLink)
                        }
                    }

                    // Store catalog icon from the first feed
                    if (catalogIcon == null && feed.icon != null) {
                        catalogIcon = if (feed.icon.startsWith("http://") || feed.icon.startsWith("https://")) {
                            feed.icon
                        } else {
                            repository.resolveUrl(url, feed.icon)
                        }
                        Log.d(TAG, "Catalog icon: $catalogIcon")
                    }

                    Log.d(TAG, "Pagination: hasNext=${feedWithFavorites.hasNextPage()}, nextUrl=$nextPageUrl, entries=${feedWithFavorites.entries.size}")

                    _uiState.value = CatalogUiState.Success(
                        feed = feedWithFavorites,
                        baseUrl = url,
                        isLoadingMore = false,
                        hasNextPage = feedWithFavorites.hasNextPage(),
                        catalogIcon = catalogIcon
                    )
                    _isNetworkActive.value = false
                },
                onFailure = { exception ->
                    Log.e(TAG, "Failed to load feed", exception)

                    // Handle 404 Not Found
                    if (exception is NotFoundException && navigationHistory.size <= 1 && currentCatalogId != null) {
                        Log.w(TAG, "404 Not Found for root catalog - offering deletion")
                        // Only offer deletion if we're at the root (first load)
                        _catalogNotFoundState.value = CatalogNotFoundState(
                            catalogId = currentCatalogId!!,
                            catalogName = savedCatalogName.ifEmpty { "this catalog" },
                            url = url
                        )
                        _uiState.value = CatalogUiState.Error("Catalog not found (404)")
                    }
                    // Handle 401 Unauthorized
                    else if (exception is UnauthorizedException) {
                        Log.w(TAG, "Authentication required for: $url")
                        val currentAttempt = _authState.value.attemptCount

                        if (currentAttempt >= 3) {
                            Log.e(TAG, "Max authentication attempts (3) exceeded")
                            _authState.value = AuthState(
                                isRequired = false,
                                url = "",
                                attemptCount = 0,
                                errorMessage = "Authentication failed after 3 attempts"
                            )
                            _uiState.value = CatalogUiState.Error("Authentication failed")
                            // Navigate back after failed auth, or to root if at first page
                            if (navigationHistory.size <= 1) {
                                Log.d(TAG, "History size is ${navigationHistory.size}, navigating to root")
                                navigateToRoot()
                            } else {
                                Log.d(TAG, "History size is ${navigationHistory.size}, navigating back")
                                navigateBack()
                            }
                        } else {
                            // Show login dialog
                            _authState.value = AuthState(
                                isRequired = true,
                                url = url,
                                attemptCount = currentAttempt + 1,
                                errorMessage = if (currentAttempt > 0) "Invalid credentials, please try again" else null
                            )
                            // Keep current UI state (don't show error yet)
                        }
                    } else {
                        _uiState.value = CatalogUiState.Error(
                            exception.message ?: "Unknown error occurred"
                        )
                    }
                    _isNetworkActive.value = false
                }
            )
        }
    }

    /**
     * Navigate to a URL (handles relative URLs)
     * @param url The URL to navigate to
     * @param entryIndex The index of the entry that was clicked (for back navigation scrolling)
     */
    fun navigateToUrl(url: String, entryIndex: Int = -1) {
        Log.d(TAG, "navigateToUrl: $url, entryIndex: $entryIndex")

        // Close book details when navigating to a new feed
        _currentBook.value = null
        currentBookIndex = -1

        // Update the current history entry with the clicked entry index before navigating
        if (entryIndex >= 0 && navigationHistory.isNotEmpty()) {
            val lastIndex = navigationHistory.size - 1
            val currentEntry = navigationHistory[lastIndex]
            navigationHistory[lastIndex] = currentEntry.copy(scrollToIndex = entryIndex)
            Log.d(TAG, "  Updated history entry with scrollToIndex: $entryIndex")
        }

        val resolvedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            // Already absolute URL
            url
        } else if (url.startsWith(FAVORITES_URL_PREFIX)) {
            // Internal favorites URL - use as is
            url
        } else {
            // Relative URL - resolve against appropriate base URL
            val baseUrl = if (_isBrowsingFavorites.value && favoritesCatalogBaseUrl != null) {
                // When in favorites, use the stored catalog base URL
                favoritesCatalogBaseUrl!!
            } else {
                // Otherwise use current URL
                _currentUrl.value
            }
            repository.resolveUrl(baseUrl, url)
        }
        Log.d(TAG, "Resolved URL: $resolvedUrl")

        // Reset scroll index for the new page
        _scrollToEntryIndex.value = -1

        loadFeed(resolvedUrl)
    }

    /**
     * Show book details page
     */
    fun showBookDetails(book: OpdsEntry, index: Int) {
        Log.d(TAG, "Showing book details: ${book.title} at index $index")

        // Add book details to navigation history
        val historyEntry = NavigationHistoryEntry(
            url = _currentUrl.value,
            title = book.title,
            updated = book.updated,
            scrollToIndex = -1,
            bookEntry = book,
            feedEntryIndex = index
        )
        navigationHistory.add(historyEntry)
        Log.d(TAG, "  Added book details to history (size: ${navigationHistory.size})")

        _currentBook.value = book
        currentBookIndex = index
        updateNavigationState()
    }

    /**
     * Close book details page
     */
    fun closeBookDetails() {
        _currentBook.value = null
        if (currentBookIndex >= 0) {
            _scrollToEntryIndex.value = currentBookIndex
        }
    }

    /**
     * Navigate back in history
     */
    fun navigateBack(): Boolean {
        Log.d(TAG, "navigateBack called")

        if (navigationHistory.size > 1) {
            // Remove current entry
            navigationHistory.removeAt(navigationHistory.size - 1)

            // Also remove last breadcrumb
            if (breadcrumbTitles.isNotEmpty()) {
                breadcrumbTitles.removeAt(breadcrumbTitles.size - 1)
            }
            if (breadcrumbUrls.isNotEmpty()) {
                breadcrumbUrls.removeAt(breadcrumbUrls.size - 1)
            }

            val entry = navigationHistory.last()
            Log.d(TAG, "Going back to: ${entry.title}")

            // Check if this is a book details entry or a feed entry
            if (entry.bookEntry != null) {
                // Restore book details
                Log.d(TAG, "  Restoring book details: ${entry.bookEntry.title} at index ${entry.feedEntryIndex}")
                _currentBook.value = entry.bookEntry
                currentBookIndex = entry.feedEntryIndex
                // Make sure we're showing the feed that contains this book
                if (_currentUrl.value != entry.url) {
                    loadFeed(entry.url, addToHistory = false)
                }
                updateNavigationState()
            } else {
                // Restore feed
                Log.d(TAG, "  Restoring feed: ${entry.url} (scrollToIndex: ${entry.scrollToIndex})")
                _currentBook.value = null
                currentBookIndex = -1
                // Set the scroll index for this page
                _scrollToEntryIndex.value = entry.scrollToIndex
                loadFeed(entry.url, addToHistory = false)
            }
            return true
        }
        Log.d(TAG, "Cannot go back - history size: ${navigationHistory.size}")
        return false
    }

    /**
     * Navigate to root catalog
     */
    fun navigateToRoot() {
        Log.d(TAG, "navigateToRoot called")
        if (rootUrl.isNotEmpty()) {
            // Close book details
            _currentBook.value = null
            currentBookIndex = -1
            // Clear history, breadcrumbs and reload root
            navigationHistory.clear()
            breadcrumbTitles.clear()
            breadcrumbUrls.clear()
            loadFeed(rootUrl)
        } else {
            Log.d(TAG, "Root URL not set")
        }
    }

    /**
     * Clear navigation history
     */
    fun clearHistory() {
        Log.d(TAG, "clearHistory called")
        navigationHistory.clear()
        catalogName = ""
        rootUrl = ""
        catalogIcon = null
        updateNavigationState()
    }

    /**
     * Retry loading the current feed
     */
    fun retry() {
        if (_currentUrl.value.isNotEmpty()) {
            loadFeed(_currentUrl.value, addToHistory = false)
        }
    }

    /**
     * Refresh the current feed by reloading from the OPDS server
     * Forces a network fetch and updates the cache
     */
    fun refresh() {
        Log.d(TAG, "refresh called - forcing network fetch")
        if (_currentUrl.value.isNotEmpty()) {
            // Remove current page from history
            if (navigationHistory.isNotEmpty()) {
                navigationHistory.removeAt(navigationHistory.size - 1)
            }
            // Reload the feed with forceRefresh=true to bypass cache
            loadFeed(_currentUrl.value, addToHistory = true, forceRefresh = true)
        }
    }

    /**
     * Load more items from the next page (pagination)
     */
    fun loadMoreItems() {
        if (isLoadingMore || nextPageUrl == null) {
            Log.d(TAG, "loadMoreItems: skipped (isLoadingMore=$isLoadingMore, nextPageUrl=$nextPageUrl)")
            return
        }

        isLoadingMore = true
        val urlToLoad = nextPageUrl!!

        // Update UI state to show loading indicator
        val currentState = _uiState.value
        if (currentState is CatalogUiState.Success) {
            _uiState.value = currentState.copy(isLoadingMore = true)
        }

        viewModelScope.launch {
            Log.d(TAG, "loadMoreItems: loading from $urlToLoad")
            _isNetworkActive.value = true

            repository.fetchFeed(urlToLoad).fold(
                onSuccess = { result ->
                    val newFeed = result.feed
                    Log.d(TAG, "More items loaded: ${newFeed.entries.size} new entries (fromCache: ${result.fromCache})")

                    // Append new entries
                    accumulatedEntries.addAll(newFeed.entries)

                    // Update next page URL
                    nextPageUrl = newFeed.getNextPageLink()?.let { nextLink ->
                        if (nextLink.startsWith("http://") || nextLink.startsWith("https://")) {
                            nextLink
                        } else {
                            repository.resolveUrl(urlToLoad, nextLink)
                        }
                    }

                    Log.d(TAG, "Pagination updated: totalEntries=${accumulatedEntries.size}, hasNext=${nextPageUrl != null}")

                    // Create a new feed with accumulated entries
                    val updatedFeed = currentFeed!!.copy(
                        entries = accumulatedEntries.toList()
                    )

                    _uiState.value = CatalogUiState.Success(
                        feed = updatedFeed,
                        baseUrl = currentBaseUrl,
                        isLoadingMore = false,
                        hasNextPage = nextPageUrl != null,
                        catalogIcon = catalogIcon
                    )

                    isLoadingMore = false
                    _isNetworkActive.value = false
                },
                onFailure = { exception ->
                    Log.e(TAG, "Failed to load more items", exception)
                    isLoadingMore = false
                    _isNetworkActive.value = false

                    // Restore state without loading indicator
                    val currentState = _uiState.value
                    if (currentState is CatalogUiState.Success) {
                        _uiState.value = currentState.copy(isLoadingMore = false)
                    }
                }
            )
        }
    }

    /**
     * Resolve a relative URL against a base URL
     * Useful for resolving image URLs in entries
     */
    fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return repository.resolveUrl(baseUrl, relativeUrl)
    }

    /**
     * Submit authentication credentials and retry loading
     */
    fun submitCredentials(username: String, password: String) {
        Log.d(TAG, "submitCredentials: username=$username")
        val authUrl = _authState.value.url

        if (authUrl.isEmpty()) {
            Log.e(TAG, "No URL stored in auth state")
            return
        }

        // Store credentials for this session
        storedUsername = username
        storedPassword = password

        // Clear auth dialog
        _authState.value = _authState.value.copy(isRequired = false)

        // Retry loading the feed with credentials
        loadFeed(authUrl, addToHistory = false)
    }

    /**
     * Cancel authentication and navigate back
     */
    fun cancelAuthentication() {
        Log.d(TAG, "cancelAuthentication: user canceled")

        // Reset auth state
        _authState.value = AuthState()

        // Clear stored credentials
        storedUsername = null
        storedPassword = null

        // Set error state to prevent re-prompting
        _uiState.value = CatalogUiState.Error("Authentication cancelled")

        // Always navigate back - if at root, signal to exit the catalog entirely
        if (navigationHistory.size <= 1) {
            Log.d(TAG, "History size is ${navigationHistory.size}, at root - signaling exit catalog")
            _shouldExitCatalog.value = true
        } else {
            Log.d(TAG, "History size is ${navigationHistory.size}, navigating back")
            navigateBack()
        }
    }

    /**
     * Mark that the catalog exit has been handled
     */
    fun exitCatalogHandled() {
        Log.d(TAG, "exitCatalogHandled: resetting exit flag")
        _shouldExitCatalog.value = false
    }

    /**
     * Reset authentication state (for new catalogs)
     */
    fun resetAuthentication() {
        Log.d(TAG, "resetAuthentication: clearing credentials")
        storedUsername = null
        storedPassword = null
        _authState.value = AuthState()
    }

    /**
     * Delete the catalog that returned 404
     */
    fun deleteCatalog() {
        val notFoundState = _catalogNotFoundState.value
        if (notFoundState != null) {
            viewModelScope.launch {
                try {
                    Log.d(TAG, "Deleting catalog: ${notFoundState.catalogName} (ID: ${notFoundState.catalogId})")
                    catalogDao.deleteCatalogById(notFoundState.catalogId)
                    _catalogNotFoundState.value = null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete catalog", e)
                }
            }
        }
    }

    /**
     * Dismiss the 404 deletion dialog without deleting
     */
    fun dismiss404Dialog() {
        Log.d(TAG, "Dismissing 404 dialog")
        _catalogNotFoundState.value = null
    }

    /**
     * Add an entry to favorites with its hierarchy path
     */
    fun addToFavorites(entry: OpdsEntry) {
        viewModelScope.launch {
            try {
                val catalogId = currentCatalogId ?: return@launch

                // Serialize the entry to JSON
                val entryJson = gson.toJson(entry)

                // Create breadcrumb arrays
                val pathTitles = gson.toJson(breadcrumbTitles)
                val pathUrls = gson.toJson(breadcrumbUrls)

                val favorite = FavoriteEntry(
                    catalogId = catalogId,
                    entryJson = entryJson,
                    hierarchyPath = pathTitles,
                    hierarchyUrls = pathUrls
                )

                favoriteDao.insert(favorite)
                Log.d(TAG, "Added to favorites: ${entry.title} with hierarchy: $pathTitles")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding to favorites", e)
            }
        }
    }

    /**
     * Remove an entry from favorites
     */
    fun removeFromFavorites(favoriteId: Long) {
        viewModelScope.launch {
            try {
                favoriteDao.deleteFavoriteById(favoriteId)
                Log.d(TAG, "Removed favorite: $favoriteId")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing from favorites", e)
            }
        }
    }

    /**
     * Remove an entry from favorites by finding and deleting it
     */
    fun removeFavoriteByEntry(entry: OpdsEntry) {
        viewModelScope.launch {
            try {
                val catalogId = currentCatalogId ?: return@launch

                // Get all favorites for this catalog
                val favorites = favoriteDao.getFavoritesForCatalogOnce(catalogId)

                // Find the favorite that matches this entry (by title and id)
                val favoriteToDelete = favorites.find { favorite ->
                    val storedEntry: OpdsEntry = gson.fromJson(favorite.entryJson, OpdsEntry::class.java)
                    storedEntry.title == entry.title && storedEntry.id == entry.id
                }

                favoriteToDelete?.let {
                    favoriteDao.deleteFavoriteById(it.id)
                    Log.d(TAG, "Removed from favorites: ${entry.title}")

                    // Refresh the current view if we're browsing favorites
                    if (_isBrowsingFavorites.value) {
                        val currentUrlValue = _currentUrl.value
                        loadFeed(currentUrlValue, addToHistory = false)
                    }
                } ?: run {
                    Log.w(TAG, "Favorite entry not found for: ${entry.title}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove from favorites", e)
            }
        }
    }

    /**
     * Clear all favorites for the current catalog
     */
    fun clearAllFavorites() {
        viewModelScope.launch {
            try {
                val catalogId = currentCatalogId ?: return@launch
                favoriteDao.deleteAllForCatalog(catalogId)
                Log.d(TAG, "Cleared all favorites for catalog: $catalogId")

                // If currently browsing favorites, navigate back to root
                if (_isBrowsingFavorites.value) {
                    navigateToRoot()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear favorites", e)
            }
        }
    }

    /**
     * Build a favorites feed from the database
     * Returns a pair of (feed, baseUrl) where baseUrl is the original catalog base URL
     */
    private suspend fun buildFavoritesFeed(catalogId: Long, hierarchyFilter: List<String> = emptyList()): Pair<OpdsFeed, String?> {
        val favorites = favoriteDao.getFavoritesForCatalogOnce(catalogId)

        // Extract base URL from the first favorite's hierarchyUrls (use first URL which is the root)
        var baseUrl: String? = null
        if (favorites.isNotEmpty()) {
            try {
                val firstFavorite = favorites.first()
                val urls: List<String> = gson.fromJson(firstFavorite.hierarchyUrls, object : TypeToken<List<String>>() {}.type)
                if (urls.isNotEmpty()) {
                    baseUrl = urls.first()  // First URL is the catalog root
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting base URL from favorites", e)
            }
        }

        // Filter by hierarchy if specified
        val filteredFavorites = if (hierarchyFilter.isEmpty()) {
            // Root level - group by first breadcrumb
            favorites
        } else {
            // Specific level - filter by hierarchy path
            favorites.filter { favorite ->
                val pathList: List<String> = try {
                    gson.fromJson(favorite.hierarchyPath, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) {
                    emptyList()
                }
                pathList.take(hierarchyFilter.size) == hierarchyFilter
            }
        }

        // Convert favorites to entries and build hierarchy
        val entries = mutableListOf<OpdsEntry>()
        val subCategories = mutableSetOf<String>()

        filteredFavorites.forEach { favorite ->
            try {
                val pathList: List<String> = gson.fromJson(favorite.hierarchyPath, object : TypeToken<List<String>>() {}.type)

                if (pathList.size > hierarchyFilter.size) {
                    // This has deeper hierarchy - add as category
                    subCategories.add(pathList[hierarchyFilter.size])
                } else {
                    // This is a leaf entry - add as book
                    val entry: OpdsEntry = gson.fromJson(favorite.entryJson, OpdsEntry::class.java)
                    entries.add(entry)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing favorite", e)
            }
        }

        // Add subcategories as navigation entries
        subCategories.forEach { categoryName ->
            val categoryEntry = OpdsEntry(
                title = categoryName,
                id = "favorites_category_$categoryName",
                links = listOf(
                    OpdsLink(
                        href = "$FAVORITES_URL_PREFIX/${(hierarchyFilter + categoryName).joinToString("/")}",
                        type = "application/atom+xml;profile=opds-catalog",
                        rel = "subsection"
                    )
                ),
                updated = "",
                content = "",
                summary = "",
                author = null,
                categories = emptyList()
            )
            entries.add(0, categoryEntry)  // Add categories at the top
        }

        val feed = OpdsFeed(
            title = if (hierarchyFilter.isEmpty()) "Favorites" else hierarchyFilter.last(),
            id = "favorites_${hierarchyFilter.joinToString("_")}",
            updated = System.currentTimeMillis().toString(),
            entries = entries,
            links = emptyList(),
            icon = null
        )

        return Pair(feed, baseUrl)
    }

    /**
     * Load favorites feed
     */
    private fun loadFavoritesFeed(url: String, addToHistory: Boolean = true) {
        viewModelScope.launch {
            try {
                _isNetworkActive.value = true
                _uiState.value = CatalogUiState.Loading
                _isBrowsingFavorites.value = true

                val catalogId = currentCatalogId ?: return@launch

                // Parse hierarchy from URL
                var hierarchy = if (url == FAVORITES_URL_PREFIX) {
                    emptyList()
                } else {
                    url.removePrefix("$FAVORITES_URL_PREFIX/").split("/")
                }

                var (feed, catalogBaseUrl) = buildFavoritesFeed(catalogId, hierarchy)
                val skippedTitles = mutableListOf<String>()

                // Store the catalog base URL for resolving relative links
                favoritesCatalogBaseUrl = catalogBaseUrl

                // Skip levels with only one entry
                while (feed.entries.size == 1) {
                    val singleEntry = feed.entries[0]

                    // Check if this is a subcategory (navigation entry)
                    val isSubcategory = singleEntry.links.any {
                        it.href.startsWith(FAVORITES_URL_PREFIX)
                    }

                    if (!isSubcategory) {
                        // This is an actual favorite entry, not a subcategory, so stop
                        break
                    }

                    // Add this title to skipped titles
                    skippedTitles.add(singleEntry.title)

                    // Navigate to this subcategory
                    hierarchy = hierarchy + singleEntry.title
                    val result = buildFavoritesFeed(catalogId, hierarchy)
                    feed = result.first
                    // Keep the catalogBaseUrl from the first call
                }

                // Build the combined title with favorites notation
                val combinedTitle = if (skippedTitles.isEmpty()) {
                    "Favorites"
                } else {
                    skippedTitles.joinToString(" > ")
                }
                _currentPageTitle.value = combinedTitle

                // Build the final URL for this level
                val finalUrl = if (hierarchy.isEmpty()) {
                    FAVORITES_URL_PREFIX
                } else {
                    "$FAVORITES_URL_PREFIX/${hierarchy.joinToString("/")}"
                }

                // Update current URL
                _currentUrl.value = finalUrl

                // Add to navigation history
                if (addToHistory) {
                    navigationHistory.add(NavigationHistoryEntry(finalUrl, combinedTitle, null))
                    Log.d(TAG, "Added favorites to history: $combinedTitle")
                }
                updateNavigationState()

                // Update state - use the catalog base URL for resolving relative links, not the favorites URL
                _uiState.value = CatalogUiState.Success(
                    feed = feed,
                    baseUrl = catalogBaseUrl ?: finalUrl,  // Use catalog base URL if available
                    catalogIcon = catalogIcon,
                    hasNextPage = false,
                    isLoadingMore = false
                )

                _isNetworkActive.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error loading favorites", e)
                _uiState.value = CatalogUiState.Error("Failed to load favorites: ${e.message}")
                _isNetworkActive.value = false
            }
        }
    }
}
