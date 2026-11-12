package io.github.dualkey.core

/** Offline word equivalents. Japanese lookup data never becomes a network request. */
object SemanticConversion {
    fun katakana(reading: String) = reading.map { if (it in 'ぁ'..'ゖ') it+0x60 else it }.joinToString("")

    // Current names and common readings that may be missing from the bundled JMdict snapshot.
    private val spellingAliases = mapOf(
        "Windows" to "うぃんどうず ういんどうず うぃんどーず ういんどーず",
        "Microsoft" to "まいくろそふと",
        "Google" to "ぐーぐる",
        "Android" to "あんどろいど",
        "iPhone" to "あいふぉん",
        "Bluetooth" to "ぶるーとぅーす ぶるーとーす",
        "ChatGPT" to "ちゃっとじーぴーてぃー ちゃっとじーぴーてぃ",
        "GitHub" to "ぎっとはぶ",
        "YouTube" to "ゆーちゅーぶ",
        "JavaScript" to "じゃばすくりぷと",
        "TypeScript" to "たいぷすくりぷと",
    ).flatMap { (spelling, readings) -> readings.split(' ').map { it to spelling } }.toMap()

    fun loanwordSpellings(reading: String, japanese: String, lexicon: JapaneseLexicon?): List<Candidate> {
        val key = Personalization.normalize(reading)
        spellingAliases[key]?.let { spelling ->
            return listOf(Candidate(spelling, reading, evidence =
                CandidateEvidence(CandidateReason.LOANWORD_SPELLING, CandidateSource.LOANWORD_ALIAS, reading)))
        }
        if (lexicon == null || key.length !in 3..80 || japanese != katakana(key) ||
            japanese.any { it !in 'ァ'..'ヶ' && it != 'ー' }) return emptyList()

        val meanings = lexicon.exact(key, 24)
        val firstCost = meanings.firstOrNull()?.cost ?: return emptyList()
        return meanings.asSequence().takeWhile { it.cost <= firstCost + 2 }.filter { word ->
            word.text.any { it in 'A'..'Z' } && word.text.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-'
            }
        }.take(2).map { word ->
            Candidate(word.text, reading, evidence =
                CandidateEvidence(CandidateReason.LOANWORD_SPELLING, CandidateSource.JMDICT, reading))
        }.toList()
    }

    fun equivalents(lexicon: JapaneseLexicon?, reading: String, japanese: String = reading): List<String> {
        if (reading.length !in 1..80) return emptyList()
        val spellings = loanwordSpellings(reading, japanese, lexicon).map { it.text }
        if (lexicon == null) return spellings
        val preferred=lexicon.exact(Personalization.normalize(japanese),24)
        val words = if (preferred.isNotEmpty()) preferred else lexicon.exact(Personalization.normalize(reading),24)
        return (spellings + words.map { it.text }).distinct()
    }
    fun english(lexicon: JapaneseLexicon?, reading: String, japanese: String) =
        equivalents(lexicon,reading,japanese).firstOrNull { value -> value.any { it in 'a'..'z' || it in 'A'..'Z' } }

    fun suggestions(reading: String, japanese: String, lexicon: JapaneseLexicon?): List<Candidate> {
        if (reading.none { it in 'ぁ'..'ゖ' }) return emptyList()
        // Candidate suggestions use the selected Japanese sense. A reading-only
        // fallback could surface another homophone (e.g. a noun for a number).
        val meanings = lexicon?.exact(Personalization.normalize(japanese), 24).orEmpty().map { it.text }.distinct()
        val katakana = meanings.filter { text -> text.any { it in 'ァ'..'ヶ' } }.take(4)
        val english = meanings.filter { text -> text.any { it in 'a'..'z' || it in 'A'..'Z' } }.take(8)
        return (katakana + english).distinct().map {
            Candidate(it, reading, CandidateKind.RELATED,
                CandidateEvidence(CandidateReason.MEANING, CandidateSource.JMDICT, reading, context = japanese))
        }
    }
}

enum class CompositionScript { KATAKANA, ENGLISH, HIRAGANA }

/** Immutable requests also let the IME discard a translation after another tap. */
data class CompositionVariant(val reading: String, val japanese: String, val script: CompositionScript,
    val text: String, val googlePowered: Boolean=false) {
    fun next(): CompositionVariant = when (script) {
        CompositionScript.KATAKANA -> copy(script=CompositionScript.ENGLISH,googlePowered=false)
        CompositionScript.ENGLISH -> copy(script=CompositionScript.HIRAGANA,text=reading,googlePowered=false)
        CompositionScript.HIRAGANA -> start(reading,japanese)
    }
    companion object {
        fun start(reading: String,japanese: String) = CompositionVariant(reading,japanese,CompositionScript.KATAKANA,SemanticConversion.katakana(reading))
    }
}
