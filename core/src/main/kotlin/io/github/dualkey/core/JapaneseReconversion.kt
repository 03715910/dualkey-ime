package io.github.dualkey.core

/** Recovers a bounded set of readings for committed text and keeps separators verbatim. */
internal object JapaneseReconversion {
    const val MAX_LENGTH = 160
    private val japanese = Regex("[\\p{sc=Han}\\p{sc=Hiragana}\\p{sc=Katakana}ーｰ々\\u3099\\u309a\\uff9e\\uff9f]+")
    private val kanji = Regex("[\\p{sc=Han}々]")
    private const val READING_LIMIT = 3
    private const val BEAM = 12

    private data class Path(val reading: String, val right: Int, val cost: Int)
    private data class Span(val range: IntRange, val choices: List<String>)

    fun candidates(text: String, lexicon: JapaneseLexicon, custom: Map<String, List<String>>,
        generate: (String) -> List<Candidate>): List<Candidate> {
        if (text.length !in 1..MAX_LENGTH) return emptyList()
        val runs = japanese.findAll(text).map { it.range }.toList()
        val kanjiRuns = runs.filter { kanji.containsMatchIn(text.substring(it)) }
        val matches = lexicon.reverseMatches(text, kanjiRuns)
        val bySpelling = HashMap<String, List<String>>()
        val byReading = HashMap<String, List<String>>()
        val spans = runs.map { run ->
            val surface = text.substring(run)
            val choices = bySpelling.getOrPut(surface) {
                val readings = if (run in kanjiRuns) readings(text, run, lexicon, matches, custom)
                    else listOf(Personalization.normalize(surface))
                val conversions = readings.map { reading ->
                    byReading.getOrPut(reading) {
                        generate(reading).map { it.text }.take(18)
                    }
                }
                // Give each plausible reading a chance before showing further homophones.
                buildList {
                    for (i in 0 until 18) conversions.forEach { it.getOrNull(i)?.let(::add) }
                    addAll(readings.flatMap { listOf(it, SemanticConversion.katakana(it)) })
                }.filter { it != surface }.distinct()
            }
            Span(run, choices)
        }.filter { it.choices.isNotEmpty() }
        if (spans.isEmpty()) return emptyList()
        val alternatives = linkedSetOf<String>()
        if (spans.size > 1) {
            var combined = text
            spans.asReversed().forEach { combined = combined.replaceRange(it.range, it.choices.first()) }
            alternatives += combined
        }
        // An alternate for one span must leave every other span as the user wrote it.
        for (choice in 0 until spans.maxOf { it.choices.size }) {
            for (span in spans) {
                val replacement = span.choices.getOrNull(choice) ?: continue
                alternatives += text.replaceRange(span.range, replacement)
                if (alternatives.size >= 64) break
            }
            if (alternatives.size >= 64) break
        }
        return alternatives.map { Candidate(it, text) }
    }

    private fun readings(text: String, run: IntRange, lexicon: JapaneseLexicon,
        matches: Array<ArrayList<LexiconMatch>>, custom: Map<String, List<String>>): List<String> {
        val end = run.last + 1
        val states = Array(run.count() + 1) { arrayListOf<Path>() }
        states[0].add(Path("", 0, 0))
        for (start in run) {
            val parents = states[start - run.first].sortedBy { it.cost }.distinctBy { it.reading to it.right }.take(BEAM)
            if (parents.isEmpty()) continue
            val edges = matches[start].toMutableList()
            custom.forEach { (reading, words) ->
                if (reading.isNotEmpty()) words.forEach { word ->
                    if (word.isNotEmpty() && word.length <= end - start && text.startsWith(word, start))
                        edges += LexiconMatch(start + word.length, LexiconWord(reading, 0, 0, -3500))
                }
            }
            val next = start + Character.charCount(text.codePointAt(start))
            val literal = text.substring(start, next)
            edges += LexiconMatch(next, LexiconWord(Personalization.normalize(literal), 0, 0,
                if (kanji.containsMatchIn(literal)) 22000 else 4500))
            for ((finish, word) in edges.sortedBy { it.word.cost }.distinctBy { Triple(it.end, it.word.text, it.word.right) }) {
                if (finish > end) continue
                val paths = states[finish - run.first]
                for (parent in parents) {
                    val reading = parent.reading + word.text
                    if (reading.length <= MAX_LENGTH) paths.add(Path(reading, word.right,
                        parent.cost + word.cost + lexicon.transition(parent.right, word.left)))
                }
                if (paths.size > BEAM * 12) {
                    val best = paths.sortedBy { it.cost }.distinctBy { it.reading to it.right }.take(BEAM * 2)
                    paths.clear()
                    paths.addAll(best)
                }
            }
        }
        return states.last().sortedBy { it.cost + lexicon.transition(it.right, 0) }
            .map { Personalization.normalize(it.reading) }.distinct().take(READING_LIMIT)
    }
}
