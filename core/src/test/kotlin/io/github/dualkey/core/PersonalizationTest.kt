package io.github.dualkey.core

import org.junit.Assert.*
import org.junit.Test

class PersonalizationTest {
    private val base=listOf(Candidate("感じ","かんじ"),Candidate("漢字","かんじ"),Candidate("幹事","かんじ"))
    @Test fun oneSelectionDoesNotChangeRankAndRepeatedSelectionIsBounded() {
        val key = "かんじ"
        val now = 2_000_000_000_000L
        val choices = (0..12).map { Candidate("候補$it", key) }
        fun rank(index: Int, count: Int, lastUsed: Long = now) =
            Personalization.rank(choices, mapOf(choices[index].text to LearnedChoice(key, choices[index].text, count, lastUsed)), now)
        assertEquals(choices.map { it.text }, rank(1, 1).map { it.text })
        assertEquals(choices[2].text, rank(2, 4).first().text)
        assertEquals(8, rank(12, 1000).indexOfFirst { it.text == choices[12].text })
        assertEquals(choices.first().text, rank(1, 1000, 1).first().text)
        assertEquals(4, rank(2, 4).first().alternatives.single().choice?.count)
        assertEquals(base, Personalization.rank(base, emptyMap(), now))
    }
    @Test fun onlyBoundedJapaneseReadingsAndConfirmedDisplayStringsAreAccepted() {
        assertTrue(Personalization.valid("よし","𠮷"))
        assertFalse(Personalization.valid("password","secret"))
        assertFalse(Personalization.valid("あ","mail@example.com"))
        assertFalse(Personalization.valid("あ","https://example.com"))
        assertFalse(Personalization.valid("あ","a\nb"))
        assertFalse(Personalization.valid("あ".repeat(81),"word"))
    }
}
