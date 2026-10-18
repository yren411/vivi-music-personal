package com.music.vivi.playback.automix

enum class DecoderExtractor { ANDROID_MEDIA_EXTRACTOR, MEDIA3_FLAC_1_7_1 }

/** Actual queue/dequeue counters only. No PCM, per-packet history or source identity is retained. */
data class DecoderCodecProgress(
    val extractorMime: String?, val codecName: String?, val extractorFormat: PcmFormat?,
    val inputBuffers: Long, val inputBytes: Long, val firstInputPtsUs: Long?, val lastInputPtsUs: Long?,
    val lastInputBytes: Int?, val extractorEndSampleTimeUs: Long?,
    val outputBuffers: Long, val outputBytes: Long, val firstOutputPtsUs: Long?, val lastOutputPtsUs: Long?,
    val outputEosBytes: Int?, val outputEosPtsUs: Long?,
    val extractor: DecoderExtractor = DecoderExtractor.ANDROID_MEDIA_EXTRACTOR,
    val encoderDelayFrames:Int?=null, val encoderPaddingFrames:Int?=null, val codecPaddingFrames:Int?=null,
    val seekTargetUs:Long?=null, val extractorBeforeSeekUs:Long?=null, val extractorAfterSeekUs:Long?=null,
    val heldAacTailFrames:Int?=null, val discardedAacTailFrames:Long?=null,
) {
    fun reportFields(): String = buildString {
        // Platform strings are still untrusted for public export. Never print arbitrary MIME/name text.
        val mime=extractorMime?.takeIf {it.matches(Regex("audio/[A-Za-z0-9.+_-]{1,80}"))}
        val codec=codecName?.takeIf {it.matches(Regex("[A-Za-z0-9._-]{1,120}"))}
        append(" codecProgressSchema=1 extractorImplementation=$extractor extractorMime=$mime codecName=$codec extractorFormat=$extractorFormat")
        append(" queuedInputBuffers=$inputBuffers queuedInputBytes=$inputBytes firstInputPtsUs=$firstInputPtsUs lastInputPtsUs=$lastInputPtsUs lastInputBytes=$lastInputBytes")
        append(" extractorEndSampleTimeUs=$extractorEndSampleTimeUs outputDataBuffers=$outputBuffers outputBytes=$outputBytes")
        append(" firstOutputPtsUs=$firstOutputPtsUs lastOutputPtsUs=$lastOutputPtsUs eosBytes=$outputEosBytes eosPtsUs=$outputEosPtsUs")
        append(" codecTimelineSchema=2 encoderDelayFrames=$encoderDelayFrames encoderPaddingFrames=$encoderPaddingFrames codecPaddingFrames=$codecPaddingFrames")
        append(" seekTargetUs=$seekTargetUs extractorBeforeSeekUs=$extractorBeforeSeekUs extractorAfterSeekUs=$extractorAfterSeekUs")
        append(" heldAacTailFrames=$heldAacTailFrames discardedAacTailFrames=$discardedAacTailFrames")
    }
}

internal class DecoderPacketAccounting(private val mime:String?,private val codec:String?,private val format:PcmFormat?,private val extractor:DecoderExtractor=DecoderExtractor.ANDROID_MEDIA_EXTRACTOR) {
    private var inputBuffers=0L;private var inputBytes=0L
    private var firstInputPts:Long?=null;private var lastInputPts:Long?=null;private var lastInputBytes:Int?=null
    private var inputEnd:Long?=null
    private var outputBuffers=0L;private var outputBytes=0L
    private var firstOutputPts:Long?=null;private var lastOutputPts:Long?=null
    private var eosBytes:Int?=null;private var eosPts:Long?=null
    private var delay:Int?=null;private var padding:Int?=null;private var codecPadding:Int?=null
    private var seekTarget:Long?=null;private var beforeSeek:Long?=null;private var afterSeek:Long?=null
    private var held:Int?=null;private var discarded:Long?=null
    fun trim(delay:Int?,padding:Int?,codecPadding:Int?) {this.delay=delay;this.padding=padding;this.codecPadding=codecPadding}
    fun seek(target:Long?,before:Long?,after:Long?) {seekTarget=target;beforeSeek=before;afterSeek=after}
    fun tail(held:Int?,discarded:Long?) {this.held=held;this.discarded=discarded}
    /** Called only after a real non-EOS packet was queued successfully. */
    fun input(bytes:Int,ptsUs:Long) {
        require(bytes>=0)
        inputBuffers++;inputBytes+=bytes;firstInputPts=firstInputPts ?: ptsUs;lastInputPts=ptsUs;lastInputBytes=bytes
    }
    /** Negative sampleTime is the extractor's observed EOF sentinel, not a track timestamp. */
    fun inputEnd(sampleTimeUs:Long) {inputEnd=sampleTimeUs}
    fun output(bytes:Int,ptsUs:Long,eos:Boolean) {
        require(bytes>=0)
        if(bytes>0) {outputBuffers++;outputBytes+=bytes;firstOutputPts=firstOutputPts ?: ptsUs;lastOutputPts=ptsUs}
        if(eos) {eosBytes=bytes;eosPts=ptsUs}
    }
    fun snapshot()=DecoderCodecProgress(mime,codec,format,inputBuffers,inputBytes,firstInputPts,lastInputPts,lastInputBytes,inputEnd,
        outputBuffers,outputBytes,firstOutputPts,lastOutputPts,eosBytes,eosPts,extractor,delay,padding,codecPadding,seekTarget,beforeSeek,afterSeek,held,discarded)
}
