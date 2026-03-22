package com.example.opdslibrary.ui

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.text.Html
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.res.painterResource
import com.example.opdslibrary.R
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.opdslibrary.data.OpdsEntry
import com.example.opdslibrary.data.OpdsFeed
import com.example.opdslibrary.data.OpdsLink
import com.example.opdslibrary.library.scanner.LibraryScanScheduler
import com.example.opdslibrary.utils.BookDownloader
import com.example.opdslibrary.utils.DownloadResult
import com.example.opdslibrary.utils.DownloadManager as AppDownloadManager
import com.example.opdslibrary.utils.DownloadItem
import com.example.opdslibrary.utils.DownloadStatus
import com.example.opdslibrary.utils.FormatSelector
import com.example.opdslibrary.utils.BookOpener
import com.example.opdslibrary.data.AppPreferences
import com.example.opdslibrary.viewmodel.CatalogUiState
import com.example.opdslibrary.viewmodel.CatalogViewModel
import com.google.gson.GsonBuilder
import com.example.opdslibrary.data.library.Book

/**
 * Main catalog screen that displays OPDS feeds
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onBack: () -> Unit,
    onNavigateToLibrary: (authorId: Long) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val catalogTitle by viewModel.catalogTitle.collectAsState()
    val currentPageTitle by viewModel.currentPageTitle.collectAsState()
    val canNavigateBack by viewModel.canNavigateBack.collectAsState()
    val authState by viewModel.authState.collectAsState()
    val isNetworkActive by viewModel.isNetworkActive.collectAsState()
    val catalogNotFoundState by viewModel.catalogNotFoundState.collectAsState()
    val shouldExitCatalog by viewModel.shouldExitCatalog.collectAsState()
    val isCurrentFeedFromCache by viewModel.isCurrentFeedFromCache.collectAsState()
    val scrollToEntryIndex by viewModel.scrollToEntryIndex.collectAsState()
    val isBrowsingFavorites by viewModel.isBrowsingFavorites.collectAsState()
    val currentBook by viewModel.currentBook.collectAsState()
    val htmlPageUrl by viewModel.htmlPageUrl.collectAsState()
    val isMatchingInProgress by viewModel.isMatchingInProgress.collectAsState()
    var showUrlDialog by remember { mutableStateOf(false) }
    var scrollToIndexTrigger by remember { mutableStateOf(0) }
    val context = LocalContext.current

    // BookDownloader for handling downloads with SAF support
    val bookDownloader = remember { BookDownloader(context) }

    // Download progress state
    var downloadProgress by remember { mutableStateOf<Int?>(null) }
    var isDownloading by remember { mutableStateOf(false) }

    // Downloads manager state
    val downloads by AppDownloadManager.downloads.collectAsState()
    val activeDownloadsCount by AppDownloadManager.activeDownloadsCount.collectAsState()
    val attentionCount by AppDownloadManager.attentionCount.collectAsState()
    var showDownloadsDialog by remember { mutableStateOf(false) }

    // Track pending downloads for retry with alternate URL (legacy - keep for now)
    val pendingDownloads = remember { mutableStateMapOf<Long, PendingDownloadInfo>() }

    // State for favorites overlay
    var selectedEntryForOverlay by remember { mutableStateOf<OpdsEntry?>(null) }
    var showFavoritesOverlay by remember { mutableStateOf(false) }
    var showClearFavoritesConfirmation by remember { mutableStateOf(false) }

    // Search state
    var showSearchDialog by remember { mutableStateOf(false) }
    var recentSearches by remember { mutableStateOf<List<String>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()

    // Preferred reader app
    val appPreferences = remember { AppPreferences(context) }
    val preferredReaderPackage by appPreferences.preferredReaderPackage.collectAsState(initial = null)

    // Auto-hide favorites overlay after 3 seconds
    LaunchedEffect(showFavoritesOverlay) {
        if (showFavoritesOverlay) {
            kotlinx.coroutines.delay(3000)
            showFavoritesOverlay = false
            selectedEntryForOverlay = null
        }
    }

    val catalogIconUrl = remember { mutableStateOf<String?>(null) }

    // Handle system back button/gesture
    BackHandler(enabled = canNavigateBack) {
        Log.d("CatalogScreen", "BackHandler: System back intercepted, calling viewModel.navigateBack()")
        viewModel.navigateBack()
    }

    // Extract catalog icon from state
    LaunchedEffect(uiState) {
        if (uiState is CatalogUiState.Success) {
            catalogIconUrl.value = (uiState as CatalogUiState.Success).catalogIcon
        }
    }

    // Handle catalog exit signal (e.g., when auth is cancelled at root level)
    LaunchedEffect(shouldExitCatalog) {
        if (shouldExitCatalog) {
            Log.d("CatalogScreen", "Exit catalog signal received - navigating back to catalog list")
            viewModel.exitCatalogHandled()
            onBack()
        }
    }

    // Register broadcast receiver for download completion
    DisposableEffect(context) {
        val downloadCompleteReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId != -1L) {
                    Log.d("CatalogScreen", "Download event received: $downloadId")

                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)

                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = cursor.getInt(statusIndex)

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                            val uri = cursor.getString(uriIndex)
                            val fileName = cursor.getString(titleIndex)

                            Log.d("CatalogScreen", "Download successful: $fileName at $uri")

                            // Remove from pending downloads
                            pendingDownloads.remove(downloadId)

                            // Show toast notification
                            Toast.makeText(context, "Book $fileName downloaded", Toast.LENGTH_SHORT).show()

                            // Schedule library scan for the downloaded file to add it to the library
                            try {
                                val scanScheduler = LibraryScanScheduler(context)
                                scanScheduler.scheduleProcessFile(Uri.parse(uri))
                                Log.d("CatalogScreen", "Scheduled library scan for downloaded file: $uri")
                            } catch (e: Exception) {
                                Log.e("CatalogScreen", "Failed to schedule library scan", e)
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = cursor.getInt(reasonIndex)
                            Log.e("CatalogScreen", "Download failed: status=$status, reason=$reason")

                            // Check if we have pending download info for retry
                            val pending = pendingDownloads[downloadId]
                            if (pending != null) {
                                pendingDownloads.remove(downloadId)

                                if (!pending.isRetry && pending.alternateUrl != null) {
                                    // Retry with alternate URL
                                    Log.d("CatalogScreen", "Retrying download with alternate URL: ${pending.alternateUrl}")
                                    Toast.makeText(context, "Primary URL failed, trying alternate...", Toast.LENGTH_SHORT).show()

                                    val newDownloadId = startDownload(
                                        context = context,
                                        url = pending.alternateUrl,
                                        filename = pending.filename,
                                        fileType = pending.fileType,
                                        downloadFolder = pending.downloadFolder
                                    )
                                    if (newDownloadId != -1L) {
                                        pendingDownloads[newDownloadId] = PendingDownloadInfo(
                                            primaryUrl = pending.primaryUrl,
                                            alternateUrl = pending.alternateUrl,
                                            filename = pending.filename,
                                            fileType = pending.fileType,
                                            downloadFolder = pending.downloadFolder,
                                            isRetry = true
                                        )
                                    } else {
                                        Toast.makeText(context, "Download failed: ${pending.filename}", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    // Both URLs failed or no alternate URL
                                    Log.e("CatalogScreen", "Download failed permanently: ${pending.filename}")
                                    Toast.makeText(context, "Download failed: ${pending.filename}", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Toast.makeText(context, "Download failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    cursor.close()
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadCompleteReceiver, filter)
        }

        onDispose {
            context.unregisterReceiver(downloadCompleteReceiver)
        }
    }

    Scaffold(
        topBar = {
            // Don't show title bar spinner when full-page loading screen is visible
            val showTitleBarSpinner = isNetworkActive && uiState !is CatalogUiState.Loading

            CatalogTopAppBar(
                catalogTitle = catalogTitle,
                catalogIconUrl = catalogIconUrl.value,
                currentPageTitle = currentPageTitle,
                canNavigateBack = canNavigateBack,
                isNetworkActive = showTitleBarSpinner,
                isFeedFromCache = isCurrentFeedFromCache,
                isBrowsingFavorites = isBrowsingFavorites,
                hasSearch = viewModel.hasSearchCapability(),
                isMatchingInProgress = isMatchingInProgress,
                activeDownloadsCount = activeDownloadsCount,
                attentionCount = attentionCount,
                onCatalogClick = { viewModel.navigateToRoot() },
                onBackClick = { viewModel.navigateBack() },
                onBackToListClick = onBack,
                onRefreshClick = { viewModel.refresh() },
                onAddClick = { showUrlDialog = true },
                onSearchClick = {
                    // Load recent searches before showing dialog
                    coroutineScope.launch {
                        recentSearches = viewModel.getRecentSearches()
                        showSearchDialog = true
                    }
                },
                onDownloadsClick = { showDownloadsDialog = true }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is CatalogUiState.Initial -> {
                    InitialScreen(onOpenCatalog = { showUrlDialog = true })
                }
                is CatalogUiState.Loading -> {
                    LoadingScreen()
                }
                is CatalogUiState.Success -> {
                    // Show either book details page or feed
                    if (currentBook != null) {
                        // Show book details page
                        BookDetailsPage(
                            book = currentBook,
                            baseUrl = state.baseUrl,
                            catalogIcon = state.catalogIcon,
                            viewModel = viewModel,
                            isBrowsingFavorites = isBrowsingFavorites,
                            bookDownloader = bookDownloader,
                            onDownloadComplete = { uri, filename ->
                                Toast.makeText(context, "Book $filename downloaded", Toast.LENGTH_SHORT).show()
                            },
                            onOpenBook = { localBook ->
                                // Open the book file using BookOpener
                                BookOpener.openBook(
                                    context = context,
                                    filePath = localBook.filePath,
                                    preferredPackage = preferredReaderPackage
                                )
                                Log.d("CatalogScreen", "Opening book: ${localBook.title} at ${localBook.filePath}")
                            }
                        )
                    } else {
                        // Show feed
                        // Use baseUrl as key to preserve scroll state across recompositions
                        key(state.baseUrl) {
                            FeedContent(
                            feed = state.feed,
                            baseUrl = state.baseUrl,
                            catalogIcon = state.catalogIcon,
                            viewModel = viewModel,
                            scrollToIndex = scrollToEntryIndex,
                            scrollTrigger = scrollToIndexTrigger,
                            onEntryClick = { entry, index ->
                            Log.d("CatalogScreen", "=== Entry clicked (index: $index) ===")
                            Log.d("CatalogScreen", "  Entry title: '${entry.title}'")
                            Log.d("CatalogScreen", "  Is navigation: ${entry.isNavigation()}")
                            Log.d("CatalogScreen", "  Is acquisition: ${entry.isAcquisition()}")

                            when {
                                // For acquisition entries (books), show details dialog
                                entry.isAcquisition() -> {
                                    Log.d("CatalogScreen", "  Setting selectedBookIndex = $index")

                                    // Dump book entry XML/data to log
                                    Log.d("BookInfo", "========================================")
                                    Log.d("BookInfo", "Opening book details for: ${entry.title}")
                                    Log.d("BookInfo", "========================================")
                                    Log.d("BookInfo", "ID: ${entry.id}")
                                    Log.d("BookInfo", "Title: ${entry.title}")
                                    Log.d("BookInfo", "Author: ${entry.author?.name ?: "N/A"}")
                                    Log.d("BookInfo", "Updated: ${entry.updated}")
                                    Log.d("BookInfo", "Published: ${entry.published ?: "N/A"}")
                                    Log.d("BookInfo", "Summary: ${entry.summary ?: "N/A"}")
                                    Log.d("BookInfo", "Content: ${entry.content ?: "N/A"}")
                                    Log.d("BookInfo", "Language: ${entry.dcLanguage ?: "N/A"}")
                                    Log.d("BookInfo", "Publisher: ${entry.dcPublisher ?: "N/A"}")
                                    Log.d("BookInfo", "Issued: ${entry.dcIssued ?: "N/A"}")
                                    Log.d("BookInfo", "----------------------------------------")
                                    Log.d("BookInfo", "Links (${entry.links.size}):")
                                    entry.links.forEachIndexed { idx, link ->
                                        Log.d("BookInfo", "  Link $idx:")
                                        Log.d("BookInfo", "    href: ${link.href}")
                                        Log.d("BookInfo", "    type: ${link.type ?: "N/A"}")
                                        Log.d("BookInfo", "    rel: ${link.rel ?: "N/A"}")
                                        Log.d("BookInfo", "    title: ${link.title ?: "N/A"}")
                                    }
                                    Log.d("BookInfo", "----------------------------------------")
                                    Log.d("BookInfo", "Categories (${entry.categories.size}):")
                                    entry.categories.forEachIndexed { idx, category ->
                                        Log.d("BookInfo", "  Category $idx: ${category.label ?: category.term}")
                                    }
                                    Log.d("BookInfo", "========================================")

                                    viewModel.showBookDetails(entry, index)
                                }
                                // For navigation entries, navigate to the URL and pass the index
                                entry.isNavigation() -> {
                                    entry.getNavigationUrl()?.let { url ->
                                        viewModel.navigateToUrl(url, index)
                                    }
                                }
                                // For other entries, try to find a navigable link
                                else -> {
                                    val urlToNavigate = entry.links.firstOrNull {
                                        it.rel == "alternate" ||
                                        it.rel == "related" ||
                                        it.type?.contains("atom+xml") == true
                                    }?.href

                                    urlToNavigate?.let { url ->
                                        viewModel.navigateToUrl(url, index)
                                    }
                                }
                            }
                        },
                        onEntryLongClick = { entry ->
                            Log.d("CatalogScreen", "Entry long-clicked: ${entry.title} (isBrowsingFavorites=$isBrowsingFavorites)")
                            if (isBrowsingFavorites) {
                                // When in favorites, long click navigates to the catalog path
                                Log.d("CatalogScreen", "Navigating to catalog path for: ${entry.id}")
                                viewModel.navigateToCatalogPath(entry.id)
                            } else {
                                // Normal behavior - show favorites overlay
                                selectedEntryForOverlay = entry
                                showFavoritesOverlay = true
                            }
                        },
                        onDownload = { url, fileType, entryId, title, relLinksJson, navHistoryJson, opdsUpdated ->
                            Log.d("CatalogScreen", "Download requested: $url ($fileType), entryId=$entryId, title=$title, hasRelLinks=${relLinksJson != null}, hasNavHistory=${navHistoryJson != null}")

                            // Extract fallback filename from URL
                            val fallbackFilename = url.substringAfterLast("/").ifEmpty {
                                "book_${System.currentTimeMillis()}.${fileType.lowercase()}"
                            }

                            // Get alternate URL if available
                            val alternateUrl = viewModel.convertToAlternateUrl(url)

                            // Get catalog ID for OPDS entry linking
                            val catalogId = viewModel.getCatalogId()

                            Toast.makeText(context, "Starting download...", Toast.LENGTH_SHORT).show()

                            // Use DownloadManager for tracked downloads
                            AppDownloadManager.startDownload(
                                context = context,
                                title = title,
                                url = url,
                                fallbackFilename = fallbackFilename,
                                alternateUrl = alternateUrl,
                                opdsEntryId = entryId,
                                catalogId = catalogId,
                                opdsRelLinks = relLinksJson,
                                opdsNavigationHistory = navHistoryJson,
                                onComplete = { uri, filename ->
                                    // Download completed - add to library
                                    Toast.makeText(context, "Book $filename downloaded", Toast.LENGTH_SHORT).show()

                                    try {
                                        val scanScheduler = LibraryScanScheduler(context)
                                        scanScheduler.scheduleProcessFile(uri, entryId, catalogId, relLinksJson, navHistoryJson, opdsUpdated)
                                        Log.d("CatalogScreen", "Scheduled library scan for: $uri (opdsEntryId=$entryId, catalogId=$catalogId)")

                                        // Update UI after scan completes
                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                            kotlinx.coroutines.delay(2000)
                                            viewModel.recheckEntryAfterDownload(entryId, opdsUpdated)
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CatalogScreen", "Failed to schedule library scan", e)
                                    }
                                },
                                onError = { error ->
                                    Toast.makeText(context, "Download failed: $error", Toast.LENGTH_LONG).show()
                                }
                            )
                        },
                        onOpenBook = { book ->
                            // Open the book file using BookOpener
                            BookOpener.openBook(
                                context = context,
                                filePath = book.filePath,
                                preferredPackage = preferredReaderPackage
                            )
                            Log.d("CatalogScreen", "Opening book: ${book.title} at ${book.filePath}")
                        },
                        onNavigateToLibrary = onNavigateToLibrary
                    )
                        }
                    }
                }
                is CatalogUiState.Error -> {
                    ErrorScreen(
                        message = state.message,
                        onRetry = { viewModel.retry() },
                        hasCachedData = state.hasCachedData,
                        onShowCached = { viewModel.loadCachedFeed(state.failedUrl) }
                    )
                }
            }
        }
    }

    // Show HTML page in WebView when server returns HTML instead of OPDS feed
    htmlPageUrl?.let { url ->
        WebViewScreen(
            url = url,
            onClose = { viewModel.closeHtmlPage() }
        )
    }

    if (showUrlDialog) {
        CatalogUrlDialog(
            onDismiss = { showUrlDialog = false },
            onConfirm = { url ->
                showUrlDialog = false
                viewModel.loadFeed(url)
            }
        )
    }

    // Downloads dialog
    if (showDownloadsDialog) {
        DownloadsDialog(
            downloads = downloads,
            onDismiss = { showDownloadsDialog = false },
            onClearCompleted = { AppDownloadManager.clearCompleted() },
            onRemoveDownload = { id -> AppDownloadManager.removeDownload(id) },
            onRetryDownload = { id -> AppDownloadManager.retryDownload(context, id) }
        )
    }

    // Login dialog for authentication
    if (authState.isRequired) {
        LoginDialog(
            attemptCount = authState.attemptCount,
            errorMessage = authState.errorMessage,
            onDismiss = {
                viewModel.cancelAuthentication()
            },
            onConfirm = { username, password ->
                viewModel.submitCredentials(username, password)
            }
        )
    }

    // 404 Catalog Not Found dialog
    catalogNotFoundState?.let { notFoundState ->
        AlertDialog(
            onDismissRequest = {
                viewModel.dismiss404Dialog()
                onBack()
            },
            icon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_library),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Catalog Not Found") },
            text = {
                Text("The catalog \"${notFoundState.catalogName}\" is no longer available (404 error).\n\nWould you like to delete it from your library?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCatalog()
                        onBack()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismiss404Dialog()
                        onBack()
                    }
                ) {
                    Text("Keep")
                }
            }
        )
    }


    // Favorites overlay - displayed on top of everything
    if (showFavoritesOverlay && selectedEntryForOverlay != null) {
        val entry = selectedEntryForOverlay!!
        val isFavoritesEntry = entry.id == "favorites_root"
        val isFavoritesSubcategory = isBrowsingFavorites && entry.links.any {
            it.href.startsWith("internal://favorites")
        }

        Log.d("FavOverlay", "entry.id='${entry.id}', title='${entry.title}'")
        Log.d("FavOverlay", "isFavoritesEntry=$isFavoritesEntry, isFavoritesSubcategory=$isFavoritesSubcategory, isBrowsingFavorites=$isBrowsingFavorites")
        Log.d("FavOverlay", "entry.links: ${entry.links.map { "${it.rel}:${it.href}" }}")
        Log.d("FavOverlay", "Will show: ${if (isFavoritesEntry) "Clear All" else if (isBrowsingFavorites && !isFavoritesSubcategory) "Display/Remove" else if (!isFavoritesSubcategory) "Add to Favorites" else "NOTHING"}")

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable {
                    showFavoritesOverlay = false
                    selectedEntryForOverlay = null
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Entry title
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    if (isFavoritesEntry) {
                        // Show "Clear All Favorites" for favorites root
                        TextButton(
                            onClick = {
                                showFavoritesOverlay = false
                                selectedEntryForOverlay = null
                                showClearFavoritesConfirmation = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Clear All Favorites",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else if (isBrowsingFavorites && !isFavoritesSubcategory) {
                        // Options when browsing favorites
                        val navHistoryJson = viewModel.getNavigationHistoryForEntry(entry.id)
                        if (navHistoryJson != null && navHistoryJson != "[]") {
                            TextButton(
                                onClick = {
                                    viewModel.displayInCatalog(entry, navHistoryJson)
                                    showFavoritesOverlay = false
                                    selectedEntryForOverlay = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Display in Catalog",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                viewModel.removeFavoriteByEntry(entry)
                                showFavoritesOverlay = false
                                selectedEntryForOverlay = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Remove from Favorites",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else if (!isFavoritesSubcategory) {
                        // Regular entry - Add to Favorites
                        TextButton(
                            onClick = {
                                viewModel.addToFavorites(entry)
                                showFavoritesOverlay = false
                                selectedEntryForOverlay = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Add to Favorites",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog for clearing all favorites
    if (showClearFavoritesConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearFavoritesConfirmation = false },
            title = { Text("Clear All Favorites") },
            text = { Text("Are you sure you want to remove all favorites from this catalog? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllFavorites()
                        showClearFavoritesConfirmation = false
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearFavoritesConfirmation = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Search dialog
    if (showSearchDialog) {
        val searchUrl = viewModel.getSearchUrl()
        if (searchUrl != null) {
            SearchDialog(
                recentSearches = recentSearches,
                onDismiss = { showSearchDialog = false },
                onSearch = { query ->
                    showSearchDialog = false
                    viewModel.performSearch(query, searchUrl)
                },
                onDeleteSearch = { query ->
                    viewModel.deleteSearchFromHistory(query)
                    // Update the list
                    coroutineScope.launch {
                        recentSearches = viewModel.getRecentSearches()
                    }
                },
                onClearHistory = {
                    viewModel.clearSearchHistory()
                    recentSearches = emptyList()
                }
            )
        }
    }
}


/**
 * Convert OPDS entry related links to JSON string for storage
 */
private fun serializeRelLinks(entry: OpdsEntry): String? {
    val relLinks = entry.getRelatedLinks()
    if (relLinks.isEmpty()) return null
    val bookRelLinks = relLinks.map { link ->
        Book.OpdsRelLink(
            href = link.href,
            title = link.title,
            type = link.type,
            rel = link.rel
        )
    }
    return Book.serializeOpdsRelLinks(bookRelLinks)
}

/**
 * Custom top app bar with clickable catalog title and optional subtitle
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogTopAppBar(
    catalogTitle: String,
    catalogIconUrl: String?,
    currentPageTitle: String,
    canNavigateBack: Boolean,
    isNetworkActive: Boolean,
    isFeedFromCache: Boolean,
    isBrowsingFavorites: Boolean,
    hasSearch: Boolean,
    isMatchingInProgress: Boolean = false,
    activeDownloadsCount: Int = 0,
    attentionCount: Int = 0,
    onCatalogClick: () -> Unit,
    onBackClick: () -> Unit,
    onBackToListClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onAddClick: () -> Unit,
    onSearchClick: () -> Unit,
    onDownloadsClick: () -> Unit = {}
) {
    // Log when composable recomposes with new values
    LaunchedEffect(catalogTitle, currentPageTitle, canNavigateBack, isFeedFromCache, isBrowsingFavorites) {
        Log.d("CatalogTopAppBar", "=== TopAppBar recomposed ===")
        Log.d("CatalogTopAppBar", "  catalogTitle: '$catalogTitle'")
        Log.d("CatalogTopAppBar", "  catalogIconUrl: '$catalogIconUrl'")
        Log.d("CatalogTopAppBar", "  currentPageTitle: '$currentPageTitle'")
        Log.d("CatalogTopAppBar", "  canNavigateBack: $canNavigateBack")
        Log.d("CatalogTopAppBar", "  isFeedFromCache: $isFeedFromCache")
        Log.d("CatalogTopAppBar", "  isBrowsingFavorites: $isBrowsingFavorites")
        Log.d("CatalogTopAppBar", "  showSubtitle: ${currentPageTitle.isNotEmpty() && canNavigateBack}")
    }

    Column {
        // Main top app bar with catalog name
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onCatalogClick)
                ) {
                    catalogIconUrl?.let { iconUrl ->
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(iconUrl)
                                .crossfade(true)
                                .allowHardware(false)
                                .build(),
                            contentDescription = "Catalog icon",
                            modifier = Modifier
                                .size(32.dp)
                                .padding(end = 8.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Text(
                        text = catalogTitle,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            },
            navigationIcon = {
                // Show back button to catalog list when at root
                if (!canNavigateBack) {
                    IconButton(onClick = onBackToListClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to catalog list"
                        )
                    }
                }
            },
            actions = {
                // Downloads indicator - shows when there are active or failed downloads
                if (attentionCount > 0) {
                    Box {
                        IconButton(onClick = onDownloadsClick) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_download),
                                contentDescription = "Downloads ($attentionCount items)",
                                tint = if (attentionCount > activeDownloadsCount)
                                    MaterialTheme.colorScheme.error  // Has failed downloads
                                else
                                    MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        // Badge showing count
                        Badge(
                            containerColor = if (attentionCount > activeDownloadsCount)
                                MaterialTheme.colorScheme.error  // Has failed downloads
                            else
                                MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-4).dp, y = 4.dp)
                        ) {
                            Text(text = attentionCount.toString())
                        }
                    }
                }

                // Network activity indicator
                if (isNetworkActive) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CustomSpinner(size = 24, strokeWidth = 3f)
                    }
                }

                // Search button - only shown when catalog has search capability
                if (hasSearch) {
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search catalog"
                        )
                    }
                }

                // Refresh button - rotates while matching, different color when feed is from cache
                IconButton(onClick = onRefreshClick) {
                    // Rotate while matching or network active
                    val shouldRotate = isMatchingInProgress || isNetworkActive
                    val rotation = if (shouldRotate) {
                        val infiniteTransition = rememberInfiniteTransition(label = "refresh_rotation")
                        infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "rotation"
                        ).value
                    } else 0f

                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = when {
                            isMatchingInProgress -> "Matching in progress..."
                            isNetworkActive -> "Loading from network..."
                            isFeedFromCache -> "Cached - tap to refresh from network"
                            else -> "Refresh catalog"
                        },
                        tint = if (isFeedFromCache) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.rotate(rotation)
                    )
                }
                // Only show Add button at root level (when can't navigate back)
                if (!canNavigateBack) {
                    IconButton(onClick = onAddClick) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Open Catalog"
                        )
                    }
                }
            }
        )

        // Subtitle bar with current page title and back button (only shown when not at root)
        if (currentPageTitle.isNotEmpty() && canNavigateBack) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = if (isBrowsingFavorites) "⭐ $currentPageTitle" else currentPageTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Initial screen shown when no catalog is loaded
 */
@Composable
fun InitialScreen(onOpenCatalog: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to OPDS Library",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Browse OPDS catalogs to discover and read books",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onOpenCatalog) {
            Text("Open Catalog")
        }
    }
}

/**
 * Custom spinning progress indicator that works even when system animator
 * duration scale is set to 0 (e.g., by Samsung's Battery Guardian).
 * Uses coroutine-based frame animation instead of Compose animation APIs.
 *
 * @param progress Optional progress value (0.0 to 1.0) for determinate mode.
 *                 If null, shows indeterminate spinning animation.
 */
@Composable
fun CustomSpinner(
    modifier: Modifier = Modifier,
    size: Int = 48,
    strokeWidth: Float = 4f,
    color: Color = MaterialTheme.colorScheme.primary,
    progress: Float? = null
) {
    var rotation by remember { mutableFloatStateOf(0f) }

    // Frame-based animation using coroutines - not affected by animator duration scale
    // Only animate if in indeterminate mode (progress is null)
    LaunchedEffect(progress == null) {
        if (progress == null) {
            while (true) {
                delay(16) // ~60 FPS
                rotation = (rotation + 6f) % 360f
            }
        }
    }

    val sizeDp = size.dp
    val strokeDp = strokeWidth.dp

    Box(
        modifier = modifier
            .size(sizeDp)
            .drawBehind {
                // Background track
                drawArc(
                    color = color.copy(alpha = 0.2f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeDp.toPx(), cap = StrokeCap.Round)
                )

                if (progress != null) {
                    // Determinate mode - show progress arc
                    val sweepAngle = 360f * progress.coerceIn(0f, 1f)
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = strokeDp.toPx(), cap = StrokeCap.Round)
                    )
                } else {
                    // Indeterminate mode - show spinning arc
                    val sweepAngle = 270f
                    val startAngle = rotation - 90f
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = strokeDp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
    )
}

/**
 * Loading screen
 */
@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CustomSpinner(size = 48, strokeWidth = 4f)
    }
}

/**
 * Error screen
 */
@Composable
fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    hasCachedData: Boolean = false,
    onShowCached: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = onRetry) {
                Text("Retry")
            }
            if (hasCachedData) {
                OutlinedButton(onClick = onShowCached) {
                    Text("Take from cache")
                }
            }
        }
    }
}

/**
 * WebView screen for displaying HTML pages
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(
    url: String,
    onClose: () -> Unit
) {
    Log.d("WebViewScreen", "=== WebViewScreen Composing ===")
    Log.d("WebViewScreen", "  URL to load: $url")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = false) { } // Prevent clicks from passing through
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Web Page") },
                    navigationIcon = {
                        IconButton(onClick = {
                            Log.d("WebViewScreen", "Close button clicked")
                            onClose()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close"
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            AndroidView(
                factory = { context ->
                    Log.d("WebViewScreen", "Creating WebView instance")
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                Log.d("WebViewScreen", "Page started loading: $url")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.d("WebViewScreen", "Page finished loading: $url")
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    Log.e("WebViewScreen", "Error loading page: ${error?.description} (code: ${error?.errorCode}, url: ${request?.url})")
                                }
                            }
                        }
                        loadUrl(url)
                        Log.d("WebViewScreen", "WebView.loadUrl() called with: $url")
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        }
    }
}

/**
 * Feed content display with infinite scrolling and pull-to-refresh
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedContent(
    feed: OpdsFeed,
    baseUrl: String,
    catalogIcon: String?,
    viewModel: CatalogViewModel,
    scrollToIndex: Int = -1,
    scrollTrigger: Int = 0,
    onEntryClick: (OpdsEntry, Int) -> Unit,
    onEntryLongClick: (OpdsEntry) -> Unit,
    onDownload: (url: String, fileType: String, entryId: String, title: String, relLinksJson: String?, navHistoryJson: String?, opdsUpdated: Long?) -> Unit,
    onOpenBook: (com.example.opdslibrary.data.library.Book) -> Unit = {},
    onNavigateToLibrary: (authorId: Long) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val uiState by viewModel.uiState.collectAsState()
    val isBrowsingFavorites by viewModel.isBrowsingFavorites.collectAsState()
    val favoritedEntryIds by viewModel.favoritedEntryIds.collectAsState()
    val isNetworkActive by viewModel.isNetworkActive.collectAsState()
    val pageBoundaries by viewModel.pageBoundaries.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Pull-to-refresh state
    val pullToRefreshState = rememberPullToRefreshState()

    // Log when FeedContent recomposes with new parameters
    LaunchedEffect(Unit) {
        Log.d("FeedContent", "FeedContent composed - scrollToIndex: $scrollToIndex, scrollTrigger: $scrollTrigger")
    }

    // Scroll to specific entry when trigger changes (for dialog close)
    LaunchedEffect(scrollTrigger) {
        if (scrollTrigger > 0 && scrollToIndex >= 0 && scrollToIndex < feed.entries.size) {
            Log.d("FeedContent", "=== Scroll trigger (dialog) fired: $scrollTrigger ===")
            Log.d("FeedContent", "  scrollToIndex: $scrollToIndex")
            try {
                listState.scrollToItem(scrollToIndex)
                Log.d("FeedContent", "  ✓ Scroll completed")
            } catch (e: Exception) {
                Log.e("FeedContent", "  ✗ Scroll failed", e)
            }
        }
    }

    // Scroll to specific entry when navigating back in history
    LaunchedEffect(scrollToIndex) {
        // Only scroll if scrollToIndex >= 0 (valid index set by navigation)
        if (scrollToIndex >= 0 && scrollToIndex < feed.entries.size) {
            Log.d("FeedContent", "=== Scroll from navigation: scrollToIndex=$scrollToIndex ===")
            Log.d("FeedContent", "  feed.entries.size: ${feed.entries.size}")
            Log.d("FeedContent", "  Current first visible: ${listState.firstVisibleItemIndex}")

            try {
                listState.scrollToItem(scrollToIndex)
                Log.d("FeedContent", "  ✓ Navigation scroll completed. New first visible: ${listState.firstVisibleItemIndex}")
            } catch (e: Exception) {
                Log.e("FeedContent", "  ✗ Navigation scroll failed", e)
            }
        }
    }

    // Track last logged range to avoid duplicate logs
    var lastLoggedRange by remember { mutableStateOf("") }

    // Detect when user scrolls near the bottom and log visible items
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .distinctUntilChanged()
            .collect { layoutInfo ->
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0

                // Log visible items in scroll (only when range changes)
                val visibleItems = layoutInfo.visibleItemsInfo
                if (visibleItems.isNotEmpty()) {
                    val firstVisible = visibleItems.first().index
                    val lastVisible = visibleItems.last().index
                    val currentRange = "$firstVisible-$lastVisible"

                    if (currentRange != lastLoggedRange) {
                        lastLoggedRange = currentRange
                    }
                }

                // Load more when within 3 items of the end
                if (totalItems > 0 && lastVisibleItem >= totalItems - 3) {
                    val state = uiState
                    if (state is CatalogUiState.Success && state.hasNextPage && !state.isLoadingMore) {
                        Log.d("FeedContent", "Near bottom: loading more items (lastVisible=$lastVisibleItem, total=$totalItems)")
                        viewModel.loadMoreItems()
                    }
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = isNetworkActive,
                state = pullToRefreshState,
                onRefresh = {
                    Log.d("FeedContent", "Pull-to-refresh triggered")
                    viewModel.refresh()
                }
            )
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(feed.entries, key = { index, entry -> "${entry.id}_$index" }) { index, entry ->
                // Show subtle page divider at page boundaries
                if (index in pageBoundaries) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }

                EntryCard(
                    entry = entry,
                    baseUrl = baseUrl,
                    catalogIcon = catalogIcon,
                    viewModel = viewModel,
                    isBrowsingFavorites = isBrowsingFavorites,
                    isFavorited = entry.id in favoritedEntryIds,
                    onClick = { onEntryClick(entry, index) },
                    onLongClick = { onEntryLongClick(entry) },
                    onDownload = onDownload,
                    onOpenBook = onOpenBook,
                    onOpenInLibrary = onNavigateToLibrary
                )
            }

            // Loading indicator at the bottom
            if (uiState is CatalogUiState.Success && (uiState as CatalogUiState.Success).isLoadingMore) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CustomSpinner(size = 32, strokeWidth = 3f)
                    }
                }
            }
        }

        // Pull-to-refresh indicator
        PullToRefreshDefaults.Indicator(
            state = pullToRefreshState,
            isRefreshing = isNetworkActive,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * Entry card component with smart download/open buttons
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EntryCard(
    entry: OpdsEntry,
    baseUrl: String,
    catalogIcon: String?,
    viewModel: CatalogViewModel,
    isBrowsingFavorites: Boolean = false,
    isFavorited: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDownload: (url: String, fileType: String, entryId: String, title: String, relLinksJson: String?, navHistoryJson: String?, opdsUpdated: Long?) -> Unit = { _, _, _, _, _, _, _ -> },
    onOpenBook: (com.example.opdslibrary.data.library.Book) -> Unit = {},
    onOpenInLibrary: (authorId: Long) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val hasNavigableLink = entry.isNavigation() || entry.links.any {
        it.rel == "alternate" || it.rel == "related" || it.type?.contains("atom+xml") == true
    }

    val acquisitionLinks = entry.getAcquisitionLinks()

    // Observe background matching results
    val matchingResults by viewModel.matchingResults.collectAsState()
    val matchResult = matchingResults[entry.id]
    val bookStatus = matchResult?.status

    // Debug: DB link state for this entry (disabled)
    val dbLinkDebugInfo: String? = null

    var localBook by remember { mutableStateOf<com.example.opdslibrary.data.library.Book?>(null) }

    // Check if this entry's feed is cached
    var isCached by remember { mutableStateOf(false) }
    LaunchedEffect(entry.id, baseUrl) {
        isCached = viewModel.isEntryCached(entry, baseUrl)
    }

    // Track if book is being downloaded
    val downloads by AppDownloadManager.downloads.collectAsState()
    val isDownloading = remember(downloads, acquisitionLinks, baseUrl) {
        if (acquisitionLinks.isEmpty()) false
        else {
            val entryUrls = acquisitionLinks.map { link ->
                if (link.href.startsWith("http://") || link.href.startsWith("https://")) {
                    link.href
                } else {
                    viewModel.resolveUrl(baseUrl, link.href)
                }
            }
            AppDownloadManager.isAnyDownloading(entryUrls)
        }
    }

    // Load local book when status indicates it's in library
    LaunchedEffect(bookStatus) {
        if (bookStatus != null && bookStatus != CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY) {
            localBook = viewModel.getLocalBook(entry.id)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (hasNavigableLink) {
                        onClick()
                    }
                },
                onLongClick = {
                    Log.d("EntryCard", "onLongClick: entry='${entry.title}'")
                    onLongClick()
                }
            ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (hasNavigableLink) 4.dp else 2.dp
        )
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                // Thumbnail - use entry image or fallback to catalog icon
                val imageUrl = entry.getThumbnailUrl()?.let { thumbnailUrl ->
                    if (thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://")) {
                        thumbnailUrl
                    } else {
                        viewModel.resolveUrl(baseUrl, thumbnailUrl)
                    }
                } ?: catalogIcon // Use catalog icon if entry has no image

                imageUrl?.let { url ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(url)
                            .crossfade(true)
                            .allowHardware(false)
                            .build(),
                        contentDescription = entry.title,
                        modifier = Modifier
                            .size(48.dp)
                            .padding(end = 12.dp),
                        contentScale = ContentScale.Fit,
                        alpha = 1f
                    )
                }

                // Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Star icon for favorited entries (only show when not browsing favorites)
                        if (isFavorited && !isBrowsingFavorites) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Favorited",
                                modifier = Modifier.size(18.dp).padding(end = 4.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    entry.author?.let { author ->
                        Text(
                            text = author.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Debug: show DB link state + in-memory match status
                    dbLinkDebugInfo?.let { info ->
                        val statusLabel = when (bookStatus) {
                            CatalogViewModel.BookLibraryStatus.CURRENT -> "mem:CURRENT"
                            CatalogViewModel.BookLibraryStatus.OUTDATED -> "mem:OUTDATED"
                            CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY -> "mem:NOT_IN_LIB"
                            null -> "mem:null"
                        }
                        val linked = info.startsWith("DB: id=")
                        Text(
                            text = "$info | $statusLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (linked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        )
                    }

                    entry.summary?.let { summary ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Count badge positioned at top-right corner
            entry.content?.let { contentValue ->
                val count = contentValue.trim().split(" ").firstOrNull()?.toIntOrNull()
                count?.let {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = it.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isCached) {
                                Text(
                                    text = "*",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.offset(y = (-4).dp)
                                )
                            }
                        }
                    }
                }
            }

            // Download/Open buttons positioned at top-right corner
            if (acquisitionLinks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    when {
                        isDownloading -> {
                            // Show downloading indicator
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CustomSpinner(size = 20, strokeWidth = 2f)
                            }
                        }
                        bookStatus == CatalogViewModel.BookLibraryStatus.CURRENT -> {
                            // Show Open button and Open in Library button
                            IconButton(
                                onClick = {
                                    localBook?.let { book ->
                                        onOpenBook(book)
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_book),
                                    contentDescription = "Open",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            // Open in Library button
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val authorId = viewModel.getAuthorIdForBook(entry.id)
                                        if (authorId != null) {
                                            onOpenInLibrary(authorId)
                                        } else {
                                            Log.w("EntryCard", "No author found for book: ${entry.id}")
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Home,
                                    contentDescription = "Open in Library",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        bookStatus == CatalogViewModel.BookLibraryStatus.OUTDATED -> {
                            // Show Open + Re-download + Open in Library buttons
                            IconButton(
                                onClick = {
                                    localBook?.let { book ->
                                        onOpenBook(book)
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_book),
                                    contentDescription = "Open",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    // Cancel matching - user explicitly wants to download
                                    viewModel.cancelMatching(entry.id)

                                    // Download using format priority
                                    coroutineScope.launch {
                                        val prefs = AppPreferences(context)
                                        val formatPriority = prefs.getFormatPriorityOnce()
                                        val bestLink = FormatSelector.selectBestLink(entry.links, formatPriority)
                                        if (bestLink != null) {
                                            val resolvedUrl = if (bestLink.href.startsWith("http://") || bestLink.href.startsWith("https://")) {
                                                bestLink.href
                                            } else {
                                                viewModel.resolveUrl(baseUrl, bestLink.href)
                                            }
                                            val relLinksJson = serializeRelLinks(entry)
                                            val navHistoryJson = viewModel.getCurrentNavigationHistoryJson()
                                            Log.d("BookEntryCard", "Download initiated: entryId=${entry.id}, hasNavHistory=${navHistoryJson != null}, navHistoryLen=${navHistoryJson?.length ?: 0}")
                                            onDownload(resolvedUrl, FormatSelector.getFormatName(bestLink), entry.id, entry.title, relLinksJson, navHistoryJson, try { java.time.Instant.parse(entry.updated).toEpochMilli() } catch (e: Exception) { null })
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_download),
                                    contentDescription = "Re-download (update available)",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            // Open in Library button
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val authorId = viewModel.getAuthorIdForBook(entry.id)
                                        if (authorId != null) {
                                            onOpenInLibrary(authorId)
                                        } else {
                                            Log.w("EntryCard", "No author found for book: ${entry.id}")
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Home,
                                    contentDescription = "Open in Library",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        else -> {
                            // NOT_IN_LIBRARY or null - Show Download button
                            IconButton(
                                onClick = {
                                    // Cancel matching - user explicitly wants to download
                                    viewModel.cancelMatching(entry.id)

                                    // Download using format priority
                                    coroutineScope.launch {
                                        val prefs = AppPreferences(context)
                                        val formatPriority = prefs.getFormatPriorityOnce()
                                        val bestLink = FormatSelector.selectBestLink(entry.links, formatPriority)
                                        if (bestLink != null) {
                                            val resolvedUrl = if (bestLink.href.startsWith("http://") || bestLink.href.startsWith("https://")) {
                                                bestLink.href
                                            } else {
                                                viewModel.resolveUrl(baseUrl, bestLink.href)
                                            }
                                            val relLinksJson = serializeRelLinks(entry)
                                            val navHistoryJson = viewModel.getCurrentNavigationHistoryJson()
                                            Log.d("BookEntryCard", "Download initiated: entryId=${entry.id}, hasNavHistory=${navHistoryJson != null}, navHistoryLen=${navHistoryJson?.length ?: 0}")
                                            onDownload(resolvedUrl, FormatSelector.getFormatName(bestLink), entry.id, entry.title, relLinksJson, navHistoryJson, try { java.time.Instant.parse(entry.updated).toEpochMilli() } catch (e: Exception) { null })
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_download),
                                    contentDescription = "Download",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog for entering catalog URL
 */
@Composable
fun CatalogUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Catalog URL") },
        text = {
            Column {
                Text("Enter the URL of an OPDS catalog:")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    placeholder = { Text("https://example.com/opds") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onConfirm(url) },
                enabled = url.isNotBlank()
            ) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog showing current downloads with progress
 */
@Composable
fun DownloadsDialog(
    downloads: List<DownloadItem>,
    onDismiss: () -> Unit,
    onClearCompleted: () -> Unit,
    onRemoveDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit = {}
) {
    val hasCompleted = downloads.any { it.status == DownloadStatus.COMPLETED }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Downloads",
                        style = MaterialTheme.typography.titleLarge
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (downloads.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No downloads",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Downloads list
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        items(downloads, key = { it.id }) { download ->
                            DownloadItemRow(
                                download = download,
                                onRemove = { onRemoveDownload(download.id) },
                                onRetry = { onRetryDownload(download.id) }
                            )
                            if (download != downloads.last()) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }

                    // Clear completed button
                    if (hasCompleted) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = onClearCompleted,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Clear Completed")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single download item row
 */
@Composable
private fun DownloadItemRow(
    download: DownloadItem,
    onRemove: () -> Unit,
    onRetry: () -> Unit = {}
) {
    val isError = download.status == DownloadStatus.ERROR

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            when (download.status) {
                DownloadStatus.PENDING -> {
                    CustomSpinner(size = 24, strokeWidth = 2f)
                }
                DownloadStatus.DOWNLOADING -> {
                    // Determinate progress
                    CustomSpinner(
                        size = 24,
                        strokeWidth = 2f,
                        progress = download.progress / 100f
                    )
                }
                DownloadStatus.COMPLETED -> {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_download),
                        contentDescription = "Completed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                DownloadStatus.ERROR -> {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and status
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = download.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            when (download.status) {
                DownloadStatus.PENDING -> {
                    Text(
                        text = "Waiting...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DownloadStatus.DOWNLOADING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { download.progress / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${download.progress}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DownloadStatus.COMPLETED -> {
                    Text(
                        text = download.filename ?: "Completed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DownloadStatus.ERROR -> {
                    Text(
                        text = download.error ?: "Download failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Action buttons
        when (download.status) {
            DownloadStatus.COMPLETED -> {
                // Remove button for completed downloads
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            DownloadStatus.ERROR -> {
                // Retry and Delete buttons for failed downloads
                IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Retry",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            else -> {
                // No action buttons for pending/downloading
            }
        }
    }
}

/**
 * Login dialog for HTTP Basic Authentication
 */
@Composable
fun LoginDialog(
    attemptCount: Int,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (username: String, password: String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Authentication Required") },
        text = {
            Column {
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text("This catalog requires authentication.")
                Text(
                    text = "Attempt $attemptCount of 3",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (username.isNotBlank() && password.isNotBlank()) {
                        onConfirm(username, password)
                    }
                },
                enabled = username.isNotBlank() && password.isNotBlank()
            ) {
                Text("Login")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Search dialog with text input and recent searches dropdown
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchDialog(
    recentSearches: List<String>,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onDeleteSearch: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showDropdown by remember { mutableStateOf(false) }

    // Show dropdown when there are recent searches and the field is focused
    val shouldShowDropdown = showDropdown && recentSearches.isNotEmpty()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Title
                Text(
                    text = "Search Catalog",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Search input with dropdown
                Box {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            showDropdown = it.isEmpty() // Show dropdown when empty (to pick from history)
                        },
                        label = { Text("Search query") },
                        placeholder = { Text("Enter search terms...") },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = "Clear"
                                    )
                                }
                            } else if (recentSearches.isNotEmpty()) {
                                IconButton(onClick = { showDropdown = !showDropdown }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_arrow_down),
                                        contentDescription = "Show recent searches",
                                        modifier = Modifier.rotate(if (showDropdown) 180f else 0f)
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDropdown = true },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = {
                                if (searchQuery.isNotBlank()) {
                                    onSearch(searchQuery)
                                }
                            }
                        )
                    )

                    // Dropdown with recent searches
                    DropdownMenu(
                        expanded = shouldShowDropdown,
                        onDismissRequest = { showDropdown = false },
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                    ) {
                        // Header with clear option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Recent searches",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    onClearHistory()
                                    showDropdown = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = "Clear all",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        HorizontalDivider()

                        // Recent search items
                        recentSearches.forEach { query ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = query,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                onClick = {
                                    searchQuery = query
                                    showDropdown = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { onDeleteSearch(query) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Delete",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                onSearch(searchQuery)
                            }
                        },
                        enabled = searchQuery.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Search")
                    }
                }
            }
        }
    }
}

/**
 * Book details page (full screen)
 */
@Composable
fun BookDetailsPage(
    book: OpdsEntry?,
    baseUrl: String,
    catalogIcon: String?,
    viewModel: CatalogViewModel,
    isBrowsingFavorites: Boolean = false,
    bookDownloader: BookDownloader,
    onDownloadComplete: (Uri, String) -> Unit,
    onOpenBook: (com.example.opdslibrary.data.library.Book) -> Unit = {}
) {
    // Handle null book case
    if (book == null) {
        return
    }

    // Debug: Dump book JSON to console
    LaunchedEffect(book) {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val bookJson = gson.toJson(book)
        Log.d("BookDetailsPage", "=== BOOK XML/JSON ===\n$bookJson")
    }

    val acquisitionLinks = book.getAcquisitionLinks()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Observe background matching results
    val matchingResults by viewModel.matchingResults.collectAsState()
    val matchResult = matchingResults[book.id]
    val bookStatus = matchResult?.status

    var localBook by remember { mutableStateOf<com.example.opdslibrary.data.library.Book?>(null) }

    // Track if book is being downloaded
    val downloads by AppDownloadManager.downloads.collectAsState()
    val isDownloading = remember(downloads, acquisitionLinks, baseUrl) {
        if (acquisitionLinks.isEmpty()) false
        else {
            val entryUrls = acquisitionLinks.map { link ->
                if (link.href.startsWith("http://") || link.href.startsWith("https://")) {
                    link.href
                } else {
                    viewModel.resolveUrl(baseUrl, link.href)
                }
            }
            AppDownloadManager.isAnyDownloading(entryUrls)
        }
    }

    // Load local book when status indicates it's in library
    LaunchedEffect(bookStatus) {
        if (bookStatus != null && bookStatus != CatalogViewModel.BookLibraryStatus.NOT_IN_LIBRARY) {
            localBook = viewModel.getLocalBook(book.id)
        }
    }

    // Scrollable content (no header needed - main top bar shows the title)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Cover image
        val imageUrl = book.getThumbnailUrl()?.let { thumbnailUrl ->
            if (thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://")) {
                thumbnailUrl
            } else {
                viewModel.resolveUrl(baseUrl, thumbnailUrl)
            }
        } ?: catalogIcon

        imageUrl?.let { url ->
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .allowHardware(false)
                    .build(),
                contentDescription = book.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Author with download button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            book.author?.let { author ->
                // Use author.uri for the author link
                val authorUri = author.uri

                Text(
                    text = "by ${author.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = if (authorUri != null) TextDecoration.Underline else null,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (authorUri != null) {
                                Modifier.clickable {
                                    val resolvedUrl = if (authorUri.startsWith("http://") || authorUri.startsWith("https://")) {
                                        authorUri
                                    } else {
                                        viewModel.resolveUrl(baseUrl, authorUri)
                                    }
                                    viewModel.navigateToUrl(resolvedUrl)
                                }
                            } else {
                                Modifier
                            }
                        )
                )
            } ?: run {
                // If no author, add spacer to push download button to the right
                Spacer(modifier = Modifier.weight(1f))
            }

            // Download/Open buttons based on library status
            if (acquisitionLinks.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        isDownloading -> {
                            // Show downloading indicator
                            OutlinedButton(
                                onClick = { },
                                enabled = false,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                CustomSpinner(size = 18, strokeWidth = 2f)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Downloading...")
                            }
                        }
                        bookStatus == CatalogViewModel.BookLibraryStatus.CURRENT -> {
                            // Show Open button only
                            Button(
                                onClick = {
                                    localBook?.let { lb ->
                                        onOpenBook(lb)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_book),
                                    contentDescription = "Open",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open")
                            }
                        }
                        bookStatus == CatalogViewModel.BookLibraryStatus.OUTDATED -> {
                            // Show Open + Re-download buttons
                            Button(
                                onClick = {
                                    localBook?.let { lb ->
                                        onOpenBook(lb)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_book),
                                    contentDescription = "Open",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open")
                            }
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        val prefs = AppPreferences(context)
                                        val formatPriority = prefs.getFormatPriorityOnce()
                                        val bestLink = FormatSelector.selectBestLink(book.links, formatPriority)
                                        if (bestLink != null) {
                                            val resolvedUrl = if (bestLink.href.startsWith("http://") || bestLink.href.startsWith("https://")) {
                                                bestLink.href
                                            } else {
                                                viewModel.resolveUrl(baseUrl, bestLink.href)
                                            }

                                            val fileType = FormatSelector.getFormatName(bestLink)
                                            val fallbackFilename = resolvedUrl.substringAfterLast("/").ifEmpty {
                                                "book_${System.currentTimeMillis()}.${fileType.lowercase()}"
                                            }
                                            val alternateUrl = viewModel.convertToAlternateUrl(resolvedUrl)
                                            val catalogId = viewModel.getCatalogId()
                                            val relLinksJson = serializeRelLinks(book)
                                            val navHistoryJson = viewModel.getCurrentNavigationHistoryJson()

                                            Toast.makeText(context, "Starting download...", Toast.LENGTH_SHORT).show()

                                            AppDownloadManager.startDownload(
                                                context = context,
                                                title = book.title,
                                                url = resolvedUrl,
                                                fallbackFilename = fallbackFilename,
                                                alternateUrl = alternateUrl,
                                                opdsEntryId = book.id,
                                                catalogId = catalogId,
                                                opdsRelLinks = relLinksJson,
                                                opdsNavigationHistory = navHistoryJson,
                                                onComplete = { uri, filename ->
                                                    onDownloadComplete(uri, filename)
                                                    try {
                                                        val scanScheduler = LibraryScanScheduler(context)
                                                        val entryUpdated = try { java.time.Instant.parse(book.updated).toEpochMilli() } catch (e: Exception) { null }
                                                        scanScheduler.scheduleProcessFile(uri, book.id, catalogId, relLinksJson, navHistoryJson, entryUpdated)

                                                        // Re-check entry after download
                                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                                            kotlinx.coroutines.delay(2000)
                                                            viewModel.recheckEntryAfterDownload(book.id, entryUpdated)
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("BookDetailsPage", "Failed to schedule library scan", e)
                                                    }
                                                },
                                                onError = { error ->
                                                    Toast.makeText(context, "Download failed: $error", Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_download),
                                    contentDescription = "Update",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Update")
                            }
                        }
                        else -> {
                            // NOT_IN_LIBRARY or null - Show Download button
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val prefs = AppPreferences(context)
                                        val formatPriority = prefs.getFormatPriorityOnce()
                                        val bestLink = FormatSelector.selectBestLink(book.links, formatPriority)
                                        if (bestLink != null) {
                                            val resolvedUrl = if (bestLink.href.startsWith("http://") || bestLink.href.startsWith("https://")) {
                                                bestLink.href
                                            } else {
                                                viewModel.resolveUrl(baseUrl, bestLink.href)
                                            }

                                            val fileType = FormatSelector.getFormatName(bestLink)
                                            val fallbackFilename = resolvedUrl.substringAfterLast("/").ifEmpty {
                                                "book_${System.currentTimeMillis()}.${fileType.lowercase()}"
                                            }
                                            val alternateUrl = viewModel.convertToAlternateUrl(resolvedUrl)
                                            val catalogId = viewModel.getCatalogId()
                                            val relLinksJson = serializeRelLinks(book)
                                            val navHistoryJson = viewModel.getCurrentNavigationHistoryJson()

                                            Toast.makeText(context, "Starting download...", Toast.LENGTH_SHORT).show()

                                            AppDownloadManager.startDownload(
                                                context = context,
                                                title = book.title,
                                                url = resolvedUrl,
                                                fallbackFilename = fallbackFilename,
                                                alternateUrl = alternateUrl,
                                                opdsEntryId = book.id,
                                                catalogId = catalogId,
                                                opdsRelLinks = relLinksJson,
                                                opdsNavigationHistory = navHistoryJson,
                                                onComplete = { uri, filename ->
                                                    onDownloadComplete(uri, filename)
                                                    try {
                                                        val scanScheduler = LibraryScanScheduler(context)
                                                        val entryUpdated = try { java.time.Instant.parse(book.updated).toEpochMilli() } catch (e: Exception) { null }
                                                        scanScheduler.scheduleProcessFile(uri, book.id, catalogId, relLinksJson, navHistoryJson, entryUpdated)

                                                        // Re-check entry after download
                                                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                                            kotlinx.coroutines.delay(2000)
                                                            viewModel.recheckEntryAfterDownload(book.id, entryUpdated)
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("BookDetailsPage", "Failed to schedule library scan", e)
                                                    }
                                                },
                                                onError = { error ->
                                                    Toast.makeText(context, "Download failed: $error", Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_download),
                                    contentDescription = "Download",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Summary/Description
        book.summary?.let { summary ->
            Text(
                text = "Description",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Check if summary is HTML and render accordingly
            if (isHtml(summary)) {
                HtmlText(
                    html = summary,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Content (if different from summary)
        book.content?.let { content ->
            if (content.toIntOrNull() == null && content != book.summary) {
                Text(
                    text = "Additional Info",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Check if content is HTML and render accordingly
                if (isHtml(content)) {
                    HtmlText(
                        html = content,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Publisher
        book.dcPublisher?.let { publisher ->
            DetailRow(label = "Publisher", value = publisher)
        }

        // Published date
        book.published?.let { published ->
            DetailRow(label = "Published", value = published)
        }

        // Issued date
        book.dcIssued?.let { issued ->
            DetailRow(label = "Issued", value = issued)
        }

        // Language
        book.dcLanguage?.let { language ->
            DetailRow(label = "Language", value = language)
        }

        // Tags
        if (book.categories.isNotEmpty()) {
            val tags = book.categories.joinToString(", ") { category ->
                category.label ?: category.term
            }
            DetailRow(label = "Tags", value = tags)
        }

        // Related links (series info, author books, etc.)
        val relatedLinks = book.links.filter { link ->
            link.rel == "related" &&
            link.type?.contains("atom+xml") == true
        }

        if (relatedLinks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Related Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            relatedLinks.forEach { link ->
                RelatedLinkCard(
                    link = link,
                    book = book,
                    baseUrl = baseUrl,
                    viewModel = viewModel,
                    isBrowsingFavorites = isBrowsingFavorites
                )
            }
        }
    }
}

/**
 * Related link card with long-press to add to favorites
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RelatedLinkCard(
    link: OpdsLink,
    book: OpdsEntry,
    baseUrl: String,
    viewModel: CatalogViewModel,
    isBrowsingFavorites: Boolean
) {
    var showFavoritesOverlay by remember { mutableStateOf(false) }

    // Auto-dismiss overlay after 2 seconds
    LaunchedEffect(showFavoritesOverlay) {
        if (showFavoritesOverlay) {
            kotlinx.coroutines.delay(2000)
            showFavoritesOverlay = false
        }
    }

    // Extract link title from href or use default
    val linkTitle = when {
        link.href.contains("/series/", ignoreCase = true) -> "View Series"
        link.title?.isNotEmpty() == true -> link.title
        else -> "View Related Content"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (showFavoritesOverlay) {
                            showFavoritesOverlay = false
                        } else {
                            val resolvedUrl = if (link.href.startsWith("http://") || link.href.startsWith("https://")) {
                                link.href
                            } else {
                                viewModel.resolveUrl(baseUrl, link.href)
                            }
                            viewModel.navigateToUrl(resolvedUrl)
                        }
                    },
                    onLongClick = {
                        if (!isBrowsingFavorites) {
                            showFavoritesOverlay = true
                        }
                    }
                ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = linkTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Navigate",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.rotate(180f)
                )
            }
        }

        // Favorites overlay
        if (showFavoritesOverlay) {
            Surface(
                modifier = Modifier.matchParentSize(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(
                        onClick = {
                            // Create an entry for the related link with author prepended if needed
                            val authorName = book.author?.name
                            val entryTitle = if (authorName != null && linkTitle.isNotEmpty()) {
                                "$authorName: $linkTitle"
                            } else {
                                linkTitle
                            }

                            val relatedEntry = OpdsEntry(
                                id = link.href,
                                title = entryTitle,
                                links = listOf(link),
                                updated = "",
                                content = null,
                                summary = null,
                                author = book.author,  // Inherit author from book
                                categories = emptyList()
                            )
                            viewModel.addToFavorites(relatedEntry)
                            showFavoritesOverlay = false
                        }
                    ) {
                        Text(
                            text = "Add to Favorites",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

/**
 * Helper composable for detail rows
 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Check if string contains HTML tags
 */
private fun isHtml(text: String): Boolean {
    return text.contains("<html", ignoreCase = true) ||
           text.contains("<p>", ignoreCase = true) ||
           text.contains("<br>", ignoreCase = true) ||
           text.contains("<div", ignoreCase = true) ||
           text.contains("<span", ignoreCase = true) ||
           text.contains("<a ", ignoreCase = true) ||
           text.contains("<b>", ignoreCase = true) ||
           text.contains("<i>", ignoreCase = true) ||
           text.contains("<strong>", ignoreCase = true) ||
           text.contains("<em>", ignoreCase = true) ||
           text.contains("</", ignoreCase = false)
}

/**
 * Composable for rendering HTML content
 */
@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurface.hashCode()
    val linkColor = MaterialTheme.colorScheme.primary.hashCode()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                textSize = 14f
                setPadding(0, 0, 0, 0)
            }
        },
        update = { textView ->
            textView.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(html)
            }
        }
    )
}

/**
 * Get the default download folder path (internal Downloads/Books)
 */
fun getDefaultDownloadFolder(context: Context): String {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val booksDir = java.io.File(downloadsDir, "Books")
    if (!booksDir.exists()) {
        booksDir.mkdirs()
        Log.d("Settings", "Created Books folder: ${booksDir.absolutePath}")
    }
    return booksDir.absolutePath
}

/**
 * Download book using BookDownloader with proper SAF support
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
fun downloadBookWithDownloader(
    context: Context,
    bookDownloader: BookDownloader,
    url: String,
    alternateUrl: String?,
    fallbackFilename: String,
    username: String? = null,
    password: String? = null,
    onProgress: (Int) -> Unit,
    onSuccess: (Uri, String, Long) -> Unit,
    onError: (String) -> Unit
) {
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        Log.d("Download", "Starting download with BookDownloader: $url")

        val result = bookDownloader.downloadBook(
            url = url,
            fallbackFilename = fallbackFilename,
            username = username,
            password = password,
            onProgress = { progress ->
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    onProgress(progress)
                }
            }
        )

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            when (result) {
                is DownloadResult.Success -> {
                    Log.d("Download", "Download successful: ${result.fileName}")
                    onSuccess(result.fileUri, result.fileName, result.fileSize)
                }
                is DownloadResult.Error -> {
                    Log.e("Download", "Download failed: ${result.message}")
                    // Try alternate URL if available
                    if (alternateUrl != null) {
                        Log.d("Download", "Trying alternate URL: $alternateUrl")
                        Toast.makeText(context, "Trying alternate URL...", Toast.LENGTH_SHORT).show()

                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val retryResult = bookDownloader.downloadBook(
                                url = alternateUrl,
                                fallbackFilename = fallbackFilename,
                                username = username,
                                password = password,
                                onProgress = { progress ->
                                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                        onProgress(progress)
                                    }
                                }
                            )

                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                when (retryResult) {
                                    is DownloadResult.Success -> {
                                        Log.d("Download", "Alternate download successful: ${retryResult.fileName}")
                                        onSuccess(retryResult.fileUri, retryResult.fileName, retryResult.fileSize)
                                    }
                                    is DownloadResult.Error -> {
                                        Log.e("Download", "Alternate download failed: ${retryResult.message}")
                                        onError(retryResult.message)
                                    }
                                }
                            }
                        }
                    } else {
                        onError(result.message)
                    }
                }
            }
        }
    }
}

/**
 * Extract filename from Content-Disposition header
 * Examples:
 * - attachment; filename="book.fb2.zip"
 * - attachment; filename=book.fb2.zip
 * - attachment; filename*=UTF-8''book.fb2.zip
 */
fun extractFilenameFromContentDisposition(contentDisposition: String?): String? {
    if (contentDisposition == null) return null

    Log.d("Download", "Parsing Content-Disposition: $contentDisposition")

    // Try to extract filename from different formats
    val patterns = listOf(
        // filename*=UTF-8''... (RFC 5987 encoding, preferred)
        Regex("""filename\*\s*=\s*[^']*'[^']*'(.+)"""),
        // filename="..."
        Regex("""filename\s*=\s*"([^"]+)""""),
        // filename=... (without quotes)
        Regex("""filename\s*=\s*([^;\s]+)""")
    )

    for (pattern in patterns) {
        val match = pattern.find(contentDisposition)
        if (match != null) {
            var filename = match.groupValues[1].trim()
            // URL decode if needed
            try {
                filename = java.net.URLDecoder.decode(filename, "UTF-8")
            } catch (e: Exception) {
                Log.w("Download", "Failed to URL decode filename: $filename")
            }
            Log.d("Download", "Extracted filename: $filename")
            return filename
        }
    }

    return null
}

/**
 * Start a download with proper filename from server
 * First makes a HEAD request to get Content-Disposition header,
 * then downloads with the correct filename to the configured folder.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
fun startDownloadWithFilename(
    context: Context,
    url: String,
    fallbackFilename: String,
    fileType: String,
    downloadFolder: String,
    onDownloadStarted: (Long) -> Unit,
    onError: (String) -> Unit
) {
    Log.d("Download", "Starting download with filename detection: URL=$url")

    // Use a coroutine to make the HEAD request
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // Make HEAD request to get Content-Disposition header
            val client = okhttp3.OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val headRequest = okhttp3.Request.Builder()
                .url(url)
                .head()
                .build()

            var actualFilename = fallbackFilename
            try {
                client.newCall(headRequest).execute().use { response ->
                    Log.d("Download", "HEAD response code: ${response.code}")
                    val contentDisposition = response.header("Content-Disposition")
                    Log.d("Download", "Content-Disposition header: $contentDisposition")

                    val extractedFilename = extractFilenameFromContentDisposition(contentDisposition)
                    if (extractedFilename != null) {
                        actualFilename = extractedFilename
                        Log.d("Download", "Using filename from server: $actualFilename")
                    } else {
                        Log.d("Download", "No filename in headers, using fallback: $actualFilename")
                    }
                }
            } catch (e: Exception) {
                Log.w("Download", "HEAD request failed, using fallback filename: ${e.message}")
            }

            // Now start the actual download with the correct filename
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val downloadId = startDownload(context, url, actualFilename, fileType, downloadFolder)
                if (downloadId != -1L) {
                    onDownloadStarted(downloadId)
                } else {
                    onError("Failed to start download")
                }
            }
        } catch (e: Exception) {
            Log.e("Download", "Error in download process", e)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onError(e.message ?: "Download error")
            }
        }
    }
}

/**
 * Start a download and return the download ID
 * Returns -1 if download failed to start
 */
fun startDownload(
    context: Context,
    url: String,
    filename: String,
    fileType: String,
    downloadFolder: String
): Long {
    Log.d("Download", "Starting download: URL=$url, filename=$filename, fileType=$fileType, folder=$downloadFolder")
    try {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Determine the target folder
        val targetFolder = if (downloadFolder.startsWith("content://")) {
            Log.d("Download", "Content URI detected, using default Downloads folder with Books subfolder")
            getDefaultDownloadFolder(context)
        } else {
            val folder = java.io.File(downloadFolder)
            if (!folder.exists()) {
                folder.mkdirs()
                Log.d("Download", "Created folder: $downloadFolder")
            }
            downloadFolder
        }

        // Get the relative path from Downloads directory
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetFolderFile = java.io.File(targetFolder)

        // Calculate subpath relative to Downloads directory
        val subPath = if (targetFolderFile.absolutePath.startsWith(downloadsDir.absolutePath)) {
            val relative = targetFolderFile.absolutePath.removePrefix(downloadsDir.absolutePath).trimStart('/', '\\')
            if (relative.isNotEmpty()) "$relative/$filename" else filename
        } else {
            // If not under Downloads, use Books subfolder
            "Books/$filename"
        }

        Log.d("Download", "Download subPath: $subPath")

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(filename)
            .setDescription("Downloading $fileType")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, subPath)

        val downloadId = downloadManager.enqueue(request)
        Log.d("Download", "Download enqueued: id=$downloadId to Downloads/$subPath")
        return downloadId
    } catch (e: Exception) {
        Log.e("Download", "Download failed to start: ${e.message}", e)
        return -1
    }
}

/**
 * Data class for tracking pending downloads
 */
data class PendingDownloadInfo(
    val primaryUrl: String,
    val alternateUrl: String?,
    val filename: String,
    val fileType: String,
    val downloadFolder: String,
    val isRetry: Boolean = false
)

/**
 * Settings dialog with overlay
 */
@Composable
fun SettingsDialog(
    currentDownloadFolder: String,
    onDismiss: () -> Unit,
    onDownloadFolderChange: (String) -> Unit
) {
    val context = LocalContext.current
    var downloadFolder by remember { mutableStateOf(currentDownloadFolder) }

    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Get persistent permission for the selected folder
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

            // Convert URI to path for display
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            val path = documentFile?.name ?: uri.toString()
            downloadFolder = uri.toString()
            Log.d("Settings", "Selected folder: $path (URI: $uri)")
        }
    }

    // Overlay background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        // Dialog surface
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .clickable(enabled = false) { }, // Prevent clicks from passing through
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Download folder setting
                Text(
                    text = "Download Folder",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = downloadFolder,
                        onValueChange = { downloadFolder = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Folder path") }
                    )

                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Browse")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Save button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onDownloadFolderChange(downloadFolder)
                            onDismiss()
                        }
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
