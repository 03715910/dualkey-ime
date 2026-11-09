package io.github.dualkey.core

/** Chooses the width of ambiguous key input. Explicit glyphs and candidates stay literal. */
object SentencePunctuation {
    const val CONTEXT_LENGTH = 64

    private val quotedCode = Regex("""\b[A-Za-z_]\w*\s*(?:\(\s*|=\s*)["'][^"'\r\n]*["']?\)?\s*$""")

    fun resolve(key: String, composing: String, japaneseMode: Boolean, keepAscii: Boolean,
        beforeCursor: () -> CharSequence?,
    ): String {
        if (key != "!" && key != "?" || keepAscii) return key
        val japanese = languageOf(composing) ?: beforeCursor()?.let(::languageOf) ?: japaneseMode
        return if (japanese) if (key == "!") "！" else "？" else key
    }

    private fun languageOf(text: CharSequence): Boolean? {
        val line = text.toString().substringAfterLast('\n').substringAfterLast('\r')
        val token = line.trimEnd().takeLastWhile { !it.isWhitespace() }
        if ("://" in token || token.startsWith("www.", ignoreCase = true) ||
            ('@' in token.trim('@') && token.any { it in 'a'..'z' || it in 'A'..'Z' }) ||
            quotedCode.containsMatchIn(line)) return false

        var end = line.length
        while (end > 0) {
            val cp = line.codePointBefore(end)
            end -= Character.charCount(cp)
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return true
                Character.UnicodeScript.LATIN -> if (Character.isLetter(cp)) return false
                else -> if (cp == 'ー'.code || cp == 'ｰ'.code) return true
            }
        }
        return null
    }
}
