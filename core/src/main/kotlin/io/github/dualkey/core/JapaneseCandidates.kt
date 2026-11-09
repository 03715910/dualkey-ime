package io.github.dualkey.core

data class CandidateLists(
    val all: List<Candidate> = emptyList(),
    val exact: List<Candidate> = emptyList(),
    val emoji: List<Candidate> = emptyList(),
    val face: List<Candidate> = emptyList(),
)

/** Separates dictionary ranking from presentation, where each source has its own budget. */
object JapaneseCandidates {
    fun generate(reading: String, prediction: Boolean, limit: Int, engine: ConversionEngine,
        history: List<LearnedChoice> = emptyList(), continuations: List<LearnedContinuation> = emptyList(),
        semantic: JapaneseLexicon? = null, symbols: Map<String, List<String>> = emptyMap(),
        expressions: ExpressionCandidates = ExpressionCandidates(), emoji: Boolean = true, face: Boolean = true,
        context: Candidate? = null, now: Long = System.currentTimeMillis(),
    ): CandidateLists {
        val key = Personalization.normalizeComposition(reading)
        if (key.isEmpty()) return CandidateLists()
        val count = limit.coerceIn(2, 10000)
        val choices = history.asSequence().filter { it.reading.isNotEmpty() && key.contains(it.reading) }
            .groupBy { it.reading }.mapValues { (_, values) -> values.associateBy { it.text } }
        val converted = if (engine is DictionaryConversionEngine) engine.search(key, prediction, count, choices, now)
            else engine.candidates(key, prediction, count)
        val byReason = converted.groupBy { it.evidence?.reason }
        fun group(reason: CandidateReason) = byReason[reason].orEmpty()
        fun rankCost(candidate: Candidate): Int = candidate.evidence?.let { (it.cost ?: 0) - it.learningDiscount } ?: 0
        val byCost = compareBy(::rankCost)
        val kata = SemanticConversion.katakana(key)
        val literals = mergeCandidates(listOf(
            Candidate(kata, key, evidence = CandidateEvidence(CandidateReason.KATAKANA, CandidateSource.INPUT, key)),
            Candidate(key, key, evidence = CandidateEvidence(CandidateReason.LITERAL, CandidateSource.INPUT, key)),
        ))
        val literalSurfaces = literals.map { it.text }.toSet()
        val phrases = PhrasePrediction.candidates(key, prediction)
        val counters = expressions.words[key].orEmpty().map {
            Candidate(it, key, evidence = CandidateEvidence(CandidateReason.COUNTER, CandidateSource.EXPRESSION, key))
        }
        val trusted = mergeCandidates(group(CandidateReason.USER_DICTIONARY) + counters + phrases.filter { it.reading == key })
        val matched = mergeCandidates((group(CandidateReason.DICTIONARY_EXACT) + group(CandidateReason.SEGMENTED))
            .sortedWith(byCost))
        val contextual = context?.let { next(it, continuations, engine, semantic, 64, now) }.orEmpty()
            .filter { it.reading == key || prediction && it.reading.startsWith(key) }
        val historyForKey = choices[key].orEmpty()
        val evidenceBySurface = converted.asSequence().filter { it.text in literalSurfaces }.groupBy { it.text }
        val raw = literals.map { candidate ->
            evidenceBySurface[candidate.text].orEmpty().fold(candidate) { result, alternative -> result.withAlternative(alternative) }
        }
        fun rankSymbols(base: List<Candidate>): List<Candidate> {
            if (base.firstOrNull()?.reading != key || historyForKey.isEmpty()) return base
            val ranked = Personalization.rank(base, historyForKey, now)
            // The general rank has a capped boost. Keep the most-used explicit
            // spelling first within the symbol group even if both hit that cap.
            val preferred = ranked.mapNotNull { candidate ->
                historyForKey[candidate.text]?.let { candidate to Personalization.recentUses(it, now) }
            }.maxByOrNull { it.second }
            return if (preferred != null && preferred.second >= 3)
                listOf(preferred.first) + ranked.filter { it.text != preferred.first.text }
            else ranked
        }
        val alias = rankSymbols(SymbolCandidates.candidates(key, expressions.symbols, prediction, CandidateSource.SYMBOL_ALIAS))
        val symbol = rankSymbols(SymbolCandidates.candidates(key, symbols, prediction))
        val exactAlias = alias.filter { it.reading == key }
        // Keep a dictionary-backed hiragana or katakana spelling eligible for its
        // natural rank. Generated script variants also have reserved short-input slots.
        // A short reading with many homophones benefits from several lexical
        // choices before literal script variants, which remain in the exact tab.
        val manyHomophones = key.length <= 2 && matched.count { it.text !in literalSurfaces } >= 6
        val lexicalOrder = if (manyHomophones) matched.filterNot { it.text in literalSurfaces } else matched
        val directWords = Personalization.rank(lexicalOrder, historyForKey, now,
            contextual.filter { it.reading == key }.associateBy { it.text })
        val directHead = when {
            trusted.isNotEmpty() -> trusted.take(3)
            exactAlias.isEmpty() -> directWords.take(3)
            group(CandidateReason.DICTIONARY_EXACT).isEmpty() -> emptyList()
            else -> directWords.take(1)
        }
        val showScriptsEarly = counters.isNotEmpty() || matched.isEmpty() && trusted.isEmpty()
        val direct = mergeCandidates(directHead + (if (showScriptsEarly && directHead.isEmpty()) raw.take(1) else emptyList()) +
            exactAlias.take(3) + (if (showScriptsEarly) raw else emptyList()) +
            trusted + directWords + raw + exactAlias + symbol.filter { it.reading == key })

        val wholePrefixes = mergeCandidates(group(CandidateReason.USER_PREFIX) +
            group(CandidateReason.PREFIX).sortedWith(byCost))
        val phrasePrefixes = phrases.filter { it.reading != key }
        val tails = mergeCandidates(group(CandidateReason.TAIL).sortedWith(byCost))
        val weak = mergeCandidates(group(CandidateReason.WEAK_SEGMENT).sortedWith(byCost))
        val contextualPrefixes = contextual.filter { it.reading != key }.map { candidate ->
            candidate.copy(evidence = candidate.evidence?.copy(matchedLength = key.length))
        }
        val leadingJapanese = (trusted + directWords).firstOrNull()?.text ?: key
        val meanings = Personalization.rank(SemanticConversion.suggestions(key, leadingJapanese, semantic), historyForKey, now)
        val emotional = findExpressions(key, prediction, count, expressions, emoji, face, directWords, wholePrefixes,
            historyForKey, now)
        val head = if (prediction && phrasePrefixes.isNotEmpty())
            mergeCandidates(directHead.take(2) + phrasePrefixes.take(2) + direct.take(5)) else direct.take(5)
        val leading = head.take(2)
        val learnedPrefixes = if (prediction && key.length >= 2 && history.isNotEmpty()) {
            learnedPrefixes(key, history, leading.map { it.text }.toSet(), contextualPrefixes,
                engine, symbols, expressions, now)
        } else emptyList()

        // Show a sample from secondary sources early, then continue through the full
        // ranked lists. A huge prefix result cannot exhaust the symbol or meaning slots.
        val fallback = if (matched.isEmpty() && trusted.isEmpty() || key.any { !it.isLetter() && it != 'ー' })
            weak.take(5) else emptyList()
        val prefix = mergeCandidates(wholePrefixes.take(4) + tails.take(4)).sortedWith(byCost)
        val leadingPredictions = if (matched.isEmpty() && trusted.isEmpty()) prefix.take(4) else prefix.take(2)
        // Round-robin the extended word lists in small groups after the first
        // screenful, so hundreds of exact kanji cannot bury valid completions.
        val followups = interleave(direct.drop(5), wholePrefixes, tails)
        val all = mergeCandidates(buildList {
            addAll(leading)
            addAll(learnedPrefixes)
            addAll(head.drop(2))
            addAll(fallback)
            addAll(contextualPrefixes.take(2))
            addAll(leadingPredictions)
            addAll(emotional.first.take(2))
            addAll(emotional.second.take(2))
            addAll(meanings.take(4))
            addAll(symbol.take(4))
            addAll(direct.take(5))
            addAll(phrasePrefixes)
            addAll(meanings.drop(4))
            addAll(followups)
            addAll(contextualPrefixes)
            addAll(alias)
            addAll(symbol)
            addAll(emotional.first)
            addAll(emotional.second)
            addAll(weak)
        })
        val limited = all.take(count)
        val ranked = if (learnedPrefixes.isEmpty()) limited else {
            val learnedBySurface = learnedPrefixes.associateBy { it.text }
            // The same symbol can have a shorter dictionary reading. The
            // prediction credits its stored full reading; the exact tab keeps its own.
            limited.map { candidate -> learnedBySurface[candidate.text]?.withAlternative(candidate) ?: candidate }
        }
        return CandidateLists(ranked, direct.take(count), emotional.first, emotional.second)
    }

    private fun learnedPrefixes(key: String, history: List<LearnedChoice>, reserved: Set<String>,
        contextual: List<Candidate>, engine: ConversionEngine, symbols: Map<String, List<String>>,
        expressions: ExpressionCandidates, now: Long,
    ): List<Candidate> {
        val inContext = contextual.map { it.reading to it.text }.toSet()
        return history.asSequence().filter { it.reading.startsWith(key) && it.text !in reserved }
            .map { it to Personalization.recentUses(it, now) }
            .filter { (choice, uses) ->
                val added = choice.reading.length - key.length
                // A short prefix has more homophones and needs stronger evidence.
                added in 1..4 && uses >= if (key.length == 2 || added > 2) 6 else 3
            }
            .sortedWith(compareBy<Pair<LearnedChoice, Int>> { it.first.reading.length }
                .thenByDescending { (it.first.reading to it.first.text) in inContext }
                .thenByDescending { it.second }.thenByDescending { it.first.lastUsed })
            .take(12)
            .mapNotNull { (choice) ->
                val (reading, text) = choice
                val isSymbol = text in expressions.symbols[reading].orEmpty() || text in symbols[reading].orEmpty()
                val known = isSymbol || text in expressions.words[reading].orEmpty() ||
                    (engine as? DictionaryConversionEngine)?.knownChoice(reading, text) == true ||
                    PhrasePrediction.candidates(reading, false).any { it.text == text }
                if (!known) return@mapNotNull null
                Candidate(text, reading, if (isSymbol) CandidateKind.SYMBOL else CandidateKind.WORD,
                    CandidateEvidence(CandidateReason.LEARNED, CandidateSource.HISTORY, reading, key.length, choice = choice))
            }.distinctBy { it.text }.take(2).toList()
    }

    fun next(context: Candidate, history: List<LearnedContinuation>, engine: ConversionEngine, semantic: JapaneseLexicon?,
        limit: Int, now: Long = System.currentTimeMillis()): List<Candidate> {
        val known = history.filter { pair ->
            pair.count >= 2 && context.text.endsWith(pair.context) && pair.context.length <= 32 &&
                pair.text.length <= 32 && pair.reading.length <= 32 &&
                ((engine as? DictionaryConversionEngine)?.knownChoice(pair.reading, pair.text) == true ||
                    PhrasePrediction.candidates(pair.reading, false).any { it.text == pair.text } ||
                    pair.text in SemanticConversion.equivalents(semantic, pair.reading))
        }
        return NextWordPrediction.candidates(context, known, limit, now)
    }

    private fun interleave(direct: List<Candidate>, prefix: List<Candidate>, tail: List<Candidate>): List<Candidate> = buildList {
        var word = 0
        var prediction = 0
        var completion = 0
        while (word < direct.size || prediction < prefix.size || completion < tail.size) {
            repeat(4) { if (word < direct.size) add(direct[word++]) }
            repeat(3) { if (prediction < prefix.size) add(prefix[prediction++]) }
            if (completion < tail.size) add(tail[completion++])
        }
    }

    private fun findExpressions(key: String, prediction: Boolean, count: Int, dictionary: ExpressionCandidates,
        emoji: Boolean, face: Boolean, words: List<Candidate>, prefixes: List<Candidate>,
        history: Map<String, LearnedChoice>, now: Long,
    ): Pair<List<Candidate>, List<Candidate>> {
        if (!emoji && !face) return emptyList<Candidate>() to emptyList()
        val expanded = count == 10000
        val exact = dictionary.find(key, false, false, emoji, face, expanded)
        val meaning = words.asSequence().take(2)
            .filter { it.evidence?.reason == CandidateReason.DICTIONARY_EXACT && it.text != key }
            .flatMap { word ->
                dictionary.find(word.text, false, false, emoji, face, expanded).asSequence().map { candidate ->
                    candidate.copy(reading = key, evidence = CandidateEvidence(CandidateReason.EXPRESSION_MEANING,
                        CandidateSource.EXPRESSION, key, context = word.text))
                }
            }.toList()
        val prefix = if (!prediction || key.length < 2) emptyList() else {
            val supported = prefixes.asSequence().take(8).map { it.reading }.toSet()
            dictionary.find(key, false, true, emoji, face, expanded, maxAddedReading = 2).filter { candidate ->
                val added = candidate.reading.length - key.length
                added == 1 || (added == 2 && candidate.reading in supported)
            }
        }
        fun ranked(kind: CandidateKind): List<Candidate> =
            mergeCandidates(Personalization.rank(exact.filter { it.kind == kind }, history, now) +
                Personalization.rank(meaning.filter { it.kind == kind }, history, now) + prefix.filter { it.kind == kind })
        return ranked(CandidateKind.EMOJI) to ranked(CandidateKind.FACE)
    }
}
