package io.github.dualkey.core

import java.text.Normalizer

/** Bounded N-best lattice search over the bundled vocabulary and POS connection costs.
 * This independent Kotlin converter uses Mozc OSS data, not the native Mozc engine.
 */
class DictionaryConversionEngine(
    private val lexicon: JapaneseLexicon,
    private val user: () -> Map<String, List<String>> = { emptyMap() }
) : ConversionEngine {
    private data class Path(val text: String, val right: Int, val cost: Int, val unknown: Boolean = false)
    private data class Result(val text: String, val cost: Int, val reading: String)

    private fun best(paths: List<Path>, limit: Int): List<Path> {
        val seen = HashSet<Pair<String, Int>>()
        val spellings = HashMap<String, Int>()
        return paths.sortedBy { it.cost + lexicon.transition(it.right, 0) }.filter {
            val count = spellings[it.text] ?: 0
            if (count >= 3 || !seen.add(it.text to it.right)) false
            else { spellings[it.text] = count + 1; true }
        }.take(limit)
    }
    private fun normalize(reading: String): String = Normalizer.normalize(reading, Normalizer.Form.NFKC)
        .map { if (it in 'ァ'..'ヶ') it - 0x60 else it }.joinToString("")

    override fun candidates(reading: String, prediction: Boolean, limit: Int): List<Candidate> {
        if (reading.isEmpty()) return emptyList()
        val count = limit.coerceIn(2, 1000)
        val input = normalize(reading)
        // Keep unusual very long input editable; never search an unbounded lattice on a key press.
        if (input.length > 160) return listOf(Candidate(reading, reading), Candidate(katakana(input), reading)).distinctBy { it.text }
        val dictionary = user()
        val states = Array(input.length + 1) { ArrayList<Path>() }
        states[0].add(Path("", 0, 0))
        // More displayed words must not multiply sentence-search CPU/memory use.
        val beam = 96
        for (start in input.indices) {
            if (Character.isLowSurrogate(input[start]) && start > 0 && Character.isHighSurrogate(input[start - 1])) continue
            val prefixes = best(states[start], beam)
            states[start].clear(); states[start].addAll(prefixes)
            val matches = lexicon.prefixes(input, start).toMutableList()
            dictionary.forEach { (key, values) ->
                if (key.isNotEmpty() && input.startsWith(key, start)) values.forEachIndexed { index, value ->
                    matches.add(LexiconMatch(start + key.length, LexiconWord(value, 1851, 1851, -3500 + index * 80)))
                }
            }
            // A literal edge preserves unknown characters, digits, emoji and mixed input.
            val end = start + Character.charCount(Character.codePointAt(input, start))
            matches.add(LexiconMatch(end, LexiconWord(input.substring(start, end), 1851, 1851, 10000), true))
            for ((finish, word, unknown) in matches) {
                val parents = prefixes.sortedBy { it.cost + lexicon.transition(it.right, word.left) }
                    .distinctBy { it.text }.take(3)
                for (parent in parents) {
                    states[finish].add(Path(parent.text + word.text, word.right,
                        parent.cost + lexicon.transition(parent.right, word.left) + word.cost,
                        parent.unknown || unknown))
                }
                if (states[finish].size > 1800) {
                    val compact = best(states[finish], beam * 2)
                    states[finish].clear(); states[finish].addAll(compact)
                }
            }
        }
        val converted = best(states[input.length], beam * 2)
            .map { Result(it.text, it.cost + lexicon.transition(it.right, 0), input) }
        val result = ArrayList<Result>(); result.addAll(converted)
        // Direct entries keep rare single kanji reachable even outside the beam.
        lexicon.exact(input,2000).forEach { word -> result.add(Result(word.text,
            word.cost+lexicon.transition(0,word.left)+lexicon.transition(word.right,0),input)) }
        if (prediction) {
            val starts = (listOf(0) + (maxOf(1, input.length - 10) until input.length).toList()).distinct()
            for (start in starts) {
                val suffix = input.substring(start)
                if (Character.isLowSurrogate(suffix[0])) continue
                val parents = best(states[start].filter { !it.unknown }, 3)
                if (parents.isEmpty()) continue
                for (group in lexicon.predictiveGroups(suffix, if (start == 0) maxOf(64,count*2) else maxOf(24,count/4))) {
                    val fullReading = lexicon.reading(group)
                    if (fullReading == suffix) continue
                    for (word in lexicon.words(group, 12)) {
                        val parent = parents.minBy { it.cost + lexicon.transition(it.right, word.left) }
                        result.add(Result(parent.text + word.text, parent.cost +
                            lexicon.transition(parent.right, word.left) + word.cost +
                            lexicon.transition(word.right, 0) + 550 + (fullReading.length - suffix.length) * 90,
                            input.substring(0,start)+fullReading))
                    }
                }
                dictionary.filterKeys { it.startsWith(suffix) && it != suffix }.forEach { (fullReading, values) ->
                    values.forEachIndexed { i, value -> result.add(Result(parents.first().text + value, parents.first().cost - 3000 + i * 80,input.substring(0,start)+fullReading)) }
                }
            }
        }
        val ranked = result.sortedBy { it.cost }.map { Candidate(it.text,it.reading) }
        val preferred = dictionary[input].orEmpty().map { Candidate(it,input) }
        return ((preferred+ranked).distinctBy { it.text }.filter { it.text!=input && it.text!=katakana(input) }
            .take(maxOf(1,count-2)) + listOf(Candidate(input,input),Candidate(katakana(input),input)))
            .distinctBy { it.text }.take(count)
    }

    private fun katakana(text: String) = text.map { if (it in 'ぁ'..'ゖ') it + 0x60 else it }.joinToString("")
}
