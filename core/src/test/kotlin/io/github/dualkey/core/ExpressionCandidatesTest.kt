package io.github.dualkey.core

import org.junit.Assert.*
import org.junit.Test

class ExpressionCandidatesTest {
    @Test fun exactFilterUsesFullReadingAndCandidateKind() {
        val candidates=listOf(Candidate("2個","にこ"), Candidate("２個","にこ"), Candidate("二個","にこ"),
            Candidate("にコメント","にこめんと"),Candidate("😊","にこ",CandidateKind.EMOJI),
            Candidate("(^_^)","にこ",CandidateKind.FACE),Candidate("related","にこ",CandidateKind.RELATED))
        assertEquals(listOf("2個","２個","二個"),candidates.filter { it.directlyMatches("ニコ") }.map { it.text })
    }

    @Test fun expandedExpressionLookupPreservesAllExactMatchesAndTheirKind() {
        val values=(1..45).map { "😀"+String(Character.toChars(0x1F300+it)) }
        val dictionary=ExpressionCandidates(ja=ExpressionIndex(mapOf("にこ" to values)))
        assertEquals(values,dictionary.find("にこ",false,true,true,false,expanded=true).map { it.text })
        assertTrue(dictionary.find("にこ",false,true,true,false,expanded=true).all { it.kind==CandidateKind.EMOJI })
    }

    @Test fun exactAliasesWinOverPrefixAndEquivalentVariantsAreDeduplicated() {
        val index=ExpressionIndex(mapOf("はーと" to listOf("❤️","❤","💕"),"はーとびーと" to listOf("💓")))
        assertEquals(listOf("❤️","💕"),index.find("ハート",true,5).map { it.text })
        assertTrue(index.find("はー",false,5).isEmpty())
        assertEquals(listOf("❤️","💕","💓"),index.find("はー",true,5).map { it.text })
        assertTrue(index.find("は",true,5).isEmpty())
    }

    @Test fun languageAndIndependentPreferencesControlSupplementalCandidates() {
        val entries=ExpressionCandidates(
            ja=ExpressionIndex(mapOf("ねこ" to listOf("🐱"))),
            en=ExpressionIndex(mapOf("cat" to listOf("🐈"))),
            faces=ExpressionIndex(mapOf("cat" to listOf("(=^・^=)"))),
        )
        assertEquals(listOf("🐱"),entries.find("ねこ",false,false,true,true).map { it.text })
        assertEquals(listOf("🐈"),entries.find("ＣＡＴ",true,false,true,false).map { it.text })
        assertEquals(listOf("(=^・^=)"),entries.find("CAT",true,false,false,true).map { it.text })
        assertTrue(entries.find("cat",false,false,true,false).isEmpty())
    }
}
