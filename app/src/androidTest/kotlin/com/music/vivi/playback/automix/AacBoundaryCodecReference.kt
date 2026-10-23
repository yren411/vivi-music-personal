package com.music.vivi.playback.automix

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import java.io.File

/** Independent forward decode from the real first compressed packet. No production seek
 * policy/tail queue, no fake PTS. Generated PNS-disabled AAC makes exact seek parity useful. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object AacBoundaryCodecReference {
    data class Result(val window:DecodedWindow?,val failure:DecoderFailureSubtype?,val firstInputPtsUs:Long?,
        val firstOutputPtsUs:Long?,val delayFrames:Int?,val acceptedFrames:Long)
    fun decode(file:File,request:DecodeRequest,control:AnalysisControl,dropPacket:Boolean=false):Result {
        val extractor=MediaExtractor();var codec:MediaCodec?=null
        val capture=CorrectedPcmWindowCapture(request,request.interval,AudioDuration(180_000_000,DurationBasis.FIXTURE))
        var firstInput:Long?=null;var firstOutput:Long?=null;var delay:Int?=null
        try {
            extractor.setDataSource(file.absolutePath)
            val track=(0 until extractor.trackCount).first {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm"}
            extractor.selectTrack(track)
            val f=extractor.getTrackFormat(track)
            delay=if(f.containsKey(MediaFormat.KEY_ENCODER_DELAY))f.getInteger(MediaFormat.KEY_ENCODER_DELAY) else null
            f.setInteger(MediaFormat.KEY_ENCODER_PADDING,0)
            val selected=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first {
                !it.isEncoder && it.supportedTypes.contains("audio/mp4a-latm") &&
                    (it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.")) }
            codec=MediaCodec.createByCodecName(selected.name);codec.configure(f,null,null,0);codec.start()
            var inputEnded=false;var eos=false;var dropped=false
            var rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);var channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding=PcmEncoding.PCM16
            val info=MediaCodec.BufferInfo()
            while(!eos && !capture.reachedEnd) {
                control.checkpoint()
                if(!inputEnded) {
                    val slot=codec.dequeueInputBuffer(5000)
                    if(slot>=0) {
                        if(dropPacket && !dropped && extractor.sampleTime>=100_000) {extractor.advance();dropped=true}
                        val buffer=codec.getInputBuffer(slot)!!;val size=extractor.readSampleData(buffer,0);val pts=extractor.sampleTime
                        if(size<0 || pts>=request.interval.endUs) {
                            codec.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true
                        } else {
                            firstInput=firstInput ?: pts
                            codec.queueInputBuffer(slot,0,size,pts,0);extractor.advance()
                        }
                    }
                }
                val slot=codec.dequeueOutputBuffer(info,5000)
                if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual=codec.outputFormat
                    rate=actual.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding=if(actual.containsKey(MediaFormat.KEY_PCM_ENCODING) && actual.getInteger(MediaFormat.KEY_PCM_ENCODING)==C.ENCODING_PCM_FLOAT)
                        PcmEncoding.FLOAT32 else PcmEncoding.PCM16
                } else if(slot>=0) {
                    try {
                        val pcm=DecoderPcmBuffers.readable(if(info.size==0)null else codec.getOutputBuffer(slot),info.offset,info.size,true)
                        if(info.size>0)firstOutput=firstOutput ?: info.presentationTimeUs
                        capture.accept(pcm,info.presentationTimeUs,SourcePcmFormat(PcmFormat(rate,channels,encoding),SourceFormatBasis.DECODER_OUTPUT),control)
                        eos=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    } finally {codec.releaseOutputBuffer(slot,false)}
                }
            }
            return Result(capture.finish(eos,control),null,firstInput,firstOutput,delay,capture.acceptedFrames)
        } catch(e:PcmPreparationException) {
            return Result(null,e.subtype,firstInput,firstOutput,delay,capture.acceptedFrames)
        } finally {runCatching {codec?.stop()};runCatching {codec?.release()};extractor.release()}
    }
}
