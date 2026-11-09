package io.github.dualkey.core

enum class CandidateKind { WORD, SYMBOL, EMOJI, FACE, RELATED }

enum class CandidateReason(val direct: Boolean = false) {
    DICTIONARY_EXACT(true), LITERAL(true), KATAKANA(true), SEGMENTED(true), WEAK_SEGMENT,
    PREFIX, TAIL, NEXT_WORD, SYMBOL(true), EXPRESSION_EXACT, EXPRESSION_MEANING, EXPRESSION_PREFIX,
    MEANING, USER_DICTIONARY(true), USER_PREFIX, LEARNED, COUNTER(true), PHRASE(true), PHRASE_PREFIX
}

enum class CandidateSource { JAPANESE_LEXICON, USER_DICTIONARY, INPUT, FALLBACK, PHRASE, SYMBOL_ALIAS, SYMBOL_DICTIONARY, EXPRESSION, JMDICT, HISTORY }

data class CandidateEvidence(
    val reason: CandidateReason,
    val source: CandidateSource,
    val reading: String,
    val matchedLength: Int = reading.length,
    val wordCost: Int? = null,
    val connectionCost: Int = 0,
    val completionCost: Int = 0,
    val learningDiscount: Int = 0,
    val leftPos: Int? = null,
    val rightPos: Int? = null,
    val choice: LearnedChoice? = null,
    val continuation: LearnedContinuation? = null,
    val context: String? = null,
) {
    val addedReadingLength get() = reading.length - matchedLength
    val cost get() = wordCost?.let { it + connectionCost + completionCost }
}

data class Candidate(
    val text: String,
    val reading: String,
    val kind: CandidateKind = CandidateKind.WORD,
    val evidence: CandidateEvidence? = null,
    val alternatives: List<CandidateEvidence> = emptyList(),
) {
    fun directlyMatches(input: String): Boolean =
        Personalization.normalizeComposition(input).lowercase().let { key ->
            if (evidence == null) (kind == CandidateKind.WORD || kind == CandidateKind.SYMBOL) &&
                Personalization.normalizeComposition(reading).lowercase() == key
            else (evidence.reason.direct && evidence.reading == key) || alternatives.any { it.reason.direct && it.reading == key }
        }

    fun withAlternative(other: Candidate): Candidate {
        val newEvidence = listOfNotNull(other.evidence) + other.alternatives
        return withEvidence(newEvidence)
    }

    fun withEvidence(extra: List<CandidateEvidence>): Candidate {
        val added = extra.filter { it != evidence && it !in alternatives }
        return if (added.isEmpty()) this else copy(alternatives = alternatives + added)
    }
}

/** Keep display order, preferring the full match for commitment and learning. */
fun mergeCandidates(candidates: Iterable<Candidate>): List<Candidate> {
    val unique = LinkedHashMap<String, Candidate>()
    for (candidate in candidates) {
        val previous = unique[candidate.text]
        unique[candidate.text] = when {
            previous == null -> candidate
            previous.evidence?.reason?.direct != true && candidate.evidence?.reason?.direct == true ->
                candidate.withAlternative(previous)
            else -> previous.withAlternative(candidate)
        }
    }
    return unique.values.toList()
}
interface ConversionEngine {
    fun candidates(reading: String, prediction: Boolean, limit: Int): List<Candidate>
}

/** Deliberately small offline fallback. Swap this interface for a full native Mozc adapter. */
class LocalConversionEngine(private val user: () -> Map<String, List<String>> = { emptyMap() }) : ConversionEngine {
    private val words = linkedMapOf(
        "きょう" to listOf("今日","京都","教"), "きょうは" to listOf("今日は"), "きょうと" to listOf("京都"),
        "にほんご" to listOf("日本語"), "にほん" to listOf("日本","二本"), "あした" to listOf("明日"), "きのう" to listOf("昨日"),
        "わたし" to listOf("私"), "ぼく" to listOf("僕"), "あなた" to listOf("貴方"), "かれ" to listOf("彼"),
        "とうきょう" to listOf("東京"), "おおさか" to listOf("大阪"), "なごや" to listOf("名古屋"), "ふくおか" to listOf("福岡"),
        "てんき" to listOf("天気"), "いい" to listOf("良い"), "はれ" to listOf("晴れ"), "あめ" to listOf("雨","飴"),
        "べんきょう" to listOf("勉強"), "がっこう" to listOf("学校"), "がくせい" to listOf("学生"), "せんせい" to listOf("先生"),
        "しごと" to listOf("仕事"), "かいしゃ" to listOf("会社"), "かいぎ" to listOf("会議"), "じかん" to listOf("時間"),
        "でんわ" to listOf("電話"), "でんしゃ" to listOf("電車"), "えき" to listOf("駅"), "くるま" to listOf("車"),
        "なまえ" to listOf("名前"), "じゅうしょ" to listOf("住所"), "めーる" to listOf("メール"), "にゅうりょく" to listOf("入力"),
        "へんかん" to listOf("変換"), "せってい" to listOf("設定"), "こうしん" to listOf("更新"), "ほぞん" to listOf("保存"),
        "かくにん" to listOf("確認"), "けんさく" to listOf("検索"), "ひょうじ" to listOf("表示"), "ひひょうじ" to listOf("非表示"),
        "もの" to listOf("物"), "ひと" to listOf("人"), "いま" to listOf("今"), "まえ" to listOf("前"), "あと" to listOf("後"),
        "おはよう" to listOf("お早う"), "ありがとう" to listOf("有難う"), "よろしく" to listOf("宜しく"), "おねがい" to listOf("お願い"),
        "りょうかい" to listOf("了解"), "だいじょうぶ" to listOf("大丈夫"), "たのしい" to listOf("楽しい"),
        "おもしろい" to listOf("面白い"), "すき" to listOf("好き"), "だいすき" to listOf("大好き"),
        "たべる" to listOf("食べる"), "たべます" to listOf("食べます"), "のむ" to listOf("飲む"), "いく" to listOf("行く"),
        "みる" to listOf("見る"), "かく" to listOf("書く","各"), "よむ" to listOf("読む"), "きく" to listOf("聞く"),
        "ねこ" to listOf("猫"), "いぬ" to listOf("犬"), "やま" to listOf("山"), "かわ" to listOf("川","皮"),
        "はな" to listOf("花","鼻"), "つき" to listOf("月"), "ほし" to listOf("星"), "そら" to listOf("空"),
        "ひ" to listOf("日","火"), "みず" to listOf("水"), "き" to listOf("木","気"), "かね" to listOf("金","鐘"),
        "げつようび" to listOf("月曜日"), "かようび" to listOf("火曜日"), "すいようび" to listOf("水曜日"),
        "もくようび" to listOf("木曜日"), "きんようび" to listOf("金曜日"), "どようび" to listOf("土曜日"), "にちようび" to listOf("日曜日"),
        "らいしゅう" to listOf("来週"), "こんしゅう" to listOf("今週"), "らいねん" to listOf("来年"), "ことし" to listOf("今年"),
        "たんじょうび" to listOf("誕生日"), "おめでとう" to listOf("おめでとう"), "ゆうびん" to listOf("郵便"),
        "ばんごう" to listOf("番号"), "ほんやく" to listOf("翻訳"), "えいご" to listOf("英語"), "かな" to listOf("仮名"),
        "かんじ" to listOf("漢字","感じ"), "じしょ" to listOf("辞書"), "へんしゅう" to listOf("編集"), "にゅうりょくちゅう" to listOf("入力中")
    )
    override fun candidates(reading: String, prediction: Boolean, limit: Int): List<Candidate> {
        if (reading.isEmpty()) return emptyList()
        val custom = user()
        val dict = words + custom
        val results = dict[reading].orEmpty().map {
            val reason = if (reading in custom) CandidateReason.USER_DICTIONARY else CandidateReason.DICTIONARY_EXACT
            Candidate(it, reading, evidence = CandidateEvidence(reason,
                if (reading in custom) CandidateSource.USER_DICTIONARY else CandidateSource.FALLBACK, reading))
        }.toMutableList()
        val sentence = buildString {
            var i = 0
            val keys = dict.keys.sortedByDescending { it.length }
            while (i < reading.length) {
                val key = keys.firstOrNull { reading.startsWith(it, i) }
                if (key != null) { append(dict.getValue(key).first()); i += key.length }
                else { append(reading[i]); i++ }
            }
        }
        if (sentence!=reading && reading.length>2) results += Candidate(sentence,reading,evidence =
            CandidateEvidence(CandidateReason.SEGMENTED,CandidateSource.FALLBACK,reading))
        if (prediction) dict.filterKeys { it.startsWith(reading) && it!=reading }.forEach { (key,values) ->
            val reason = if (key in custom) CandidateReason.USER_PREFIX else CandidateReason.PREFIX
            results += values.map { Candidate(it,key,evidence = CandidateEvidence(reason,
                if (key in custom) CandidateSource.USER_DICTIONARY else CandidateSource.FALLBACK,key,reading.length)) }
        }
        val maximum = limit.coerceIn(2,10000)
        val literals = listOf(Candidate(reading,reading),Candidate(SemanticConversion.katakana(reading),reading))
        return mergeCandidates(mergeCandidates(results).take(maxOf(1,maximum-2)) + literals).take(maximum)
    }
}

object EnglishPrediction {
    private val words = ("hello help helmet here her hey happy have has how house home hope hotel good great green give going game get " +
        "morning meeting message my make many may music more most much must new next nice night no now not number need " +
        "one only open our out over please play phone people plan project question quick read right release really return " +
        "see send she should some sorry sound start stop setting small system thank thanks that the there their these they this " +
        "think time today tomorrow together translate update use user very version view want water way we well welcome what when " +
        "where which who why will with work would world write yes yesterday you your keyboard know language look love like input " +
        "install information into is it in I can could come check change call back be because before build but by day do download " +
        "done does did each easy English everything first for from friend find file full after again all also always an and any app are as at").split(' ')
    fun candidates(prefix: String, limit: Int) = if (prefix.isBlank()) emptyList() else words.filter { it.startsWith(prefix, true) }.take(limit).map {
        if (prefix.all(Char::isUpperCase)) it.uppercase() else if (prefix.first().isUpperCase()) it.replaceFirstChar(Char::uppercase) else it
    }
}
