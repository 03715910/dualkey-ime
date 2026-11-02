package io.github.dualkey.core

import java.text.Normalizer

data class LearnedChoice(val reading: String, val text: String, val count: Int, val lastUsed: Long)

object Personalization {
    fun normalize(reading: String): String = Normalizer.normalize(reading,Normalizer.Form.NFKC)
        .map { if (it in 'ァ'..'ヶ') it-0x60 else it }.joinToString("")

    fun valid(reading: String,text: String): Boolean = reading.length in 1..80 && text.length in 1..160 &&
        reading.all { it in 'ぁ'..'ゖ' || it=='ー' } && text.none { it.isISOControl() } &&
        !text.contains('@') && !text.contains("://")

    /** Blend bounded dictionary rank, log-frequency, local context and recency. */
    fun candidates(reading: String, base: List<Candidate>, history: List<LearnedChoice>, prediction: Boolean, limit: Int,context: Set<String> = emptySet()): List<Candidate> {
        if (reading.isEmpty()) return emptyList()
        val key=normalize(reading)
        val matches=history.asSequence().filter {
            it.count>0 && (it.reading==key || (prediction && it.reading.startsWith(key)))
        }.sortedWith(compareByDescending<LearnedChoice> { it.count }.thenByDescending { it.lastUsed }).toList()
        val byText=matches.distinctBy { it.text }.associateBy { it.text }
        val ranks=base.mapIndexed { i,c -> c.text to i }.toMap()
        val options=(base+matches.map { Candidate(it.text,it.reading) }).distinctBy { it.text }
        return options.sortedWith(compareByDescending<Candidate> {
            val learned=byText[it.text]
            (if (learned!=null) kotlin.math.ln(learned.count.coerceAtMost(1_000_000)+1.0)*300 else 0.0) +
                (if (it.text in context) 400 else 0) - (if (learned!=null) 0 else minOf(ranks[it.text] ?: 40,40)*4)
        }.thenByDescending { byText[it.text]?.lastUsed ?: 0L }.thenBy { ranks[it.text] ?: Int.MAX_VALUE })
            .take(limit.coerceIn(2,10000))
    }
}
