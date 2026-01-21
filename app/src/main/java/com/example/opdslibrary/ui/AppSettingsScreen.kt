package com.example.opdslibrary.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.example.opdslibrary.viewmodel.AppSettingsViewModel

/**
 * Application Settings screen for managing app-wide settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel,
    onBack: () -> Unit
) {
    val downloadFolderName by viewModel.downloadFolderName.collectAsState()
    val parallelWorkers by viewModel.parallelWorkers.collectAsState()
    val maxParallelWorkers = viewModel.maxParallelWorkers
    val formatPriority by viewModel.formatPriority.collectAsState()
    val imageCacheSize by viewModel.imageCacheSize.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val context = LocalContext.current

    var showDownloadFolderPickerDialog by remember { mutableStateOf(false) }
    var showFormatPriorityDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // Folder picker launcher for download folder
    val downloadFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistent permission
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)

            // Get display name
            val documentFile = DocumentFile.fromTreeUri(context, it)
            val displayName = documentFile?.name ?: "Unknown Folder"

            viewModel.setDownloadFolder(it, displayName)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Downloads section
            item {
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Download folder setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showDownloadFolderPickerDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Download Folder",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = downloadFolderName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Where OPDS downloads are saved",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Change folder",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Format priority setting
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showFormatPriorityDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Format Priority",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatPriority.take(3).joinToString(", ") + if (formatPriority.size > 3) "..." else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Preferred format order for downloads",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit priority",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Performance section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Performance",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Parallel workers slider
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Parallel Workers",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "$parallelWorkers",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = "Number of files to process simultaneously during library scan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = parallelWorkers.toFloat(),
                            onValueChange = { viewModel.setParallelWorkers(it.toInt()) },
                            valueRange = 1f..maxParallelWorkers.toFloat(),
                            steps = maxParallelWorkers - 2,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "1",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$maxParallelWorkers (max)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Cache section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Cache",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Image cache
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Image Cache",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatCacheSize(imageCacheSize),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Cached catalog icons and book covers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { showClearCacheDialog = true },
                            enabled = imageCacheSize > 0
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }
    }

    // Storage location picker dialog for download folder
    if (showDownloadFolderPickerDialog) {
        StorageLocationPickerDialog(
            onDismiss = { showDownloadFolderPickerDialog = false },
            onLocationSelected = { uri ->
                showDownloadFolderPickerDialog = false
                downloadFolderPickerLauncher.launch(uri)
            }
        )
    }

    // Format priority dialog
    if (showFormatPriorityDialog) {
        FormatPriorityDialog(
            currentPriority = formatPriority,
            onDismiss = { showFormatPriorityDialog = false },
            onSave = { newPriority ->
                viewModel.setFormatPriority(newPriority)
                showFormatPriorityDialog = false
            },
            onReset = {
                viewModel.resetFormatPriority()
                showFormatPriorityDialog = false
            }
        )
    }

    // Clear cache confirmation dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Image Cache") },
            text = {
                Text("This will delete all cached catalog icons and book covers. They will be re-downloaded when needed.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearImageCache()
                        showClearCacheDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Error snackbar
    errorMessage?.let { error ->
        LaunchedEffect(error) {
            viewModel.clearError()
        }
    }
}

/**
 * Dialog that shows available storage locations to start browsing from
 */
@Composable
private fun StorageLocationPickerDialog(
    onDismiss: () -> Unit,
    onLocationSelected: (Uri?) -> Unit
) {
    val context = LocalContext.current

    // Get available storage locations
    val storageLocations = remember {
        buildList {
            // Internal storage common folders
            add(StorageLocation(
                name = "Downloads",
                path = "primary:Download",
                icon = Icons.Default.KeyboardArrowDown
            ))
            add(StorageLocation(
                name = "Documents",
                path = "primary:Documents",
                icon = Icons.Default.Create
            ))
            add(StorageLocation(
                name = "Internal Storage",
                path = "primary:",
                icon = Icons.Default.Home
            ))

            // Check for external SD card
            val externalDirs = context.getExternalFilesDirs(null)
            externalDirs.forEachIndexed { index, file ->
                if (file != null && index > 0) {
                    // This is likely an SD card
                    val path = file.absolutePath
                    // Extract the SD card ID from path like /storage/XXXX-XXXX/Android/...
                    val sdCardId = path.split("/").getOrNull(2)
                    if (sdCardId != null && sdCardId.contains("-")) {
                        add(StorageLocation(
                            name = "SD Card",
                            path = "$sdCardId:",
                            icon = Icons.Default.Info
                        ))
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Starting Location") },
        text = {
            Column {
                Text(
                    "Choose where to start browsing:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                storageLocations.forEach { location ->
                    ListItem(
                        headlineContent = { Text(location.name) },
                        leadingContent = {
                            Icon(location.icon, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            val uri = Uri.Builder()
                                .scheme("content")
                                .authority("com.android.externalstorage.documents")
                                .appendPath("document")
                                .appendPath(location.path)
                                .build()
                            onLocationSelected(uri)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private data class StorageLocation(
    val name: String,
    val path: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * Dialog for editing format priority order
 */
@Composable
private fun FormatPriorityDialog(
    currentPriority: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onReset: () -> Unit
) {
    var formats by remember { mutableStateOf(currentPriority.toMutableList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Format Priority") },
        text = {
            Column {
                Text(
                    "Use arrows to reorder. Formats at the top are preferred when downloading.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    itemsIndexed(formats, key = { _, item -> item }) { index, format ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "${index + 1}.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = format.uppercase(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Row {
                                    // Move up button
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                formats = formats.toMutableList().apply {
                                                    add(index - 1, removeAt(index))
                                                }
                                            }
                                        },
                                        enabled = index > 0
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowUp,
                                            contentDescription = "Move up",
                                            tint = if (index > 0)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        )
                                    }
                                    // Move down button
                                    IconButton(
                                        onClick = {
                                            if (index < formats.size - 1) {
                                                formats = formats.toMutableList().apply {
                                                    add(index + 1, removeAt(index))
                                                }
                                            }
                                        },
                                        enabled = index < formats.size - 1
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Move down",
                                            tint = if (index < formats.size - 1)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(formats) }) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) {
                    Text("Reset")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

private fun formatCacheSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / 1024 / 1024} MB"
    }
}
