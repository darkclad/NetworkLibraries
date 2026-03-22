package com.example.opdslibrary.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.data.AppPreferences
import com.example.opdslibrary.data.FavoriteEntry
import com.example.opdslibrary.data.LastVisitedAuthor
import com.example.opdslibrary.data.SerializableNavHistoryEntry
import com.example.opdslibrary.data.OpdsLink
import com.example.opdslibrary.data.OpdsCatalog
import com.example.opdslibrary.data.OpdsFeed
import com.example.opdslibrary.data.OpdsEntry
import com.example.opdslibrary.data.OpdsParser
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.InterruptedIOException
import com.example.opdslibrary.network.HtmlResponseException
import com.example.opdslibrary.network.NotFoundException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.opdslibrary.network.OpdsRepository
import com.example.opdslibrary.network.UnauthorizedException
import com.example.opdslibrary.image.ImageCacheManager
import com.example.opdslibrary.image.ImageDownloadScheduler
import kotlinx.coroutines.Job
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
    data class Error(
        val message: String,
        val hasCachedData: Boolean = false,
        val failedUrl: String = ""
    ) : CatalogUiState()
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
    private val lastVisitedAuthorDao = database.lastVisitedAuthorDao()
    private val feedCacheDao = database.feedCacheDao()
    private val bookDao = database.bookDao()
    private val opdsParser = OpdsParser()
    private val gson = Gson()
    private val appPreferences = AppPreferences(application.applicationContext)

    // Image caching
    private val imageCacheManager = ImageCacheManager(application.applicationContext)
    private val imageDownloadScheduler = ImageDownloadScheduler(application.applicationContext)

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

    private val _htmlPageUrl = MutableStateFlow<String?>(null)
    val htmlPageUrl: StateFlow<String?> = _htmlPageUrl.asStateFlow()

    // Search state
    private val _searchUrl = MutableStateFlow<String?>(null)
    val searchUrl: StateFlow<String?> = _searchUrl.asStateFlow()

    private val searchHistoryDao = database.searchHistoryDao()

    // Store the catalog base URL when browsing favorites (for resolving relative links)
    private var favoritesCatalogBaseUrl: String? = null

    // Map of entry IDs to their navigation history JSON (for "Display in catalog" feature)
    private val favoriteNavHistoryMap = mutableMapOf<String, String>()

    // Track if we navigated from favorites (to return to favorites on back)
    private var navigatedFromFavorites = false

    // Current book being viewed (null means showing feed)
    private val _currentBook = MutableStateFlow<OpdsEntry?>(null)
    val currentBook: StateFlow<OpdsEntry?> = _currentBook.asStateFlow()

    // Index of the book in the feed (for scroll restoration)
    private var currentBookIndex: Int = -1

    // Navigation history with entry indices for scroll restoration
    private val navigationHistory = mutableListOf<NavigationHistoryEntry>()

    // OPDS page type and browsing context for smart matching
    enum class OpdsPageType {
        COLLECTION,     // Books from multiple authors (use broad matching)
        AUTHOR_PAGE,    // Author's books - author in feed root (use author context)
        BOOK_DETAIL     // Single book detail page (use full title matching)
    }

    data class OpdsBrowsingContext(
        val pageType: OpdsPageType,
        val authorName: String? = null,      // Set when browsing author page
        val currentEntry: OpdsEntry? = null  // Set when viewing book detail
    )

    private var browsingContext = OpdsBrowsingContext(OpdsPageType.COLLECTION)

    // OPDS matching processor for background filename matching
    private var matchingProcessor: OpdsMatchingProcessor? = null

    // Stable StateFlows exposed to the UI — updated whenever the processor is replaced
    private val _matchingResults = MutableStateFlow<Map<String, MatchingResult>>(emptyMap())
    val matchingResults: StateFlow<Map<String, MatchingResult>> = _matchingResults.asStateFlow()

    private val _isMatchingInProgress = MutableStateFlow(false)
    val isMatchingInProgress: StateFlow<Boolean> = _isMatchingInProgress.asStateFlow()

    // Job that forwards the current processor's flows into the stable StateFlows
    private var processorForwardingJob: Job? = null

    /**
     * Call whenever matchingProcessor is replaced to keep stable flows in sync.
     * @param resetResults true when loading a new feed page (wipes old results);
     *                     false when re-matching a single book detail (preserves list results).
     */
    private fun attachProcessor(processor: OpdsMatchingProcessor, resetResults: Boolean = true) {
        processorForwardingJob?.cancel()
        if (resetResults) {
            _matchingResults.value = emptyMap()
        }
        _isMatchingInProgress.value = false
        processorForwardingJob = viewModelScope.launch {
            launch {
                processor.results.collect { map ->
                    _matchingResults.value = if (resetResults) {
                        // Full feed load: replace entirely with processor's results
                        map
                    } else {
                        // Single-book rematch: merge so other entries keep their status
                        _matchingResults.value + map
                    }
                }
            }
            launch {
                processor.isProcessing.collect { _isMatchingInProgress.value = it }
            }
        }
    }

    /**
     * Re-check an entry after download completes
     * Updates matching status immediately for newly downloaded books
     * @deprecated Use handleDownloadComplete instead for proper coordination
     */
    fun recheckEntryAfterDownload(entryId: String, opdsUpdated: Long? = null) {
        matchingProcessor?.recheckEntry(entryId, opdsUpdated)
    }

    /**
     * Cancel matching for an entry (called when user explicitly downloads)
     * User's explicit download action takes precedence over background matching
     */
    fun cancelMatching(entryId: String) {
        matchingProcessor?.cancelQueuedEntry(entryId)
        Log.d("CatalogViewModel", "Cancelled matching for entry: $entryId (user initiated download)")
    }

    /**
     * Handle download completion - just recheck to update UI
     * Matching is already cancelled when download button is clicked
     */
    fun notifyDownloadComplete(entryId: String) {
        recheckEntryAfterDownload(entryId, null)
    }

    // Set of favorited entry IDs for the current catalog (for showing star icon)
    private val _favoritedEntryIds = MutableStateFlow<Set<String>>(emptySet())
    val favoritedEntryIds: StateFlow<Set<String>> = _favoritedEntryIds.asStateFlow()

    private var savedCatalogName: String = "" // Name from saved catalog data
    private var catalogName: String = "" // Name of the root catalog (from OPDS feed)
    private var rootUrl: String = "" // URL of the root catalog
    private var currentCatalogId: Long? = null // ID of the current catalog from database

    /**
     * Get the current catalog ID for use in downloads
     */
    fun getCatalogId(): Long? = currentCatalogId

    /**
     * Represents the status of a book in the local library relative to the OPDS entry
     */
    enum class BookLibraryStatus {
        NOT_IN_LIBRARY,  // Book is not downloaded
        CURRENT,         // Book is downloaded and up-to-date
        OUTDATED         // Book is downloaded but outdated (OPDS has newer version)
    }

    /**
     * Check the library status of a book by its OPDS entry ID
     * @param entryId The OPDS entry ID
     * @param opdsUpdated The "updated" timestamp from the OPDS entry (milliseconds since epoch)
     * @return BookLibraryStatus indicating if book is not in library, current, or outdated
     */
    suspend fun getBookLibraryStatus(entryId: String, opdsUpdated: Long?): BookLibraryStatus {
        val catalogId = currentCatalogId ?: return BookLibraryStatus.NOT_IN_LIBRARY

        // First check if book exists
        val exists = bookDao.existsByOpdsEntry(entryId, catalogId)
        if (!exists) {
            return BookLibraryStatus.NOT_IN_LIBRARY
        }

        // If no updated timestamp from OPDS, assume current
        if (opdsUpdated == null) {
            return BookLibraryStatus.CURRENT
        }

        // Check if outdated
        val isOutdated = bookDao.isBookOutdated(entryId, catalogId, opdsUpdated)
        return when (isOutdated) {
            null -> BookLibraryStatus.NOT_IN_LIBRARY
            1 -> BookLibraryStatus.OUTDATED
            else -> BookLibraryStatus.CURRENT
        }
    }

    /**
     * Get the local book by OPDS entry ID (for opening)
     */
    suspend fun getLocalBook(entryId: String): com.example.opdslibrary.data.library.Book? {
        val catalogId = currentCatalogId ?: return null
        return bookDao.getBookByOpdsEntry(entryId, catalogId)
    }

    /**
     * Get debug info about the DB link state for an OPDS entry.
     * Returns a short string like "DB: id=123 linked" or "DB: not linked".
     */
    suspend fun getDbLinkDebugInfo(entryId: String): String {
        val catalogId = currentCatalogId ?: return "DB: no catalogId"
        val book = bookDao.getBookByOpdsEntry(entryId, catalogId)
        return if (book != null) {
            "DB: id=${book.id} opdsEntry=${book.opdsEntryId?.take(12)}.. cat=${book.catalogId}"
        } else {
            "DB: not linked (cat=$catalogId)"
        }
    }

    /**
     * Get the primary author ID for a book matched to an OPDS entry
     * Used for "Open in Library" navigation to author view
     */
    suspend fun getAuthorIdForBook(entryId: String): Long? {
        val catalogId = currentCatalogId ?: return null
        val bookWithDetails = database.bookDao().getBookWithDetailsByOpdsEntry(entryId, catalogId)
        return bookWithDetails?.authors?.firstOrNull()?.id
    }

    // Pagination state
    private var currentFeed: OpdsFeed? = null
    private var currentBaseUrl: String = ""
    private var nextPageUrl: String? = null
    private var accumulatedEntries = mutableListOf<OpdsEntry>()
    private var loadedPageUrls = mutableListOf<String>() // Track all loaded page URLs for refresh
    private var lastPageEntriesCount = 0 // Track entries count from the last loaded page (for refresh)
    private var pageBoundaryIndices = mutableListOf<Int>() // Track indices where new pages start (for dividers)
    private var isLoadingMore = false
    private var catalogIcon: String? = null

    // Expose page boundaries to UI for showing dividers
    private val _pageBoundaries = MutableStateFlow<Set<Int>>(emptySet())
    val pageBoundaries: StateFlow<Set<Int>> = _pageBoundaries.asStateFlow()

    // Authentication credentials (session-based)
    private var storedUsername: String? = null
    private var storedPassword: String? = null

    // Alternate URL fallback state
    private var currentCatalogAlternateUrl: String? = null
    private var primaryBaseUrl: String = ""  // Store primary URL for path resolution

    companion object {
        private const val TAG = "CatalogViewModel"
        private const val FAVORITES_URL_PREFIX = "internal://favorites"
        private const val LVA_URL_PREFIX = "internal://last-visited-authors"
        private const val LVA_MAX_ENTRIES = 15
    }

    /**
     * Check for cached catalog icon and schedule download if needed.
     * Returns the local path if cached, or null if not.
     * Also schedules background download if icon URL is available but not cached.
     */
    private suspend fun getCachedIconOrScheduleDownload(catalogId: Long, iconUrl: String?): String? {
        if (iconUrl == null) return null

        // First check if we have a local path in the database
        val catalog = catalogDao.getCatalogById(catalogId)
        if (catalog?.iconLocalPath != null) {
            // Verify the file still exists
            val file = java.io.File(catalog.iconLocalPath)
            if (file.exists()) {
                Log.d(TAG, "Using cached icon: ${catalog.iconLocalPath}")
                return catalog.iconLocalPath
            } else {
                // File was deleted, clear the path and re-download
                catalogDao.updateIconLocalPath(catalogId, null)
            }
        }

        // Check if ImageCacheManager has it cached
        val cachedPath = imageCacheManager.getCachedCatalogIcon(catalogId)
        if (cachedPath != null) {
            // Update database with cached path
            catalogDao.updateIconLocalPath(catalogId, cachedPath)
            Log.d(TAG, "Found icon in cache, updated database: $cachedPath")
            return cachedPath
        }

        // Not cached - schedule background download
        Log.d(TAG, "Scheduling icon download for catalog $catalogId: $iconUrl")
        imageDownloadScheduler.scheduleCatalogIconDownload(catalogId, iconUrl)

        return null
    }

    /**
     * Check if the current catalog has an alternate URL configured
     */
    fun hasAlternateUrl(): Boolean = currentCatalogAlternateUrl != null

    /**
     * Convert a URL from primary domain to alternate domain.
     * Simply removes the primary base URL prefix and prepends the alternate URL.
     * E.g., if primary is "http://flibusta.is/opds" and alternate is "http://flibusta.net/opds",
     * and the current URL is "http://flibusta.is/opds/books/123", the result will be
     * "http://flibusta.net/opds/books/123".
     */
    fun convertToAlternateUrl(url: String): String? {
        val alternateUrl = currentCatalogAlternateUrl ?: return null
        if (primaryBaseUrl.isEmpty()) return null

        return convertUrl(url, primaryBaseUrl, alternateUrl)
    }

    /**
     * Convert a URL from alternate domain back to primary domain.
     */
    private fun convertToPrimaryUrl(url: String): String? {
        val alternateUrl = currentCatalogAlternateUrl ?: return null
        if (primaryBaseUrl.isEmpty()) return null

        return convertUrl(url, alternateUrl, primaryBaseUrl)
    }

    /**
     * Convert a URL from one base to another.
     */
    private fun convertUrl(url: String, fromBase: String, toBase: String): String? {
        try {
            val from = fromBase.trimEnd('/')
            val to = toBase.trimEnd('/')

            if (url.startsWith(from)) {
                val relativePath = url.removePrefix(from)
                val result = to + relativePath
                Log.d(TAG, "Converted URL: $url -> $result")
                return result
            } else {
                Log.d(TAG, "URL doesn't start with base: $url vs $from")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting URL", e)
            return null
        }
    }

    /**
     * Convert all URLs in a feed to primary URLs (used after loading from alternate)
     */
    private fun convertFeedToPrimaryUrls(feed: OpdsFeed): OpdsFeed {
        val convertedEntries = feed.entries.map { entry ->
            entry.copy(
                links = entry.links.map { link ->
                    val convertedHref = convertToPrimaryUrl(link.href) ?: link.href
                    link.copy(href = convertedHref)
                }
            )
        }

        // Convert feed-level links too
        val convertedLinks = feed.links.map { link ->
            val convertedHref = convertToPrimaryUrl(link.href) ?: link.href
            link.copy(href = convertedHref)
        }

        return feed.copy(entries = convertedEntries, links = convertedLinks)
    }

    /**
     * Represents a navigation history entry
     * @param url The URL of the feed
     * @param title The title of the feed
     * @param updated The update timestamp from the feed
     * @param scrollToIndex The index of the entry that was clicked to navigate here (for back navigation)
     * @param cachedEntries Accumulated entries from pagination (to restore scroll position in long lists)
     * @param cachedNextPageUrl The next page URL at the time of navigation
     * @param cachedPageUrls List of loaded page URLs (for refresh)
     * @param cachedPageBoundaries Page boundary indices (for showing dividers)
     * @param cachedLastPageEntriesCount Number of entries from the last loaded page (for proper refresh behavior)
     */
    private data class NavigationHistoryEntry(
        val url: String,
        val title: String,
        val updated: String?,
        val scrollToIndex: Int = -1,
        // For book details entries
        val bookEntry: OpdsEntry? = null,
        val feedEntryIndex: Int = -1,
        // Cached pagination state for scroll restoration
        val cachedEntries: List<OpdsEntry>? = null,
        val cachedNextPageUrl: String? = null,
        val cachedPageUrls: List<String>? = null,
        val cachedPageBoundaries: List<Int>? = null,
        val cachedLastPageEntriesCount: Int? = null,
        // For favorites: store the catalog base URL for resolving relative links
        val favoritesCatalogBaseUrl: String? = null
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
                        primaryBaseUrl = catalog.url
                        currentCatalogAlternateUrl = catalog.alternateUrl
                        Log.d(TAG, "Catalog loaded: ${catalog.url}, alternate: ${catalog.alternateUrl}")

                        // Check for cached icon or schedule download
                        val cachedIconPath = getCachedIconOrScheduleDownload(catalogId, catalog.iconUrl)
                        if (cachedIconPath != null) {
                            // Use local cached icon (file:// URI)
                            catalogIcon = "file://$cachedIconPath"
                            Log.d(TAG, "Using cached icon: $catalogIcon")
                        } else {
                            // Use remote URL while download is scheduled
                            catalogIcon = catalog.iconUrl
                        }

                        // Load favorited entry IDs for showing star icons
                        refreshFavoritedEntryIds()

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
     * Initialize the catalog by loading from database using catalog ID and navigate to a specific URL
     * Used for navigating from library book details to related OPDS links
     */
    fun initializeCatalogByIdWithUrl(catalogId: Long, initialUrl: String) {
        if (!initialized) {
            initialized = true
            currentCatalogId = catalogId
            viewModelScope.launch {
                try {
                    val catalog = catalogDao.getCatalogById(catalogId)
                    if (catalog != null) {
                        savedCatalogName = catalog.getDisplayName()

                        // Load favorited entry IDs for showing star icons
                        refreshFavoritedEntryIds()
                        rootUrl = catalog.url
                        primaryBaseUrl = catalog.url
                        currentCatalogAlternateUrl = catalog.alternateUrl
                        Log.d(TAG, "Catalog loaded with initial URL: ${catalog.url}, initial: $initialUrl")

                        // Check for cached icon or schedule download
                        val cachedIconPath = getCachedIconOrScheduleDownload(catalogId, catalog.iconUrl)
                        if (cachedIconPath != null) {
                            catalogIcon = "file://$cachedIconPath"
                        } else {
                            catalogIcon = catalog.iconUrl
                        }

                        // Resolve the initial URL if it's relative
                        val resolvedInitialUrl = if (initialUrl.startsWith("http://") || initialUrl.startsWith("https://")) {
                            initialUrl
                        } else {
                            resolveUrl(catalog.url, initialUrl)
                        }

                        // Load the initial URL directly
                        loadFeed(resolvedInitialUrl)
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
     * Initialize the catalog by loading from database using catalog ID and restoring navigation history
     * Used for "View in Catalog" from library book details
     */
    fun initializeCatalogByIdWithHistory(catalogId: Long, navHistoryJson: String) {
        if (!initialized) {
            initialized = true
            currentCatalogId = catalogId
            viewModelScope.launch {
                try {
                    val catalog = catalogDao.getCatalogById(catalogId)
                    if (catalog != null) {
                        savedCatalogName = catalog.getDisplayName()

                        // Load favorited entry IDs for showing star icons
                        refreshFavoritedEntryIds()
                        rootUrl = catalog.url
                        primaryBaseUrl = catalog.url
                        currentCatalogAlternateUrl = catalog.alternateUrl
                        Log.d(TAG, "Catalog loaded with history: ${catalog.url}")

                        // Check for cached icon or schedule download
                        val cachedIconPath = getCachedIconOrScheduleDownload(catalogId, catalog.iconUrl)
                        if (cachedIconPath != null) {
                            catalogIcon = "file://$cachedIconPath"
                        } else {
                            catalogIcon = catalog.iconUrl
                        }

                        // Parse and restore navigation history
                        val navHistoryType = object : TypeToken<List<SerializableNavHistoryEntry>>() {}.type
                        val savedNavHistory: List<SerializableNavHistoryEntry> = gson.fromJson(navHistoryJson, navHistoryType)

                        if (savedNavHistory.isEmpty()) {
                            Log.w(TAG, "No navigation history, loading root")
                            loadFeed(catalog.url)
                            return@launch
                        }

                        // Clear and restore navigation history
                        navigationHistory.clear()
                        savedNavHistory.forEach { savedEntry ->
                            navigationHistory.add(NavigationHistoryEntry(
                                url = savedEntry.url,
                                title = savedEntry.title,
                                updated = savedEntry.updated
                            ))
                        }

                        // Restore catalog name from first entry
                        catalogName = savedNavHistory.first().title

                        Log.d(TAG, "Restored navigation history with ${navigationHistory.size} entries")

                        // Load the last URL in the history (where the book was displayed)
                        val lastUrl = savedNavHistory.last().url
                        _currentUrl.value = lastUrl
                        loadFeed(lastUrl)
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
     * Detect the OPDS page type and extract context for smart matching
     *
     * Page types:
     * - COLLECTION: Books from multiple authors (broad matching)
     * - AUTHOR_PAGE: Author's books - author in feed root (use author context)
     * - BOOK_DETAIL: Single book detail page (use full title matching)
     */
    private fun detectPageType(feed: OpdsFeed): OpdsBrowsingContext {
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "DETECTING PAGE TYPE")
        Log.d(TAG, "Feed title: '${feed.title}'")
        Log.d(TAG, "Feed author: '${feed.author?.name}' (uri: ${feed.author?.uri})")
        Log.d(TAG, "Current URL: '$currentUrl'")
        Log.d(TAG, "Total entries: ${feed.entries.size}")

        val acquisitionEntries = feed.entries.filter { it.isAcquisition() }
        Log.d(TAG, "Acquisition entries: ${acquisitionEntries.size}")

        // Check if feed has author in root (indicates author page)
        if (feed.author?.name?.isNotBlank() == true) {
            Log.i(TAG, "→ Detected AUTHOR_PAGE (author in feed root)")
            Log.i(TAG, "  Author from feed root: '${feed.author.name}'")
            Log.i(TAG, "  Will filter ${acquisitionEntries.size} books by this author")
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            return OpdsBrowsingContext(
                pageType = OpdsPageType.AUTHOR_PAGE,
                authorName = feed.author.name,
                currentEntry = null
            )
        }

        // Check if URL indicates author page (e.g., /opds/author/123, /author/name)
        val currentUrlValue = _currentUrl.value
        if (currentUrlValue.contains("/author/", ignoreCase = true)) {
            // Try to extract author name from feed title
            // Common patterns: "Books by Author Name", "Author Name", "Книги автора Имя Автора"
            val authorName = extractAuthorFromTitle(feed.title)
            if (authorName != null) {
                Log.i(TAG, "→ Detected AUTHOR_PAGE (from URL pattern and title)")
                Log.i(TAG, "  URL contains '/author/': $currentUrlValue")
                Log.i(TAG, "  Extracted author from title: '$authorName'")
                Log.i(TAG, "  Will filter ${acquisitionEntries.size} books by this author")
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                return OpdsBrowsingContext(
                    pageType = OpdsPageType.AUTHOR_PAGE,
                    authorName = authorName,
                    currentEntry = null
                )
            }
        }

        // Check if this is a book detail page (single acquisition entry)
        // But exclude "About the author" pages and similar
        if (acquisitionEntries.size == 1) {
            val entry = acquisitionEntries.first()
            val isAboutPage = entry.title.contains("about", ignoreCase = true) ||
                             entry.title.contains("об авторе", ignoreCase = true) ||
                             entry.title.contains("bio", ignoreCase = true)

            if (!isAboutPage) {
                Log.i(TAG, "→ Detected BOOK_DETAIL page")
                Log.i(TAG, "  Entry title: '${entry.title}'")
                Log.i(TAG, "  Entry author: '${entry.author?.name}'")
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                return OpdsBrowsingContext(
                    pageType = OpdsPageType.BOOK_DETAIL,
                    authorName = entry.author?.name,
                    currentEntry = entry
                )
            } else {
                Log.i(TAG, "  Single entry is 'About' page, skipping BOOK_DETAIL detection")
            }
        }

        // Otherwise, it's a collection page
        Log.i(TAG, "→ Detected COLLECTION page")
        Log.i(TAG, "  Multiple entries (${acquisitionEntries.size}), no feed author")
        Log.i(TAG, "  Will use broad matching strategy")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        return OpdsBrowsingContext(
            pageType = OpdsPageType.COLLECTION,
            authorName = null,
            currentEntry = null
        )
    }

    /**
     * Extract author name from feed title
     * Handles patterns like:
     * - "Книги автора Демченко Антон Витальевич"
     * - "Books by John Smith"
     * - "Author: Jane Doe"
     */
    private fun extractAuthorFromTitle(title: String): String? {
        // Russian pattern: "Книги автора [Name]"
        val russianPattern = Regex("""книги автора (.+)""", RegexOption.IGNORE_CASE)
        russianPattern.find(title)?.let { matchResult ->
            val authorName = matchResult.groupValues[1].trim()
            Log.d(TAG, "  Extracted author (Russian pattern): '$authorName'")
            return authorName
        }

        // English pattern: "Books by [Name]"
        val englishPattern = Regex("""books by (.+)""", RegexOption.IGNORE_CASE)
        englishPattern.find(title)?.let { matchResult ->
            val authorName = matchResult.groupValues[1].trim()
            Log.d(TAG, "  Extracted author (English pattern): '$authorName'")
            return authorName
        }

        // Pattern: "Author: [Name]"
        val authorPattern = Regex("""author:\s*(.+)""", RegexOption.IGNORE_CASE)
        authorPattern.find(title)?.let { matchResult ->
            val authorName = matchResult.groupValues[1].trim()
            Log.d(TAG, "  Extracted author (Author: pattern): '$authorName'")
            return authorName
        }

        Log.d(TAG, "  Could not extract author from title: '$title'")
        return null
    }

    /**
     * Load an OPDS feed from the given URL
     */
    fun loadFeed(url: String, addToHistory: Boolean = true, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            Log.d(TAG, "loadFeed: url=$url, addToHistory=$addToHistory, forceRefresh=$forceRefresh")

            // Clear matching results when loading a new feed
            matchingProcessor?.clearResults()

            // Handle favorites URLs specially
            if (url.startsWith(FAVORITES_URL_PREFIX)) {
                loadFavoritesFeed(url, addToHistory)
                return@launch
            }

            // Handle last visited authors URL
            if (url == LVA_URL_PREFIX) {
                loadLastVisitedAuthorsFeed(addToHistory)
                return@launch
            }

            _isNetworkActive.value = true
            _uiState.value = CatalogUiState.Loading
            _currentUrl.value = url
            _isBrowsingFavorites.value = false
            favoritesCatalogBaseUrl = null  // Clear stored base URL when exiting favorites
            favoriteNavHistoryMap.clear()  // Clear navigation history map when exiting favorites

            // Reset pagination when loading a new feed (not loading more)
            accumulatedEntries.clear()
            loadedPageUrls.clear()
            loadedPageUrls.add(url) // Track the base page URL for refresh
            lastPageEntriesCount = 0
            pageBoundaryIndices.clear()
            _pageBoundaries.value = emptySet()

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

                        // Store catalog name and root URL from the first feed loaded
                        if (catalogName.isEmpty()) {
                            catalogName = feed.title
                            rootUrl = url
                            Log.d(TAG, "Set catalog name: $catalogName, root URL: $rootUrl")
                        }
                    }
                    updateNavigationState()

                    // Inject virtual entries at root level (Favorites, Last Visited Authors)
                    val feedWithFavorites = if (navigationHistory.size == 1) {
                        feed.copy(entries = buildRootVirtualEntries() + feed.entries)
                    } else {
                        feed
                    }

                    // Store feed and pagination info
                    currentFeed = feedWithFavorites
                    currentBaseUrl = url
                    accumulatedEntries.addAll(feed.entries)
                    lastPageEntriesCount = feed.entries.size // Track entries from this page for refresh
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

                    // Detect page type and update browsing context
                    browsingContext = detectPageType(feed)
                    Log.d(TAG, "Detected page type: ${browsingContext.pageType}, author: ${browsingContext.authorName}")

                    // Save last visited author when browsing an author page
                    if (browsingContext.pageType == OpdsPageType.AUTHOR_PAGE && browsingContext.authorName != null) {
                        saveLastVisitedAuthor(feed, url, browsingContext.authorName!!)
                    }

                    // Start background filename matching for acquisition entries
                    currentCatalogId?.let { catalogId ->
                        matchingProcessor?.stop()
                        matchingProcessor = OpdsMatchingProcessor(
                            database,
                            appPreferences,
                            catalogId,
                            viewModelScope,
                            browsingContext
                        )
                        attachProcessor(matchingProcessor!!)
                        matchingProcessor?.start()

                        // Queue all acquisition entries for processing
                        feedWithFavorites.entries.filter { it.isAcquisition() }.forEach { entry ->
                            val opdsUpdated = try {
                                java.time.Instant.parse(entry.updated).toEpochMilli()
                            } catch (e: Exception) {
                                null
                            }
                            matchingProcessor?.queueEntry(entry, opdsUpdated)
                        }
                        Log.d(TAG, "Queued ${feedWithFavorites.entries.count { it.isAcquisition() }} entries for matching")
                    }
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
                    // Handle HTML response - show in WebView
                    else if (exception is HtmlResponseException) {
                        Log.d(TAG, "HTML response received, showing in WebView: ${exception.url}")
                        _htmlPageUrl.value = exception.url
                        _isNetworkActive.value = false
                        return@fold
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
                        // Check if this is a timeout/network error and we have an alternate URL to try
                        val isNetworkError = exception is SocketTimeoutException ||
                            exception is InterruptedIOException ||
                            exception is UnknownHostException ||
                            exception is java.net.ConnectException ||
                            exception.message?.contains("timeout", ignoreCase = true) == true

                        // If network error and alternate URL available, try alternate once
                        if (isNetworkError && currentCatalogAlternateUrl != null) {
                            // Try the alternate URL (hasTriedAlternate=true to prevent further retries)
                            val alternateUrlForRequest = convertToAlternateUrl(url)
                            if (alternateUrlForRequest != null) {
                                Log.d(TAG, "Network error occurred, trying alternate URL: $alternateUrlForRequest")
                                _isNetworkActive.value = false
                                loadFeedWithRetry(alternateUrlForRequest, url, addToHistory, forceRefresh, true, true)
                                return@fold
                            }
                        }

                        // Check if we have cached data for this URL
                        val hasCached = feedCacheDao.getCachedFeed(url) != null

                        // Provide better error messages for common network issues
                        val errorMessage = when {
                            isNetworkError ->
                                "Network error - both primary and alternate URLs failed"
                            exception.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                                "Network error - unable to reach server. Check your internet connection"
                            else -> exception.message ?: "Unknown error occurred"
                        }

                        _uiState.value = CatalogUiState.Error(
                            message = errorMessage,
                            hasCachedData = hasCached,
                            failedUrl = url
                        )
                    }
                    _isNetworkActive.value = false
                }
            )
        }
    }

    /**
     * Load a feed with retry logic: primary -> alternate (one cycle only)
     * @param urlToLoad The actual URL to load (may be primary or alternate)
     * @param originalUrl The original primary URL (for history and cache lookups)
     * @param addToHistory Whether to add to navigation history
     * @param forceRefresh Whether to bypass cache
     * @param isAlternate Whether this attempt is using the alternate URL
     * @param hasTriedAlternate Whether we've already tried the alternate URL (to prevent infinite loop)
     */
    private fun loadFeedWithRetry(
        urlToLoad: String,
        originalUrl: String,
        addToHistory: Boolean,
        forceRefresh: Boolean,
        isAlternate: Boolean,
        hasTriedAlternate: Boolean = false
    ) {
        viewModelScope.launch {
            Log.d(TAG, "loadFeedWithRetry: url=$urlToLoad, original=$originalUrl, isAlternate=$isAlternate")

            _isNetworkActive.value = true

            val parentUpdated = if (navigationHistory.isNotEmpty()) {
                navigationHistory.last().updated
            } else {
                null
            }

            // When loading from alternate, pass primary URL for cache operations
            val primaryUrlForCache = if (isAlternate) originalUrl else null
            repository.fetchFeed(urlToLoad, storedUsername, storedPassword, forceRefresh, parentUpdated, primaryUrlForCache).fold(
                onSuccess = { result ->
                    val feed = result.feed
                    val fromCache = result.fromCache
                    Log.d(TAG, "Feed loaded successfully via ${if (isAlternate) "alternate" else "primary"}: ${feed.title}")

                    // Convert all URLs in feed to primary if loaded from alternate
                    val normalizedFeed = if (isAlternate) {
                        Log.d(TAG, "Converting feed URLs from alternate to primary")
                        convertFeedToPrimaryUrls(feed)
                    } else {
                        feed
                    }

                    _isCurrentFeedFromCache.value = fromCache

                    if (addToHistory) {
                        // Use original URL in history for consistency
                        navigationHistory.add(NavigationHistoryEntry(originalUrl, normalizedFeed.title, normalizedFeed.updated))

                        if (catalogName.isEmpty()) {
                            catalogName = normalizedFeed.title
                            rootUrl = originalUrl
                        }
                    }
                    updateNavigationState()

                    // Inject virtual entries at root level (Favorites, Last Visited Authors)
                    val feedWithFavorites = if (navigationHistory.size == 1) {
                        normalizedFeed.copy(entries = buildRootVirtualEntries() + normalizedFeed.entries)
                    } else {
                        normalizedFeed
                    }

                    currentFeed = feedWithFavorites
                    currentBaseUrl = originalUrl  // Use primary URL as base
                    accumulatedEntries.addAll(normalizedFeed.entries)

                    // Get next page URL (already converted if from alternate)
                    nextPageUrl = normalizedFeed.getNextPageLink()?.let { nextLink ->
                        val resolvedLink = if (nextLink.startsWith("http://") || nextLink.startsWith("https://")) {
                            nextLink
                        } else {
                            repository.resolveUrl(originalUrl, nextLink)
                        }
                        // Ensure it's primary URL
                        convertToPrimaryUrl(resolvedLink) ?: resolvedLink
                    }

                    if (catalogIcon == null && normalizedFeed.icon != null) {
                        catalogIcon = if (normalizedFeed.icon.startsWith("http://") || normalizedFeed.icon.startsWith("https://")) {
                            normalizedFeed.icon
                        } else {
                            repository.resolveUrl(originalUrl, normalizedFeed.icon)
                        }
                    }

                    _uiState.value = CatalogUiState.Success(
                        feed = feedWithFavorites,
                        baseUrl = originalUrl,  // Always use primary URL as base
                        isLoadingMore = false,
                        hasNextPage = feedWithFavorites.hasNextPage(),
                        catalogIcon = catalogIcon
                    )
                    _isNetworkActive.value = false
                },
                onFailure = { exception ->
                    Log.e(TAG, "Failed to load feed (isAlternate=$isAlternate, hasTriedAlternate=$hasTriedAlternate)", exception)

                    val isNetworkError = exception is SocketTimeoutException ||
                        exception is InterruptedIOException ||
                        exception is UnknownHostException ||
                        exception is java.net.ConnectException ||
                        exception.message?.contains("timeout", ignoreCase = true) == true

                    // Try alternate URL once if we haven't tried it yet
                    if (isNetworkError && currentCatalogAlternateUrl != null && !hasTriedAlternate && !isAlternate) {
                        val alternateUrl = convertToAlternateUrl(originalUrl)
                        if (alternateUrl != null) {
                            Log.d(TAG, "Primary failed, trying alternate URL: $alternateUrl")
                            // Add a small delay before retrying to avoid hammering servers
                            kotlinx.coroutines.delay(1000)
                            loadFeedWithRetry(alternateUrl, originalUrl, addToHistory, forceRefresh, true, true)
                            return@fold
                        }
                    }

                    // No more retries - show error with options
                    val hasCached = feedCacheDao.getCachedFeed(originalUrl) != null

                    val errorMessage = when {
                        isNetworkError ->
                            "Network error - both primary and alternate URLs failed"
                        exception.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                            "Network error - unable to reach server. Check your internet connection"
                        else -> exception.message ?: "Unknown error occurred"
                    }

                    _uiState.value = CatalogUiState.Error(
                        message = errorMessage,
                        hasCachedData = hasCached,
                        failedUrl = originalUrl
                    )
                    _isNetworkActive.value = false
                }
            )
        }
    }

    /**
     * Load a feed from cache (used when network fails but cached data is available)
     */
    fun loadCachedFeed(url: String) {
        viewModelScope.launch {
            Log.d(TAG, "loadCachedFeed: url=$url")

            try {
                val cached = feedCacheDao.getCachedFeed(url)
                if (cached == null) {
                    Log.e(TAG, "No cached feed found for: $url")
                    _uiState.value = CatalogUiState.Error(
                        message = "No cached data available",
                        hasCachedData = false,
                        failedUrl = url
                    )
                    return@launch
                }

                val feed = opdsParser.parse(cached.xmlContent.byteInputStream())
                Log.d(TAG, "Loaded cached feed: ${feed.title}")

                // Track that this is from cache
                _isCurrentFeedFromCache.value = true

                // Add to history if not already there
                if (navigationHistory.isEmpty() || navigationHistory.last().url != url) {
                    navigationHistory.add(NavigationHistoryEntry(url, feed.title, feed.updated))

                    if (catalogName.isEmpty()) {
                        catalogName = feed.title
                        rootUrl = url
                    }
                }
                updateNavigationState()

                // Inject virtual entries at root level (Favorites, Last Visited Authors)
                val feedWithFavorites = if (navigationHistory.size == 1) {
                    feed.copy(entries = buildRootVirtualEntries() + feed.entries)
                } else {
                    feed
                }

                currentFeed = feedWithFavorites
                currentBaseUrl = url

                _uiState.value = CatalogUiState.Success(
                    feed = feedWithFavorites,
                    baseUrl = url,
                    isLoadingMore = false,
                    hasNextPage = feedWithFavorites.hasNextPage(),
                    catalogIcon = catalogIcon
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cached feed", e)
                _uiState.value = CatalogUiState.Error(
                    message = "Failed to load cached data: ${e.message}",
                    hasCachedData = false,
                    failedUrl = url
                )
            }
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

        // Update the current history entry with the clicked entry index and cached entries before navigating
        if (entryIndex >= 0 && navigationHistory.isNotEmpty()) {
            val lastIndex = navigationHistory.size - 1
            val currentEntry = navigationHistory[lastIndex]
            navigationHistory[lastIndex] = currentEntry.copy(
                scrollToIndex = entryIndex,
                cachedEntries = accumulatedEntries.toList(),
                cachedNextPageUrl = nextPageUrl,
                cachedPageUrls = loadedPageUrls.toList(),
                cachedPageBoundaries = pageBoundaryIndices.toList(),
                cachedLastPageEntriesCount = lastPageEntriesCount
            )
            Log.d(TAG, "  Updated history entry with scrollToIndex: $entryIndex, cachedEntries: ${accumulatedEntries.size}, cachedPages: ${loadedPageUrls.size}")
        }

        val resolvedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            // Already absolute URL
            url
        } else if (url.startsWith(FAVORITES_URL_PREFIX) || url == LVA_URL_PREFIX) {
            // Internal URL - use as is
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

        // Update the current history entry (feed page) with cached entries before navigating to book
        if (navigationHistory.isNotEmpty()) {
            val lastIndex = navigationHistory.size - 1
            val currentEntry = navigationHistory[lastIndex]
            navigationHistory[lastIndex] = currentEntry.copy(
                scrollToIndex = index,
                cachedEntries = accumulatedEntries.toList(),
                cachedNextPageUrl = nextPageUrl,
                cachedPageUrls = loadedPageUrls.toList(),
                cachedPageBoundaries = pageBoundaryIndices.toList(),
                cachedLastPageEntriesCount = lastPageEntriesCount
            )
            Log.d(TAG, "  Updated feed history entry with scrollToIndex: $index, cachedEntries: ${accumulatedEntries.size}, cachedPages: ${loadedPageUrls.size}")
        }

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
     * Close HTML WebView
     */
    fun closeHtmlPage() {
        _htmlPageUrl.value = null
    }

    /**
     * Navigate back in history
     */
    fun navigateBack(): Boolean {
        Log.d(TAG, "navigateBack called (navigatedFromFavorites=$navigatedFromFavorites, historySize=${navigationHistory.size})")

        // If we navigated from favorites and are at the root of catalog navigation, go back to favorites
        if (navigatedFromFavorites && navigationHistory.size <= 1) {
            Log.d(TAG, "Returning to favorites from catalog path")
            navigatedFromFavorites = false
            loadFavoritesFeed(FAVORITES_URL_PREFIX, addToHistory = false)
            return true
        }

        if (navigationHistory.size > 1) {
            // Remove current entry
            navigationHistory.removeAt(navigationHistory.size - 1)

            val entry = navigationHistory.last()
            Log.d(TAG, "Going back to: ${entry.title}")

            // Check if this is a book details entry or a feed entry
            if (entry.bookEntry != null) {
                // Restore book details - don't reload feed, just show the book
                Log.d(TAG, "  Restoring book details: ${entry.bookEntry.title} at index ${entry.feedEntryIndex}")
                _currentBook.value = entry.bookEntry
                currentBookIndex = entry.feedEntryIndex
                _currentUrl.value = entry.url
                // Don't reload feed - the book has all the data it needs
                // Feed will be reloaded when user closes the book
                updateNavigationState()
            } else {
                // Restore feed
                Log.d(TAG, "  Restoring feed: ${entry.url} (scrollToIndex: ${entry.scrollToIndex})")
                _currentBook.value = null
                currentBookIndex = -1

                // Check if we have cached entries (for scroll restoration in paginated lists)
                if (entry.cachedEntries != null && entry.cachedEntries.isNotEmpty() && entry.scrollToIndex >= 0) {
                    Log.d(TAG, "  Restoring from cache: ${entry.cachedEntries.size} entries, scrollTo: ${entry.scrollToIndex}")
                    viewModelScope.launch { restoreFeedFromCache(entry) }
                } else {
                    // Set the scroll index for this page
                    _scrollToEntryIndex.value = entry.scrollToIndex
                    loadFeed(entry.url, addToHistory = false)
                }
            }
            return true
        }
        Log.d(TAG, "Cannot go back - history size: ${navigationHistory.size}")
        return false
    }

    /**
     * Restore feed state from cached entries (for scroll restoration in paginated lists)
     */
    private suspend fun restoreFeedFromCache(entry: NavigationHistoryEntry) {
        val cachedEntries = entry.cachedEntries ?: return

        // Restore accumulated entries and pagination state
        accumulatedEntries.clear()
        accumulatedEntries.addAll(cachedEntries)
        nextPageUrl = entry.cachedNextPageUrl
        _currentUrl.value = entry.url

        // Restore loaded page URLs for refresh functionality
        loadedPageUrls.clear()
        entry.cachedPageUrls?.let { loadedPageUrls.addAll(it) }
        // Restore last page entries count for proper refresh behavior
        lastPageEntriesCount = entry.cachedLastPageEntriesCount ?: 0

        // Restore page boundaries for dividers
        pageBoundaryIndices.clear()
        entry.cachedPageBoundaries?.let { pageBoundaryIndices.addAll(it) }
        _pageBoundaries.value = pageBoundaryIndices.toSet()

        // Check if browsing favorites
        val isFavorites = entry.url.startsWith(FAVORITES_URL_PREFIX)
        _isBrowsingFavorites.value = isFavorites

        // Inject virtual entries at root level (Favorites, Last Visited Authors)
        val entriesForDisplay = if (!isFavorites && entry.url != LVA_URL_PREFIX && navigationHistory.size == 1) {
            buildRootVirtualEntries() + cachedEntries
        } else {
            cachedEntries
        }

        // Create a feed from cached entries (entries were already processed when cached)
        val cachedFeed = OpdsFeed(
            id = "cached",
            title = entry.title,
            updated = entry.updated ?: "",
            entries = entriesForDisplay,
            links = emptyList() // Links not needed for display
        )

        // Restore favorites catalog base URL if browsing favorites
        if (isFavorites && entry.favoritesCatalogBaseUrl != null) {
            favoritesCatalogBaseUrl = entry.favoritesCatalogBaseUrl
            Log.d(TAG, "Restored favoritesCatalogBaseUrl: ${entry.favoritesCatalogBaseUrl}")
        }

        // Set the scroll index
        _scrollToEntryIndex.value = entry.scrollToIndex

        // Update UI state
        _uiState.value = CatalogUiState.Success(
            feed = cachedFeed,
            baseUrl = entry.url,
            isLoadingMore = false,
            hasNextPage = nextPageUrl != null,
            catalogIcon = catalogIcon
        )

        // Refresh favorited entry IDs
        viewModelScope.launch {
            refreshFavoritedEntryIds()
        }

        // Re-run matching for acquisition entries in the restored feed.
        // _matchingResults may contain stale results from the page the user navigated *to*,
        // so we reset and re-check the DB for every acquisition entry in this list.
        currentCatalogId?.let { catalogId ->
            matchingProcessor?.stop()
            browsingContext = detectPageType(cachedFeed)
            matchingProcessor = OpdsMatchingProcessor(database, appPreferences, catalogId, viewModelScope, browsingContext)
            attachProcessor(matchingProcessor!!)
            matchingProcessor?.start()
            cachedFeed.entries.filter { it.isAcquisition() }.forEach { opdsEntry ->
                val opdsUpdated = try {
                    java.time.Instant.parse(opdsEntry.updated).toEpochMilli()
                } catch (e: Exception) { null }
                matchingProcessor?.queueEntry(opdsEntry, opdsUpdated)
            }
            Log.d(TAG, "Re-queued ${cachedFeed.entries.count { it.isAcquisition() }} entries for matching (cache restore)")
        }

        _isCurrentFeedFromCache.value = true
        updateNavigationState()

        Log.d(TAG, "Feed restored from cache: ${cachedEntries.size} entries, scrollTo: ${entry.scrollToIndex}, hasNextPage: ${nextPageUrl != null}")
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
            // Clear history and reload root
            navigationHistory.clear()
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
     * Refresh the current feed by reloading the last loaded page from the OPDS server
     * Forces a network fetch and updates the cache, preserving scroll position
     */
    fun refresh() {
        Log.d(TAG, "refresh called - forcing network fetch for last page only")

        // If viewing a book detail, force re-match that single book (even if already matched)
        val currentBook = _currentBook.value
        if (currentBook != null) {
            Log.d(TAG, "Book detail rematch: clearing and re-matching '${currentBook.title}'")
            viewModelScope.launch {
                currentCatalogId?.let { catalogId ->
                    try {
                        // Clear existing match so queueEntry won't take the fast path
                        val bookByEntry = database.bookDao().getBookByOpdsEntry(currentBook.id, catalogId)
                        if (bookByEntry != null) {
                            database.bookDao().updateOpdsEntryId(bookByEntry.id, "", 0)
                            Log.d(TAG, "Cleared match for book ${bookByEntry.id} ('${bookByEntry.title}')")
                        }

                        browsingContext = detectPageType(OpdsFeed(entries = listOf(currentBook)))
                        matchingProcessor?.stop()
                        matchingProcessor = OpdsMatchingProcessor(database, appPreferences, catalogId, viewModelScope, browsingContext)
                        attachProcessor(matchingProcessor!!, resetResults = false)
                        matchingProcessor?.start()

                        val opdsUpdated = try {
                            java.time.Instant.parse(currentBook.updated).toEpochMilli()
                        } catch (e: Exception) {
                            null
                        }
                        matchingProcessor?.queueEntry(currentBook, opdsUpdated)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to re-match book", e)
                    }
                }
            }
            return
        }

        // Refresh the feed — already-matched books will use the fast path in queueEntry,
        // so only unmatched books will be processed by filename matching.
        if (loadedPageUrls.isEmpty()) {
            Log.d(TAG, "No pages to refresh")
            return
        }

        // Get the last loaded page URL
        val lastPageUrl = loadedPageUrls.last()
        val lastPageIndex = loadedPageUrls.size - 1
        Log.d(TAG, "Refreshing last page only: $lastPageUrl (page ${lastPageIndex + 1} of ${loadedPageUrls.size})")

        viewModelScope.launch {
            _isNetworkActive.value = true

            try {
                // Fetch the last page with force refresh
                repository.fetchFeed(lastPageUrl, storedUsername, storedPassword, forceRefresh = true, null).fold(
                    onSuccess = { result ->
                        val newFeed = result.feed
                        Log.d(TAG, "Last page refreshed: ${newFeed.entries.size} entries")

                        // Calculate entries count from previous pages (all except last)
                        val previousPagesCount = if (lastPageIndex > 0 && lastPageEntriesCount > 0) {
                            accumulatedEntries.size - lastPageEntriesCount
                        } else {
                            0
                        }

                        if (lastPageIndex == 0) {
                            // Only one page - simple refresh, replace all entries
                            accumulatedEntries.clear()
                            accumulatedEntries.addAll(newFeed.entries)
                            lastPageEntriesCount = newFeed.entries.size
                        } else {
                            // Multiple pages - keep entries from previous pages, replace last page
                            val previousEntries = accumulatedEntries.take(previousPagesCount)
                            accumulatedEntries.clear()
                            accumulatedEntries.addAll(previousEntries)
                            accumulatedEntries.addAll(newFeed.entries)
                            lastPageEntriesCount = newFeed.entries.size
                        }

                        // Update next page URL
                        nextPageUrl = newFeed.getNextPageLink()?.let { nextLink ->
                            if (nextLink.startsWith("http://") || nextLink.startsWith("https://")) {
                                nextLink
                            } else {
                                repository.resolveUrl(lastPageUrl, nextLink)
                            }
                        }

                        // Inject Favorites entry at the top if at root level
                        val entriesForDisplay = if (navigationHistory.size == 1) {
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
                            listOf(favoritesEntry) + accumulatedEntries
                        } else {
                            accumulatedEntries.toList()
                        }

                        // Update the feed with refreshed entries
                        val updatedFeed = currentFeed?.copy(
                            entries = entriesForDisplay
                        ) ?: OpdsFeed(
                            id = "refreshed",
                            title = catalogName,
                            updated = newFeed.updated,
                            entries = entriesForDisplay,
                            links = emptyList()
                        )

                        currentFeed = updatedFeed
                        _isCurrentFeedFromCache.value = false

                        _uiState.value = CatalogUiState.Success(
                            feed = updatedFeed,
                            baseUrl = currentBaseUrl,
                            isLoadingMore = false,
                            hasNextPage = nextPageUrl != null,
                            catalogIcon = catalogIcon
                        )

                        // Restart matching processor for re-matching (DEBUG MODE)
                        currentCatalogId?.let { catalogId ->
                            Log.w(TAG, "DEBUG: Restarting matching processor after refresh")

                            // Detect page type
                            browsingContext = detectPageType(updatedFeed)
                            Log.d(TAG, "Detected page type: ${browsingContext.pageType}, author: ${browsingContext.authorName}")

                            matchingProcessor?.stop()
                            matchingProcessor = OpdsMatchingProcessor(
                                database,
                                appPreferences,
                                catalogId,
                                viewModelScope,
                                browsingContext
                            )
                            attachProcessor(matchingProcessor!!)
                            matchingProcessor?.start()

                            // Queue all acquisition entries for processing
                            updatedFeed.entries.filter { it.isAcquisition() }.forEach { entry ->
                                val opdsUpdated = try {
                                    java.time.Instant.parse(entry.updated).toEpochMilli()
                                } catch (e: Exception) {
                                    null
                                }
                                matchingProcessor?.queueEntry(entry, opdsUpdated)
                            }
                            Log.d(TAG, "Queued ${updatedFeed.entries.count { it.isAcquisition() }} entries for re-matching")
                        }

                        // Refresh favorited entry IDs
                        refreshFavoritedEntryIds()

                        Log.d(TAG, "Refresh complete: ${accumulatedEntries.size} total entries (last page: $lastPageEntriesCount)")
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "Failed to refresh last page", exception)
                        // Keep current state, just log the error
                    }
                )
            } finally {
                _isNetworkActive.value = false
            }
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
        val originalUrl = nextPageUrl!!

        // Update UI state to show loading indicator
        val currentState = _uiState.value
        if (currentState is CatalogUiState.Success) {
            _uiState.value = currentState.copy(isLoadingMore = true)
        }

        // Start with primary URL (useAlternate = false)
        loadMoreItemsWithRetry(originalUrl, false)
    }

    /**
     * Load more items with retry logic: primary -> alternate (one cycle only)
     * @param useAlternate Whether to use alternate URL for this attempt
     * @param hasTriedAlternate Whether we've already tried the alternate URL
     */
    private fun loadMoreItemsWithRetry(originalUrl: String, useAlternate: Boolean, hasTriedAlternate: Boolean = false) {
        val urlToLoad = if (useAlternate) {
            convertToAlternateUrl(originalUrl) ?: originalUrl
        } else {
            originalUrl
        }
        Log.d(TAG, "loadMoreItemsWithRetry: useAlternate=$useAlternate, hasTriedAlternate=$hasTriedAlternate, url=$urlToLoad")

        viewModelScope.launch {
            _isNetworkActive.value = true

            // When loading from alternate, pass primary URL for cache operations
            val primaryUrlForCache = if (useAlternate) originalUrl else null
            repository.fetchFeed(urlToLoad, primaryUrl = primaryUrlForCache).fold(
                onSuccess = { result ->
                    val newFeed = result.feed
                    Log.d(TAG, "More items loaded: ${newFeed.entries.size} new entries (fromCache: ${result.fromCache})")

                    // Convert URLs to primary if loaded from alternate
                    val normalizedFeed = if (useAlternate) {
                        Log.d(TAG, "Converting pagination feed URLs from alternate to primary")
                        convertFeedToPrimaryUrls(newFeed)
                    } else {
                        newFeed
                    }

                    // Track page boundary before adding new entries
                    val boundaryIndex = accumulatedEntries.size
                    pageBoundaryIndices.add(boundaryIndex)
                    _pageBoundaries.value = pageBoundaryIndices.toSet()

                    // Append new entries (with converted URLs), filtering out duplicates
                    val existingIds = accumulatedEntries.map { it.id }.toSet()
                    val newEntries = normalizedFeed.entries.filter { it.id !in existingIds }
                    if (newEntries.size < normalizedFeed.entries.size) {
                        Log.d(TAG, "Filtered ${normalizedFeed.entries.size - newEntries.size} duplicate entries")
                    }
                    accumulatedEntries.addAll(newEntries)
                    lastPageEntriesCount = newEntries.size // Track entries from this page for refresh

                    // Track this page URL for refresh (use original/primary URL)
                    if (loadedPageUrls.size < 10) {
                        loadedPageUrls.add(originalUrl)
                    }

                    // Update next page URL - always use primary
                    nextPageUrl = normalizedFeed.getNextPageLink()?.let { nextLink ->
                        val resolvedLink = if (nextLink.startsWith("http://") || nextLink.startsWith("https://")) {
                            nextLink
                        } else {
                            repository.resolveUrl(originalUrl, nextLink)
                        }
                        // Ensure it's primary URL
                        convertToPrimaryUrl(resolvedLink) ?: resolvedLink
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

                    // Queue new acquisition entries for matching
                    newEntries.filter { it.isAcquisition() }.forEach { entry ->
                        val opdsUpdated = try {
                            java.time.Instant.parse(entry.updated).toEpochMilli()
                        } catch (e: Exception) {
                            null
                        }
                        matchingProcessor?.queueEntry(entry, opdsUpdated)
                    }
                    Log.d(TAG, "Queued ${newEntries.count { it.isAcquisition() }} new entries for matching (pagination)")

                    isLoadingMore = false
                    _isNetworkActive.value = false
                },
                onFailure = { exception ->
                    Log.e(TAG, "Failed to load more items (useAlternate=$useAlternate, hasTriedAlternate=$hasTriedAlternate)", exception)

                    // Check if this is a timeout/network error
                    val isNetworkError = exception is SocketTimeoutException ||
                        exception is InterruptedIOException ||
                        exception is UnknownHostException ||
                        exception is java.net.ConnectException ||
                        exception.message?.contains("timeout", ignoreCase = true) == true

                    // Try alternate URL once if we haven't tried it yet
                    if (isNetworkError && currentCatalogAlternateUrl != null && !hasTriedAlternate && !useAlternate) {
                        Log.d(TAG, "Primary failed for loadMoreItems, trying alternate URL after delay...")
                        // Add a small delay before retrying to avoid hammering servers
                        kotlinx.coroutines.delay(1000)
                        loadMoreItemsWithRetry(originalUrl, true, true)
                        return@fold
                    }

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
     * Check if an entry's navigation link is cached
     * Returns true if the entry is a navigation entry and its target URL exists in cache
     */
    suspend fun isEntryCached(entry: OpdsEntry, baseUrl: String): Boolean {
        // Only check cache for navigation entries (non-book entries)
        if (!entry.isNavigation()) return false

        // Get the navigation link
        val navLink = entry.links.firstOrNull { link ->
            link.rel == "subsection" ||
            link.type?.contains("atom+xml") == true ||
            link.type?.contains("opds-catalog") == true
        } ?: return false

        // Resolve the URL
        val targetUrl = if (navLink.href.startsWith("http://") || navLink.href.startsWith("https://")) {
            navLink.href
        } else {
            repository.resolveUrl(baseUrl, navLink.href)
        }

        // Check if this URL is in cache
        return feedCacheDao.isCached(targetUrl)
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

                Log.d(TAG, "========== ADD TO FAVORITES ==========")
                Log.d(TAG, "Entry: ${entry.title}")
                Log.d(TAG, "Entry ID: ${entry.id}")
                Log.d(TAG, "Navigation history size: ${navigationHistory.size}")
                navigationHistory.forEachIndexed { index, histEntry ->
                    Log.d(TAG, "  [$index] Title: ${histEntry.title}")
                    Log.d(TAG, "       URL: ${histEntry.url}")
                }

                // Serialize the entry to JSON
                val entryJson = gson.toJson(entry)

                // Derive breadcrumbs from navigation history (not from separate breadcrumb lists)
                // This ensures breadcrumbs always match the actual navigation path
                val pathTitles = navigationHistory.map { it.title }
                val pathUrls = navigationHistory.map { it.url }

                val pathTitlesJson = gson.toJson(pathTitles)
                val pathUrlsJson = gson.toJson(pathUrls)

                Log.d(TAG, "Saving hierarchy:")
                Log.d(TAG, "  Titles: $pathTitlesJson")
                Log.d(TAG, "  URLs: $pathUrlsJson")

                // Serialize navigation history for "Display in catalog" feature
                val navHistoryList = navigationHistory.map { histEntry ->
                    SerializableNavHistoryEntry(
                        url = histEntry.url,
                        title = histEntry.title,
                        updated = histEntry.updated
                    )
                }
                val navHistoryJson = gson.toJson(navHistoryList)

                val favorite = FavoriteEntry(
                    catalogId = catalogId,
                    entryJson = entryJson,
                    hierarchyPath = pathTitlesJson,
                    hierarchyUrls = pathUrlsJson,
                    navigationHistory = navHistoryJson
                )

                favoriteDao.insert(favorite)
                Log.d(TAG, "Favorite saved successfully!")
                Log.d(TAG, "========== END ADD TO FAVORITES ==========")

                // Refresh favorited entry IDs set
                refreshFavoritedEntryIds()
            } catch (e: Exception) {
                Log.e(TAG, "Error adding to favorites", e)
            }
        }
    }

    /**
     * Refresh the set of favorited entry IDs for the current catalog
     */
    private suspend fun refreshFavoritedEntryIds() {
        val catalogId = currentCatalogId ?: return
        try {
            val favorites = favoriteDao.getFavoritesForCatalogOnce(catalogId)
            val entryIds = favorites.mapNotNull { favorite ->
                try {
                    val entry: OpdsEntry = gson.fromJson(favorite.entryJson, OpdsEntry::class.java)
                    entry.id
                } catch (e: Exception) {
                    null
                }
            }.toSet()
            _favoritedEntryIds.value = entryIds
            Log.d(TAG, "Refreshed favorited entry IDs: ${entryIds.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh favorited entry IDs", e)
        }
    }

    /**
     * Load favorited entry IDs when catalog is initialized
     */
    fun loadFavoritedEntryIds() {
        viewModelScope.launch {
            refreshFavoritedEntryIds()
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
                refreshFavoritedEntryIds()
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

                    // Refresh favorited entry IDs set
                    refreshFavoritedEntryIds()

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
     * Display a favorite entry in its original catalog location
     * Restores the navigation history so back button navigates through the catalog
     */
    fun displayInCatalog(entry: OpdsEntry, navHistoryJson: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "displayInCatalog: ${entry.title}")

                // Parse navigation history
                val navHistoryType = object : TypeToken<List<SerializableNavHistoryEntry>>() {}.type
                val savedNavHistory: List<SerializableNavHistoryEntry> = gson.fromJson(navHistoryJson, navHistoryType)

                if (savedNavHistory.isEmpty()) {
                    Log.w(TAG, "No navigation history saved for this favorite, opening entry directly")
                    // Fall back to just navigating to the entry's link
                    val entryLink = entry.links.firstOrNull {
                        it.type?.contains("atom+xml") == true
                    }?.href
                    if (entryLink != null) {
                        navigateToUrl(entryLink)
                    }
                    return@launch
                }

                // Exit favorites mode
                _isBrowsingFavorites.value = false
                favoritesCatalogBaseUrl = null

                // Clear current navigation state
                navigationHistory.clear()

                // Restore navigation history from saved state
                savedNavHistory.forEach { savedEntry ->
                    navigationHistory.add(NavigationHistoryEntry(
                        url = savedEntry.url,
                        title = savedEntry.title,
                        updated = savedEntry.updated
                    ))
                }

                // Restore catalog name from first entry
                if (savedNavHistory.isNotEmpty()) {
                    catalogName = savedNavHistory.first().title
                    rootUrl = savedNavHistory.first().url
                }

                Log.d(TAG, "Restored navigation history with ${navigationHistory.size} entries")

                // Now navigate to the entry (either as a feed or show as book)
                val entryLink = entry.links.firstOrNull {
                    it.type?.contains("atom+xml") == true
                }?.href

                if (entry.isAcquisition()) {
                    // For books, load the parent feed and show the book
                    val lastUrl = savedNavHistory.lastOrNull()?.url
                    if (lastUrl != null) {
                        _currentUrl.value = lastUrl
                        // Add the book entry to navigation history
                        navigationHistory.add(NavigationHistoryEntry(
                            url = lastUrl,
                            title = entry.title,
                            updated = null,
                            bookEntry = entry,
                            feedEntryIndex = -1
                        ))
                        _currentBook.value = entry
                        updateNavigationState()

                        // Load the parent feed in background for when user goes back
                        repository.fetchFeed(lastUrl, storedUsername, storedPassword).fold(
                            onSuccess = { result ->
                                currentFeed = result.feed
                                currentBaseUrl = lastUrl
                                _isCurrentFeedFromCache.value = result.fromCache
                            },
                            onFailure = {
                                Log.e(TAG, "Failed to load parent feed", it)
                            }
                        )
                    }
                } else if (entryLink != null) {
                    // For navigation entries, load the feed
                    val resolvedUrl = if (entryLink.startsWith("http://") || entryLink.startsWith("https://")) {
                        entryLink
                    } else {
                        val baseUrl = savedNavHistory.lastOrNull()?.url ?: rootUrl
                        repository.resolveUrl(baseUrl, entryLink)
                    }
                    loadFeed(resolvedUrl)
                } else {
                    Log.w(TAG, "Entry has no navigable link")
                    updateNavigationState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying in catalog", e)
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
     * Get navigation history JSON for a favorite entry (by entry ID)
     * Used by the UI for "Display in catalog" feature
     */
    fun getNavigationHistoryForEntry(entryId: String): String? {
        return favoriteNavHistoryMap[entryId]
    }

    /**
     * Navigate to the catalog path where a favorite entry was originally found (long click)
     * This loads the navigation history and navigates to the last page
     */
    fun navigateToCatalogPath(entryId: String) {
        val navHistoryJson = favoriteNavHistoryMap[entryId] ?: return
        Log.d(TAG, "Navigating to catalog path for entry: $entryId")

        try {
            val navHistory: List<SerializableNavHistoryEntry> = gson.fromJson(
                navHistoryJson,
                object : TypeToken<List<SerializableNavHistoryEntry>>() {}.type
            )

            if (navHistory.isEmpty()) {
                Log.w(TAG, "Empty navigation history for entry: $entryId")
                return
            }

            // Mark that we navigated from favorites (for back navigation)
            navigatedFromFavorites = true
            _isBrowsingFavorites.value = false

            // Clear current navigation history and load the stored one
            navigationHistory.clear()
            navHistory.forEach { entry ->
                navigationHistory.add(NavigationHistoryEntry(entry.url, entry.title, entry.updated))
            }

            // Navigate to the last URL in the history (where the entry was found)
            val lastEntry = navHistory.last()
            Log.d(TAG, "Loading catalog path: ${lastEntry.url} (${lastEntry.title})")

            _currentPageTitle.value = lastEntry.title
            loadFeed(lastEntry.url, addToHistory = false)
            updateNavigationState()
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to catalog path", e)
        }
    }

    /**
     * Get current navigation history as JSON string
     * Used for storing with downloaded books
     */
    fun getCurrentNavigationHistoryJson(): String? {
        if (navigationHistory.isEmpty()) return null
        val navHistoryList = navigationHistory.map { histEntry ->
            SerializableNavHistoryEntry(
                url = histEntry.url,
                title = histEntry.title,
                updated = histEntry.updated
            )
        }
        return gson.toJson(navHistoryList)
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

        // Build flat list of all favorite entries (no hierarchy grouping)
        val entries = mutableListOf<OpdsEntry>()

        Log.d(TAG, "=== Building favorites feed (flat list) ===")
        favorites.forEach { favorite ->
            try {
                val pathList: List<String> = gson.fromJson(favorite.hierarchyPath, object : TypeToken<List<String>>() {}.type)
                val entry: OpdsEntry = gson.fromJson(favorite.entryJson, OpdsEntry::class.java)

                Log.d(TAG, "  Favorite: ${entry.title}")
                Log.d(TAG, "    Path: ${pathList.joinToString(" > ")}")
                Log.d(TAG, "    ID: ${entry.id}")

                entries.add(entry)

                // Store navigation history for "Display in catalog" feature (long click)
                favoriteNavHistoryMap[entry.id] = favorite.navigationHistory
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing favorite", e)
            }
        }
        Log.d(TAG, "=== Favorites: ${entries.size} entries ===")

        // Sort entries alphabetically by title
        val sortedEntries = entries.sortedBy { entry ->
            entry.title.lowercase()
        }

        val feed = OpdsFeed(
            title = "Favorites",
            id = "favorites",
            updated = System.currentTimeMillis().toString(),
            entries = sortedEntries,
            links = emptyList(),
            icon = null
        )

        return Pair(feed, baseUrl)
    }

    /**
     * One-time cleanup: Remove duplicate hierarchy URLs from favorites
     * Call this once to fix existing data, then remove
     */
    suspend fun cleanupDuplicateHierarchyUrls() {
        Log.d(TAG, "========== CLEANUP: Removing duplicate hierarchy URLs ==========")

        val allFavorites = favoriteDao.getAllFavorites()
        Log.d(TAG, "Processing ${allFavorites.size} favorites")

        var fixedCount = 0

        allFavorites.forEach { favorite ->
            try {
                val urlList: MutableList<String> = gson.fromJson(
                    favorite.hierarchyUrls,
                    object : TypeToken<MutableList<String>>() {}.type
                )
                val titleList: MutableList<String> = gson.fromJson(
                    favorite.hierarchyPath,
                    object : TypeToken<MutableList<String>>() {}.type
                )

                if (urlList.size != titleList.size) {
                    Log.w(TAG, "  Favorite ${favorite.id}: URL/Title size mismatch (${urlList.size} vs ${titleList.size}), skipping")
                    return@forEach
                }

                // Find and remove duplicates (keep first occurrence)
                val seenUrls = mutableSetOf<String>()
                val indicesToRemove = mutableListOf<Int>()

                urlList.forEachIndexed { index, url ->
                    if (seenUrls.contains(url)) {
                        indicesToRemove.add(index)
                    } else {
                        seenUrls.add(url)
                    }
                }

                if (indicesToRemove.isNotEmpty()) {
                    Log.d(TAG, "  Favorite ${favorite.id}: Found ${indicesToRemove.size} duplicate URLs")

                    // Remove from end to start to preserve indices
                    indicesToRemove.sortedDescending().forEach { index ->
                        Log.d(TAG, "    Removing index $index: ${urlList[index]} / ${titleList[index]}")
                        urlList.removeAt(index)
                        titleList.removeAt(index)
                    }

                    // Update the database
                    val newUrlsJson = gson.toJson(urlList)
                    val newTitlesJson = gson.toJson(titleList)

                    favoriteDao.updateHierarchy(favorite.id, newTitlesJson, newUrlsJson)
                    fixedCount++

                    Log.d(TAG, "    Fixed! New hierarchy has ${urlList.size} levels")
                }
            } catch (e: Exception) {
                Log.e(TAG, "  Favorite ${favorite.id}: Error processing - ${e.message}")
            }
        }

        Log.d(TAG, "========== CLEANUP COMPLETE: Fixed $fixedCount favorites ==========")
    }

    /**
     * Debug: Dump the complete favorites hierarchy to logcat
     */
    private suspend fun dumpFavoritesHierarchy(catalogId: Long) {
        Log.d(TAG, "========== FAVORITES HIERARCHY DUMP ==========")
        Log.d(TAG, "Catalog ID: $catalogId")

        val favorites = favoriteDao.getFavoritesForCatalogOnce(catalogId)
        Log.d(TAG, "Total favorites count: ${favorites.size}")
        Log.d(TAG, "")

        favorites.forEachIndexed { index, favorite ->
            Log.d(TAG, "--- Favorite #${index + 1} ---")
            Log.d(TAG, "  ID: ${favorite.id}")
            Log.d(TAG, "  Catalog ID: ${favorite.catalogId}")
            Log.d(TAG, "  Added At: ${favorite.addedAt}")

            // Parse and log hierarchy path
            try {
                val pathList: List<String> = gson.fromJson(favorite.hierarchyPath, object : TypeToken<List<String>>() {}.type)
                Log.d(TAG, "  Hierarchy Path (${pathList.size} levels):")
                pathList.forEachIndexed { level, title ->
                    Log.d(TAG, "    [$level] $title")
                }
            } catch (e: Exception) {
                Log.e(TAG, "  Hierarchy Path: ERROR parsing - ${e.message}")
                Log.d(TAG, "  Hierarchy Path (raw): ${favorite.hierarchyPath}")
            }

            // Parse and log hierarchy URLs
            try {
                val urlList: List<String> = gson.fromJson(favorite.hierarchyUrls, object : TypeToken<List<String>>() {}.type)
                Log.d(TAG, "  Hierarchy URLs (${urlList.size} urls):")
                urlList.forEachIndexed { level, url ->
                    Log.d(TAG, "    [$level] $url")
                }
            } catch (e: Exception) {
                Log.e(TAG, "  Hierarchy URLs: ERROR parsing - ${e.message}")
                Log.d(TAG, "  Hierarchy URLs (raw): ${favorite.hierarchyUrls}")
            }

            // Parse and log entry JSON (title and links only)
            try {
                val entry: OpdsEntry = gson.fromJson(favorite.entryJson, OpdsEntry::class.java)
                Log.d(TAG, "  Entry Details:")
                Log.d(TAG, "    Title: ${entry.title}")
                Log.d(TAG, "    ID: ${entry.id}")
                Log.d(TAG, "    Updated: ${entry.updated}")
                Log.d(TAG, "    Links (${entry.links.size}):")
                entry.links.forEach { link ->
                    Log.d(TAG, "      - href: ${link.href}")
                    Log.d(TAG, "        type: ${link.type}")
                    Log.d(TAG, "        rel: ${link.rel}")
                    Log.d(TAG, "        title: ${link.title}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "  Entry JSON: ERROR parsing - ${e.message}")
            }

            // Log navigation history length
            try {
                val navHistory = favorite.navigationHistory
                Log.d(TAG, "  Navigation History length: ${navHistory?.length ?: 0} chars")
            } catch (e: Exception) {
                Log.e(TAG, "  Navigation History: ERROR - ${e.message}")
            }

            Log.d(TAG, "")
        }

        Log.d(TAG, "========== END FAVORITES HIERARCHY DUMP ==========")
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

                val (feed, catalogBaseUrl) = buildFavoritesFeed(catalogId)

                // Store the catalog base URL for resolving relative links
                favoritesCatalogBaseUrl = catalogBaseUrl

                // Store feed and entries for scroll restoration
                currentFeed = feed
                accumulatedEntries.clear()
                accumulatedEntries.addAll(feed.entries)

                _currentPageTitle.value = "Favorites"
                _currentUrl.value = FAVORITES_URL_PREFIX

                // Add to navigation history (store catalog base URL for resolving relative links)
                if (addToHistory) {
                    navigationHistory.add(NavigationHistoryEntry(
                        url = FAVORITES_URL_PREFIX,
                        title = "Favorites",
                        updated = null,
                        favoritesCatalogBaseUrl = catalogBaseUrl
                    ))
                    Log.d(TAG, "Added favorites to history (baseUrl: $catalogBaseUrl)")
                }
                updateNavigationState()

                // Log all entries in favorites
                Log.d(TAG, "=== Favorites entries (${feed.entries.size} items) ===")
                feed.entries.forEachIndexed { index, entry ->
                    val links = entry.links.map { it.href }
                    Log.d(TAG, "  [$index] ${entry.title}")
                    Log.d(TAG, "       id: ${entry.id}")
                    Log.d(TAG, "       links: $links")
                }
                Log.d(TAG, "=== End favorites entries ===")

                // Update state - use the catalog base URL for resolving relative links
                _uiState.value = CatalogUiState.Success(
                    feed = feed,
                    baseUrl = catalogBaseUrl ?: FAVORITES_URL_PREFIX,
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

    // ==================== Last Visited Authors ====================

    /**
     * Save the current author page as a last visited author entry
     */
    private fun saveLastVisitedAuthor(feed: OpdsFeed, url: String, authorName: String) {
        val catalogId = currentCatalogId ?: return
        viewModelScope.launch {
            try {
                // Build navigation history JSON from current history
                val navHistory = navigationHistory.map { entry ->
                    SerializableNavHistoryEntry(entry.url, entry.title, entry.updated)
                }
                val navHistoryJson = gson.toJson(navHistory)

                // Check if this URL was already visited - update timestamp
                val updated = lastVisitedAuthorDao.updateVisitedAt(
                    catalogId, url, System.currentTimeMillis(), navHistoryJson
                )

                if (updated == 0) {
                    // New entry
                    lastVisitedAuthorDao.insert(
                        LastVisitedAuthor(
                            catalogId = catalogId,
                            authorName = authorName,
                            url = url,
                            feedTitle = feed.title,
                            navigationHistory = navHistoryJson,
                            visitedAt = System.currentTimeMillis()
                        )
                    )
                }

                // Trim to max entries
                lastVisitedAuthorDao.trimToLimit(catalogId, LVA_MAX_ENTRIES)
                Log.d(TAG, "Saved last visited author: $authorName ($url)")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving last visited author", e)
            }
        }
    }

    /**
     * Load last visited authors as a virtual feed
     */
    private fun loadLastVisitedAuthorsFeed(addToHistory: Boolean = true) {
        viewModelScope.launch {
            try {
                _isNetworkActive.value = true
                _uiState.value = CatalogUiState.Loading

                val catalogId = currentCatalogId ?: return@launch
                val entries = lastVisitedAuthorDao.getForCatalog(catalogId)

                val opdsEntries = entries.map { lva ->
                    OpdsEntry(
                        title = lva.authorName,
                        id = "lva_${lva.id}",
                        links = listOf(
                            OpdsLink(
                                href = lva.url,
                                type = "application/atom+xml;profile=opds-catalog",
                                rel = "subsection"
                            )
                        ),
                        updated = "",
                        content = "",
                        summary = lva.feedTitle,
                        author = null,
                        categories = emptyList()
                    )
                }

                val feed = OpdsFeed(
                    id = "last_visited_authors",
                    title = "Last Visited Authors",
                    updated = "",
                    entries = opdsEntries,
                    links = emptyList()
                )

                currentFeed = feed
                accumulatedEntries.clear()
                accumulatedEntries.addAll(feed.entries)

                _currentPageTitle.value = "Last Visited Authors"
                _currentUrl.value = LVA_URL_PREFIX

                if (addToHistory) {
                    navigationHistory.add(NavigationHistoryEntry(
                        url = LVA_URL_PREFIX,
                        title = "Last Visited Authors",
                        updated = null
                    ))
                }
                updateNavigationState()

                _uiState.value = CatalogUiState.Success(
                    feed = feed,
                    baseUrl = rootUrl,
                    catalogIcon = catalogIcon,
                    hasNextPage = false,
                    isLoadingMore = false
                )

                _isNetworkActive.value = false
            } catch (e: Exception) {
                Log.e(TAG, "Error loading last visited authors", e)
                _uiState.value = CatalogUiState.Error("Failed to load last visited authors: ${e.message}")
                _isNetworkActive.value = false
            }
        }
    }

    /**
     * Build the root-level virtual entries (Favorites, Last Visited Authors)
     */
    private suspend fun buildRootVirtualEntries(): List<OpdsEntry> {
        val catalogId = currentCatalogId ?: return emptyList()
        val entries = mutableListOf<OpdsEntry>()

        // Favorites entry (only if non-empty)
        val favCount = favoriteDao.getFavoritesCount(catalogId)
        if (favCount > 0) {
            entries.add(OpdsEntry(
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
            ))
        }

        // Last visited authors entry (only if non-empty)
        val lvaCount = lastVisitedAuthorDao.getCount(catalogId)
        if (lvaCount > 0) {
            entries.add(OpdsEntry(
                title = "📖 Last Visited Authors",
                id = "lva_root",
                links = listOf(
                    OpdsLink(
                        href = LVA_URL_PREFIX,
                        type = "application/atom+xml;profile=opds-catalog",
                        rel = "subsection"
                    )
                ),
                updated = "",
                content = "",
                summary = "Recently browsed author pages",
                author = null,
                categories = emptyList()
            ))
        }

        return entries
    }

    // ==================== Search Functions ====================

    // Cache for OpenSearch templates (catalogId -> template URL)
    private val openSearchTemplateCache = mutableMapOf<Long, String>()

    /**
     * Check if current feed has search capability
     */
    fun hasSearchCapability(): Boolean {
        return currentFeed?.hasSearch() == true
    }

    /**
     * Get the search URL template from current feed
     */
    fun getSearchUrl(): String? {
        return currentFeed?.getSearchLink()?.href
    }

    /**
     * Check if the search link is an OpenSearch Description document
     */
    fun isOpenSearchDescription(): Boolean {
        val searchLink = currentFeed?.getSearchLink() ?: return false
        return searchLink.type?.contains("opensearchdescription", ignoreCase = true) == true
    }

    /**
     * Get recent search queries for the current catalog
     */
    suspend fun getRecentSearches(): List<String> {
        val catalogId = currentCatalogId ?: return emptyList()
        return searchHistoryDao.getRecentSearchesOnce(catalogId)
    }

    /**
     * Perform a search using the catalog's search feature
     * Handles both direct search URLs and OpenSearch Description documents
     * @param query The search query
     * @param searchLinkUrl The search link URL (may be a template or OpenSearch Description URL, can be relative)
     */
    fun performSearch(query: String, searchLinkUrl: String) {
        if (query.isBlank()) return

        viewModelScope.launch {
            val catalogId = currentCatalogId ?: return@launch

            // Resolve relative URL against current base URL
            val baseUrl = _currentUrl.value
            val resolvedSearchUrl = repository.resolveUrl(baseUrl, searchLinkUrl)
            Log.d(TAG, "Resolved search URL: $searchLinkUrl -> $resolvedSearchUrl (base: $baseUrl)")

            // Save to search history
            searchHistoryDao.addSearch(catalogId, query.trim())

            // Check if we need to fetch OpenSearch Description
            if (isOpenSearchDescription()) {
                // Check cache first
                val cachedTemplate = openSearchTemplateCache[catalogId]
                if (cachedTemplate != null) {
                    Log.d(TAG, "Using cached OpenSearch template: $cachedTemplate")
                    val searchUrl = buildSearchUrl(cachedTemplate, query)
                    navigateToUrl(searchUrl)
                    return@launch
                }

                // Fetch and parse OpenSearch Description
                Log.d(TAG, "Fetching OpenSearch Description from: $resolvedSearchUrl")
                _isNetworkActive.value = true

                try {
                    val template = fetchOpenSearchTemplate(resolvedSearchUrl)
                    if (template != null) {
                        // Cache the template
                        openSearchTemplateCache[catalogId] = template
                        Log.d(TAG, "Parsed OpenSearch template: $template")

                        val searchUrl = buildSearchUrl(template, query)
                        Log.d(TAG, "Performing search: query='$query', url=$searchUrl")
                        _isNetworkActive.value = false
                        navigateToUrl(searchUrl)
                    } else {
                        Log.e(TAG, "Failed to parse OpenSearch Description")
                        _isNetworkActive.value = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching OpenSearch Description", e)
                    _isNetworkActive.value = false
                }
            } else {
                // Direct search URL template - resolve it first
                val resolvedTemplate = repository.resolveUrl(baseUrl, searchLinkUrl)
                val searchUrl = buildSearchUrl(resolvedTemplate, query)
                Log.d(TAG, "Performing search: query='$query', url=$searchUrl")
                navigateToUrl(searchUrl)
            }
        }
    }

    /**
     * Fetch and parse OpenSearch Description document to extract the search template URL
     */
    private suspend fun fetchOpenSearchTemplate(url: String): String? {
        return try {
            val response = repository.fetchRawContent(url, storedUsername, storedPassword)
            if (response != null) {
                parseOpenSearchDescription(response)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching OpenSearch Description", e)
            null
        }
    }

    /**
     * Parse OpenSearch Description XML to extract the Atom/OPDS search template
     */
    private fun parseOpenSearchDescription(xml: String): String? {
        try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(xml.byteInputStream())

            // Find all Url elements
            val urlElements = document.getElementsByTagName("Url")
            for (i in 0 until urlElements.length) {
                val urlElement = urlElements.item(i)
                val type = urlElement.attributes.getNamedItem("type")?.nodeValue ?: ""
                val template = urlElement.attributes.getNamedItem("template")?.nodeValue

                // Prefer Atom/OPDS type
                if (template != null && (type.contains("atom+xml", ignoreCase = true) ||
                            type.contains("application/atom", ignoreCase = true))) {
                    Log.d(TAG, "Found OpenSearch Atom template: $template")
                    return template
                }
            }

            // Fallback: return first template found
            for (i in 0 until urlElements.length) {
                val urlElement = urlElements.item(i)
                val template = urlElement.attributes.getNamedItem("template")?.nodeValue
                if (template != null) {
                    Log.d(TAG, "Using fallback OpenSearch template: $template")
                    return template
                }
            }

            Log.w(TAG, "No search template found in OpenSearch Description")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing OpenSearch Description", e)
            return null
        }
    }

    /**
     * Build the actual search URL from template and query
     * Handles OpenSearch templates with {searchTerms} placeholder
     */
    private fun buildSearchUrl(templateUrl: String, query: String): String {
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")

        // Remove optional parameters like {startPage?} along with their query param names
        // Pattern matches: &paramName={optionalParam?} or paramName={optionalParam?}&
        var url = templateUrl
            .replace(Regex("&[^=]+=\\{[^}]+\\?\\}"), "") // Remove &paramName={optional?}
            .replace(Regex("[?&][^=]+=\\{[^}]+\\?\\}"), "") // Remove ?paramName={optional?} or &paramName={optional?}
            .replace(Regex("\\{[^}]+\\?\\}"), "") // Remove any remaining {optional?} without param name

        // Clean up any trailing & or ? and double &&
        url = url
            .replace(Regex("&&+"), "&")  // Replace multiple & with single &
            .replace(Regex("\\?&"), "?") // Replace ?& with just ?
            .replace(Regex("[?&]$"), "") // Remove trailing ? or &

        Log.d(TAG, "buildSearchUrl: template='$templateUrl' -> cleaned='$url'")

        return when {
            // OpenSearch template with {searchTerms}
            url.contains("{searchTerms}") -> {
                url.replace("{searchTerms}", encodedQuery)
            }
            // Some catalogs use {searchTerm} (singular)
            url.contains("{searchTerm}") -> {
                url.replace("{searchTerm}", encodedQuery)
            }
            // Some catalogs use {query} or other placeholders
            url.contains("{query}") -> {
                url.replace("{query}", encodedQuery)
            }
            // If URL ends with = or ?, append query directly
            url.endsWith("=") || url.endsWith("?") -> {
                url + encodedQuery
            }
            // Otherwise append as query parameter
            url.contains("?") -> {
                "$url&q=$encodedQuery"
            }
            else -> {
                "$url?q=$encodedQuery"
            }
        }
    }

    /**
     * Delete a specific search from history
     */
    fun deleteSearchFromHistory(query: String) {
        viewModelScope.launch {
            val catalogId = currentCatalogId ?: return@launch
            searchHistoryDao.deleteSearch(catalogId, query)
        }
    }

    /**
     * Clear all search history for the current catalog
     */
    fun clearSearchHistory() {
        viewModelScope.launch {
            val catalogId = currentCatalogId ?: return@launch
            searchHistoryDao.deleteForCatalog(catalogId)
        }
    }

    /**
     * Clean up old search history entries (older than specified days)
     * Should be called periodically (e.g., on app startup)
     */
    suspend fun cleanupOldSearchHistory(daysToKeep: Int = 30) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep.toLong() * 24 * 60 * 60 * 1000)
        val deletedCount = searchHistoryDao.deleteOlderThan(cutoffTime)
        if (deletedCount > 0) {
            Log.d(TAG, "Cleaned up $deletedCount old search history entries")
        }
    }
}
