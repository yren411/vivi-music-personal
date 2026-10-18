package com.music.vivi.playback.automix

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodecList
import android.os.Build
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import kotlin.math.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class AnalysisSource(val uri: Uri, val cacheKey: String, val length: Long,
                          val identity: String, val factory: DataSource.Factory, val fullyCached: Boolean = false)

/** Android's decoder, fed through the SAME permitted cache/CDN path as playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidWindowDecoder(
    val preprocessing: AnalysisPreprocessing = AnalysisPreprocessing.PRODUCTION,
    /** Synchronous validation observer after actual PCM acceptance; no PCM/source data escapes.
     * Production leaves this null. It lets instrumentation cancel during active decode reliably. */
    private val onDecodedOutput: (() -> Unit)? = null,
) : AnalysisWindowDecoder<AnalysisSource> {
    override fun decode(source: AnalysisSource, request: DecodeRequest, control: AnalysisControl): DecodeResult {
        val observation = Observation(request, preprocessing, source.fullyCached)
        return try {
            control.checkpoint()
            require(request.interval.valid && request.interval.startUs % 1000 == 0L && request.interval.endUs % 1000 == 0L)
            require(request.identity == AnalysisSourceIdentity(source.cacheKey, source.identity))
            val window = decodeWindow(source, request, control, observation)
            observation.window = window
            observation.stage = DecoderStage.COMPLETE
            val coverage = window.coverage.status(window.decodeInterval)
            val partial = preprocessing == AnalysisPreprocessing.BANDLIMITED && coverage != CoverageStatus.FULL
            val diagnostic = observation.snapshot(if (!partial) DecoderFailureSubtype.NONE else
                if (coverage == CoverageStatus.UNKNOWN) DecoderFailureSubtype.UNKNOWN_COVERAGE else DecoderFailureSubtype.PARTIAL_SOURCE_COVERAGE,
                if (partial) AutoMixFailure.INSUFFICIENT_AUDIO_DATA else null)
            control.decoderDiagnostics.record(diagnostic)
            DecodeResult.Decoded(window.copy(decoderDiagnostic = diagnostic))
        } catch (error: Exception) {
            val subtype = if (error is MediaCodec.CodecException) DecoderFailureSubtype.CODEC_ERROR
                else DecoderFailureClassification.subtype(error)
            val category = if (error is PcmPreparationException) when (subtype) {
                DecoderFailureSubtype.FLAC_PARSER_BUDGET -> AutoMixFailure.ANALYSIS_BYTE_BUDGET
                DecoderFailureSubtype.SOURCE_GAP, DecoderFailureSubtype.TIMESTAMP_REGRESSION,
                DecoderFailureSubtype.EMPTY_OUTPUT, DecoderFailureSubtype.PARTIAL_SOURCE_COVERAGE,
                DecoderFailureSubtype.UNKNOWN_COVERAGE -> AutoMixFailure.INSUFFICIENT_AUDIO_DATA
                else -> AutoMixFailure.UNSUPPORTED_FORMAT
            } else AutoMixFailure.of(error)
            val original = if (error is PcmPreparationException)
                AnalysisUnavailable(category.name).apply { initCause(error) } else error
            val diagnostic = observation.snapshot(subtype, category)
            control.decoderDiagnostics.record(diagnostic)
            DecodeResult.Failed(AnalysisFailure(AnalysisFailureStage.DECODING, category,
                if (error is PcmPreparationException) subtype.name else
                    if (error is AnalysisUnavailable) error.reason else category.name, original), diagnostic)
        }
    }

    private class Observation(val request: DecodeRequest, val preprocessing: AnalysisPreprocessing, val cached: Boolean) {
        val began = System.nanoTime()
        private var stageBegan=began
        private val elapsed=LongArray(DecoderStage.entries.size)
        var stage = DecoderStage.SOURCE
            set(value) {val now=System.nanoTime();elapsed[field.ordinal]+=now-stageBegan;stageBegan=now;field=value}
        var effective: AudioIntervalUs? = null
        var duration: AudioDuration? = null
        var legacy: LegacyPcmWindowCapture? = null
        var corrected: CorrectedPcmWindowCapture? = null
        var window: DecodedWindow? = null
        var codecEos: Boolean? = null
        var inputEnd = DecoderInputEnd.UNKNOWN
        var range = DecoderRangeStatus.UNKNOWN
        var fetched = 0L
        var packets: DecoderPacketAccounting? = null
        var aacTail: AacAnalysisTailTrim? = null
        var codecOutputFrames: Long? = null
        var firstCodecPtsUs: Long? = null
        var lastCodecFrameEndUs: Long? = null
        fun snapshot(subtype: DecoderFailureSubtype = DecoderFailureSubtype.NONE, category: AutoMixFailure? = null): DecoderDiagnostic {
            val legacyWindow = window ?: legacy?.finish(codecEos ?: false)
            packets?.tail(aacTail?.retainedFrames,aacTail?.discardedAtNaturalEos)
            val now=System.nanoTime()
            val timing=DecoderStage.entries.associateWith {(elapsed[it.ordinal]+if(it==stage)now-stageBegan else 0)/1000}
            return DecoderDiagnostic(preprocessing, stage, subtype, category, request.interval, effective,
                window?.coverage ?: corrected?.coverage ?: legacyWindow?.coverage,
                window?.frameCount ?: legacyWindow?.frameCount,
                window?.sourceFormats ?: corrected?.sourceFormats ?: legacyWindow?.sourceFormats ?: emptyList(),
                duration, codecEos, inputEnd, fetched, cached, range, (System.nanoTime() - began) / 1000,
                window?.preparation ?: corrected?.preparation, window?.repeatedOutputFrames ?: legacyWindow?.repeatedOutputFrames,
                codecOutputFrames, firstCodecPtsUs, lastCodecFrameEndUs, packets?.snapshot(),timing)
        }
    }

    private fun decodeWindow(source: AnalysisSource, request: DecodeRequest, control: AnalysisControl, observation: Observation): DecodedWindow {
        val startMs = request.interval.startUs / 1000
        val requestedLengthMs = (request.interval.endUs - request.interval.startUs) / 1000
        val extractor = MediaExtractor()
        val bridge = RangeSource(source, control)
        var codec: MediaCodec? = null
        var flac: BoundedFlacPackets? = null
        try {
            control.checkpoint()
            if (!source.fullyCached && source.uri.scheme in listOf("http", "https")) {
                val probe = source.factory.createDataSource()
                try {
                    // Uncached, read-only probe: no prefix-discard workaround for non-range servers.
                    probe.open(DataSpec.Builder().setUri(source.uri).setKey(source.cacheKey + ":automix-range-probe")
                        .setPosition(0).setLength(1).build())
                    val range = probe.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
                    if (range == null || !range.startsWith("bytes 0-")) { observation.range = DecoderRangeStatus.REJECTED; throw AnalysisUnavailable("source-not-range-seekable") }
                } finally { probe.close() }
            }
            observation.range = if (!source.fullyCached && source.uri.scheme in listOf("http", "https")) DecoderRangeStatus.VERIFIED else DecoderRangeStatus.NOT_REQUIRED
            observation.stage = DecoderStage.EXTRACTOR
            extractor.setDataSource(bridge)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AnalysisUnavailable("no-audio-track")
            val platformFormat = extractor.getTrackFormat(track)
            // Some vendor extractors re-chunk FLAC frames and reset trailing PTS to zero.
            // Use the existing Media3 frame parser only for corrected FLAC input; keep the
            // authorized bridge, byte/deadline budgets and all legacy source handling intact.
            if (preprocessing == AnalysisPreprocessing.BANDLIMITED && platformFormat.getString(MediaFormat.KEY_MIME)=="audio/flac")
                flac = BoundedFlacPackets(source.length, control, bridge::readAt)
            val format = flac?.let { reader ->
                MediaFormat.createAudioFormat("audio/flac",reader.format.sampleRate,reader.format.channelCount).apply {
                    setLong(MediaFormat.KEY_DURATION,reader.durationUs)
                    if(reader.format.maxInputSize>0)setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,reader.format.maxInputSize)
                    reader.format.initializationData.forEachIndexed {i,data -> setByteBuffer("csd-$i",java.nio.ByteBuffer.wrap(data))}
                }
            } ?: platformFormat
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            observation.duration = AudioDuration(durationUs.takeIf { it > 0 }, if (durationUs > 0) DurationBasis.EXTRACTOR else DurationBasis.UNKNOWN)
            val durationMs = durationUs / 1000
            if (durationMs <= 0 || durationMs > 30 * 60_000 || startMs >= durationMs) throw AnalysisUnavailable("unknown-or-unsupported-duration")
            val lengthMs = min(requestedLengthMs, durationMs - startMs).coerceAtMost(32_000)
            if (lengthMs < 4000) throw AnalysisUnavailable("insufficient-audio")
            observation.effective = AudioIntervalUs(startMs * 1000, (startMs + lengthMs) * 1000)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val opusWindow = if (preprocessing == AnalysisPreprocessing.BANDLIMITED && mime == "audio/opus")
                OpusAnalysisWindow.create(startMs * 1000, (startMs + lengthMs) * 1000,
                    format.getByteBuffer("csd-1"), format.getByteBuffer("csd-2")) else null
            val seekTarget=if(flac!=null)startMs*1000 else if(opusWindow!=null)opusWindow.seekTargetUs
                else DecoderSeekPolicy.targetUs(preprocessing,startMs*1000)
            // Do not fetch the initial packet just to observe a pre-seek timestamp.
            // Nonzero tail seeks must retain the original bounded source-read behavior.
            if(flac==null)extractor.selectTrack(track)
            if (flac != null) flac.seek(startMs * 1000) else {
                seekTarget?.let {
                    extractor.seekTo(it, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }
            }
            // Do not consume scarce hardware/DSP decoder instances needed by audible playback.
            observation.stage = DecoderStage.CODEC_SETUP
            val codecs=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            fun supported(info:android.media.MediaCodecInfo)=!info.isEncoder && info.supportedTypes.any {it.equals(mime,true)} &&
                (if(Build.VERSION.SDK_INT>=29)info.isSoftwareOnly else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))
            val software = if(flac!=null) {
                val name=PlatformFlacCodecPolicy.select(codecs.filter(::supported).map {it.name})
                codecs.firstOrNull {it.name==name}
            } else codecs.firstOrNull(::supported)
            if(software==null)throw AnalysisUnavailable("software-decoder-unavailable")
            codec = MediaCodec.createByCodecName(software.name)
            observation.packets = DecoderPacketAccounting(mime, software.name, PcmFormat(
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                if (!format.containsKey(MediaFormat.KEY_PCM_ENCODING)) PcmEncoding.UNKNOWN else when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                    C.ENCODING_PCM_16BIT -> PcmEncoding.PCM16
                    C.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT32
                    else -> PcmEncoding.UNKNOWN
                }), if(flac!=null)DecoderExtractor.MEDIA3_FLAC_1_7_1 else DecoderExtractor.ANDROID_MEDIA_EXTRACTOR)
            val encoderDelay=format.takeIf {it.containsKey(MediaFormat.KEY_ENCODER_DELAY)}?.getInteger(MediaFormat.KEY_ENCODER_DELAY)
            val encoderPadding=format.takeIf {it.containsKey(MediaFormat.KEY_ENCODER_PADDING)}?.getInteger(MediaFormat.KEY_ENCODER_PADDING)
            val configuredPadding=AacAnalysisTrimPolicy.codecPadding(corrected=preprocessing==AnalysisPreprocessing.BANDLIMITED,mime=mime,padding=encoderPadding)
            if(configuredPadding!=encoderPadding) {
                if(encoderPadding!!>AacAnalysisTailTrim.MAX_PADDING_FRAMES)throw PcmPreparationException(DecoderFailureSubtype.UNSUPPORTED_PCM)
                observation.aacTail=AacAnalysisTailTrim(encoderPadding,format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                format.setInteger(MediaFormat.KEY_ENCODER_PADDING,configuredPadding!!)
            }
            observation.packets?.trim(encoderDelay,encoderPadding,configuredPadding)
            observation.packets?.seek(seekTarget,null,null)
            observation.packets?.opusWindow(opusWindow)
            codec.configure(format, null, null, 0)
            codec.start()
            val targetRate = 22_050 // analysis-only fixed quality; audible PCM is never resampled here
            val capture = if (preprocessing == AnalysisPreprocessing.LEGACY) LegacyPcmWindowCapture(request,
                observation.effective!!, observation.duration!!, targetRate).also { observation.legacy = it } else null
            val corrected = if (preprocessing == AnalysisPreprocessing.BANDLIMITED) CorrectedPcmWindowCapture(request,
                observation.effective!!, observation.duration!!).also { observation.corrected = it } else null
            var inputEnded = false
            var outputEnded = false
            var outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = C.ENCODING_PCM_16BIT
            var formatBasis = SourceFormatBasis.CONTAINER_WITH_ASSUMED_PCM16
            val info = MediaCodec.BufferInfo()
            // Opus output is shortened/shifted by its declared codec delay. A packet
            // at the requested endpoint can still contain required real output PCM.
            val endUs = opusWindow?.inputBoundaryUs ?: (startMs + lengthMs) * 1000
            observation.stage = DecoderStage.CODEC_OUTPUT
            observation.codecEos = false
            while (!outputEnded && (capture?.let { it.written < it.capacity } ?: !corrected!!.reachedEnd)) {
                control.checkpoint()
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(5000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val packet=flac?.next()
                        val size=if(flac!=null) {
                            if(packet==null)-1 else {
                                buffer.clear()
                                if(packet.bytes.size>buffer.remaining())throw AnalysisUnavailable("unsupported-decoded-pcm")
                                buffer.put(packet.bytes);packet.bytes.size
                            }
                        } else extractor.readSampleData(buffer,0)
                        val sampleTime=if(flac!=null)packet?.ptsUs ?: -1L else extractor.sampleTime
                        if (size < 0 || sampleTime >= endUs) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                            observation.packets?.inputEnd(sampleTime)
                            observation.inputEnd = if (size < 0) DecoderInputEnd.NATURAL_EOS else DecoderInputEnd.REQUEST_BOUNDARY
                        } else {
                            if (flac==null && (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw AnalysisUnavailable("encrypted-source")
                            codec.queueInputBuffer(index, 0, size, sampleTime, 0)
                            observation.packets?.input(size, sampleTime)
                            if(flac==null)extractor.advance()
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 5000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = codec.outputFormat
                    formatBasis = SourceFormatBasis.DECODER_OUTPUT
                    outputRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else C.ENCODING_PCM_16BIT
                    if (outputRate !in 8000..192000 || channels !in 1..8 || encoding !in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) throw AnalysisUnavailable("unsupported-decoded-pcm")
                } else if (output >= 0) {
                    // This flag is already observed even if conversion/cancellation then fails.
                    observation.codecEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    observation.packets?.output(info.size, info.presentationTimeUs, observation.codecEos == true)
                    try {
                        val buffer = DecoderPcmBuffers.readable(if (info.size == 0) null else codec.getOutputBuffer(output),
                            info.offset, info.size, corrected != null)
                        val bytes = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
                        val frames = buffer.remaining() / (bytes * channels)
                        observation.codecOutputFrames = (observation.codecOutputFrames ?: 0) + frames
                        if (frames > 0) {
                            observation.firstCodecPtsUs = observation.firstCodecPtsUs ?: info.presentationTimeUs
                            observation.lastCodecFrameEndUs = info.presentationTimeUs + frames * 1_000_000L / outputRate
                        }
                        val observedFormat = SourcePcmFormat(PcmFormat(outputRate, channels,
                            if (encoding == C.ENCODING_PCM_FLOAT) PcmEncoding.FLOAT32 else PcmEncoding.PCM16), formatBasis)
                        observation.stage = DecoderStage.PCM_CONVERSION
                        val retainedBefore = if (onDecodedOutput != null)
                            corrected?.acceptedFrames ?: capture!!.written.toLong() else 0L
                        if (corrected != null) {
                            val tail=observation.aacTail
                            if(tail==null)corrected.accept(buffer,info.presentationTimeUs,observedFormat,control)
                            else tail.accept(buffer,info.presentationTimeUs,observedFormat,control) {pcm,pts,f -> corrected.accept(pcm,pts,f,control)}
                        }
                        else {
                            for (frame in 0 until frames) {
                                if (frame % 512 == 0) control.checkpoint()
                                var mono = 0f
                                repeat(channels) { mono += if (bytes == 4) buffer.float else buffer.short / 32768f }
                                mono /= channels
                                val timeUs = info.presentationTimeUs + frame * 1_000_000L / outputRate
                                capture!!.accept(mono, timeUs, observedFormat)
                            }
                        }
                        // Pre-sync/priming-only buffers must not trigger an accepted-output hook.
                        if (onDecodedOutput != null &&
                            (corrected?.acceptedFrames ?: capture!!.written.toLong()) > retainedBefore) {
                            onDecodedOutput.invoke()
                            control.checkpoint()
                        }
                        observation.stage = DecoderStage.CODEC_OUTPUT
                        outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    } finally { codec.releaseOutputBuffer(output, false) }
                }
            }
            if (capture != null) {
                if (capture.written < capture.capacity - targetRate / 2) throw AnalysisUnavailable("truncated-audio-window")
                return capture.finish(outputEnded)
            }
            observation.stage = DecoderStage.PCM_CONVERSION
            observation.aacTail?.finish(outputEnded && observation.inputEnd==DecoderInputEnd.NATURAL_EOS,control) {pcm,pts,f -> corrected!!.accept(pcm,pts,f,control)}
            observation.stage = DecoderStage.RESAMPLING
            return corrected!!.finish(outputEnded, control)
        } finally {
            observation.fetched = bridge.fetchedBytes
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { flac?.close() }; runCatching { extractor.release() }; runCatching { bridge.close() }
        }
    }

    /** Bounded random access. Range failures are errors, never whole-song download workarounds. */
    private class RangeSource(private val source: AnalysisSource, private val control: AnalysisControl) : MediaDataSource() {
        private var fetched = 0L
        val fetchedBytes get() = fetched
        private var closed = false
        private val blocks = object : LinkedHashMap<Long, ByteArray>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > 4
        }
        override fun getSize() = source.length.takeIf { it > 0 } ?: -1L
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            control.checkpoint()
            if (closed || position < 0) throw IOException("Analysis source closed")
            if (source.length > 0 && position >= source.length) return -1
            val blockStart = position / 65536 * 65536
            val block = blocks[blockStart] ?: run {
                if (fetched >= (if (source.fullyCached) 12 else 3) * 1024 * 1024) throw AnalysisUnavailable("analysis-byte-budget-exhausted")
                val data = source.factory.createDataSource()
                try {
                    val length = if (source.length > 0) min(65536L, source.length - blockStart) else 65536L
                    data.open(DataSpec.Builder().setUri(source.uri).setKey(source.cacheKey).setPosition(blockStart).setLength(length).build())
                    val bytes = ByteArray(length.toInt())
                    var count = 0
                    while (count < bytes.size) {
                        control.checkpoint()
                        val read = data.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        count += read; fetched += read
                    }
                    bytes.copyOf(count).also { blocks[blockStart] = it }
                } finally { data.close() }
            }
            val start = (position - blockStart).toInt()
            if (start >= block.size) return -1
            val count = min(size, block.size - start)
            block.copyInto(buffer, offset, start, start + count)
            return count
        }
        @Synchronized override fun close() { closed = true; blocks.clear() }
    }
}
