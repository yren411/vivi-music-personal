package com.music.vivi.playback.automix

import java.nio.ByteBuffer
import kotlin.math.roundToLong

/** MediaCodec's SkipCutBuffer retains encoder-padding PCM but keeps the new buffer's
 * PTS on old retained bytes. For corrected AAC only, configure codec padding=0 and
 * retain that tail here with each segment's ORIGINAL source PTS instead.
 * Natural codec EOS drops only the declared padding; artificial request EOS releases it.
 * No sample interpolation, clock stretching or gap tolerance change is involved.
 */
internal class AacAnalysisTailTrim(private val paddingFrames: Int, private val configuredRate: Int) {
    init { require(paddingFrames in 0..MAX_PADDING_FRAMES && configuredRate in 8000..192000) }
    private data class Pending(val bytes: ByteBuffer, val ptsUs: Double, val format: SourcePcmFormat)
    private val pending=java.util.ArrayDeque<Pending>()
    private var heldFrames=0
    private var actualFormat:SourcePcmFormat?=null
    var discardedAtNaturalEos=0L
        private set
    val retainedFrames get()=heldFrames

    fun accept(buffer:ByteBuffer,ptsUs:Long,format:SourcePcmFormat,control:AnalysisControl,
               emit:(ByteBuffer,Long,SourcePcmFormat)->Unit) {
        control.checkpoint()
        val rate=requireNotNull(format.format.sampleRateHz)
        val channels=requireNotNull(format.format.channels)
        val bytes=when(format.format.encoding) {PcmEncoding.PCM16->2;PcmEncoding.FLOAT32->4
            else->throw PcmPreparationException(DecoderFailureSubtype.UNSUPPORTED_PCM)}
        if(rate !in 8000..192000 || channels !in 1..8 || buffer.remaining()%(bytes*channels)!=0)
            throw PcmPreparationException(DecoderFailureSubtype.MALFORMED_PCM_BUFFER)
        if(actualFormat!=null && actualFormat!=format)throw PcmPreparationException(DecoderFailureSubtype.FORMAT_CHANGED)
        actualFormat=format
        val frameBytes=bytes*channels
        val frames=buffer.remaining()/frameBytes
        val retain=(paddingFrames.toLong()*rate/configuredRate).toInt()
        if(retain>MAX_PADDING_FRAMES)throw PcmPreparationException(DecoderFailureSubtype.UNSUPPORTED_PCM)
        var emitFrames=(heldFrames+frames-retain).coerceAtLeast(0)
        while(emitFrames>0 && pending.isNotEmpty()) {
            control.checkpoint()
            val p=pending.removeFirst()
            val n=minOf(emitFrames,p.bytes.remaining()/frameBytes)
            val part=p.bytes.duplicate().apply {limit(position()+n*frameBytes)}
            emit(part,p.ptsUs.roundToLong(),p.format)
            p.bytes.position(p.bytes.position()+n*frameBytes)
            heldFrames-=n;emitFrames-=n
            if(p.bytes.hasRemaining())pending.addFirst(p.copy(ptsUs=p.ptsUs+n*1_000_000.0/rate))
        }
        val input=buffer.duplicate()
        if(emitFrames>0) {
            val part=input.duplicate().apply {limit(position()+emitFrames*frameBytes)}
            emit(part,ptsUs,format)
            input.position(input.position()+emitFrames*frameBytes)
        }
        if(input.hasRemaining()) {
            // Only the withheld tail is copied before the codec recycles its buffer.
            // At most 8192 source frames, including segmented timestamps; no PCM cache.
            val tail=ByteBuffer.allocate(input.remaining()).put(input).apply {flip()}
            pending.addLast(Pending(tail,ptsUs+emitFrames*1_000_000.0/rate,format))
            heldFrames+=tail.remaining()/frameBytes
        }
    }

    fun finish(naturalEos:Boolean,control:AnalysisControl,emit:(ByteBuffer,Long,SourcePcmFormat)->Unit) {
        try {
            while(pending.isNotEmpty()) {
                control.checkpoint()
                val p=pending.removeFirst()
                if(!naturalEos)emit(p.bytes,p.ptsUs.roundToLong(),p.format)
            }
            if(naturalEos)discardedAtNaturalEos+=heldFrames
        } finally {pending.clear();heldFrames=0}
    }

    companion object {const val MAX_PADDING_FRAMES=8192}
}

/** Unchanged codec policy for legacy and non-AAC formats. Front/encoder-delay trim
 * remains codec-owned, including preserved negative initial packets. */
internal object AacAnalysisTrimPolicy {
    fun codecPadding(corrected:Boolean,mime:String,padding:Int?) =
        if(corrected && mime=="audio/mp4a-latm" && padding!=null && padding>0)0 else padding
}
