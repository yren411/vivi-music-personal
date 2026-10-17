package com.music.vivi.playback.automix

/** Immutable selection: decoder and advertised profile are constructed from the SAME value. */
enum class AnalysisPreprocessing(val version: String) {
    LEGACY(PcmPreprocessing.LEGACY), BANDLIMITED(PcmPreprocessing.BANDLIMITED);
    companion object { val PRODUCTION = LEGACY }
}

enum class DecoderStage { SOURCE, EXTRACTOR, CODEC_SETUP, CODEC_OUTPUT, PCM_CONVERSION, RESAMPLING, COMPLETE }
enum class DecoderFailureSubtype {
    NONE, SOURCE_ACCESS, RANGE_UNSUPPORTED, BYTE_BUDGET, NO_AUDIO_TRACK, DURATION_UNAVAILABLE,
    REQUEST_TOO_SHORT, SOFTWARE_CODEC_UNAVAILABLE, CODEC_ERROR, UNSUPPORTED_PCM,
    MALFORMED_PCM_BUFFER, NONFINITE_PCM, FORMAT_CHANGED, TIMESTAMP_REGRESSION, SOURCE_GAP,
    UNKNOWN_COVERAGE, EMPTY_OUTPUT, TRUNCATED_WINDOW, PARTIAL_SOURCE_COVERAGE,
    DEADLINE, CANCELED, INVALID_REQUEST, UNCLASSIFIED
}
enum class DecoderRangeStatus { UNKNOWN, NOT_REQUIRED, VERIFIED, REJECTED }
enum class DecoderInputEnd { UNKNOWN, NATURAL_EOS, REQUEST_BOUNDARY }

data class PcmPreparationMetadata(
    val acceptedSourceFrames: Long,
    val droppedOverlapFrames: Long,
    val jitterAdjustedBuffers: Long,
    val maximumJitterUs: Long,
    val gapCount: Int,
    val edgeSupportedOutputFrames: Long,
    /** No inserted silence/repeated samples. Edge support doesn't extend source coverage. */
    val synthesizedTimelineFrames: Long = 0,
)

/** No source IDs, exception messages, URLs or PCM. Platform codec/MIME text is filtered
 * by the export formatter; null means not observed. */
data class DecoderDiagnostic(
    val preprocessing: AnalysisPreprocessing,
    val stage: DecoderStage,
    val subtype: DecoderFailureSubtype,
    val category: AutoMixFailure?,
    val requested: AudioIntervalUs,
    val effective: AudioIntervalUs? = null,
    val coverage: AudioCoverage? = null,
    val outputFrames: Int? = null,
    val sourceFormats: List<SourcePcmFormat> = emptyList(),
    val duration: AudioDuration? = null,
    val codecEos: Boolean? = null,
    val inputEnd: DecoderInputEnd = DecoderInputEnd.UNKNOWN,
    val sourceBytesRead: Long? = null,
    val fullyCached: Boolean? = null,
    val rangeStatus: DecoderRangeStatus = DecoderRangeStatus.UNKNOWN,
    val elapsedUs: Long? = null,
    val preparation: PcmPreparationMetadata? = null,
    val repeatedOutputFrames: Long? = null,
    val codecOutputFrames: Long? = null,
    val firstCodecPtsUs: Long? = null,
    val lastCodecFrameEndUs: Long? = null,
    val codecProgress: DecoderCodecProgress? = null,
) {
    fun reportFields(): String = buildString {
        append(" preprocessing=" + preprocessing.version + " decoderStage=" + stage + " subtype=" + subtype)
        append(" requestedUs=" + requested.startUs + ".." + requested.endUs)
        effective?.let { append(" effectiveUs=" + it.startUs + ".." + it.endUs) }
        append(" coverage=" + (coverage?.status(effective ?: requested) ?: CoverageStatus.UNKNOWN))
        coverage?.intervals?.take(8)?.forEach { append(" sourceUs=" + it.startUs + ".." + it.endUs) }
        append(" codecFrames=" + codecOutputFrames + " firstCodecPtsUs=" + firstCodecPtsUs + " lastCodecEndUs=" + lastCodecFrameEndUs)
        append(" acceptedFrames=" + coverage?.acceptedDecodedFrames + " outputFrames=" + outputFrames)
        append(" targetOutputFormat=FLOAT32/22050/1 repeatedFrames=" + repeatedOutputFrames)
        sourceFormats.take(8).forEach { append(" inputFormat=" + it.format + "/" + it.basis) }
        append(" durationUs=" + duration?.durationUs + " durationBasis=" + (duration?.basis ?: DurationBasis.UNKNOWN))
        val acceptedEnd = coverage?.intervals?.maxOfOrNull { it.endUs }
        if (duration?.durationUs != null && acceptedEnd != null) append(" durationMinusAcceptedEndUs=" + (duration.durationUs - acceptedEnd))
        append(" codecEos=" + codecEos + " inputEnd=" + inputEnd + " bytesRead=" + sourceBytesRead)
        append(" fullyCached=" + fullyCached + " range=" + rangeStatus + " elapsedUs=" + elapsedUs)
        codecProgress?.let { append(it.reportFields()) }
        preparation?.let { append(" droppedOverlapFrames=" + it.droppedOverlapFrames + " jitterBuffers=" + it.jitterAdjustedBuffers +
            " maxJitterUs=" + it.maximumJitterUs + " gaps=" + it.gapCount + " filterEdgeFrames=" + it.edgeSupportedOutputFrames +
            " synthesizedTimelineFrames=" + it.synthesizedTimelineFrames) }
    }
}

fun interface DecoderDiagnosticSink {
    fun record(diagnostic: DecoderDiagnostic)
    companion object { val NONE = DecoderDiagnosticSink {} }
}

internal class PcmPreparationException(val subtype: DecoderFailureSubtype) :
    java.io.IOException(subtype.name)
