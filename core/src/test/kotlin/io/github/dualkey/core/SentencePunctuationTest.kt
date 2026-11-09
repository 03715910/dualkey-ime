package io.github.dualkey.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencePunctuationTest {
    @Test fun followsTheLastScriptEvenAfterChangingInputMode() {
        for (text in listOf("こんにちは", "ありがとう", "本当", "Hello日本語", "本当　 ", "ｶﾀｶﾅ", "𠮷")) {
            assertEquals("$text + !", "！", SentencePunctuation.resolve("!", "", false, false) { text })
            assertEquals("$text + ?", "？", SentencePunctuation.resolve("?", "", false, false) { text })
        }
        for (text in listOf("Hello", "Really", "abc", "日本語abc", "Hello  ", "café")) {
            assertEquals("$text + !", "!", SentencePunctuation.resolve("!", "", true, false) { text })
            assertEquals("$text + ?", "?", SentencePunctuation.resolve("?", "", true, false) { text })
        }
        assertEquals("？", SentencePunctuation.resolve("?", "", true, false) { null })
        assertEquals("!", SentencePunctuation.resolve("!", "", false, false) { "日本語\n " })
    }

    @Test fun usesTheVisibleCompositionAndDoesNotReadPrivateFieldsOrExplicitKeys() {
        val romaji = Composer(preserveAsciiBangAndQuestion = true).apply { append("konnichiha") }
        assertEquals("こんにちは", romaji.text)
        romaji.append(SentencePunctuation.resolve("!", romaji.reading, true, false) { error("read unnecessary") })
        assertEquals("こんにちは！", romaji.reading)
        assertEquals("?", SentencePunctuation.resolve("?", "Hello", true, false) { error("read unnecessary") })
        assertEquals("!", SentencePunctuation.resolve("!", "こんにちは", true, true) { error("private cursor read") })
        assertEquals("？", SentencePunctuation.resolve("？", "Hello", false, true) { error("explicit cursor read") })
        romaji.replace("!")
        assertEquals("!", romaji.text)
        assertEquals("!", romaji.reading)
    }

    @Test fun recognizableAddressesAndCodeStayAsciiInPlainEditors() {
        for (text in listOf("参照 https://example.com/日本語", "www.example.com/日本語",
            "user@ドメイン", "print(\"日本語\")", "word = '日本語'")) {
            assertEquals(text, "?", SentencePunctuation.resolve("?", "", true, false) { text })
        }
    }
}
