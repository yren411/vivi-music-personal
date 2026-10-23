package com.music.vivi.playback.automix

/** Real compressed support before a nonzero AAC analysis window. Configuring a new
 * MediaCodec applies its declared front trim again; seeking only to the requested
 * packet can consequently remove required PCM. Never rebase PTS or remove the codec's
 * delay metadata: decode earlier packets and let capture discard pre-request samples.
 * Two 1024-frame units also bound synthesis warmup (including common doubled-rate AAC).
 * This is an analysis resource policy, not a promise that every AAC source has coverage. */
internal data class AacAnalysisWindow(val sampleRate: Int, val encoderDelayFrames: Int?,
    val supportFrames: Int, val supportUs: Long, val seekTargetUs: Long?) {
    companion object {
        const val MAX_DELAY_FRAMES = 8192
        const val WARMUP_FRAMES = 2048

        fun create(startUs: Long, sampleRate: Int, encoderDelayFrames: Int?): AacAnalysisWindow {
            require(startUs >= 0)
            if (sampleRate !in 8000..192000 || encoderDelayFrames != null && encoderDelayFrames !in 0..MAX_DELAY_FRAMES)
                throw PcmPreparationException(DecoderFailureSubtype.UNSUPPORTED_AAC_TIMING)
            val frames = (encoderDelayFrames ?: 0) + WARMUP_FRAMES
            val supportUs = (frames * 1_000_000L + sampleRate - 1) / sampleRate
            // Null preserves negative priming access units when support reaches the origin.
            val target = (startUs - supportUs).coerceAtLeast(0).takeIf { it > 0 }
            return AacAnalysisWindow(sampleRate, encoderDelayFrames, frames, supportUs, target)
        }
    }
}

/** Numeric coverage evidence only. Frame deficits are ceiling estimates from the
 * microsecond coverage intervals, not a claim that missing PCM has been decoded. */
data class AacBoundaryEvidence(val configuredRateHz: Int, val declaredDelayFrames: Int?,
    val coverageRateHz: Int,
    val prerollFrames: Int, val prerollUs: Long, val prerollInputPackets: Long,
    val preRequestDecodedFrames: Long?, val missingLeadingUs: Long?, val missingTrailingUs: Long?,
    val missingLeadingFramesCeil: Long?, val missingTrailingFramesCeil: Long?) {
    fun reportFields() = " aacBoundarySchema=1 aacConfiguredRateHz=$configuredRateHz aacDeclaredDelayFrames=$declaredDelayFrames" +
        " aacCoverageRateHz=$coverageRateHz" +
        " aacPrerollFrames=$prerollFrames aacPrerollUs=$prerollUs aacPrerollInputPackets=$prerollInputPackets" +
        " preRequestDecodedFrames=$preRequestDecodedFrames missingLeadingUs=$missingLeadingUs missingTrailingUs=$missingTrailingUs" +
        " missingLeadingFramesCeil=$missingLeadingFramesCeil missingTrailingFramesCeil=$missingTrailingFramesCeil"
}
