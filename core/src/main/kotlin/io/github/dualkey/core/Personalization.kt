package io.github.dualkey.core

import java.text.Normalizer

data class LearnedChoice(val reading: String, val text: String, val count: Int, val lastUsed: Long)

object Personalization {
    fun normalize(reading: String): String = Normalizer.normalize(reading,Normalizer.Form.NFKC)
        .map { if (it in 'ァ'..'ヶ') it-0x60 else it }.joinToString("")

    fun valid(reading: String,text: String): Boolean = reading.length in 1..80 && text.length in 1..160 &&
        reading.all { it in 'ぁ'..'ゖ' || it=='ー' } && text.none { it.isISOControl() } &&
        !text.contains('@') && !text.contains("://")

    /** Only matching readings participate. Frequency wins; recency breaks ties. */
    fun candidates(reading: String, base: List<Candidate>, history: List<LearnedChoice>, prediction: Boolean, limit: Int): List<Candidate> {
        if (reading.isEmpty()) return emptyList()
        val key=normalize(reading)
        val learned=history.asSequence().filter {
            it.count>0 && (it.reading==key || (prediction && it.reading.startsWith(key)))
        }.sortedWith(compareByDescending<LearnedChoice> { it.count }
            .thenByDescending { it.reading==key }.thenByDescending { it.lastUsed }.thenBy { it.text })
            .map { Candidate(it.text,it.reading) }.toList()
        return (learned+base).distinctBy { it.text }.take(limit.coerceIn(2,1000))
    }
}
