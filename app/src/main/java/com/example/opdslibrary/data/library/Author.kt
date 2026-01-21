package com.example.opdslibrary.data.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Author entity for book authors
 */
@Entity(
    tableName = "authors",
    indices = [
        Index("sortName")
    ]
)
data class Author(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val nickname: String? = null,      // For single-name authors or pen names

    val sortName: String               // "LastName, FirstName" for sorting
) {
    companion object {
        /**
         * Create Author from full name string
         */
        fun fromFullName(fullName: String): Author {
            val parts = fullName.trim().split("\\s+".toRegex())
            return when (parts.size) {
                0 -> Author(nickname = "Unknown", sortName = "Unknown")
                1 -> Author(nickname = parts[0], sortName = parts[0])
                2 -> Author(
                    firstName = parts[0],
                    lastName = parts[1],
                    sortName = "${parts[1]}, ${parts[0]}"
                )
                else -> Author(
                    firstName = parts[0],
                    middleName = parts.subList(1, parts.size - 1).joinToString(" "),
                    lastName = parts.last(),
                    sortName = "${parts.last()}, ${parts[0]}"
                )
            }
        }

        /**
         * Create sortName from name parts
         */
        fun createSortName(firstName: String?, middleName: String?, lastName: String?, nickname: String?): String {
            return when {
                lastName != null && firstName != null -> "$lastName, $firstName"
                lastName != null -> lastName
                nickname != null -> nickname
                firstName != null -> firstName
                else -> "Unknown"
            }
        }
    }

    /**
     * Get display name for UI (FirstName LastName format)
     */
    fun getDisplayName(): String {
        return when {
            nickname != null && firstName == null && lastName == null -> nickname
            firstName != null && lastName != null -> {
                if (middleName != null) {
                    "$firstName $middleName $lastName"
                } else {
                    "$firstName $lastName"
                }
            }
            lastName != null -> lastName
            firstName != null -> firstName
            nickname != null -> nickname
            else -> "Unknown Author"
        }
    }

    /**
     * Get display name in LastName FirstName format (for author listings)
     */
    fun getDisplayNameLastFirst(): String {
        return when {
            nickname != null && firstName == null && lastName == null -> nickname
            firstName != null && lastName != null -> {
                if (middleName != null) {
                    "$lastName $firstName $middleName"
                } else {
                    "$lastName $firstName"
                }
            }
            lastName != null -> lastName
            firstName != null -> firstName
            nickname != null -> nickname
            else -> "Unknown Author"
        }
    }

    /**
     * Get folder name for file organization
     */
    fun getFolderName(): String {
        return when {
            nickname != null && lastName == null -> nickname
            lastName != null && firstName != null -> "$lastName $firstName"
            lastName != null -> lastName
            nickname != null -> nickname
            else -> "Unknown Author"
        }
    }

    /**
     * Get first letter for grouping (Cyrillic or Latin)
     */
    fun getFirstLetter(): String {
        val firstChar = sortName.firstOrNull()?.uppercaseChar() ?: 'U'
        return when {
            firstChar in 'А'..'Я' || firstChar == 'Ё' -> firstChar.toString()
            firstChar in 'A'..'Z' -> firstChar.toString()
            firstChar.isDigit() -> "0-9"
            else -> "#"
        }
    }
}
