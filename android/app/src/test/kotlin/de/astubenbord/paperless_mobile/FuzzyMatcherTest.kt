package de.astubenbord.paperless_mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class FuzzyMatcherTest {
    @Test
    fun tokenizeSplitsOnNonLetterOrDigit() {
        assertEquals(listOf("счет", "2024", "abc"), FuzzyMatcher.tokenize("счет-2024, abc!"))
    }

    @Test
    fun distanceOneForSingleEdits() {
        assertEquals(1, FuzzyMatcher.distance("invoice", "invoise", 2))
        assertEquals(1, FuzzyMatcher.distance("invoice", "invoicee", 2))
        assertEquals(1, FuzzyMatcher.distance("invoice", "invoce", 2))
        assertEquals(1, FuzzyMatcher.distance("invoice", "invoiec", 2))
    }

    @Test
    fun distanceStopsAboveMax() {
        assertEquals(2, FuzzyMatcher.distance("contract", "kontrakt", 2))
        assertEquals(2, FuzzyMatcher.distance("abcdef", "uvwxyz", 1))
    }

    @Test
    fun maxEditsByLength() {
        assertEquals(0, FuzzyMatcher.maxEdits(3))
        assertEquals(1, FuzzyMatcher.maxEdits(4))
        assertEquals(1, FuzzyMatcher.maxEdits(7))
        assertEquals(2, FuzzyMatcher.maxEdits(8))
    }

    @Test
    fun longWordsAllowTwoEdits() {
        assertEquals(listOf("insurance"), FuzzyMatcher.candidates("insurence", listOf("insurance", "residence")))
        assertEquals(listOf("contract"), FuzzyMatcher.candidates("kontrakt", listOf("contract")))
    }

    @Test
    fun shortWordsHaveNoCandidates() {
        assertEquals(emptyList<String>(), FuzzyMatcher.candidates("tax", listOf("tax", "tan", "taxi")))
    }

    @Test
    fun cyrillicTypos() {
        val vocab = listOf("снилс", "свидетельство", "паспорт")
        assertEquals(listOf("снилс"), FuzzyMatcher.candidates("снелс", vocab))
        assertEquals(listOf("свидетельство"), FuzzyMatcher.candidates("свидетелство", vocab))
    }

    @Test
    fun candidatesSortedByDistanceThenWordAndLimited() {
        val vocab = listOf("dokumente", "documental", "docunent", "documents", "cabinet")
        assertEquals(
            listOf("documents", "docunent", "documental", "dokumente"),
            FuzzyMatcher.candidates("document", vocab),
        )
        assertEquals(listOf("documents", "docunent"), FuzzyMatcher.candidates("document", vocab, limit = 2))
    }

    @Test
    fun tokenItselfExcluded() {
        assertEquals(listOf("invoices"), FuzzyMatcher.candidates("invoice", listOf("invoice", "invoices")))
    }
}
