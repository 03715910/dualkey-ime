package io.github.dualkey.core

/** Offline word equivalents. Japanese lookup data never becomes a network request. */
object SemanticConversion {
    fun katakana(reading: String) = reading.map { if (it in 'ぁ'..'ゖ') it+0x60 else it }.joinToString("")
    fun equivalents(lexicon: JapaneseLexicon?, reading: String, japanese: String = reading): List<String> {
        if (lexicon==null || reading.length !in 1..80) return emptyList()
        val preferred=lexicon.exact(Personalization.normalize(japanese),24)
        return (if (preferred.isNotEmpty()) preferred else lexicon.exact(Personalization.normalize(reading),24))
            .map { it.text }.distinct()
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
