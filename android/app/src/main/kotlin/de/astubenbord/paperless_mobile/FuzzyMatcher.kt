package de.astubenbord.paperless_mobile

import kotlin.math.abs
import kotlin.math.min

object FuzzyMatcher {
    private val separator = Regex("[^\\p{L}\\p{N}]+")

    fun tokenize(normalizedText: String): List<String> =
        normalizedText.split(separator).filter { it.isNotEmpty() }

    fun maxEdits(length: Int): Int = when {
        length < 4 -> 0
        length < 8 -> 1
        else -> 2
    }

    fun candidates(token: String, vocab: Collection<String>, limit: Int = 10): List<String> {
        val max = maxEdits(token.length)
        if (max == 0) return emptyList()
        return vocab.asSequence()
            .filter { it != token && abs(it.length - token.length) <= max }
            .map { it to distance(token, it, max) }
            .filter { it.second <= max }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    // Optimal string alignment distance; returns max + 1 as soon as the result must exceed max.
    fun distance(a: String, b: String, max: Int): Int {
        var twoBack = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var value = min(min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    value = min(value, twoBack[j - 2] + 1)
                }
                current[j] = value
                rowMin = min(rowMin, value)
            }
            if (rowMin > max) return max + 1
            val recycled = twoBack
            twoBack = previous
            previous = current
            current = recycled
        }
        return min(previous[b.length], max + 1)
    }
}
