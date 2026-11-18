package com.example.opdslibrary.utils

/**
 * Pure title-comparison rules used to link local books to OPDS catalog entries.
 *
 * Kept free of Android dependencies so it can be unit-tested on the host.
 */
object TitleMatcher {

    /** Words after which a number or ordinal names a volume ("Книга 2", "Часть вторая", "Book II"). */
    private val VOLUME_KEYWORDS = setOf(
        "книга", "кн", "том", "т", "часть", "ч", "выпуск", "вып",
        "book", "part", "volume", "vol", "episode", "ep"
    )

    /** Ordinal word stems → number; only honoured right after a volume keyword. */
    private val ORDINAL_STEMS = listOf(
        "перв" to 1, "втор" to 2, "трет" to 3, "четв" to 4, "пят" to 5,
        "шест" to 6, "седьм" to 7, "восьм" to 8, "девят" to 9, "десят" to 10,
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10
    )

    /** English cardinals after a volume keyword ("Book One"); matched as whole words. */
    private val CARDINALS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    /** Valid Roman numerals 1..399 (strict form, so words like "civil" or "mix" don't count). */
    private val ROMAN = Regex("^c{0,3}(xc|xl|l?x{0,3})(ix|iv|v?i{0,3})$")

    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{Nd}]+")

    /**
     * Normalize a title for comparison: lowercase, collapse whitespace, drop a leading
     * English article. "a " is intentionally kept: it is a real first word in many
     * non-English titles.
     */
    fun normalize(title: String): String =
        title.lowercase()
            .replace('і', 'i') // Cyrillic і, often used for Roman numerals in FB2 metadata
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("the ")
            .removePrefix("an ")

    /**
     * The volume numbers a title carries: standalone integers ("Ревизор 52", "Книга #3",
     * "2. Название"), Roman numerals ("Дозоры II"), and ordinals after a volume keyword
     * ("Книга вторая"). Two titles from the same series that name different volumes —
     * or where only one of them names a volume — have different marker sets.
     */
    fun volumeMarkers(title: String): Set<Int> {
        // Cyrillic і/І is often used for Roman numerals in FB2 metadata.
        val tokens = title.lowercase()
            .replace('і', 'i')
            .split(TOKEN_SPLIT)
            .filter { it.isNotEmpty() }
        val markers = mutableSetOf<Int>()
        tokens.forEachIndexed { i, token ->
            val afterKeyword = i > 0 && tokens[i - 1] in VOLUME_KEYWORDS
            when {
                token.all { it.isDigit() } -> token.trimStart('0').ifEmpty { "0" }
                    .takeIf { it.length <= 6 }?.toIntOrNull()?.let { markers += it }
                ROMAN.matches(token) && (token.length >= 2 || afterKeyword || i == tokens.lastIndex) ->
                    romanToInt(token)?.let { markers += it }
                afterKeyword && token in CARDINALS -> markers += CARDINALS.getValue(token)
                afterKeyword -> ORDINAL_STEMS.firstOrNull { token.startsWith(it.first) }
                    ?.let { markers += it.second }
            }
        }
        return markers
    }

    /** True when both titles name the same volume (or neither names one). */
    fun sameVolume(title1: String, title2: String): Boolean =
        volumeMarkers(title1) == volumeMarkers(title2)

    /**
     * Whether two normalized titles refer to the same book.
     * @param strictMode requires 90% similarity instead of 80% for the fuzzy fallback.
     */
    fun titlesSimilar(title1: String, title2: String, strictMode: Boolean = false): Boolean {
        if (title1 == title2) return true

        // Different volumes of a series — or a numbered volume vs. an unnumbered one
        // ("Точка бифуркации" vs "Точка бифуркации 2", "Дозоры I" vs "Дозоры II") —
        // are different books no matter how similar the rest of the title is.
        if (!sameVolume(title1, title2)) return false

        // One contains the other ("Book" vs "Book: Subtitle"). The shorter title must be
        // at least 8 characters so short tokens don't match unrelated longer titles.
        val shorter = if (title1.length <= title2.length) title1 else title2
        val longer = if (title1.length <= title2.length) title2 else title1
        if (shorter.length >= 8 && longer.contains(shorter)) return true

        // Fuzzy fallback for typos and minor differences.
        val distance = levenshtein(title1, title2)
        val maxLength = maxOf(title1.length, title2.length)
        val similarity = 1.0 - (distance.toDouble() / maxLength)
        return similarity >= if (strictMode) 0.90 else 0.80
    }

    private fun romanToInt(s: String): Int? {
        if (s.isEmpty()) return null
        val values = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
        var total = 0
        for (i in s.indices) {
            val v = values[s[i]] ?: return null
            val next = if (i + 1 < s.length) values[s[i + 1]] ?: return null else 0
            total += if (v < next) -v else v
        }
        return total
    }

    fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }
}
