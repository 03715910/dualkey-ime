package io.github.dualkey.core

/** One configurable chord toggles visibility in both directions. Lock states are irrelevant. */
data class ToggleShortcut(val key: String="K",val modifiers: Int=CTRL or ALT) {
    val valid get() = key in keys && modifiers and ALL==modifiers &&
        (modifiers and (CTRL or ALT or META)!=0 || key.startsWith("F") && key.length>1)
    fun matches(key: String,modifiers: Int)=valid && this.key==key && this.modifiers==modifiers
    val label get() = buildList {
        if (modifiers and CTRL!=0) add("Ctrl")
        if (modifiers and ALT!=0) add("Alt")
        if (modifiers and SHIFT!=0) add("Shift")
        if (modifiers and META!=0) add("Meta")
        add(key)
    }.joinToString(" + ")
    companion object {
        const val CTRL=1
        const val ALT=2
        const val SHIFT=4
        const val META=8
        const val ALL=CTRL or ALT or SHIFT or META
        val keys=('A'..'Z').map(Char::toString)+('0'..'9').map(Char::toString)+(1..12).map { "F$it" }
    }
}
