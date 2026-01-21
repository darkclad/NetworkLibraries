package com.example.opdslibrary.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.opdslibrary.data.library.ScanFolder
import com.example.opdslibrary.ui.CustomSpinner
import com.example.opdslibrary.viewmodel.LibrarySettingsViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Library Settings screen for managing scan folders
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySettingsScreen(
    viewModel: LibrarySettingsViewModel,
    onBack: () -> Unit
) {
    val scanFolders by viewModel.scanFolders.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val context = LocalContext.current

    // Folder picker launcher for scan folders
    val folderPickerLauncher = rememberLauncherForActivityResult(
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

            viewModel.addScanFolder(it, displayName)
        }
    }

    var showDeleteDialog by remember { mutableStateOf<ScanFolder?>(null) }
    var showClearLibraryDialog by remember { mutableStateOf(false) }
    var showStoragePickerDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library Settings") },
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
            // Scan folders section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scan Folders",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(onClick = { showStoragePickerDialog = true }) {
                        Icon(Icons.Default.Add, "Add Folder")
                    }
                }
            }

            if (scanFolders.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No folders added",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Add folders to scan for books",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = { showStoragePickerDialog = true }) {
                                Icon(Icons.Default.Add, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Add Folder")
                            }
                        }
                    }
                }
            } else {
                items(scanFolders, key = { it.id }) { folder ->
                    ScanFolderCard(
                        folder = folder,
                        onToggle = { viewModel.toggleFolderEnabled(folder) },
                        onScan = { viewModel.scanFolder(folder) },
                        onDelete = { showDeleteDialog = folder }
                    )
                }
            }

            // Scan actions
            if (scanFolders.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Scan Actions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { viewModel.startScan(fullScan = false) },
                            modifier = Modifier.weight(1f),
                            enabled = !isScanning
                        ) {
                            if (isScanning) {
                                CustomSpinner(size = 16, strokeWidth = 2f)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Quick Scan")
                        }

                        OutlinedButton(
                            onClick = { viewModel.startScan(fullScan = true) },
                            modifier = Modifier.weight(1f),
                            enabled = !isScanning
                        ) {
                            Text("Full Scan")
                        }
                    }
                }

                if (isScanning || scanProgress.isScanning) {
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        // Show folder name if available
                                        scanProgress.folderName?.let { folderName ->
                                            Text(
                                                text = folderName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        // Show progress counter
                                        if (scanProgress.totalCount > 0) {
                                            Text(
                                                text = "Scanning: ${scanProgress.processedCount} / ${scanProgress.totalCount}",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CustomSpinner(size = 16, strokeWidth = 2f)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Counting files...",
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }

                                        // Show current file (truncated)
                                        scanProgress.currentFile?.let { fileName ->
                                            Text(
                                                text = fileName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    TextButton(onClick = { viewModel.cancelScans() }) {
                                        Text("Cancel")
                                    }
                                }

                                // Progress bar
                                if (scanProgress.totalCount > 0) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { scanProgress.progressPercent },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Danger zone
            item {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Danger Zone",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }

            item {
                OutlinedButton(
                    onClick = { showClearLibraryDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Library Database")
                }
            }
        }
    }

    // Delete folder confirmation
    showDeleteDialog?.let { folder ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Remove Folder") },
            text = {
                Text("Remove \"${folder.displayName}\" from scan folders? Books from this folder will remain in the library.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeScanFolder(folder)
                    showDeleteDialog = null
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear library confirmation
    if (showClearLibraryDialog) {
        AlertDialog(
            onDismissRequest = { showClearLibraryDialog = false },
            title = { Text("Clear Library Database") },
            text = {
                Column {
                    Text(
                        "This will permanently remove all books from the library database.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• All book metadata will be deleted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• Your book files will NOT be deleted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• Scan folders will be preserved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Are you sure you want to continue?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLibrary()
                        showClearLibraryDialog = false
                    }
                ) {
                    Text("Clear Library", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLibraryDialog = false }) {
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

    // Storage location picker dialog for scan folders
    if (showStoragePickerDialog) {
        StorageLocationPickerDialog(
            onDismiss = { showStoragePickerDialog = false },
            onLocationSelected = { uri ->
                showStoragePickerDialog = false
                folderPickerLauncher.launch(uri)
            }
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanFolderCard(
    folder: ScanFolder,
    onToggle: () -> Unit,
    onScan: () -> Unit,
    onDelete: () -> Unit
) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )

                    if (folder.lastScan != null) {
                        Text(
                            text = "Last scan: ${formatDate(folder.lastScan)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (folder.fileCount > 0) {
                        Text(
                            text = "${folder.fileCount} books found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = folder.enabled,
                    onCheckedChange = { onToggle() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan")
                }

                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    return dateFormat.format(Date(timestamp))
}
