package io.github.dualkey.core

/** Alphabetically indexed aliases; prefix lookup visits only a bounded part of the index. */
class ExpressionIndex(entries: Map<String,List<String>> = emptyMap()) {
    private val keys=entries.keys.sorted()
    private val values=entries.toMap()
    fun find(reading: String, prefix: Boolean, limit: Int, maxAddedReading: Int = Int.MAX_VALUE): List<Candidate> {
        val key=Personalization.normalize(reading).trim().lowercase()
        if (key.isBlank() || key.length>36) return emptyList()
        val matches=values[key].orEmpty()
        if (matches.isNotEmpty()) return matches.distinctBy { it.replace("\uFE0F","") }.take(limit).map {
            Candidate(it,key,evidence=CandidateEvidence(CandidateReason.EXPRESSION_EXACT,CandidateSource.EXPRESSION,key))
        }
        if (!prefix || key.length<2) return emptyList()
        val start=keys.binarySearch(key).let { if (it>=0) it else -it-1 }
        val candidates=keys.subList(start,keys.size).asSequence().takeWhile { it.startsWith(key) }
            .filter { it.length-key.length <= maxAddedReading }.take(20)
            .flatMap { alias -> values.getValue(alias).asSequence().map {
                Candidate(it,alias,evidence=CandidateEvidence(CandidateReason.EXPRESSION_PREFIX,CandidateSource.EXPRESSION,alias,key.length))
            } }.toList()
        return mergeCandidates(candidates).distinctBy { it.text.replace("\uFE0F","") }.take(limit)
    }
}

class ExpressionCandidates(
    val symbols: Map<String,List<String>> = emptyMap(),
    private val ja: ExpressionIndex = ExpressionIndex(),
    private val en: ExpressionIndex = ExpressionIndex(),
    private val faces: ExpressionIndex = ExpressionIndex(),
    val words: Map<String,List<String>> = emptyMap(),
) {
    fun find(reading: String, english: Boolean, prefix: Boolean, emoji: Boolean, face: Boolean,
        expanded: Boolean = false, maxAddedReading: Int = Int.MAX_VALUE): List<Candidate> {
        val pictures=if (emoji) (if (english) en else ja).find(reading,prefix,if (expanded) 10000 else if (prefix) 6 else 20,maxAddedReading)
            .map { it.copy(kind=CandidateKind.EMOJI) } else emptyList()
        val kaomoji=if (face) faces.find(reading,prefix,if (expanded) 10000 else if (prefix) 3 else 12,maxAddedReading)
            .map { it.copy(kind=CandidateKind.FACE) } else emptyList()
        return mergeCandidates(pictures.take(3)+kaomoji.take(3)+pictures.drop(3)+kaomoji.drop(3))
    }
}
