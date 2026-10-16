package com.music.vivi.playback.automix

/** Pure positioning policy; null means read the freshly selected track without seeking.
 * MP4 may expose negative-PTS AAC priming packets before its edit-list origin. Seeking
 * to zero can skip those packets while the decoder still applies encoder-delay trimming.
 * Preserve them for corrected initial reads; capture trims real PCM to the requested span.
 * Legacy positioning and every nonzero seek retain their original behavior.
 */
internal object DecoderSeekPolicy {
    fun targetUs(preprocessing: AnalysisPreprocessing, startUs: Long): Long? {
        require(startUs >= 0)
        return if (preprocessing == AnalysisPreprocessing.BANDLIMITED && startUs == 0L) null else startUs
    }
}
