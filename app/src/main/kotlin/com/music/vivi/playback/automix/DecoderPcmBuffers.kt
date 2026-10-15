package com.music.vivi.playback.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException
import java.util.concurrent.CancellationException

/** Codec EOS may have no buffer and a nonzero offset; it contributes zero PCM frames. */
internal object DecoderPcmBuffers {
    fun readable(buffer: ByteBuffer?, offset: Int, size: Int, corrected: Boolean): ByteBuffer {
        if (size == 0) return ByteBuffer.allocate(0).order(ByteOrder.nativeOrder())
        val data = buffer ?: throw PcmPreparationException(DecoderFailureSubtype.MALFORMED_PCM_BUFFER)
        if (corrected && (offset < 0 || size < 0 || offset > data.capacity() - size))
            throw PcmPreparationException(DecoderFailureSubtype.MALFORMED_PCM_BUFFER)
        data.order(ByteOrder.nativeOrder())
        data.position(offset)
        data.limit(offset + size)
        return data
    }
}

internal object DecoderFailureClassification {
    fun subtype(error: Exception): DecoderFailureSubtype = when (error) {
        is PcmPreparationException -> error.subtype
        is CancellationException, is InterruptedException -> DecoderFailureSubtype.CANCELED
        is AnalysisUnavailable -> when (error.reason) {
            "source-not-range-seekable" -> DecoderFailureSubtype.RANGE_UNSUPPORTED
            "no-audio-track" -> DecoderFailureSubtype.NO_AUDIO_TRACK
            "unknown-or-unsupported-duration" -> DecoderFailureSubtype.DURATION_UNAVAILABLE
            "insufficient-audio" -> DecoderFailureSubtype.REQUEST_TOO_SHORT
            "software-decoder-unavailable" -> DecoderFailureSubtype.SOFTWARE_CODEC_UNAVAILABLE
            "unsupported-decoded-pcm" -> DecoderFailureSubtype.UNSUPPORTED_PCM
            "truncated-audio-window" -> DecoderFailureSubtype.TRUNCATED_WINDOW
            "analysis-byte-budget-exhausted" -> DecoderFailureSubtype.BYTE_BUDGET
            "ANALYSIS_TIMEOUT" -> DecoderFailureSubtype.DEADLINE
            else -> DecoderFailureSubtype.UNCLASSIFIED
        }
        is IllegalArgumentException -> DecoderFailureSubtype.INVALID_REQUEST
        is IOException -> DecoderFailureSubtype.SOURCE_ACCESS
        else -> DecoderFailureSubtype.UNCLASSIFIED
    }
}
