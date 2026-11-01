package io.github.dualkey.core

data class LearnedContinuation(val context: String,val text: String,val reading: String,val count: Int,val lastUsed: Long)

/** Small local phrase model plus personal confirmed word pairs; no remote requests. */
object NextWordPrediction {
    private data class Rule(val endings: List<String>,val choices: List<Candidate>)
    private fun rule(endings: String,vararg words: Pair<String,String>) =
        Rule(endings.split('|'),words.map { Candidate(it.first,it.second) })
    private val rules=listOf(
        rule("続きを|つづきを|続き|つづき","お願いします" to "おねがいします","教えてください" to "おしえてください","見せてください" to "みせてください","聞かせてください" to "きかせてください"),
        rule("よろしく|宜しく","お願いします" to "おねがいします","お願いいたします" to "おねがいいたします"),
        rule("ありがとう|有難う","ございます" to "ございます","ございました" to "ございました"),
        rule("ありがとうございます|ありがとうございました|お願いします|お願いいたします|お疲れさまです|お疲れ様です","。" to "。","！" to "！","よろしくお願いします" to "よろしくおねがいします"),
        rule("こんにちは|こんばんは|おはようございます","。" to "。","よろしくお願いします" to "よろしくおねがいします","今日は" to "きょうは"),
        rule("お疲れ|おつかれ","さまです" to "さまです","さまでした" to "さまでした"),
        rule("お世話|おせわ","になっています" to "になっています","になります" to "になります"),
        rule("すみません|すいません","。" to "。","ありがとうございます" to "ありがとうございます","お願いします" to "おねがいします"),
        rule("お手数|おてすう","ですが" to "ですが","をおかけします" to "をおかけします"),
        rule("確認|かくにん","お願いします" to "おねがいします","しました" to "しました","してください" to "してください","します" to "します"),
        rule("教えて|おしえて|見せて|みせて|送って|おくって","ください" to "ください","もらえますか" to "もらえますか","いただけますか" to "いただけますか"),
        rule("了解|りょうかい|承知|しょうち","しました" to "しました","です" to "です","いたしました" to "いたしました"),
        rule("今日|きょう|明日|あした|昨日|きのう|来週|らいしゅう|今週|こんしゅう","は" to "は","の予定" to "のよてい","も" to "も","に" to "に"),
        rule("予定|よてい|時間|じかん","を教えてください" to "をおしえてください","はありますか" to "はありますか","を確認します" to "をかくにんします"),
        rule("誕生日|たんじょうび","おめでとう" to "おめでとう","おめでとうございます" to "おめでとうございます"),
        rule("日本語|にほんご|英語|えいご","で" to "で","を教えてください" to "をおしえてください","に翻訳してください" to "にほんやくしてください"),
        rule("アプリ|キーボード","の設定" to "のせってい","を更新" to "をこうしん","を使います" to "をつかいます"),
        rule("と思い|とおもい","ます" to "ます","ました" to "ました"),
        rule("です|ます|でした|ました|ください","。" to "。","！" to "！","か？" to "か？"),
        rule("を","お願いします" to "おねがいします","教えてください" to "おしえてください","見せてください" to "みせてください","確認してください" to "かくにんしてください","送ってください" to "おくってください","ください" to "ください"),
        rule("には","どうですか" to "どうですか","ありますか" to "ありますか","必要ですか" to "ひつようですか"),
        rule("は","どうですか" to "どうですか","ありますか" to "ありますか","できますか" to "できますか","こちらです" to "こちらです","大丈夫です" to "だいじょうぶです"),
        rule("が","あります" to "あります","できます" to "できます","好きです" to "すきです","必要です" to "ひつようです","ありません" to "ありません"),
        rule("に","します" to "します","行きます" to "いきます","なります" to "なります","ついて" to "ついて","お願いします" to "おねがいします"),
        rule("で","お願いします" to "おねがいします","大丈夫です" to "だいじょうぶです","できます" to "できます","確認します" to "かくにんします"),
        rule("の","予定" to "よてい","こと" to "こと","ために" to "ために","ように" to "ように","続き" to "つづき"),
        rule("と","思います" to "おもいます","申します" to "もうします","一緒に" to "いっしょに","同じです" to "おなじです"),
        rule("も","お願いします" to "おねがいします","できます" to "できます","大丈夫です" to "だいじょうぶです","あります" to "あります"),
        rule("へ","行きます" to "いきます","向かいます" to "むかいます","送ります" to "おくります"),
        rule("から","お願いします" to "おねがいします","始めます" to "はじめます","です" to "です"),
        rule("まで","お願いします" to "おねがいします","です" to "です","に" to "に"),
        rule("、","また" to "また","そして" to "そして","よろしくお願いします" to "よろしくおねがいします")
    )
    fun validContext(text: String) = text.length in 1..80 && text.any { it.isLetter() } &&
        text.none { it.isISOControl() || it.isDigit() } && !text.contains('@') && !text.contains("://")
    fun valid(choice: LearnedContinuation) = validContext(choice.context) &&
        Personalization.valid(choice.reading,choice.text) && choice.count>0

    fun candidates(context: Candidate,history: List<LearnedContinuation>,limit: Int): List<Candidate> {
        if (!validContext(context.text)) return emptyList()
        val learned=history.asSequence().filter { it.context==context.text && valid(it) }
            .sortedWith(compareByDescending<LearnedContinuation> { it.count }.thenByDescending { it.lastUsed }.thenBy { it.text })
            .map { Candidate(it.text,it.reading) }.toList()
        val matching=rules.mapNotNull { rule ->
            rule.endings.filter { context.text.endsWith(it) || context.reading.endsWith(it) }
                .maxOfOrNull { it.length }?.let { it to rule.choices }
        }.sortedByDescending { it.first }.flatMap { it.second }
        return (learned+matching).distinctBy { it.text }.take(limit.coerceIn(2,1000))
    }
}
