package com.example.opdslibrary.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Scan folder configuration - folders to scan for books
 */
@Entity(
    tableName = "scan_folders",
    indices = [
        Index("path", unique = true)
    ]
)
data class ScanFolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val path: String,                  // SAF URI or absolute path
    val displayName: String,           // Human-readable folder name
    val lastScan: Long? = null,        // Timestamp of last scan
    val fileCount: Int = 0,            // Number of files found in last scan
    val enabled: Boolean = true,       // Whether to include in scans
    val addedAt: Long = System.currentTimeMillis()
)
