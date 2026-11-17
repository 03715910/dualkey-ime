package io.github.dualkey.core

/** Editing decisions operate on a bounded cursor context, never on saved input text. */
object EditingPolicy {
    const val CONTEXT_LIMIT=2048
    private val boundaries="、。！？!?.,\n\r"
    fun chunkBeforeCursor(before: String): Int {
        if (before.isEmpty()) return 0
        if (before.last()=='\n' || before.last()=='\r') return 1
        var end=before.length
        while (end>0 && before[end-1] in boundaries && before[end-1]!='\n' && before[end-1]!='\r') end--
        val boundary=(end-1 downTo 0).firstOrNull { before[it] in boundaries }
        if (boundary==null && before.length>CONTEXT_LIMIT) return 1
        val start=(boundary ?: -1)+1
        return before.codePointCount(start,before.length)
    }
}

/** Single upper-case is consumed only by a letter, independent of hardware modifiers. */
object ScreenShift {
    fun tap(state: Int,elapsed: Long,doubleTapTimeout: Long): Int = when {
        state==2 -> 0
        state==1 && elapsed in 0..doubleTapTimeout -> 2
        state==1 -> 0
        else -> 1
    }
    fun afterInput(state: Int,text: String)=if (state==1 && text.any(Char::isLetter)) 0 else state
}

object ClipboardPrivacy {
    private val credentialWord=Regex("(?i)password|passwd|パスワード|secret|token|api[_ -]?key|-----BEGIN|認証コード|確認コード|暗証番号|\\b(?:otp|pin)\\b")
    private val calendarYear=Regex("(?<![0-9０-９])[12１２][0-9０-９]{3}(?=年|[-/.][0-9０-９]{1,2}[-/.][0-9０-９]{1,2}(?![0-9０-９]))")
    private val digits=Regex("[0-9０-９]{4,}")
    private val opaqueValue=Regex("[A-Za-z0-9_+/=-]{24,}")
    private val uriLink=Regex("(?i)[a-z][a-z0-9+.-]*://[^\\s<>\"「」]+")

    fun retain(text: String,sensitive: Boolean): Boolean = !sensitive && text.length in 1..2000 &&
        text.isNotBlank() && text.none { it.isISOControl() && it !in "\r\n\t" } &&
        '@' !in text && !credentialWord.containsMatchIn(text) &&
        !digits.containsMatchIn(calendarYear.replace(text," ")) && !opaqueValue.containsMatchIn(text) &&
        uriLink.findAll(text).all { match ->
            val uri=runCatching { java.net.URI(match.value) }.getOrNull()
            uri!=null && uri.scheme.lowercase() in setOf("http","https") && uri.host!=null &&
                uri.rawUserInfo==null && uri.rawQuery==null && uri.rawFragment==null
        }
}
