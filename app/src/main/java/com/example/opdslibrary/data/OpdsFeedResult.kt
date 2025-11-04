package com.example.opdslibrary.data

/**
 * Result wrapper for OPDS feed that includes cache information
 */
data class OpdsFeedResult(
    val feed: OpdsFeed,
    val fromCache: Boolean
)
