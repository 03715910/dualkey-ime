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
        .map { Candidate(it.second,it.first) }.toList()
}

object SymbolCandidates {
    fun candidates(reading: String,entries: Map<String,List<String>>,prediction: Boolean): List<Candidate> {
        val key=Personalization.normalize(reading)
        val exact=entries[key].orEmpty().map { Candidate(it,key) }
        if (!prediction || key.length<2 || exact.isNotEmpty()) return exact
        return entries.asSequence().filter { it.key.startsWith(key) }.take(24)
            .flatMap { (r,v) -> v.asSequence().map { Candidate(it,r) } }.distinctBy { it.text }.take(64).toList()
    }
}
