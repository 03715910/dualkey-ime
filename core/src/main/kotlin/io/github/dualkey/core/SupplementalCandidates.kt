package io.github.dualkey.core

/** Common phrase priors supplement the word-cost/POS model, independently of learning. */
object PhrasePrediction {
    private val phrases=listOf(
        "おはよう" to "おはよう", "おはようございます" to "おはようございます",
        "こんにちは" to "こんにちは", "こんにちわ" to "こんにちは", "こんばんは" to "こんばんは",
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
    private val bracketPairs = setOf("（）", "()", "［］", "[]", "｛｝", "{}", "＜＞", "<>",
        "「」", "｢｣", "『』", "【】", "〔〕", "〈〉", "《》", "〘〙", "〚〛", "«»", "‹›", "‘’", "“”", "〝〟")
    private val japaneseWidths = mapOf("「」" to "｢｣", "「" to "｢", "」" to "｣",
        "。" to "｡", "、" to "､", "・" to "･", "￥" to "¥")
    private val otherJapaneseWidth = japaneseWidths + japaneseWidths.entries.associate { (full, half) -> half to full }

    fun isBracketPair(text: String): Boolean = text in bracketPairs

    internal fun alternateWidth(text: String): String? {
        otherJapaneseWidth[text]?.let { return it }
        if (text.length != 1 && !isBracketPair(text)) return null
        fun Char.isAsciiSymbol() = this in '!'..'~' && !isLetterOrDigit()
        return when {
            text.all { it.isAsciiSymbol() } -> text.map { it + 0xfee0 }.joinToString("")
            text.all { (it - 0xfee0).isAsciiSymbol() } -> text.map { it - 0xfee0 }.joinToString("")
            else -> null
        }
    }

    fun contains(entries: Map<String,List<String>>, reading: String, text: String): Boolean {
        val values = entries[reading].orEmpty()
        return text in values || alternateWidth(text)?.let { it in values } == true
    }

    private fun withWidths(values: List<String>): List<String> = buildList {
        val available = values.toSet()
        for (value in values) {
            add(value)
            alternateWidth(value)?.takeUnless { it in available }?.let(::add)
        }
    }

    fun candidates(reading: String,entries: Map<String,List<String>>,prediction: Boolean,
        source: CandidateSource = CandidateSource.SYMBOL_DICTIONARY): List<Candidate> {
        val key=Personalization.normalize(reading)
        fun symbol(text: String, matchedReading: String) = Candidate(text, matchedReading, CandidateKind.SYMBOL,
            CandidateEvidence(if (matchedReading == key) CandidateReason.SYMBOL else CandidateReason.PREFIX,
                source, matchedReading, key.length))
        val exact=withWidths(entries[key].orEmpty()).map { symbol(it,key) }
        if (!prediction || key.length<2 || exact.isNotEmpty()) return exact
        val prefix = entries.asSequence().filter { it.key.startsWith(key) }.take(24)
            .flatMap { (r,v) -> withWidths(v).asSequence().map { symbol(it,r) } }.toList()
        return mergeCandidates(prefix).take(64)
    }
}
