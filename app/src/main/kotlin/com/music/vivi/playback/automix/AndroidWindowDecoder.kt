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
import java.nio.ByteOrder
import kotlin.math.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class AnalysisSource(val uri: Uri, val cacheKey: String, val length: Long,
                          val identity: String, val factory: DataSource.Factory, val fullyCached: Boolean = false)

/** Android's decoder, fed through the SAME permitted cache/CDN path as playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidWindowDecoder {
    fun analyze(source: AnalysisSource, startMs: Long, requestedLengthMs: Long,
                deadlineNanos: Long, detail: AnalysisDetail = AnalysisDetail.DETAIL): TrackFeatures {
        val extractor = MediaExtractor()
        val bridge = RangeSource(source, deadlineNanos)
        var codec: MediaCodec? = null
        try {
            checkAnalysisCancellation(deadlineNanos)
            if (!source.fullyCached && source.uri.scheme in listOf("http", "https")) {
                val probe = source.factory.createDataSource()
                try {
                    // Uncached, read-only probe: no prefix-discard workaround for non-range servers.
                    probe.open(DataSpec.Builder().setUri(source.uri).setKey(source.cacheKey + ":automix-range-probe")
                        .setPosition(0).setLength(1).build())
                    val range = probe.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
                    if (range == null || !range.startsWith("bytes 0-")) throw IOException("Source does not establish byte-range support")
                } finally { probe.close() }
            }
            extractor.setDataSource(bridge)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No analyzable audio track")
            val format = extractor.getTrackFormat(track)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            if (durationMs <= 0 || durationMs > 30 * 60_000 || startMs >= durationMs) throw IOException("Unsupported analysis duration")
            val lengthMs = min(requestedLengthMs, durationMs - startMs).coerceAtMost(32_000)
            if (lengthMs < 4000) throw IOException("Insufficient audio")
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            // Do not consume scarce hardware/DSP decoder instances needed by audible playback.
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
                    (if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
                     else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android."))
            } ?: throw IOException("No software analysis decoder")
            codec = MediaCodec.createByCodecName(software.name)
            codec.configure(format, null, null, 0)
            codec.start()
            val targetRate = 22_050 // analysis-only fixed quality; audible PCM is never resampled here
            val samples = FloatArray((lengthMs * targetRate / 1000).toInt())
            var written = 0
            var inputEnded = false
            var outputEnded = false
            var outputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = C.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            val endUs = (startMs + lengthMs) * 1000
            while (!outputEnded && written < samples.size) {
                checkAnalysisCancellation(deadlineNanos)
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(5000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime >= endUs) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw IOException("Encrypted analysis is unsupported")
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 5000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = codec.outputFormat
                    outputRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else C.ENCODING_PCM_16BIT
                    if (outputRate !in 8000..192000 || channels !in 1..8 || encoding !in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) throw IOException("Unsupported decoded PCM")
                } else if (output >= 0) {
                    try {
                        val buffer = codec.getOutputBuffer(output)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
                        val frames = buffer.remaining() / (bytes * channels)
                        for (frame in 0 until frames) {
                            if (frame % 512 == 0) checkAnalysisCancellation(deadlineNanos)
                            var mono = 0f
                            repeat(channels) { mono += if (bytes == 4) buffer.float else buffer.short / 32768f }
                            mono /= channels
                            val timeUs = info.presentationTimeUs + frame * 1_000_000L / outputRate
                            if (timeUs >= startMs * 1000 && timeUs < endUs) {
                                val index = ((timeUs - startMs * 1000) * targetRate / 1_000_000).toInt()
                                if (index in written until samples.size) {
                                    // Fixed nearest-time analysis conversion, not a playback-rate adjustment.
                                    while (written <= index) samples[written++] = mono
                                }
                            }
                        }
                        outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    } finally { codec.releaseOutputBuffer(output, false) }
                }
            }
            if (written < samples.size - targetRate / 2) throw IOException("Truncated audio window")
            return TrackFeaturesAnalyzer().analyze(samples.copyOf(written), targetRate, startMs, durationMs, deadlineNanos, detail)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { extractor.release() }; runCatching { bridge.close() }
        }
    }

    /** Bounded random access. Range failures are errors, never whole-song download workarounds. */
    private class RangeSource(private val source: AnalysisSource, private val deadline: Long) : MediaDataSource() {
        private var fetched = 0L
        private var closed = false
        private val blocks = object : LinkedHashMap<Long, ByteArray>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > 4
        }
        override fun getSize() = source.length.takeIf { it > 0 } ?: -1L
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            checkAnalysisCancellation(deadline)
            if (closed || position < 0) throw IOException("Analysis source closed")
            if (source.length > 0 && position >= source.length) return -1
            val blockStart = position / 65536 * 65536
            val block = blocks[blockStart] ?: run {
                if (fetched >= (if (source.fullyCached) 12 else 3) * 1024 * 1024) throw IOException("Analysis byte budget exhausted")
                val data = source.factory.createDataSource()
                try {
                    val length = if (source.length > 0) min(65536L, source.length - blockStart) else 65536L
                    data.open(DataSpec.Builder().setUri(source.uri).setKey(source.cacheKey).setPosition(blockStart).setLength(length).build())
                    val bytes = ByteArray(length.toInt())
                    var count = 0
                    while (count < bytes.size) {
                        checkAnalysisCancellation(deadline)
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
