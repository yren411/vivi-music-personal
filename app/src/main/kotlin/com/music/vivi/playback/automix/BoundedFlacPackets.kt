package com.music.vivi.playback.automix

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.*
import androidx.media3.extractor.flac.FlacExtractor
import java.io.EOFException

/** Existing Media3 parser, not a decoder. Emits one FLAC frame with its header-derived PTS.
 * All reads still go through the caller's authorized, byte-budgeted random-access bridge. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class BoundedFlacPackets(private val length:Long,private val control:AnalysisControl,
    private val readAt:(Long,ByteArray,Int,Int)->Int):AutoCloseable {
    data class Packet(val bytes:ByteArray,val ptsUs:Long)
    private val extractor=FlacExtractor(FlacExtractor.FLAG_DISABLE_ID3_METADATA)
    private var input:DefaultExtractorInput
    private var cursor=0L
    private var seekMap:SeekMap?=null
    private var streamFormat:Format?=null
    private var pending:Packet?=null
    private var lastPts:Long?=null
    private var end=false
    private var data=ByteArray(32768)
    private var size=0
    val format:Format get()=requireNotNull(streamFormat)
    val durationUs:Long get()=requireNotNull(seekMap).durationUs
    init {
        guardMetadataAllocations()
        input=open(0)
        extractor.init(object:ExtractorOutput {
            override fun track(id:Int,type:Int):TrackOutput {
                require(type==C.TRACK_TYPE_AUDIO)
                return object:TrackOutput {
                    override fun format(format:Format) {
                        require(format.sampleMimeType=="audio/flac" && format.sampleRate in 8000..192000 && format.channelCount in 1..8)
                        if(format.maxInputSize>MAX_FRAME_BYTES)throw PcmPreparationException(DecoderFailureSubtype.FLAC_PARSER_BUDGET)
                        streamFormat=format
                    }
                    override fun sampleData(source:DataReader,length:Int,allowEndOfInput:Boolean,sampleDataPart:Int):Int {
                        reserve(length)
                        val count=source.read(data,size,length)
                        if(count<0){if(allowEndOfInput)return C.RESULT_END_OF_INPUT;throw EOFException()}
                        size+=count;return count
                    }
                    override fun sampleData(source:ParsableByteArray,length:Int,sampleDataPart:Int) {
                        control.checkpoint();reserve(length);source.readBytes(data,size,length);size+=length
                    }
                    override fun sampleMetadata(timeUs:Long,flags:Int,frameSize:Int,offset:Int,cryptoData:TrackOutput.CryptoData?) {
                        require(cryptoData==null && offset==0 && pending==null && frameSize==size && frameSize in 1..MAX_FRAME_BYTES)
                        require(timeUs>=0 && (lastPts==null || timeUs>lastPts!!)){"FLAC_UNORDERED_FRAMES"}
                        lastPts=timeUs;pending=Packet(data.copyOf(size),timeUs);size=0
                    }
                }
            }
            override fun endTracks() {}
            override fun seekMap(value:SeekMap){seekMap=value}
        })
        while(streamFormat==null || seekMap==null) {check(!end);pump()}
    }
    /** Validate lengths before Media3 can allocate an embedded metadata block. Only headers
     * are read here; source fetch limits remain enforced by the caller's bridge. */
    private fun guardMetadataAllocations() {
        fun header(position:Long,count:Int):ByteArray {
            val result=ByteArray(count);var read=0
            while(read<count) {
                control.checkpoint()
                val n=readAt(position+read,result,read,count-read)
                if(n<0)throw EOFException()
                require(n>0 && n<=count-read);read+=n
            }
            return result
        }
        fun budget(position:Long,blocks:Int) {
            if(position>MAX_METADATA_BYTES || blocks>256)throw PcmPreparationException(DecoderFailureSubtype.FLAC_PARSER_BUDGET)
        }
        var position=0L;var blocks=0
        var marker=header(position,4)
        while(marker[0]==73.toByte() && marker[1]==68.toByte() && marker[2]==51.toByte()) {
            val id3=header(position,10)
            require((6..9).all {(id3[it].toInt() and 128)==0})
            val size=(6..9).fold(0){value,i->(value shl 7) or id3[i].toInt()}
            position+=10L+size+if((id3[5].toInt() and 16)!=0)10 else 0
            budget(position,++blocks);marker=header(position,4)
        }
        require(marker.contentEquals(byteArrayOf(102,76,97,67))){"INVALID_FLAC_HEADER"}
        position+=4
        do {
            val h=header(position,4)
            val size=((h[1].toInt() and 255) shl 16) or ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
            position+=4L+size;budget(position,++blocks)
            val last=(h[0].toInt() and 128)!=0
        }while(!last)
    }
    private fun reserve(count:Int) {
        if(count<0 || count>MAX_FRAME_BYTES-size)throw PcmPreparationException(DecoderFailureSubtype.FLAC_PARSER_BUDGET)
        if(size+count>data.size)data=data.copyOf(minOf(MAX_FRAME_BYTES,maxOf(size+count,data.size*2)))
    }
    private fun open(position:Long):DefaultExtractorInput {
        require(position>=0 && (length<0 || position<=length))
        cursor=position
        return DefaultExtractorInput(DataReader {buffer,offset,count ->
            control.checkpoint()
            val read=readAt(cursor,buffer,offset,count)
            require(read!=0 || count==0){"FLAC_SOURCE_NO_PROGRESS"}
            if(read>0)cursor=Math.addExact(cursor,read.toLong())
            read
        },position,length)
    }
    fun seek(timeUs:Long) {
        require(timeUs>=0)
        if(timeUs==0L)return // initial FLAC header position already established; no priming PCM invented
        val map=requireNotNull(seekMap)
        if(!map.isSeekable)throw AnalysisUnavailable("source-not-range-seekable")
        val position=map.getSeekPoints(timeUs).first.position
        extractor.seek(position,timeUs);input=open(position);pending=null;lastPts=null;size=0;end=false
    }
    fun next():Packet? {
        control.checkpoint()
        while(pending==null && !end)pump()
        return pending.also {pending=null}
    }
    private fun pump() {
        control.checkpoint()
        val position=PositionHolder()
        when(extractor.read(input,position)) {
            Extractor.RESULT_CONTINUE -> Unit
            Extractor.RESULT_SEEK -> input=open(position.position)
            Extractor.RESULT_END_OF_INPUT -> end=true
        }
    }
    override fun close(){extractor.release();pending=null;data=ByteArray(0)}
    companion object {const val MAX_FRAME_BYTES=1024*1024;const val MAX_METADATA_BYTES=4L*1024*1024}
}
