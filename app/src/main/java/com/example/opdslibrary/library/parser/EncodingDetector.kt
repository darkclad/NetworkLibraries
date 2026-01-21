package com.example.opdslibrary.library.parser

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Detects and fixes encoding issues in FB2 files.
 *
 * Common problem: XML declares UTF-8 but content is actually Windows-1251 or KOI8-R.
 * This results in garbled Cyrillic text like "Ð Ð¾Ð±ÐµÑ‚" instead of "Роберт".
 */
object EncodingDetector {

    private const val TAG = "EncodingDetector"

    // Common Russian encodings to try
    private val RUSSIAN_ENCODINGS = listOf(
        "UTF-8",
        "Windows-1251",
        "KOI8-R",
        "ISO-8859-5",
        "CP866"
    )

    // Cyrillic Unicode ranges
    private val CYRILLIC_RANGE = '\u0410'..'\u044F' // А-я
    private val CYRILLIC_EXTENDED = '\u0401'..'\u0451' // Ё-ё

    // Common garbled patterns when UTF-8 bytes are read as Windows-1251
    // These appear when Cyrillic UTF-8 is misinterpreted
    private val GARBLED_PATTERNS = listOf(
        "Ð", "Ñ", "Ð°", "Ð±", "Ð²", "Ðº", "Ð¾", "Ñ€", "Ñ‚"
    )

    /**
     * Read stream with automatic encoding detection and correction.
     * Returns the content as a properly decoded string and the detected encoding.
     */
    fun readWithEncodingDetection(inputStream: InputStream): Pair<String, String> {
        // Read all bytes first
        val bytes = inputStream.readBytes()

        // Try to detect declared encoding from XML header
        val declaredEncoding = detectDeclaredEncoding(bytes)
        Log.d(TAG, "Declared encoding: $declaredEncoding")

        // First try the declared encoding
        val declaredResult = tryDecode(bytes, declaredEncoding ?: "UTF-8")
        if (declaredResult != null && !isGarbled(declaredResult)) {
            Log.d(TAG, "Using declared encoding: ${declaredEncoding ?: "UTF-8"}")
            return Pair(declaredResult, declaredEncoding ?: "UTF-8")
        }

        // If garbled, try other encodings
        Log.d(TAG, "Declared encoding produced garbled text, trying alternatives...")

        for (encoding in RUSSIAN_ENCODINGS) {
            if (encoding.equals(declaredEncoding, ignoreCase = true)) continue

            val result = tryDecode(bytes, encoding)
            if (result != null && !isGarbled(result) && hasValidCyrillic(result)) {
                Log.d(TAG, "Detected correct encoding: $encoding")
                return Pair(fixXmlDeclaration(result, encoding), encoding)
            }
        }

        // Try double-decode: UTF-8 bytes were saved as Windows-1251
        val doubleDecoded = tryDoubleDecode(bytes)
        if (doubleDecoded != null) {
            Log.d(TAG, "Fixed double-encoding issue")
            return Pair(fixXmlDeclaration(doubleDecoded, "UTF-8"), "UTF-8 (recovered)")
        }

        // Fallback to declared or UTF-8
        Log.w(TAG, "Could not detect correct encoding, using fallback")
        return Pair(declaredResult ?: String(bytes, Charsets.UTF_8), declaredEncoding ?: "UTF-8")
    }

    /**
     * Create an InputStream from bytes with correct encoding
     */
    fun createCorrectedStream(inputStream: InputStream): InputStream {
        val (correctedContent, encoding) = readWithEncodingDetection(inputStream)
        Log.d(TAG, "Creating corrected stream with encoding: $encoding")
        return ByteArrayInputStream(correctedContent.toByteArray(Charsets.UTF_8))
    }

    /**
     * Detect encoding declared in XML header
     */
    private fun detectDeclaredEncoding(bytes: ByteArray): String? {
        // Read first 200 bytes to find XML declaration
        val header = String(bytes.take(200).toByteArray(), Charsets.US_ASCII)

        // Pattern: <?xml ... encoding="..." ?>
        val encodingPattern = Regex("""encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val match = encodingPattern.find(header)

        return match?.groupValues?.get(1)
    }

    /**
     * Try to decode bytes with given encoding
     */
    private fun tryDecode(bytes: ByteArray, encodingName: String): String? {
        return try {
            val charset = Charset.forName(encodingName)
            String(bytes, charset)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode with $encodingName: ${e.message}")
            null
        }
    }

    /**
     * Check if text contains garbled characters (mojibake)
     * These patterns appear when Cyrillic UTF-8 is read as Latin-1 or Windows-1251
     */
    private fun isGarbled(text: String): Boolean {
        // Check for common garbled patterns
        val sampleText = text.take(2000)

        // Count garbled pattern occurrences
        var garbledCount = 0
        for (pattern in GARBLED_PATTERNS) {
            garbledCount += sampleText.split(pattern).size - 1
        }

        // If we see many garbled patterns, text is likely mis-decoded
        if (garbledCount > 10) {
            Log.d(TAG, "Detected $garbledCount garbled patterns")
            return true
        }

        // Check for replacement characters
        if (sampleText.count { it == '\uFFFD' } > 5) {
            Log.d(TAG, "Detected replacement characters")
            return true
        }

        // Check for suspicious high-frequency of certain byte sequences
        // When UTF-8 Cyrillic is read as Windows-1251, we get lots of Ð and Ñ
        val suspiciousRatio = sampleText.count { it == 'Ð' || it == 'Ñ' }.toFloat() / sampleText.length
        if (suspiciousRatio > 0.1) {
            Log.d(TAG, "Suspicious character ratio: $suspiciousRatio")
            return true
        }

        return false
    }

    /**
     * Check if text contains valid Cyrillic characters
     */
    private fun hasValidCyrillic(text: String): Boolean {
        val sampleText = text.take(2000)
        val cyrillicCount = sampleText.count { it in CYRILLIC_RANGE || it in CYRILLIC_EXTENDED }

        // For Russian books, we expect at least some Cyrillic
        return cyrillicCount > 20
    }

    /**
     * Try to fix double-encoding issue:
     * UTF-8 text was incorrectly saved as Windows-1251, then read as UTF-8
     */
    private fun tryDoubleDecode(bytes: ByteArray): String? {
        return try {
            // Decode as Windows-1251 first
            val win1251 = String(bytes, Charset.forName("Windows-1251"))

            // Check if this looks like UTF-8 bytes stored as Windows-1251
            // UTF-8 Cyrillic starts with bytes 0xD0 or 0xD1
            if (win1251.any { it.code == 0xD0 || it.code == 0xD1 }) {
                // Re-encode as Windows-1251 bytes, then decode as UTF-8
                val reEncoded = win1251.toByteArray(Charset.forName("Windows-1251"))
                val utf8Result = String(reEncoded, Charsets.UTF_8)

                if (!isGarbled(utf8Result) && hasValidCyrillic(utf8Result)) {
                    return utf8Result
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fix XML declaration to match actual encoding (UTF-8 after our conversion)
     */
    private fun fixXmlDeclaration(content: String, detectedEncoding: String): String {
        // Replace encoding in XML declaration to UTF-8 since we converted to UTF-8
        val encodingPattern = Regex("""(encoding\s*=\s*["'])[^"']+(["'])""", RegexOption.IGNORE_CASE)

        return if (encodingPattern.containsMatchIn(content)) {
            encodingPattern.replace(content) { match ->
                "${match.groupValues[1]}UTF-8${match.groupValues[2]}"
            }
        } else {
            content
        }
    }

    /**
     * Quick check if content might have encoding issues
     */
    fun mightHaveEncodingIssues(sample: String): Boolean {
        return isGarbled(sample)
    }
}
