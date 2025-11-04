package com.example.opdslibrary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.opdslibrary.R
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.opdslibrary.data.OpdsCatalog
import com.example.opdslibrary.viewmodel.StartScreenViewModel

/**
 * Start screen that displays all OPDS catalogs
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(
    onCatalogSelected: (OpdsCatalog) -> Unit,
    viewModel: StartScreenViewModel = viewModel()
) {
    val catalogs by viewModel.catalogs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<OpdsCatalog?>(null) }
    var showDeleteDialog by remember { mutableStateOf<OpdsCatalog?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network libraries") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Catalog")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (catalogs.isEmpty() && !isLoading) {
                // Empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_library),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No catalogs yet",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Tap + to add your first OPDS catalog",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(catalogs, key = { it.id }) { catalog ->
                        CatalogCard(
                            catalog = catalog,
                            onClick = { onCatalogSelected(catalog) },
                            onEdit = { showEditDialog = catalog },
                            onDelete = { showDeleteDialog = catalog },
                            onSetDefault = { viewModel.setDefaultCatalog(catalog.id) }
                        )
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Error snackbar
        errorMessage?.let { error ->
            LaunchedEffect(error) {
                // Show error message
                kotlinx.coroutines.delay(3000)
                viewModel.clearError()
            }
        }

        // Dialogs
        if (showAddDialog) {
            AddCatalogDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { url, customName ->
                    viewModel.addCatalog(url, customName.ifBlank { null })
                    showAddDialog = false
                }
            )
        }

        showEditDialog?.let { catalog ->
            EditCatalogDialog(
                catalog = catalog,
                onDismiss = { showEditDialog = null },
                onSave = { updatedCatalog ->
                    viewModel.updateCatalog(updatedCatalog)
                    showEditDialog = null
                }
            )
        }

        showDeleteDialog?.let { catalog ->
            DeleteCatalogDialog(
                catalog = catalog,
                onDismiss = { showDeleteDialog = null },
                onConfirm = {
                    viewModel.deleteCatalog(catalog)
                    showDeleteDialog = null
                }
            )
        }
    }
}

/**
 * Card displaying a single catalog
 */
@Composable
fun CatalogCard(
    catalog: OpdsCatalog,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Catalog icon
            if (catalog.iconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(catalog.iconUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Catalog icon",
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_library),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Catalog info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = catalog.getDisplayName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (catalog.isDefault) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "DEFAULT",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = catalog.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // More options button
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (!catalog.isDefault) {
                        DropdownMenuItem(
                            text = { Text("Set as Default") },
                            onClick = {
                                onSetDefault()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Star, contentDescription = null)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            onEdit()
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            onDelete()
                            showMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Dialog for adding a new catalog
 */
@Composable
fun AddCatalogDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, customName: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add OPDS Catalog") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Catalog URL") },
                    placeholder = { Text("http://example.com/opds") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("Custom Name (Optional)") },
                    placeholder = { Text("My Catalog") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "If no custom name is provided, the catalog's name will be fetched from the OPDS feed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(url, customName) },
                enabled = url.isNotBlank()
            ) {
                Text("Add")
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
 * Dialog for editing a catalog
 */
@Composable
fun EditCatalogDialog(
    catalog: OpdsCatalog,
    onDismiss: () -> Unit,
    onSave: (OpdsCatalog) -> Unit
) {
    var url by remember { mutableStateOf(catalog.url) }
    var customName by remember { mutableStateOf(catalog.customName ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Catalog") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Catalog URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("Custom Name (Optional)") },
                    placeholder = { Text(catalog.opdsName ?: "Unnamed") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "OPDS Name: ${catalog.opdsName ?: "Unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        catalog.copy(
                            url = url,
                            customName = customName.ifBlank { null }
                        )
                    )
                },
                enabled = url.isNotBlank()
            ) {
                Text("Save")
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
 * Dialog for confirming catalog deletion
 */
@Composable
fun DeleteCatalogDialog(
    catalog: OpdsCatalog,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Catalog") },
        text = {
            Text("Are you sure you want to delete \"${catalog.getDisplayName()}\"?")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
