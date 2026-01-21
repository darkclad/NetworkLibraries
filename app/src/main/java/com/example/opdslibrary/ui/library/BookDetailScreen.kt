package com.example.opdslibrary.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.opdslibrary.data.library.BookWithDetails
import com.example.opdslibrary.ui.CustomSpinner
import com.example.opdslibrary.viewmodel.LibraryViewModel
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
    onViewInCatalog: (catalogId: Long, navHistoryJson: String) -> Unit = { _, _ -> }
) {
    var bookWithDetails by remember { mutableStateOf<BookWithDetails?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

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
                        try {
                            val uri = Uri.parse(book.filePath)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, getMimeType(book.filePath))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Handle error
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Book")
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
            text = { Text("Are you sure you want to remove this book from the library?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBook(bookId, deleteFile = false)
                        showDeleteDialog = false
                        onBack()
                    }
                ) {
                    Text("Remove from Library")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
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

private fun getMimeType(path: String): String {
    val lower = path.lowercase()
    return when {
        lower.endsWith(".fb2.zip") -> "application/zip"
        lower.endsWith(".fb2") -> "application/x-fictionbook+xml"
        lower.endsWith(".epub") -> "application/epub+zip"
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".mobi") -> "application/x-mobipocket-ebook"
        else -> "application/octet-stream"
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
