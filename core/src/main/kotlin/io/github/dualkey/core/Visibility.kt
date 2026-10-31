package io.github.dualkey.core

enum class DisplayMode { FULL, COMPACT, HIDDEN }
enum class HardwarePolicy { AUTO, FULL, COMPACT, HIDDEN }

class VisibilityController {
    var connected = false; private set
    var policy = HardwarePolicy.AUTO
    var manual: DisplayMode? = null
    val mode: DisplayMode get() = manual ?: if (!connected) DisplayMode.FULL else when (policy) {
        HardwarePolicy.AUTO, HardwarePolicy.HIDDEN -> DisplayMode.HIDDEN
        HardwarePolicy.FULL -> DisplayMode.FULL
        HardwarePolicy.COMPACT -> DisplayMode.COMPACT
    }
    fun connectionChanged(value: Boolean, remembered: DisplayMode? = null) {
        if (connected == value) return
        connected = value
        // A disconnect must always restore touch input.
        manual = if (value) remembered else null
    }
    fun toggle() { manual = if (mode == DisplayMode.FULL) DisplayMode.HIDDEN else DisplayMode.FULL }
}
