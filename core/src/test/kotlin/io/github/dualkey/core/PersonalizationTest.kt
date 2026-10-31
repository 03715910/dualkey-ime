package io.github.dualkey.core

import org.junit.Assert.*
import org.junit.Test

class PersonalizationTest {
    private val base=listOf(Candidate("感じ","かんじ"),Candidate("漢字","かんじ"),Candidate("幹事","かんじ"))
    @Test fun frequencyRanksConfirmedChoicesAndRecencyOnlyBreaksTies() {
        val history=listOf(LearnedChoice("かんじ","漢字",5,10),LearnedChoice("かんじ","幹事",2,1000))
        assertEquals(listOf("漢字","幹事","感じ"),Personalization.candidates("かんじ",base,history,false,100).map { it.text })
        assertEquals("幹事",Personalization.candidates("かんじ",base,history+LearnedChoice("かんじ","幹事",5,2000),false,100).first().text)
    }
    @Test fun learnedFullReadingsCompletePrefixesAndDoNotLeakIntoUnrelatedQueries() {
        val history=listOf(LearnedChoice("いいかんじ","いい感じ",3,1),LearnedChoice("かんじ","幹事",8,2))
        val predicted=Personalization.candidates("いいか",emptyList(),history,true,100)
        assertEquals(Candidate("いい感じ","いいかんじ"),predicted.first())
        assertTrue(Personalization.candidates("いいか",emptyList(),history,false,100).isEmpty())
        assertTrue(Personalization.candidates("とうきょう",emptyList(),history,true,100).isEmpty())
        assertTrue(Personalization.candidates("",base,history,true,100).isEmpty())
    }
    @Test fun unlearnedDefaultAndClearedHistoryKeepSystemOrder() {
        assertEquals(base,Personalization.candidates("かんじ",base,emptyList(),false,100))
        assertEquals("感じ",Personalization.candidates("カンジ",base,listOf(LearnedChoice("かんじ","感じ",1,1)),false,100).first().text)
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
