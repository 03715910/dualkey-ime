package io.github.dualkey.core

import org.junit.Assert.*
import org.junit.Test

class InputTest {
    @Test fun standardRomaji() {
        mapOf("KYO" to "きょ", "KYOU" to "きょう", "KYOUHA" to "きょうは", "NIHONGO" to "にほんご", "SHA" to "しゃ",
            "CHA" to "ちゃ", "TTA" to "った", "NNA" to "んな", "gakkou" to "がっこう", "kan'i" to "かんい",
            "shinjuku" to "しんじゅく", "xtsu" to "っ", "ltsu" to "っ", "kya" to "きゃ", "fa" to "ふぁ", "va" to "ゔぁ").forEach { (r,k) -> assertEquals(r,k,Romaji.convert(r,true)) }
    }
    @Test fun incompleteAndN() {
        assertEquals("ky",Romaji.convert("KY"))
        assertEquals("n",Romaji.convert("n"))
        assertEquals("ん",Romaji.convert("n",true))
        assertEquals("ん",Romaji.convert("nn"))
        assertEquals("ん",Romaji.convert("NN",true))
        assertEquals("かんp",Romaji.convert("kanp"))
        assertEquals("こんや",Romaji.convert("kon'ya"))
        assertEquals("んあ",Romaji.convert("n'a"))
    }
    @Test fun mixedFlickAndRomajiDeletion() {
        val c=Composer(); c.append("あ"); c.append("kyo"); assertEquals("あきょ",c.text)
        c.delete(); assertEquals("あき",c.text); c.append("u"); assertEquals("あきう",c.text)
        c.clear(); assertTrue(c.isEmpty); assertFalse(c.delete())
        c.append("あ😀"); c.delete(); assertEquals("あ",c.text)
    }
    @Test fun deletesConvertedKanaRatherThanOriginalRomaji() {
        listOf("ka" to "", "k" to "", "ky" to "k", "nn" to "", "nna" to "ん",
            "tta" to "っ", "kyo" to "き", "nihongo" to "にほん", "kan" to "か").forEach { (raw,expected) ->
            val c=Composer(); c.append(raw); assertTrue(c.delete()); assertEquals(raw,expected,c.text)
        }
        val c=Composer(); c.append("kak"); c.delete(); assertEquals("か",c.text)
        c.append("na"); assertEquals("かな",c.text); c.delete(); c.delete(); assertTrue(c.isEmpty)
    }
    @Test fun japanesePunctuationIsFullWidthWithoutChangingNumbersOrRomajiN() {
        assertEquals("？！（）：；＠１２",Romaji.convert("?!():;@１２"))
        assertEquals("、。「」・ー1230",Romaji.convert(",.[]/-1230"))
        assertEquals("かんい",Romaji.convert("kan'i"))
        assertEquals("ん？",Romaji.convert("n?"))
    }
    @Test fun kanjiSentencesAndUserDictionary() {
        val engine=LocalConversionEngine { mapOf("でゅある" to listOf("DualKey")) }
        assertEquals("今日",engine.candidates("きょう",false,10).first().text)
        assertTrue(engine.candidates("きょう",false,10).any { it.text=="京都" })
        assertEquals("今日は",engine.candidates("きょうは",false,10).first().text)
        assertEquals("日本語",engine.candidates("にほんご",false,10).first().text)
        assertEquals("今日は日本語を勉強します",engine.candidates("きょうはにほんごをべんきょうします",false,10).first().text)
        assertEquals("DualKey",engine.candidates("でゅある",false,10).first().text)
        assertTrue(engine.candidates("にほ",true,10).any { it.text=="日本語" })
        assertTrue(engine.candidates("",true,10).isEmpty())
    }
    @Test fun englishPredictions() { assertEquals(listOf("hello","help","helmet"),EnglishPrediction.candidates("hel",10)); assertEquals("Hello",EnglishPrediction.candidates("Hel",1).first()) }
    @Test fun hardwareDisplayLifecycle() {
        val c=VisibilityController(); assertEquals(DisplayMode.FULL,c.mode)
        c.connectionChanged(true); assertEquals(DisplayMode.HIDDEN,c.mode)
        c.manual=DisplayMode.FULL; c.connectionChanged(true); assertEquals(DisplayMode.FULL,c.mode)
        c.manual=DisplayMode.COMPACT; assertEquals(DisplayMode.COMPACT,c.mode)
        c.connectionChanged(false); assertEquals(DisplayMode.FULL,c.mode)
        c.connectionChanged(true,DisplayMode.COMPACT); assertEquals(DisplayMode.COMPACT,c.mode)
        c.toggle(); assertEquals(DisplayMode.FULL,c.mode); c.toggle(); assertEquals(DisplayMode.HIDDEN,c.mode)
    }
    @Test fun explicitHardwarePolicy() {
        val c=VisibilityController(); c.policy=HardwarePolicy.COMPACT; c.connectionChanged(true)
        assertEquals(DisplayMode.COMPACT,c.mode); c.policy=HardwarePolicy.FULL; assertEquals(DisplayMode.FULL,c.mode)
        c.policy=HardwarePolicy.HIDDEN; assertEquals(DisplayMode.HIDDEN,c.mode)
        c.connectionChanged(false); assertEquals(DisplayMode.FULL,c.mode)
    }
}
