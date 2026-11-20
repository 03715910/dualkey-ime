package io.github.dualkey.core

/** Bounded N-best lattice search over the bundled vocabulary and POS connection costs.
 * This independent Kotlin converter uses Mozc OSS data, not the native Mozc engine.
 */
class DictionaryConversionEngine(
    private val lexicon: JapaneseLexicon,
    private val user: () -> Map<String, List<String>> = { emptyMap() }
) : ConversionEngine {
    companion object {
        const val MAX_RECONVERSION_LENGTH = JapaneseReconversion.MAX_LENGTH
    }

    private val known=object: LinkedHashMap<String,Set<String>>(64,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Set<String>>?)=size>64
    }
    private data class Path(
        val text: String, val right: Int, val wordCost: Int, val connectionCost: Int = 0,
        val unknown: Boolean = false, val weak: Boolean = false,
        val learningDiscount: Int = 0, val left: Int = 0,
        val startsWithParticle: Boolean = false, val styleCost: Int = 0,
        val supplemental: Boolean = false,
    ) {
        val rankCost get() = wordCost + connectionCost + styleCost - learningDiscount
    }

    private fun best(paths: List<Path>, limit: Int): List<Path> {
        val seen = HashSet<Pair<String, Int>>()
        val spellings = HashMap<String, Int>()
        return paths.sortedBy { it.rankCost + lexicon.transition(it.right, 0) }.filter {
            val count = spellings[it.text] ?: 0
            if (count >= 3 || !seen.add(it.text to it.right)) false
            else { spellings[it.text] = count + 1; true }
        }.take(limit)
    }

    private fun bestParents(paths: List<Path>, left: Int): List<Path> {
        if (paths.size < 2) return paths
        val best = ArrayList<Path>(3)
        val costs = IntArray(3)
        for (path in paths) {
            val cost = path.rankCost + lexicon.transition(path.right, left)
            val position = (0 until best.size).firstOrNull { cost < costs[it] } ?: best.size
            if (position == 3) continue
            val duplicate = best.indexOfFirst { it.text == path.text }
            if (duplicate in 0 until position) continue
            if (duplicate >= 0) best.removeAt(duplicate)
            else if (best.size == 3) best.removeAt(2)
            for (index in (duplicate.takeIf { it >= 0 } ?: best.size) downTo position + 1) {
                costs[index] = costs[index - 1]
            }
            best.add(position, path)
            costs[position] = cost
        }
        return best
    }

    override fun candidates(reading: String, prediction: Boolean, limit: Int): List<Candidate> =
        JapaneseCandidates.generate(reading, prediction, limit, this).all

    private fun inflectedSpellingPenalty(reading: String, word: LexiconWord,
        lemmas: MutableMap<String, List<LexiconWord>>): Int {
        if (!lexicon.hasBundledPos) return 0
        // These form IDs come from the bundled Mozc id.def. A negative stem
        // must not make a rare spelling cheaper than its own dictionary form.
        val (basicIds, ending) = when (word.left) {
            in 694..700 -> (680..686) to "る"
            715 -> (713..713) to "る"
            725, 733, 741 -> (word.left - 2..word.left - 2) to "く"
            in 771..774 -> (763..766) to "る"
            793 -> (791..791) to "る"
            801 -> (799..799) to "う"
            in 821..824 -> (813..816) to "う"
            839 -> (837..837) to when (reading.lastOrNull()) {
                'か' -> "く"
                'が' -> "ぐ"
                'さ' -> "す"
                'た' -> "つ"
                'な' -> "ぬ"
                'ば' -> "ぶ"
                'ま' -> "む"
                'ら' -> "る"
                'わ' -> "う"
                else -> return 0
            }
            else -> return 0
        }
        val remove = if (word.left in 694..700 || word.left == 715) 0 else 1
        if (word.text.none { it in '一'..'龯' } || reading.length <= remove || word.text.length <= remove ||
            remove == 1 && word.text.last() != reading.last()) return 0
        val basicReading = reading.dropLast(remove) + ending
        val basicText = word.text.dropLast(remove) + ending
        val cost = lemmas.getOrPut(basicReading) { lexicon.exact(basicReading) }
            .filter { it.left in basicIds && it.text == basicText }.minOfOrNull { it.cost } ?: return 0
        return (cost - word.cost).coerceAtLeast(0)
    }

    internal fun search(input: String, prediction: Boolean, count: Int, history: Map<String, Map<String, LearnedChoice>>,
        now: Long, semantic: JapaneseLexicon? = null): List<Candidate> {
        if (input.isEmpty() || input.length > 160) return emptyList()
        val dictionary = user()
        val result = ArrayList<Candidate>()
        val source = CandidateSource.JAPANESE_LEXICON
        fun addWord(word: LexiconWord, reason: CandidateReason, reading: String = input, prefix: Path? = null,
            addedLength: Int = 0, origin: CandidateSource = source) {
            val wordCost = (prefix?.wordCost ?: 0) + word.cost
            val connection = (prefix?.connectionCost ?: 0) + lexicon.transition(prefix?.right ?: 0, word.left) +
                lexicon.transition(word.right, 0)
            // Keep the raw dictionary and connection costs separate from completion and learning.
            val completion = if (addedLength == 0) 0 else addedLength * 90
            result += Candidate((prefix?.text ?: "") + word.text, reading, CandidateKind.WORD,
                CandidateEvidence(reason, origin, reading, input.length, wordCost, connection, completion,
                    leftPos = if (prefix == null || prefix.text.isEmpty()) word.left else prefix.left, rightPos = word.right,
                    styleCost = prefix?.styleCost ?: 0))
        }
        // Read exact entries independently of the lattice beam and prefix lookup budgets.
        lexicon.exact(input, 10000).forEach { addWord(it, CandidateReason.DICTIONARY_EXACT) }
        dictionary[input].orEmpty().forEach { text ->
            result += Candidate(text, input, evidence = CandidateEvidence(CandidateReason.USER_DICTIONARY, CandidateSource.USER_DICTIONARY, input))
        }

        val states = Array(input.length + 1) { ArrayList<Path>() }
        val lemmas = HashMap<String, List<LexiconWord>>()
        states[0].add(Path("", 0, 0))
        val beam = 96
        val containsDigits = input.any(Char::isDigit)
        for (start in input.indices) {
            if (start > 0 && Character.isLowSurrogate(input[start]) && Character.isHighSurrogate(input[start - 1])) continue
            val prefixes = best(states[start], beam)
            states[start].clear(); states[start].addAll(prefixes)
            val matches = lexicon.prefixes(input, start).toMutableList()
            if (lexicon.hasBundledPos) {
                val loans = SemanticConversion.loanwordMatches(input, start, semantic)
                if (loans.isNotEmpty()) {
                    val spellings = matches.map { it.end to it.word.text }.toHashSet()
                    val additional = loans.filter { (it.end to it.word.text) !in spellings }
                    if (start == 0) additional.filter { it.end == input.length }.forEach {
                        addWord(it.word, CandidateReason.DICTIONARY_EXACT, origin = it.source)
                    }
                    matches.addAll(additional)
                }
            }
            dictionary.forEach { (key, values) ->
                if (key.isNotEmpty() && input.startsWith(key, start)) values.forEachIndexed { index, value ->
                    matches.add(LexiconMatch(start + key.length, LexiconWord(value, 1851, 1851, -3500 + index * 80)))
                }
            }
            val codePoint = Character.codePointAt(input, start)
            val end = start + Character.charCount(codePoint)
            val literal = input.substring(start, end)
            val punctuationWord = if (literal[0] in Personalization.literalPunctuation) {
                // Use the indexed punctuation's connection cost with the original spelling.
                val normalized = Personalization.normalize(literal)
                lexicon.exact(normalized).firstOrNull { it.text == normalized }?.copy(text = literal)
            } else null
            // Symbols and spaces can be carried through verbatim. They do not
            // make the recognized Japanese on either side an incomplete reading.
            matches.add(if (punctuationWord != null) LexiconMatch(end, punctuationWord)
                else LexiconMatch(end, LexiconWord(literal, 1851, 1851, 10000), Character.isLetter(codePoint)))
            // A composing span can start in the middle of a sentence. Preserve
            // hiragana function words where the dictionary attests that reading.
            val functionWordEnds = matches.asSequence().filter { match ->
                lexicon.hasBundledPos && (match.word.left in 29..433 || match.word.left in 2593..2595) &&
                    match.word.text.any { it in 'ぁ'..'ゖ' } && input.substring(start, match.end) == match.word.text
            }.map { it.end }.toSet()
            val parentsByLeft = HashMap<Int, List<Path>>()
            for ((finish, word, unknown, origin) in matches) {
                if (start == 0 && finish == input.length) continue // already collected by exact lookup
                val parents = parentsByLeft.getOrPut(word.left) { bestParents(prefixes, word.left) }
                val reading = input.substring(start, finish)
                val discount = Personalization.strength(history[reading]?.get(word.text), now) * 100
                val startsWithParticle = start == 0 && lexicon.hasBundledPos && word.left in 268..433
                val strayDigit = !containsDigits && start > 0 && word.text.all(Char::isDigit) &&
                    lexicon.hasBundledPos && word.left in 2043..2055
                // Reserve stylized spellings such as ノ and デス as alternatives
                // when the same span has a normal grammatical spelling.
                val styleCost = (if (finish in functionWordEnds && word.text != reading &&
                    word.text == SemanticConversion.katakana(reading)) 3000 else 0) +
                    inflectedSpellingPenalty(reading, word, lemmas)
                for (parent in parents) {
                    states[finish].add(Path(parent.text + word.text, word.right, parent.wordCost + word.cost,
                        parent.connectionCost + lexicon.transition(parent.right, word.left), parent.unknown || unknown,
                        parent.weak || strayDigit,
                        (parent.learningDiscount + discount).coerceAtMost(400), if (start == 0) word.left else parent.left,
                        parent.startsWithParticle || startsWithParticle, parent.styleCost + styleCost,
                        parent.supplemental || origin == CandidateSource.JMDICT))
                }
                if (states[finish].size > 1800) {
                    val compact = best(states[finish], beam * 2)
                    states[finish].clear(); states[finish].addAll(compact)
                }
            }
        }
        for (path in best(states.last(), beam * 2)) {
            val weak = path.weak || path.unknown || input.length <= 2
            val reason = if (weak) CandidateReason.WEAK_SEGMENT else CandidateReason.SEGMENTED
            val connection = path.connectionCost + lexicon.transition(path.right, 0)
            result += Candidate(path.text, input, if (weak) CandidateKind.RELATED else CandidateKind.WORD,
                CandidateEvidence(reason, if (path.supplemental) CandidateSource.JMDICT else source,
                    input, wordCost = path.wordCost, connectionCost = connection,
                    learningDiscount = path.learningDiscount, leftPos = path.left, rightPos = path.right, styleCost = path.styleCost))
        }
        if (!prediction) return result

        // Short readings still get whole-input completions, including user dictionary words.
        // A tail needs two typed kana on both sides and an established, non-particle parent.
        val starts = listOf(0) + (maxOf(2, input.length - 10)..input.length - 2)
        for (start in starts) {
            val suffix = input.substring(start)
            if (Character.isLowSurrogate(suffix[0])) continue
            val parents = best(states[start].filter { !it.unknown && !it.weak && !it.startsWithParticle }, 3)
            if (parents.isEmpty()) continue
            val reason = if (start == 0) CandidateReason.PREFIX else CandidateReason.TAIL
            val groups = if (start == 0) maxOf(64, minOf(1024, count * 2)) else maxOf(24, minOf(64, count / 4))
            for (group in lexicon.predictiveGroups(suffix, groups)) {
                val fullReading = lexicon.reading(group)
                if (fullReading == suffix) continue
                val added = fullReading.length - suffix.length
                val reading = input.substring(0, start) + fullReading
                for (word in lexicon.words(group, 12)) {
                    val parent = parents.minBy { it.rankCost + lexicon.transition(it.right, word.left) }
                    addWord(word, reason, reading, parent, added)
                }
            }
            dictionary.forEach { (fullReading, values) ->
                if (fullReading.startsWith(suffix) && fullReading != suffix) {
                    val reading = input.substring(0, start) + fullReading
                    for (value in values) {
                        result += Candidate(parents.first().text + value, reading, evidence = CandidateEvidence(
                            if (start == 0) CandidateReason.USER_PREFIX else CandidateReason.TAIL,
                            CandidateSource.USER_DICTIONARY, reading, input.length))
                    }
                }
            }
        }
        return result
    }

    fun knownChoice(reading: String,text: String,semantic: JapaneseLexicon?=null): Boolean =
        text in user()[reading].orEmpty() || synchronized(known) {
        text in known.getOrPut(reading) { lexicon.exact(reading,10000).map { it.text }.toSet() }
    } || SemanticConversion.loanwordMatches(reading,0,semantic).any { it.end==reading.length && it.word.text==text }

    fun reconvert(text: String, generate: (String) -> List<Candidate>): List<Candidate> =
        JapaneseReconversion.candidates(text, lexicon, user(), generate)

    /** Retain dictionary units only; arbitrary literal/unknown edges are never learned. */
    fun learningUnits(reading: String,text: String,semantic: JapaneseLexicon?=null): List<Candidate> {
        val input=Personalization.normalize(reading)
        if (input.length !in 1..160 || text.length !in 1..160) return emptyList()
        if (input.length<=32 && text.length<=32 && knownChoice(input,text,semantic)) return listOf(Candidate(text,input))
        data class UnitPath(val surface: Int,val units: List<Candidate>,val cost: Int)
        val states=Array(input.length+1) { mutableListOf<UnitPath>() }
        states[0].add(UnitPath(0,emptyList(),0))
        val custom=user()
        for (start in input.indices) {
            if (states[start].isEmpty()) continue
            val matches=lexicon.prefixes(input,start).toMutableList()
            if (lexicon.hasBundledPos) matches.addAll(SemanticConversion.loanwordMatches(input,start,semantic))
            custom.forEach { (r,values) -> if (r.isNotEmpty() && input.startsWith(r,start)) values.forEach { matches.add(LexiconMatch(start+r.length,LexiconWord(it,0,0,-3500))) } }
            val parents=states[start].sortedBy { it.cost }.distinctBy { it.surface }.take(8)
            for (match in matches) {
                val r=input.substring(start,match.end); val t=match.word.text
                if (r.length>32 || t.isEmpty() || t.length>32) continue
                for (parent in parents) if (parent.units.size<16 && text.startsWith(t,parent.surface)) {
                    states[match.end].add(UnitPath(parent.surface+t.length,parent.units+Candidate(t,r),parent.cost+match.word.cost+200))
                }
            }
        }
        return states.last().filter { it.surface==text.length }.minByOrNull { it.cost }?.units.orEmpty()
    }
}
