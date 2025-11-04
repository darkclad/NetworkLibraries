package com.example.opdslibrary.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.text.Html
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.res.painterResource
import com.example.opdslibrary.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import com.example.opdslibrary.viewmodel.CatalogUiState
import com.example.opdslibrary.viewmodel.CatalogViewModel

/**
 * Main catalog screen that displays OPDS feeds
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onBack: () -> Unit
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
    var showUrlDialog by remember { mutableStateOf(false) }
    var showOpenFileDialog by remember { mutableStateOf(false) }
    var downloadedFileUri by remember { mutableStateOf<Uri?>(null) }
    var downloadedFileName by remember { mutableStateOf<String?>(null) }
    var scrollToIndexTrigger by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val catalogIconUrl = remember { mutableStateOf<String?>(null) }

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
                    Log.d("CatalogScreen", "Download completed: $downloadId")

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

                            downloadedFileUri = Uri.parse(uri)
                            downloadedFileName = fileName
                            showOpenFileDialog = true
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
            CatalogTopAppBar(
                catalogTitle = catalogTitle,
                catalogIconUrl = catalogIconUrl.value,
                currentPageTitle = currentPageTitle,
                canNavigateBack = canNavigateBack,
                isNetworkActive = isNetworkActive,
                isFeedFromCache = isCurrentFeedFromCache,
                isBrowsingFavorites = isBrowsingFavorites,
                onCatalogClick = { viewModel.navigateToRoot() },
                onBackClick = { viewModel.navigateBack() },
                onBackToListClick = onBack,
                onRefreshClick = { viewModel.refresh() },
                onAddClick = { showUrlDialog = true }
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
                            viewModel = viewModel
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
                        onDownload = { url, fileType ->
                            Log.d("CatalogScreen", "Download requested: $url ($fileType)")

                            try {
                                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

                                // Extract filename from URL or use a default
                                val filename = url.substringAfterLast("/").ifEmpty {
                                    "book_${System.currentTimeMillis()}.${fileType.lowercase()}"
                                }

                                val request = DownloadManager.Request(Uri.parse(url))
                                    .setTitle(filename)
                                    .setDescription("Downloading $fileType file")
                                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                                    .setAllowedOverMetered(true)
                                    .setAllowedOverRoaming(true)

                                downloadManager.enqueue(request)

                                Toast.makeText(context, "Downloading $filename", Toast.LENGTH_SHORT).show()
                                Log.d("CatalogScreen", "Download started: $filename")
                            } catch (e: Exception) {
                                Log.e("CatalogScreen", "Download failed", e)
                                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                        }
                    }
                }
                is CatalogUiState.Error -> {
                    ErrorScreen(
                        message = state.message,
                        onRetry = { viewModel.retry() }
                    )
                }
            }
        }
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

    // Open downloaded file dialog
    if (showOpenFileDialog && downloadedFileUri != null && downloadedFileName != null) {
        AlertDialog(
            onDismissRequest = {
                showOpenFileDialog = false
                downloadedFileUri = null
                downloadedFileName = null
            },
            title = { Text("Download Complete") },
            text = {
                Text("$downloadedFileName has been downloaded. Would you like to open it?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(downloadedFileUri, getMimeType(downloadedFileName!!))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            Log.d("CatalogScreen", "Opening file: $downloadedFileName")
                        } catch (e: Exception) {
                            Log.e("CatalogScreen", "Failed to open file", e)
                            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                        }
                        showOpenFileDialog = false
                        downloadedFileUri = null
                        downloadedFileName = null
                    }
                ) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showOpenFileDialog = false
                        downloadedFileUri = null
                        downloadedFileName = null
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }
}

/**
 * Get MIME type from filename
 */
private fun getMimeType(filename: String): String {
    return when {
        filename.endsWith(".epub", ignoreCase = true) -> "application/epub+zip"
        filename.endsWith(".fb2", ignoreCase = true) -> "application/fb2"
        filename.endsWith(".fb2.zip", ignoreCase = true) -> "application/zip"
        filename.endsWith(".zip", ignoreCase = true) -> "application/zip"
        filename.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
        filename.endsWith(".mobi", ignoreCase = true) -> "application/x-mobipocket-ebook"
        filename.endsWith(".azw3", ignoreCase = true) -> "application/vnd.amazon.ebook"
        filename.endsWith(".djvu", ignoreCase = true) -> "image/vnd.djvu"
        else -> "*/*"
    }
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
    onCatalogClick: () -> Unit,
    onBackClick: () -> Unit,
    onBackToListClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onAddClick: () -> Unit
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
                // Network activity indicator
                if (isNetworkActive) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Refresh button - shows different color when feed is from cache
                IconButton(onClick = onRefreshClick) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = if (isFeedFromCache) "Cached - tap to refresh from network" else "Refresh catalog",
                        tint = if (isFeedFromCache) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onAddClick) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Open Catalog"
                    )
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
 * Loading screen
 */
@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Error screen
 */
@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
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
        Button(onClick = onRetry) {
            Text("Retry")
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
 * Feed content display with infinite scrolling
 */
@Composable
fun FeedContent(
    feed: OpdsFeed,
    baseUrl: String,
    catalogIcon: String?,
    viewModel: CatalogViewModel,
    scrollToIndex: Int = -1,
    scrollTrigger: Int = 0,
    onEntryClick: (OpdsEntry, Int) -> Unit,
    onDownload: (String, String) -> Unit
) {
    val listState = rememberLazyListState()
    val uiState by viewModel.uiState.collectAsState()
    val isBrowsingFavorites by viewModel.isBrowsingFavorites.collectAsState()
    val coroutineScope = rememberCoroutineScope()

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

    // Detect when user scrolls near the bottom
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .distinctUntilChanged()
            .collect { layoutInfo ->
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0

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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(feed.entries) { index, entry ->
            EntryCard(
                entry = entry,
                baseUrl = baseUrl,
                catalogIcon = catalogIcon,
                viewModel = viewModel,
                isBrowsingFavorites = isBrowsingFavorites,
                onClick = { onEntryClick(entry, index) },
                onDownload = onDownload
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
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * Entry card component
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EntryCard(
    entry: OpdsEntry,
    baseUrl: String,
    catalogIcon: String?,
    viewModel: CatalogViewModel,
    isBrowsingFavorites: Boolean = false,
    onClick: () -> Unit,
    onDownload: (String, String) -> Unit = { _, _ -> }
) {
    val hasNavigableLink = entry.isNavigation() || entry.links.any {
        it.rel == "alternate" || it.rel == "related" || it.type?.contains("atom+xml") == true
    }

    val acquisitionLinks = entry.getAcquisitionLinks()
    var showDownloadMenu by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    // Check if this is a subcategory (synthetic entry) in favorites mode
    val isFavoritesSubcategory = isBrowsingFavorites && entry.links.any {
        it.href.startsWith("internal://favorites")
    }

    // Check if this is the Favorites entry itself
    val isFavoritesEntry = entry.id == "favorites_root"

    // Debug: Log immediately when card is composed
    if (entry.links.isNotEmpty()) {
        Log.d("EntryCard_DEBUG", "=== CARD RENDERED: '${entry.title}' ===")
        Log.d("EntryCard_DEBUG", "Total links: ${entry.links.size}")
        entry.links.forEach { link ->
            Log.d("EntryCard_DEBUG", "  type=${link.type}, rel=${link.rel}, href=${link.href}")
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (hasNavigableLink) {
                            onClick()
                        }
                    },
                    onLongPress = { offset ->
                        contextMenuOffset = offset
                        showContextMenu = true
                    }
                )
            },
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
                Log.d("EntryCard", "Image URL: $url (catalog fallback=${entry.getThumbnailUrl() == null})")

                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(url)
                        .crossfade(true)
                        .allowHardware(false) // Disable hardware bitmaps to support alpha channel
                        .build(),
                    contentDescription = entry.title,
                    modifier = Modifier
                        .size(48.dp) // Standardized icon size
                        .padding(end = 12.dp),
                    contentScale = ContentScale.Fit,
                    alpha = 1f // Ensure full alpha support
                )
            }

            // Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically)
            ) {
                // Title
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                entry.author?.let { author ->
                    Text(
                        text = author.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                // Extract number from content (e.g., "4 новые книги" -> 4)
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
                        Text(
                            text = it.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

        // Download button positioned at top-right corner
        if (acquisitionLinks.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                IconButton(
                    onClick = { showDownloadMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_download),
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Dropdown menu for file formats
                DropdownMenu(
                    expanded = showDownloadMenu,
                    onDismissRequest = { showDownloadMenu = false }
                ) {
                    // Sort links to prioritize fb2+zip and fb2
                    val sortedLinks = acquisitionLinks.sortedByDescending { link ->
                        when {
                            link.type?.contains("fb2+zip", ignoreCase = true) == true -> 5
                            link.type?.contains("fb2.zip", ignoreCase = true) == true -> 5
                            link.type?.contains("fb2", ignoreCase = true) == true -> 4
                            link.type?.contains("epub+zip", ignoreCase = true) == true -> 3
                            link.type?.contains("epub", ignoreCase = true) == true -> 2
                            link.type?.contains("pdf", ignoreCase = true) == true -> 1
                            else -> 0
                        }
                    }

                    sortedLinks.forEach { link ->
                        DropdownMenuItem(
                            text = { Text(link.getFriendlyType()) },
                            onClick = {
                                showDownloadMenu = false
                                val resolvedUrl = if (link.href.startsWith("http://") || link.href.startsWith("https://")) {
                                    link.href
                                } else {
                                    viewModel.resolveUrl(baseUrl, link.href)
                                }
                                onDownload(resolvedUrl, link.getFriendlyType())
                            }
                        )
                    }
                }
            }
        }
    }
    }

    // Context menu for favorites
    DropdownMenu(
        expanded = showContextMenu,
        onDismissRequest = { showContextMenu = false },
        offset = with(density) {
            DpOffset(
                x = contextMenuOffset.x.toDp(),
                y = contextMenuOffset.y.toDp()
            )
        }
    ) {
        if (isFavoritesEntry) {
            // Show "Clear" option for the Favorites entry itself
            DropdownMenuItem(
                text = { Text("Clear") },
                onClick = {
                    showContextMenu = false
                    showClearConfirmation = true
                }
            )
        } else if (isBrowsingFavorites && !isFavoritesSubcategory) {
            // Show "Remove from Favorites" when browsing favorites and entry is not a subcategory
            DropdownMenuItem(
                text = { Text("Remove from Favorites") },
                onClick = {
                    viewModel.removeFavoriteByEntry(entry)
                    showContextMenu = false
                }
            )
        } else if (!isFavoritesSubcategory) {
            // Show "Add to Favorites" for regular entries (not favorites subcategories)
            DropdownMenuItem(
                text = { Text("Add to Favorites") },
                onClick = {
                    viewModel.addToFavorites(entry)
                    showContextMenu = false
                }
            )
        }
    }

    // Confirmation dialog for clearing all favorites
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear All Favorites") },
            text = { Text("Are you sure you want to remove all favorites from this catalog? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllFavorites()
                        showClearConfirmation = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmation = false }
                ) {
                    Text("Cancel")
                }
            }
        )
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
 * Book details page (full screen)
 */
@Composable
fun BookDetailsPage(
    book: OpdsEntry?,
    baseUrl: String,
    catalogIcon: String?,
    viewModel: CatalogViewModel
) {
    // Handle null book case
    if (book == null) {
        return
    }

    val acquisitionLinks = book.getAcquisitionLinks()
    var showDownloadMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
                // Find author link
                val authorLink = book.links.find { link ->
                    link.rel == "related" &&
                    link.href.contains("/author/", ignoreCase = true)
                }

                Text(
                    text = "by ${author.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = if (authorLink != null) TextDecoration.Underline else null,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (authorLink != null) {
                                Modifier.clickable {
                                    val resolvedUrl = if (authorLink.href.startsWith("http://") || authorLink.href.startsWith("https://")) {
                                        authorLink.href
                                    } else {
                                        viewModel.resolveUrl(baseUrl, authorLink.href)
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

            // Download button with dropdown menu
            if (acquisitionLinks.isNotEmpty()) {
                Box {
                    Button(
                        onClick = { showDownloadMenu = true },
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

                    // Dropdown menu for file formats
                    DropdownMenu(
                        expanded = showDownloadMenu,
                        onDismissRequest = { showDownloadMenu = false }
                    ) {
                        // Sort links to prioritize fb2+zip and fb2
                        val sortedLinks = acquisitionLinks.sortedByDescending { link ->
                            when {
                                link.type?.contains("fb2+zip", ignoreCase = true) == true -> 5
                                link.type?.contains("fb2.zip", ignoreCase = true) == true -> 5
                                link.type?.contains("fb2", ignoreCase = true) == true -> 4
                                link.type?.contains("epub+zip", ignoreCase = true) == true -> 3
                                link.type?.contains("epub", ignoreCase = true) == true -> 2
                                link.type?.contains("pdf", ignoreCase = true) == true -> 1
                                else -> 0
                            }
                        }

                        sortedLinks.forEach { link ->
                            DropdownMenuItem(
                                text = { Text(link.getFriendlyType()) },
                                onClick = {
                                    showDownloadMenu = false
                                    val resolvedUrl = if (link.href.startsWith("http://") || link.href.startsWith("https://")) {
                                        link.href
                                    } else {
                                        viewModel.resolveUrl(baseUrl, link.href)
                                    }

                                    // Handle download inline
                                    try {
                                        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        val fileType = link.getFriendlyType()
                                        val filename = resolvedUrl.substringAfterLast("/").ifEmpty {
                                            "book_${System.currentTimeMillis()}.${fileType.lowercase()}"
                                        }

                                        val request = DownloadManager.Request(Uri.parse(resolvedUrl))
                                            .setTitle(filename)
                                            .setDescription("Downloading $fileType file")
                                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                                            .setAllowedOverMetered(true)
                                            .setAllowedOverRoaming(true)

                                        downloadManager.enqueue(request)

                                        Toast.makeText(context, "Downloading $filename", Toast.LENGTH_SHORT).show()
                                        Log.d("BookDetailsPage", "Download started: $filename")
                                    } catch (e: Exception) {
                                        Log.e("BookDetailsPage", "Download failed", e)
                                        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_download),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
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

        // Related links (series info, etc.) - exclude author links as they're in the header
        val relatedLinks = book.links.filter { link ->
            link.rel == "related" &&
            link.type?.contains("atom+xml") == true &&
            !link.href.contains("/author/", ignoreCase = true)
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
                // Extract link title from href or use default
                val linkTitle = when {
                    link.href.contains("/series/", ignoreCase = true) -> "View Series"
                    link.title?.isNotEmpty() == true -> link.title
                    else -> "View Related Content"
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            val resolvedUrl = if (link.href.startsWith("http://") || link.href.startsWith("https://")) {
                                link.href
                            } else {
                                viewModel.resolveUrl(baseUrl, link.href)
                            }
                            viewModel.navigateToUrl(resolvedUrl)
                        },
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
