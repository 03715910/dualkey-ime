package io.github.dualkey.core

import org.junit.Assert.*
import org.junit.Test

class NextWordPredictionTest {
    @Test fun commonCommittedPhrasesOfferTheFollowingWords() {
        for (text in listOf("続きを","つづきを","今日は続きを")) {
            val values=NextWordPrediction.candidates(Candidate(text,"つづきを"),emptyList(),30)
            assertEquals("お願いします",values.first().text)
            assertTrue(values.any { it.text=="教えてください" })
        }
        assertEquals("ございます",NextWordPrediction.candidates(Candidate("ありがとう","ありがとう"),emptyList(),30).first().text)
        assertEquals("お願いします",NextWordPrediction.candidates(Candidate("よろしく","よろしく"),emptyList(),30).first().text)
    }
    @Test fun confirmedSequencesRankByFrequencyThenRecencyAndStayContextual() {
        val history=listOf(LearnedContinuation("続きを","見せてください","みせてください",4,20),
            LearnedContinuation("続きを","お願いします","おねがいします",4,10),
            LearnedContinuation("別の文","関係ない候補","かんけいないこうほ",100,100))
        val values=NextWordPrediction.candidates(Candidate("続きを","つづきを"),history,30)
        assertEquals("見せてください",values.first().text)
        assertEquals("お願いします",values[1].text)
        assertFalse(values.any { it.text=="関係ない候補" })
        assertEquals(values.size,values.map { it.text }.distinct().size)
    }
    @Test fun unknownWordsUseOnlyTheirLearnedContinuation() {
        val source=Candidate("独自用語","どくじようご")
        assertTrue(NextWordPrediction.candidates(source,emptyList(),30).isEmpty())
        val learned=LearnedContinuation(source.text,"を確認します","をかくにんします",2,30)
        assertEquals(listOf(Candidate(learned.text,learned.reading)),NextWordPrediction.candidates(source,listOf(learned),30))
        for (unsafe in listOf("", "test@example.com", "https://example.com", "123456", "改行\n入り")) {
            assertTrue(NextWordPrediction.candidates(Candidate(unsafe,"かな"),listOf(learned),30).isEmpty())
        }
    }
}
