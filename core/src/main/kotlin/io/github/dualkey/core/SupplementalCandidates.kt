package io.github.dualkey.core

/** Common phrase priors supplement the word-cost/POS model, independently of learning. */
object PhrasePrediction {
    private val phrases=listOf(
        "おはよう" to "おはよう", "おはようございます" to "おはようございます",
        "こんにちは" to "こんにちは", "こんばんは" to "こんばんは",
        "ありがとうございます" to "ありがとうございます", "ありがとうございました" to "ありがとうございました",
        "よろしくおねがいします" to "よろしくお願いします", "よろしくおねがいいたします" to "よろしくお願いいたします",
        "おつかれさまです" to "お疲れさまです", "おつかれさまでした" to "お疲れさまでした",
        "おせわになっております" to "お世話になっております", "しょうちしました" to "承知しました",
        "りょうかいしました" to "了解しました", "かくにんおねがいします" to "確認お願いします",
        "ありがとうございます。" to "ありがとうございます。", "すみません" to "すみません",
        "おねがいします" to "お願いします", "おねがいいたします" to "お願いいたします",
        "だいじょうぶです" to "大丈夫です", "またあした" to "また明日", "おやすみなさい" to "おやすみなさい")
    fun candidates(reading: String,prediction: Boolean): List<Candidate> = phrases.asSequence()
        .filter { it.first==reading || (prediction && reading.length>=2 && it.first.startsWith(reading)) }
        .map { (key, text) -> Candidate(text, key, evidence = CandidateEvidence(
            if (key == reading) CandidateReason.PHRASE else CandidateReason.PHRASE_PREFIX,
            CandidateSource.PHRASE, key, reading.length)) }.toList()
}

object SymbolCandidates {
    fun candidates(reading: String,entries: Map<String,List<String>>,prediction: Boolean,
        source: CandidateSource = CandidateSource.SYMBOL_DICTIONARY): List<Candidate> {
        val key=Personalization.normalize(reading)
        fun symbol(text: String, matchedReading: String) = Candidate(text, matchedReading, CandidateKind.SYMBOL,
            CandidateEvidence(if (matchedReading == key) CandidateReason.SYMBOL else CandidateReason.PREFIX,
                source, matchedReading, key.length))
        val exact=entries[key].orEmpty().map { symbol(it,key) }
        if (!prediction || key.length<2 || exact.isNotEmpty()) return exact
        val prefix = entries.asSequence().filter { it.key.startsWith(key) }.take(24)
            .flatMap { (r,v) -> v.asSequence().map { symbol(it,r) } }.toList()
        return mergeCandidates(prefix).take(64)
    }
}
