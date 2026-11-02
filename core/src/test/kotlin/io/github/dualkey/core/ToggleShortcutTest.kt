package io.github.dualkey.core

import org.junit.Assert.*
import org.junit.Test

class ToggleShortcutTest {
    @Test fun defaultAndCustomChordsMatchExactly() {
        val default=ToggleShortcut()
        assertTrue(default.valid); assertEquals("Ctrl + Alt + K",default.label)
        assertTrue(default.matches("K",ToggleShortcut.CTRL or ToggleShortcut.ALT))
        assertFalse(default.matches("K",ToggleShortcut.CTRL or ToggleShortcut.ALT or ToggleShortcut.SHIFT))
        assertFalse(default.matches("J",default.modifiers))
        val custom=ToggleShortcut("J",ToggleShortcut.CTRL or ToggleShortcut.SHIFT)
        assertTrue(custom.valid); assertEquals("Ctrl + Shift + J",custom.label)
        assertTrue(custom.matches("J",ToggleShortcut.CTRL or ToggleShortcut.SHIFT))
    }
    @Test fun ordinaryCapitalLettersCannotBecomeVisibilityShortcuts() {
        for (key in ToggleShortcut.keys) {
            val function=key.startsWith("F") && key.length>1
            assertEquals(key,function,ToggleShortcut(key,0).valid)
            assertEquals(key,function,ToggleShortcut(key,ToggleShortcut.SHIFT).valid)
            assertTrue(key,ToggleShortcut(key,ToggleShortcut.CTRL).valid)
        }
        assertFalse(ToggleShortcut("SPACE",ToggleShortcut.CTRL).valid)
        assertFalse(ToggleShortcut("K",16).valid)
        assertFalse(ToggleShortcut("F13",0).valid)
    }
}
