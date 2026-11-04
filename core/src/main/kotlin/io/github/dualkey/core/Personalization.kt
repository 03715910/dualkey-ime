package io.github.dualkey.core

import java.text.Normalizer

data class LearnedChoice(val reading: String, val text: String, val count: Int, val lastUsed: Long)

object Personalization {
    fun normalize(reading: String): String = Normalizer.normalize(reading,Normalizer.Form.NFKC)
        .map { if (it in 'ァ'..'ヶ') it-0x60 else it }.joinToString("")

    fun valid(reading: String,text: String): Boolean = reading.length in 1..80 && text.length in 1..160 &&
        reading.all { it in 'ぁ'..'ゖ' || it=='ー' } && text.none { it.isISOControl() } &&
        !text.contains('@') && !text.contains("://")

    private const val HALF_LIFE = 90L * 24 * 60 * 60 * 1000

    /** Each doubling after the first selection buys one step, with a four-step ceiling. */
    internal fun strength(choice: LearnedChoice?, now: Long): Int {
        if (choice == null || choice.count < 2) return 0
        val age = (now - choice.lastUsed.coerceAtLeast(0)).coerceAtLeast(0)
        val count = choice.count shr (age / HALF_LIFE).coerceAtMost(30).toInt()
        return (31 - Integer.numberOfLeadingZeros(count)).coerceIn(0, 4)
    }

    internal fun rank(base: List<Candidate>, history: Map<String, LearnedChoice>, now: Long,
        context: Map<String, Candidate> = emptyMap()): List<Candidate> {
        if (history.isEmpty() && context.isEmpty()) return base
        return base.mapIndexed { index, candidate ->
            val choice = history[candidate.text]
            val continuation = context[candidate.text]
            // Segmented paths already used bounded learning on their constituent words.
            val shift = if (candidate.evidence?.reason == CandidateReason.SEGMENTED) 0 else strength(choice, now)
            val learned = choice?.let { CandidateEvidence(CandidateReason.LEARNED, CandidateSource.HISTORY,
                it.reading, choice = it) }
            val enriched = candidate.withEvidence(listOfNotNull(learned)).let {
                if (continuation == null) it else it.withAlternative(continuation)
            }
            val boost = (shift + if (continuation != null) 1 else 0).coerceAtMost(4)
            (index * 2 - boost * 2 - if (boost > 0) 1 else 0) to enriched
        }.sortedBy { it.first }.map { it.second }
    }
}
