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

    /** Reserve a small tail for equivalents; preserve the leading Japanese order. */
    fun augment(reading: String, base: List<Candidate>, lexicon: JapaneseLexicon?, limit: Int): List<Candidate> {
        val maximum=limit.coerceIn(2,10000)
        if (reading.none { it in 'ぁ'..'ゖ' }) return base.take(maximum)
        val japanese=base.firstOrNull { it.reading==reading }?.text ?: reading
        val meanings=equivalents(lexicon,reading,japanese)
        val additions=(listOf(katakana(reading)) + meanings.filter { it.any { c -> c in 'ァ'..'ヶ' } } +
            meanings.filter { it.any { c -> c in 'a'..'z' || c in 'A'..'Z' } })
            .distinct().filter { value -> base.take(8).none { it.text==value } }.take(minOf(8,maximum/3))
        if (additions.isEmpty()) return base.take(maximum)
        val extraSet=additions.toSet()
        val leading=base.filterNot { it.text in extraSet }.take(maximum-additions.size)
        return leading + additions.map { value -> base.firstOrNull { it.text==value }
            ?: Candidate(value,reading,if (value==katakana(reading)) CandidateKind.WORD else CandidateKind.RELATED) }
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
