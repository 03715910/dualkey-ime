package io.github.user03715910.dualkey.ime

import io.github.dualkey.core.CandidateLists

internal enum class CandidateMode(val label: String) {
    ALL("すべて"), EXACT("絞り込み"), EMOJI("絵文字"), FACE("顔文字");

    fun candidates(lists: CandidateLists) = when (this) {
        ALL -> lists.all
        EXACT -> lists.exact
        EMOJI -> lists.emoji
        FACE -> lists.face
    }
}
