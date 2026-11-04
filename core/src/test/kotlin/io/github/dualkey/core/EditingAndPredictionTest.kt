package io.github.dualkey.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EditingAndPredictionTest {
    @Test fun fallbackConverterDoesNotKeepItsOldThirtyCandidateCeiling() {
        val converter=LocalConversionEngine { mapOf("てすと" to List(120) { "候補$it" }) }
        val candidates=converter.candidates("てすと",false,120)
        assertEquals(120,candidates.size); assertEquals("候補80",candidates[80].text)
    }
    @Test fun deletionKeepsEarlierClausesAndTreatsUnicodeAsCodePoints() {
        val text="前の文。次の文、最後。"
        assertEquals("最後。".codePointCount(0,3),EditingPolicy.chunkBeforeCursor(text))
        assertEquals(1,EditingPolicy.chunkBeforeCursor("文章。\n"))
        assertEquals(2,EditingPolicy.chunkBeforeCursor("前。😀。"))
        assertEquals(0,EditingPolicy.chunkBeforeCursor(""))
    }
    @Test fun deletionWillNotGuessAcrossATruncatedLongContext() {
        assertEquals(1,EditingPolicy.chunkBeforeCursor("あ".repeat(2049)))
        assertEquals(3,EditingPolicy.chunkBeforeCursor("あ".repeat(2040)+"。末尾。"))
    }
    @Test fun shiftSupportsOneLetterDoubleTapLockAndExplicitUnlock() {
        assertEquals(1,ScreenShift.tap(0,Long.MAX_VALUE,300))
        assertEquals(2,ScreenShift.tap(1,100,300))
        assertEquals(0,ScreenShift.tap(1,301,300))
        assertEquals(0,ScreenShift.tap(2,100,300))
        assertEquals(0,ScreenShift.afterInput(1,"A"))
        assertEquals(1,ScreenShift.afterInput(1," "))
        assertEquals(2,ScreenShift.afterInput(2,"A"))
    }
    @Test fun clipboardHistoryRejectsMarkedAndCommonSensitiveFormats() {
        assertFalse(ClipboardPrivacy.retain("普通の文",true))
        for (text in listOf("mail@example.com","https://example.com","09012345678","password: abc","sk-1234567890123456789012345678",""))
            assertFalse(text,ClipboardPrivacy.retain(text,false))
        assertTrue(ClipboardPrivacy.retain("あとで確認する文章",false))
        assertTrue(ClipboardPrivacy.retain("hello world",false))
        assertFalse(ClipboardPrivacy.retain("あ".repeat(2001),false))
    }
    @Test fun greetingCompletionAndContextDoNotNeedPersonalHistory() {
        assertTrue(PhrasePrediction.candidates("おは",true).map { it.text }.containsAll(listOf("おはよう","おはようございます")))
        assertTrue(PhrasePrediction.candidates("おは",false).isEmpty())
        assertEquals("いい天気ですね",NextWordPrediction.candidates(Candidate("今日は","きょうは"),emptyList(),30).first().text)
    }
    @Test fun oneChoiceCannotDisplaceAContextuallySupportedMatch() {
        val base=listOf(Candidate("感じ","かんじ"),Candidate("漢字","かんじ"))
        val choice=LearnedChoice("かんじ","漢字",1,1)
        assertEquals("感じ",Personalization.rank(base,mapOf(choice.text to choice),1,mapOf("感じ" to base.first())).first().text)
        assertEquals("漢字",Personalization.rank(base,mapOf(choice.text to choice.copy(count=100)),1,mapOf("感じ" to base.first())).first().text)
    }
    @Test fun repeatedSpecificWordPairPromotesFullModelPhrase() {
        val pairs=listOf(LearnedContinuation("続きを","見せ","みせ",4,1))
        assertEquals("見せてください",NextWordPrediction.candidates(Candidate("続きを","つづきを"),pairs,30,now=1).first().text)
        assertEquals("お願いします",NextWordPrediction.candidates(Candidate("続きを","つづきを"),pairs.map { it.copy(count=1) },30,now=1).first().text)
    }
    @Test fun bundledSymbolDataSuppliesBroadReadingsAndUnicodeVariants() {
        val entries=linkedMapOf<String,MutableList<String>>()
        File("../app/src/main/assets/symbols/symbol.tsv").readLines().drop(1).forEach { line ->
            val c=line.split('\t'); if (c.size>=3) c[2].split(' ').filter { it.isNotEmpty() }.forEach { entries.getOrPut(Personalization.normalize(it)) { mutableListOf() }.add(c[1]) }
        }
        entries.values.forEach { if ("〜" in it) it.add("～") }
        assertTrue(entries.size>1000)
        assertTrue(SymbolCandidates.candidates("おんぷ",entries,false).map { it.text }.containsAll(listOf("♪","♫","♬")))
        for (reading in listOf("から","ー")) assertTrue(SymbolCandidates.candidates(reading,entries,false).any { it.text=="～" })
        assertNotEquals("～".codePointAt(0),"〜".codePointAt(0))
    }
}
