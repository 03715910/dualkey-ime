package io.github.dualkey.core

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class SemanticConversionTest {
    companion object {
        private lateinit var lexicon: JapaneseLexicon
        @JvmStatic @BeforeClass fun load() {
            val temporary=File.createTempFile("dualkey-semantic-", ".dkl").apply { deleteOnExit() }
            GZIPInputStream(File("../app/src/main/assets/semantic/lexicon.dklz").inputStream(),65536).use { source ->
                temporary.outputStream().use { source.copyTo(it) }
            }
            lexicon=JapaneseLexicon.open(temporary)
        }
    }
    @Test fun ordinaryWordsHaveMeaningAndActualLoanwords() {
        assertTrue(lexicon.readingCount>100_000)
        for (source in listOf("いす","椅子","イス")) {
            val values=SemanticConversion.equivalents(lexicon,"いす",source)
            assertTrue("$source: $values",values.containsAll(listOf("chair","チェア")))
            assertEquals("chair",SemanticConversion.english(lexicon,"いす",source))
        }
        assertEquals("cat",SemanticConversion.english(lexicon,"ねこ","猫"))
        assertEquals("desk",SemanticConversion.english(lexicon,"つくえ","机"))
        assertNull(SemanticConversion.english(lexicon,"これはみとうろくのちょうぶんです","これは未登録の長文です"))
    }
    @Test fun additionsKeepLeadingJapaneseChoicesAndCanBeLearned() {
        val base=LocalConversionEngine().candidates("みず",false,30)
        val values=SemanticConversion.augment("みず",base,lexicon,30)
        assertEquals("水",values.first().text)
        assertTrue(values.toString(),values.any { it.text=="ミズ" })
        assertTrue(values.toString(),values.any { it.text=="water" })
        assertEquals(values.size,values.map { it.text }.distinct().size)
        assertTrue(values.size<=30)
        val ranked=Personalization.candidates("みず",values,listOf(LearnedChoice("みず","water",3,10)),false,30)
        assertEquals("water",ranked.first().text)
    }
    @Test fun japaneseChoiceDisambiguatesWordsWithTheSameReading() {
        val bridge=SemanticConversion.augment("はし",listOf(Candidate("橋","はし")),lexicon,30).map { it.text }
        val chopsticks=SemanticConversion.augment("はし",listOf(Candidate("箸","はし")),lexicon,30).map { it.text }
        assertTrue(bridge.toString(),"bridge" in bridge)
        assertFalse(bridge.toString(),"chopsticks" in bridge)
        assertTrue(chopsticks.toString(),"chopsticks" in chopsticks)
        assertFalse(chopsticks.toString(),"bridge" in chopsticks)
        val chair=SemanticConversion.augment("いす",listOf(Candidate("椅子","いす")),lexicon,30).map { it.text }
        assertTrue("chair" in chair)
        assertFalse("swords" in chair)
    }
    @Test fun wholeCompositionReturnsToOriginalHiraganaAcrossCycles() {
        val first=CompositionVariant.start("きょうはいすをかいます","今日は椅子を買います")
        assertEquals("キョウハイスヲカイマス",first.text)
        val english=first.next().copy(text="I will buy a chair today.")
        val original=english.next()
        assertEquals(CompositionScript.HIRAGANA,original.script)
        assertEquals("きょうはいすをかいます",original.text)
        assertEquals(first,original.next())
        assertFalse(original.googlePowered)
    }
}
