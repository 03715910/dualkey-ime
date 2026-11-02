package io.github.user03715910.dualkey.ime

import android.view.KeyEvent
import io.github.dualkey.core.ToggleShortcut

object HardwareShortcuts {
    fun key(code: Int)=KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    fun modifiers(event: KeyEvent)=
        (if (event.isCtrlPressed) ToggleShortcut.CTRL else 0) or
        (if (event.isAltPressed) ToggleShortcut.ALT else 0) or
        (if (event.isShiftPressed) ToggleShortcut.SHIFT else 0) or
        (if (event.isMetaPressed) ToggleShortcut.META else 0)
}
