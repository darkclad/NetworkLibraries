package com.example.opdslibrary.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.opdslibrary.data.AppDatabase
import com.example.opdslibrary.data.CatalogDao
import com.example.opdslibrary.data.OpdsCatalog
import com.example.opdslibrary.network.OpdsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the Start Screen that manages OPDS catalogs
 */
class StartScreenViewModel(application: Application) : AndroidViewModel(application) {
    private val catalogDao: CatalogDao = AppDatabase.getDatabase(application).catalogDao()
    private val opdsRepository = OpdsRepository(application.applicationContext)

    // Flow of all catalogs
    val catalogs: StateFlow<List<OpdsCatalog>> = catalogDao.getAllCatalogs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error message
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Add a new catalog
     * If customName is null, will attempt to fetch the name from OPDS feed
     */
    fun addCatalog(url: String, customName: String? = null, alternateUrl: String? = null) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                // Check if catalog already exists
                if (catalogDao.catalogExists(url) > 0) {
                    _errorMessage.value = "Catalog with this URL already exists"
                    return@launch
                }

                // Fetch catalog info from OPDS feed (force refresh to get latest info)
                val result = opdsRepository.fetchFeed(url, forceRefresh = true)

                val catalog = if (result.isSuccess) {
                    val feedResult = result.getOrNull()!!
                    val feed = feedResult.feed
                    OpdsCatalog(
                        url = url,
                        customName = customName,
                        opdsName = feed.title,
                        iconUrl = feed.icon,
                        iconUpdated = feed.updated,
                        isDefault = false,
                        alternateUrl = alternateUrl
                    )
                } else {
                    // If we can't fetch the feed, still add the catalog with provided info
                    OpdsCatalog(
                        url = url,
                        customName = customName,
                        opdsName = null,
                        iconUrl = null,
                        iconUpdated = null,
                        isDefault = false,
                        alternateUrl = alternateUrl
                    )
                }

                catalogDao.insert(catalog)
            } catch (e: Exception) {
                _errorMessage.value = "Error adding catalog: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Update an existing catalog
     */
    fun updateCatalog(catalog: OpdsCatalog) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                catalogDao.update(catalog)
            } catch (e: Exception) {
                _errorMessage.value = "Error updating catalog: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Delete a catalog
     */
    fun deleteCatalog(catalog: OpdsCatalog) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                catalogDao.delete(catalog)
            } catch (e: Exception) {
                _errorMessage.value = "Error deleting catalog: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Set a catalog as the default
     */
    fun setDefaultCatalog(catalogId: Long) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                catalogDao.setDefaultCatalog(catalogId)
            } catch (e: Exception) {
                _errorMessage.value = "Error setting default catalog: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Refresh a catalog's info from OPDS feed
     */
    fun refreshCatalogInfo(catalog: OpdsCatalog) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                // Force refresh to get latest catalog info
                val result = opdsRepository.fetchFeed(catalog.url, forceRefresh = true)
                if (result.isSuccess) {
                    val feedResult = result.getOrNull()!!
                    val feed = feedResult.feed
                    val updatedCatalog = catalog.copy(
                        opdsName = feed.title,
                        iconUrl = feed.icon,
                        iconUpdated = feed.updated
                    )
                    catalogDao.update(updatedCatalog)
                } else {
                    _errorMessage.value = "Failed to refresh catalog info"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error refreshing catalog: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }
}
