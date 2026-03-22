package com.example.opdslibrary.ui.library

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.opdslibrary.data.library.BookWithDetails
import com.example.opdslibrary.data.AppPreferences
import com.example.opdslibrary.ui.CustomSpinner
import com.example.opdslibrary.utils.BookOpener
import com.example.opdslibrary.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Book detail screen showing full metadata
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    viewModel: LibraryViewModel,
    bookId: Long,
    onBack: () -> Unit,
    onNavigateToCatalog: (catalogId: Long, url: String) -> Unit = { _, _ -> },
    onViewInCatalog: (catalogId: Long, navHistoryJson: String) -> Unit = { _, _ -> },
    onShowAuthorBooks: (authorId: Long) -> Unit = {},
    onShowSeriesBooks: (seriesId: Long) -> Unit = {}
) {
    var bookWithDetails by remember { mutableStateOf<BookWithDetails?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Preferred reader app
    val appPreferences = remember { AppPreferences(context) }
    val preferredReaderPackage by appPreferences.preferredReaderPackage.collectAsState(initial = null)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(bookId) {
        bookWithDetails = viewModel.getBookDetails(bookId)
        bookWithDetails?.let { details ->
            val b = details.book
            android.util.Log.d("BookDetailScreen", "=== FULL BOOK INFO ===")
            android.util.Log.d("BookDetailScreen", "id=${b.id}")
            android.util.Log.d("BookDetailScreen", "title=${b.title}")
            android.util.Log.d("BookDetailScreen", "filePath=${b.filePath}")
            android.util.Log.d("BookDetailScreen", "fileHash=${b.fileHash}")
            android.util.Log.d("BookDetailScreen", "fileSize=${b.fileSize}")
            android.util.Log.d("BookDetailScreen", "lang=${b.lang}")
            android.util.Log.d("BookDetailScreen", "year=${b.year}")
            android.util.Log.d("BookDetailScreen", "seriesId=${b.seriesId}, seriesNumber=${b.seriesNumber}")
            android.util.Log.d("BookDetailScreen", "metadataSource=${b.metadataSource}")
            android.util.Log.d("BookDetailScreen", "downloadedViaApp=${b.downloadedViaApp}")
            android.util.Log.d("BookDetailScreen", "catalogId=${b.catalogId}")
            android.util.Log.d("BookDetailScreen", "opdsEntryId=${b.opdsEntryId}")
            android.util.Log.d("BookDetailScreen", "opdsUpdated=${b.opdsUpdated}")
            android.util.Log.d("BookDetailScreen", "opdsRelLinks=${b.opdsRelLinks}")
            android.util.Log.d("BookDetailScreen", "opdsNavigationHistory=${b.opdsNavigationHistory}")
            android.util.Log.d("BookDetailScreen", "authors=${details.authors.map { it.getDisplayName() }}")
            android.util.Log.d("BookDetailScreen", "series=${details.series?.name}")
            android.util.Log.d("BookDetailScreen", "genres=${details.genres.map { it.name }}")
            android.util.Log.d("BookDetailScreen", "=== END BOOK INFO ===")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Book Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, "Delete")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        bookWithDetails?.let { details ->
            val book = details.book
            val authors = details.authors
            val series = details.series
            val genres = details.genres

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Cover and title row
                Row(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Cover
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (book.coverPath != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(File(book.coverPath))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = book.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Title and authors
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (authors.isNotEmpty()) {
                            authors.forEach { author ->
                                Text(
                                    text = author.getDisplayName(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (series != null) {
                            Text(
                                text = buildString {
                                    append(series.name)
                                    book.seriesNumber?.let { num ->
                                        append(" #${num.toInt()}")
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        book.year?.let { year ->
                            Text(
                                text = year.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Open button
                Button(
                    onClick = {
                        BookOpener.openBook(
                            context = context,
                            filePath = book.filePath,
                            preferredPackage = preferredReaderPackage
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Book")
                }

                // Author and series navigation buttons
                if (authors.isNotEmpty() || series != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (authors.size == 1) {
                        OutlinedButton(
                            onClick = { onShowAuthorBooks(authors[0].id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Books by ${authors[0].getDisplayName()}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else if (authors.size > 1) {
                        authors.forEach { author ->
                            OutlinedButton(
                                onClick = { onShowAuthorBooks(author.id) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Books by ${author.getDisplayName()}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (series != null) {
                        OutlinedButton(
                            onClick = { onShowSeriesBooks(series.id) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Series: ${series.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Genres
                if (genres.isNotEmpty()) {
                    Text(
                        text = "Genres",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        genres.take(5).forEach { genre ->
                            SuggestionChip(
                                onClick = { },
                                label = { Text(genre.name) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Description
                book.description?.let { desc ->
                    Text(
                        text = "Description",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // File info
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "File Information",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                DetailRow("Format", getFormatFromPath(book.filePath))
                DetailRow("Size", formatFileSize(book.fileSize))
                DetailRow("Added", formatDate(book.addedAt))
                book.lang?.let { lang ->
                    DetailRow("Language", lang.uppercase())
                }
                book.isbn?.let { isbn ->
                    DetailRow("ISBN", isbn)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // File location section
                Text(
                    text = "File Location",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                // File name with rename option
                val fileName = book.filePath.substringAfterLast("/").substringAfterLast("\\")
                val folderPath = book.filePath.substringBeforeLast("/").substringBeforeLast("\\")

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        // File name row with rename button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "File name",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // Only show rename for regular files, not content:// URIs
                            if (!book.filePath.startsWith("content://")) {
                                IconButton(
                                    onClick = {
                                        newFileName = fileName
                                        showRenameDialog = true
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Rename file",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Folder path
                        Text(
                            text = "Folder",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = folderPath,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Copy path button
                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(book.filePath))
                                Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Full Path")
                        }
                    }
                }

                // View in Catalog button (if book has navigation history)
                val navHistory = book.parseOpdsNavigationHistory()
                android.util.Log.d("BookDetailScreen", "View in Catalog check: catalogId=${book.catalogId}, navHistory.size=${navHistory.size}, opdsNavigationHistory=${book.opdsNavigationHistory?.take(100)}")
                if (navHistory.isNotEmpty() && book.catalogId != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            book.catalogId?.let { catId ->
                                book.opdsNavigationHistory?.let { navHistoryJson ->
                                    onViewInCatalog(catId, navHistoryJson)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View in Catalog")
                    }
                }

                // OPDS Related Links
                val relLinks = book.parseOpdsRelLinks()
                if (relLinks.isNotEmpty() && book.catalogId != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "OPDS Links",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    relLinks.forEach { link ->
                        val linkTitle = link.title ?: getLinkDisplayName(link.rel, link.href)
                        OutlinedButton(
                            onClick = {
                                book.catalogId?.let { catId ->
                                    onNavigateToCatalog(catId, link.href)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(linkTitle)
                        }
                    }
                }
            }
        } ?: run {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CustomSpinner()
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Book") },
            text = { Text("What would you like to do with this book?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBook(bookId, deleteFile = true)
                        showDeleteDialog = false
                        onBack()
                    }
                ) {
                    Text("Delete with File", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            viewModel.deleteBook(bookId, deleteFile = false)
                            showDeleteDialog = false
                            onBack()
                        }
                    ) {
                        Text("Remove from Library")
                    }
                }
            }
        )
    }

    // Rename file dialog
    if (showRenameDialog) {
        val currentBook = bookWithDetails?.book
        val currentPath = currentBook?.filePath ?: ""
        val currentExtension = getFileExtension(currentPath)

        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename File") },
            text = {
                Column {
                    Text(
                        text = "Enter new file name:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("File name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Note: The file extension ($currentExtension) should be preserved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFileName.isNotBlank() && currentBook != null) {
                            val trimmedName = newFileName.trim()
                            // Ensure the extension is preserved
                            val finalName = if (!trimmedName.lowercase().endsWith(currentExtension.lowercase())) {
                                "$trimmedName$currentExtension"
                            } else {
                                trimmedName
                            }

                            viewModel.renameBookFile(
                                bookId = currentBook.id,
                                currentPath = currentPath,
                                newFileName = finalName,
                                onSuccess = {
                                    Toast.makeText(context, "File renamed successfully", Toast.LENGTH_SHORT).show()
                                    // Refresh book details
                                    coroutineScope.launch {
                                        bookWithDetails = viewModel.getBookDetails(bookId)
                                    }
                                },
                                onError = { error ->
                                    Toast.makeText(context, "Rename failed: $error", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                        showRenameDialog = false
                    },
                    enabled = newFileName.isNotBlank()
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}


private fun getFormatFromPath(path: String): String {
    val lower = path.lowercase()
    return when {
        lower.endsWith(".fb2.zip") -> "FB2 (ZIP)"
        lower.endsWith(".fb2") -> "FB2"
        lower.endsWith(".epub") -> "EPUB"
        lower.endsWith(".pdf") -> "PDF"
        lower.endsWith(".mobi") -> "MOBI"
        lower.endsWith(".azw3") -> "AZW3"
        else -> "Unknown"
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

private fun formatDate(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}

private fun getLinkDisplayName(rel: String?, href: String): String {
    // Try to get a meaningful name from rel or href
    return when {
        rel == "related" -> "Related"
        rel == "subsection" -> "Section"
        rel == "alternate" -> "More Info"
        else -> {
            // Extract meaningful part from href
            val path = href.substringAfterLast("/").substringBefore("?")
            if (path.isNotEmpty()) path else "Link"
        }
    }
}

private fun getFileExtension(path: String): String {
    val lower = path.lowercase()
    return when {
        lower.endsWith(".fb2.zip") -> ".fb2.zip"
        else -> {
            val lastDot = path.lastIndexOf('.')
            if (lastDot > 0 && lastDot < path.length - 1) {
                path.substring(lastDot)
            } else {
                ""
            }
        }
    }
}
