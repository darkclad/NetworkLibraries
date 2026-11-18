package com.example.opdslibrary

import com.example.opdslibrary.utils.TitleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMatcherTest {

    private fun similar(a: String, b: String, strict: Boolean = false) =
        TitleMatcher.titlesSimilar(TitleMatcher.normalize(a), TitleMatcher.normalize(b), strict)

    // ── Different volumes of a series must never match ──────────────────────

    @Test fun unnumberedFirstBookDoesNotMatchNumberedSequel() {
        assertFalse(similar("Точка бифуркации", "Точка бифуркации 2"))
        assertFalse(similar("Точка бифуркации 2", "Точка бифуркации"))
    }

    @Test fun romanNumeralsDiffer() {
        assertFalse(similar("Дозоры I", "Дозоры II"))
        assertFalse(similar("Дозоры II", "Дозоры III"))
        assertFalse(similar("Дозоры", "Дозоры II"))
    }

    @Test fun cyrillicIUsedAsRomanNumeral() {
        assertFalse(similar("Дозоры І", "Дозоры ІІ"))
        assertTrue(similar("Дозоры ІІ", "Дозоры II"))
    }

    @Test fun hashNumbersDiffer() {
        assertFalse(similar("Точка бифуркации #1", "Точка бифуркации #15"))
        assertFalse(similar("Точка бифуркации #1", "Точка бифуркации #17. Финал"))
    }

    @Test fun numberNotAtEndDiffers() {
        assertFalse(similar("Ревизор 2. Возвращение", "Ревизор 3. Возвращение"))
        assertFalse(similar("Name-2", "Name-3"))
        assertFalse(similar("Охотник (Книга 2)", "Охотник (Книга 3)"))
    }

    @Test fun ordinalWordsAfterKeywordDiffer() {
        assertFalse(similar("Темная башня. Книга первая", "Темная башня. Книга вторая"))
        assertFalse(similar("Хроники. Часть третья", "Хроники. Часть четвертая"))
        assertFalse(similar("The Saga, Book One", "The Saga, Book Two"))
        assertTrue(TitleMatcher.sameVolume("The Saga, Book Two", "The Saga, Book 2"))
        assertFalse(similar("The Saga. Part First", "The Saga. Part Second"))
    }

    @Test fun trailingNumbersDiffer() {
        assertFalse(similar("ревизор 51", "ревизор 52"))
    }

    // ── Same book still matches ─────────────────────────────────────────────

    @Test fun identicalTitles() {
        assertTrue(similar("Ночной Дозор", "ночной дозор"))
        assertTrue(similar("Дозоры II", "Дозоры II"))
    }

    @Test fun sameVolumeWithSubtitle() {
        assertTrue(similar("Точка бифуркации 2", "Точка бифуркации 2: Возвращение"))
        assertTrue(similar("Дневной Дозор", "Дневной Дозор. Роман"))
    }

    @Test fun sameVolumeDifferentNotation() {
        assertTrue(TitleMatcher.sameVolume("Книга вторая", "Книга 2"))
        assertTrue(TitleMatcher.sameVolume("Дозоры II", "Дозоры 2"))
        assertTrue(TitleMatcher.sameVolume("Точка бифуркации #3", "Точка бифуркации 3"))
    }

    @Test fun smallTypoStillMatches() {
        assertTrue(similar("Сумеречный дозор", "Сумеречный дозорр"))
    }

    @Test fun numbersThatAreNotVolumesMatchThemselves() {
        assertTrue(similar("451 градус по Фаренгейту", "451 градус по Фаренгейту. Роман"))
        assertTrue(similar("1984", "1984"))
    }

    // ── Marker extraction ───────────────────────────────────────────────────

    @Test fun markerExtraction() {
        assertEquals(emptySet<Int>(), TitleMatcher.volumeMarkers("Ночной Дозор"))
        assertEquals(setOf(2), TitleMatcher.volumeMarkers("Дозоры II"))
        assertEquals(setOf(2), TitleMatcher.volumeMarkers("Книга вторая"))
        assertEquals(setOf(15), TitleMatcher.volumeMarkers("Точка бифуркации #15"))
        assertEquals(emptySet<Int>(), TitleMatcher.volumeMarkers("I, Robot"))
    }

    @Test fun wordsMadeOfRomanLettersAreNotNumerals() {
        // "civil", "mix", "ill" are not valid strict Roman numerals.
        assertEquals(emptySet<Int>(), TitleMatcher.volumeMarkers("Civil war"))
        assertEquals(emptySet<Int>(), TitleMatcher.volumeMarkers("The mix"))
        assertEquals(emptySet<Int>(), TitleMatcher.volumeMarkers("Ill wind"))
    }

    @Test fun singleLetterRomanOnlyAtEndOrAfterKeyword() {
        assertEquals(emptySet<Int>(), TitleMatcher.volumeMarkers("I am legend"))
        assertEquals(setOf(5), TitleMatcher.volumeMarkers("Saga V"))
        assertEquals(setOf(1), TitleMatcher.volumeMarkers("Том I. Начало"))
    }
}
