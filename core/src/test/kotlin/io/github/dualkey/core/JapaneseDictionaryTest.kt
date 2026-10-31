package io.github.dualkey.core

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class JapaneseDictionaryTest {
    companion object {
        private lateinit var lexicon: JapaneseLexicon
        @JvmStatic @BeforeClass fun load() {
            val asset = File("../app/src/main/assets/japanese/lexicon.dklz")
            val temp = File.createTempFile("dualkey-lexicon", ".dkl").apply { deleteOnExit() }
            GZIPInputStream(asset.inputStream(),65536).use { source -> temp.outputStream().use { source.copyTo(it) } }
            lexicon = JapaneseLexicon.open(temp)
        }
    }
    private val engine get() = DictionaryConversionEngine(lexicon)
    private fun words(reading: String, predict: Boolean = false, limit: Int = 30) =
        engine.candidates(reading, predict, limit).map { it.text }

    @Test fun broadVocabularyAndHomophones() {
        assertTrue(lexicon.entryCount > 1_000_000)
        for ((reading, spelling) in mapOf("かんじる" to "感じる", "ゆうしょう" to "優勝", "けんこう" to "健康",
            "かいはつ" to "開発", "そうぞう" to "想像", "かんきょう" to "環境", "しんかんせん" to "新幹線",
            "びじゅつかん" to "美術館", "きょうりょく" to "協力", "しょうがっこう" to "小学校")) {
            assertTrue("$reading → $spelling: ${words(reading)}", spelling in words(reading))
        }
        val homophones = words("かんじ", limit = 60)
        assertTrue(homophones.toString(), homophones.containsAll(listOf("感じ", "漢字", "幹事", "監事")))
        assertTrue("Multiple spellings are reachable", words("こうしょう", limit = 60).size >= 20)
    }
    @Test fun feelingPhraseAndSentenceAlternatives() {
        for (reading in listOf("いいかんじ", "いいかんじです", "かんじがいい", "きょうはいいてんきです", "にほんごをべんきょうします")) {
            println("$reading: ${words(reading).take(10)}")
        }
        assertEquals("いい感じ", words("いいかんじ").first())
        assertTrue(words("いいかんじ").toString(), "いい漢字" in words("いいかんじ"))
        assertEquals("いい感じです", words("いいかんじです").first())
        assertEquals("感じがいい", words("かんじがいい").first())
        assertEquals("今日は", words("きょうは").first())
        assertEquals("今日は日本語を勉強します", words("きょうはにほんごをべんきょうします").first())
    }
    @Test fun prefixAndSentencePredictionsUseLargeDictionary() {
        for (reading in listOf("かんじ", "いいかん", "とうきょ", "きょうはにほ")) println("predict $reading: ${words(reading, true).take(10)}")
        assertTrue(words("かんじ", true).toString(), "感じる" in words("かんじ", true))
        assertTrue(words("いいかん", true).toString(), "いい感じ" in words("いいかん", true))
        assertTrue(words("とうきょ", true).toString(), "東京" in words("とうきょ", true))
        assertTrue(words("きょうはにほ", true, 60).toString(), "今日は日本語" in words("きょうはにほ", true, 60))
    }
    @Test fun userDictionaryLiteralsAndNormalization() {
        val custom = DictionaryConversionEngine(lexicon) { mapOf("でゅある" to listOf("DualKey")) }
        assertEquals("DualKey", custom.candidates("でゅある", false, 30).first().text)
        assertTrue(custom.candidates("でゅあるをつかう", false, 30).any { it.text == "DualKeyを使う" })
        assertEquals("いい感じ", words("ｲｲｶﾝｼﾞ").first())
        assertTrue(words("みちのご😀").any { it.endsWith("😀") })
        assertTrue(words("かんじ").containsAll(listOf("かんじ", "カンジ")))
        assertTrue(words("", true).isEmpty())
        assertTrue(words("あ".repeat(200), true).isNotEmpty())
    }
    @Test fun indexedLookupDoesNotScanDictionaryPerKeystroke() {
        // A generous regression ceiling, not a claim about Samsung device latency.
        repeat(2) { words("いいかんじです", true) }
        val start = System.nanoTime()
        repeat(6) { for (input in listOf("あ", "かん", "いいかんじ", "きょうはにほ", "きょうはいいてんきですね")) words(input, true) }
        val millis = (System.nanoTime() - start) / 1_000_000
        println("30 dictionary/prediction searches: ${millis}ms")
        assertTrue("Bounded indexed search: ${millis}ms", millis < 15_000)
    }
}
