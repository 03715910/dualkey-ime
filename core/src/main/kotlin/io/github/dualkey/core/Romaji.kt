package io.github.dualkey.core

/** Pure, deterministic romaji conversion. Unfinished keystrokes stay in the composing span. */
object Romaji {
    private val table = buildMap {
        fun row(keys: List<String>, values: List<String>) { keys.zip(values).forEach { (k, v) -> put(k, v) } }
        row(listOf("a","i","u","e","o"), listOf("あ","い","う","え","お"))
        val rows = mapOf("k" to "かきくけこ", "s" to "さしすせそ", "t" to "たちつてと", "n" to "なにぬねの",
            "h" to "はひふへほ", "m" to "まみむめも", "r" to "らりるれろ", "g" to "がぎぐげご",
            "z" to "ざじずぜぞ", "d" to "だぢづでど", "b" to "ばびぶべぼ", "p" to "ぱぴぷぺぽ")
        rows.forEach { (c, kana) -> "aiueo".toList().zip(kana.toList()).forEach { (v,k) -> put("$c$v", "$k") } }
        putAll(mapOf("ya" to "や", "yu" to "ゆ", "yo" to "よ", "wa" to "わ", "wo" to "を", "wi" to "うぃ", "we" to "うぇ",
            "shi" to "し", "si" to "し", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ", "fu" to "ふ", "ji" to "じ",
            "ye" to "いぇ", "n'" to "ん", "-" to "ー", "," to "、", "." to "。", "[" to "「", "]" to "」", "/" to "・"))
        mapOf("ky" to "き", "gy" to "ぎ", "sy" to "し", "sh" to "し", "zy" to "じ", "jy" to "じ", "j" to "じ",
            "ty" to "ち", "ch" to "ち", "cy" to "ち", "dy" to "ぢ", "ny" to "に", "hy" to "ひ", "by" to "び", "py" to "ぴ", "my" to "み", "ry" to "り").forEach { (k,v) ->
            mapOf("a" to "ゃ", "u" to "ゅ", "o" to "ょ", "e" to "ぇ").forEach { (a,b) -> put(k+a,v+b) }
        }
        mapOf("f" to "ふ", "v" to "ゔ", "ts" to "つ", "th" to "て", "dh" to "で", "tw" to "と", "dw" to "ど", "kw" to "く", "gw" to "ぐ").forEach { (k,v) ->
            "aiueo".toList().zip("ぁぃぅぇぉ".toList()).forEach { (a,b) -> if (!containsKey(k+a)) put(k+a,v+b) }
        }
        mapOf("a" to "ぁ", "i" to "ぃ", "u" to "ぅ", "e" to "ぇ", "o" to "ぉ", "ya" to "ゃ", "yu" to "ゅ", "yo" to "ょ", "tu" to "っ", "tsu" to "っ", "wa" to "ゎ", "ka" to "ゕ", "ke" to "ゖ").forEach { (k,v) -> put("x$k",v); put("l$k",v) }
    }
    private val prefixes = table.keys.flatMap { k -> (1 until k.length).map { k.take(it) } }.toSet()
    private const val sokuonInitials = "bcdfghjkmprstvyz"

    private fun syllableAt(text: String, start: Int): String? {
        for (length in minOf(4, text.length - start) downTo 1) {
            val key = text.substring(start, start + length)
            if (key in table) return key
        }
        return null
    }

    fun convert(raw: String, flush: Boolean = false): String = buildString {
        val s = raw.lowercase()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c !in 'a'..'z' && c !in "-,.[]/") {
                // Japanese punctuation uses full-width forms; numbers stay half-width.
                append(if (c in '!'..'~' && !c.isLetterOrDigit()) c + 0xfee0 else c)
                i++; continue
            }
            val next = s.getOrNull(i + 1)
            if (c == next && c in sokuonInitials && syllableAt(s, i + 1) != null) {
                append('っ'); i++; continue
            }
            if (c == 'n') {
                if (next == 'n') {
                    append('ん')
                    // The second n is available for na/nya, but has already
                    // contributed to the displayed ん before a different consonant.
                    val afterPair = s.getOrNull(i + 2)
                    i += when {
                        afterPair == '\'' -> 3
                        afterPair != null && (syllableAt(s, i + 1) != null || s.substring(i + 1) in prefixes) -> 1
                        else -> 2
                    }
                    continue
                }
                if (next == null) { append(if (flush) 'ん' else 'n'); i++; continue }
                if (next !in "aiueoy'") { append('ん'); i++; continue }
            }
            val match = syllableAt(s, i)
            if (match != null) { append(table.getValue(match)); i += match.length }
            else if (!flush && s.substring(i) in prefixes) { append(s.substring(i)); break }
            else { append(c); i++ }
        }
    }
}

class Composer {
    private val raw = StringBuilder()
    val text get() = Romaji.convert(raw.toString())
    val reading get() = Romaji.convert(raw.toString(), true)
    val isEmpty get() = raw.isEmpty()
    fun append(value: String) { raw.append(value) }
    fun delete(): Boolean {
        if (raw.isEmpty()) return false
        val rendered=text
        // Undo a pending key without losing lookahead state (nnk -> nn).
        if (rendered.last() in 'a'..'z' && rendered.last()==raw.last().lowercaseChar()) {
            raw.setLength(raw.length-1)
            return true
        }
        // Once converted, delete a displayed character instead of undoing a romaji key.
        replace(rendered.substring(0,rendered.offsetByCodePoints(rendered.length,-1)))
        return true
    }
    fun clear() { raw.clear() }
    fun replace(value: String) { raw.clear(); raw.append(value) }
}
