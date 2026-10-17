package com.music.vivi.playback.automix

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BoundedFlacPacketsTest {
    private fun bytes():ByteArray {
        val relative="src/androidTest/assets/automix/rhythm-128-44100.flac"
        return listOf(File(relative),File("app/$relative")).first{it.isFile}.readBytes()
    }
    @Test fun actualMedia3ParserEmitsAll288FramesWithTheFinal504SampleTail() {
        val bytes=bytes()
        BoundedFlacPackets(bytes.size.toLong(),AnalysisControl()){p,b,o,n ->
            if(p>=bytes.size)-1 else minOf(n,bytes.size-p.toInt(),977).also {bytes.copyInto(b,o,p.toInt(),p.toInt()+it)}
        }.use {reader ->
            assertEquals(44100,reader.format.sampleRate);assertEquals(1,reader.format.channelCount)
            assertEquals(30_000_000L,reader.durationUs)
            var count=0;var total=0;var last=-1L
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            while(true) {
                val packet=reader.next() ?: break
                val expected=count*4608L*1_000_000/44100
                assertEquals(expected,packet.ptsUs);assertTrue(packet.ptsUs>last);last=packet.ptsUs
                digest.update(packet.bytes)
                if(count==287)assertEquals(295,packet.bytes.size)
                count++;total+=packet.bytes.size
            }
            assertEquals(288,count);assertEquals(835064,total);assertEquals(29_988_571L,last)
            assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(8286,bytes.size)),digest.digest())
        }
    }
    @Test fun seekUsesBoundedRandomReadsAndRetainsTheRealFrameBeforeTheRequestedTime() {
        val bytes=bytes();var readBytes=0L
        BoundedFlacPackets(bytes.size.toLong(),AnalysisControl()){p,b,o,n ->
            if(p>=bytes.size)-1 else minOf(n,bytes.size-p.toInt(),4096).also {bytes.copyInto(b,o,p.toInt(),p.toInt()+it);readBytes+=it}
        }.use {reader ->
            reader.seek(16_000_000)
            var packet=requireNotNull(reader.next());val first=packet.ptsUs
            assertTrue(first in 15_895_510L..16_000_000L)
            var last=first
            while(true){packet=reader.next() ?: break;assertTrue(packet.ptsUs>last);last=packet.ptsUs}
            assertEquals(29_988_571L,last);assertTrue(readBytes<3*1024*1024)
            println("FLAC_MEDIA3_SEEK firstPtsUs=$first lastPtsUs=$last bytesRead=$readBytes")
        }
    }
    @Test fun cancellationDuringActualFrameReadAndDeadlineRemainCooperative() {
        val bytes=bytes();val token=AnalysisCancellationToken();var armed=false;var triggered=false
        BoundedFlacPackets(bytes.size.toLong(),AnalysisControl(cancellation=token)){p,b,o,n ->
            if(armed){token.cancel();triggered=true}
            if(p>=bytes.size)-1 else minOf(n,bytes.size-p.toInt(),997).also {bytes.copyInto(b,o,p.toInt(),p.toInt()+it)}
        }.use {reader ->
            armed=true
            assertThrows(java.util.concurrent.CancellationException::class.java){while(reader.next()!=null){}}
            assertTrue(triggered)
        }
        val error=assertThrows(AnalysisUnavailable::class.java) {
            BoundedFlacPackets(bytes.size.toLong(),AnalysisControl(System.nanoTime()-1)){_,_,_,_->error("Expired reader accessed source")}
        }
        assertEquals("ANALYSIS_TIMEOUT",error.reason)
    }
    @Test fun oversizedDeclaredFramesAreRejectedBeforeFrameAllocation() {
        val bytes=bytes();bytes[15]=0x20;bytes[16]=0;bytes[17]=0
        val failure=assertThrows(PcmPreparationException::class.java) {
            BoundedFlacPackets(bytes.size.toLong(),AnalysisControl()){p,b,o,n ->
                if(p>=bytes.size)-1 else minOf(n,bytes.size-p.toInt()).also {bytes.copyInto(b,o,p.toInt(),p.toInt()+it)}
            }
        }
        assertEquals(DecoderFailureSubtype.FLAC_PARSER_BUDGET,failure.subtype)
    }
    @Test fun oversizedEmbeddedMetadataIsRejectedBeforeItsPayloadIsReadOrAllocated() {
        val bytes=bytes();bytes[5]=0x7f;bytes[6]=0xff.toByte();bytes[7]=0xff.toByte()
        var fetched=0
        val failure=assertThrows(PcmPreparationException::class.java) {
            BoundedFlacPackets(bytes.size.toLong(),AnalysisControl()){p,b,o,n ->
                require(p<16){"Oversized metadata payload was read"}
                minOf(n,bytes.size-p.toInt()).also {bytes.copyInto(b,o,p.toInt(),p.toInt()+it);fetched+=it}
            }
        }
        assertEquals(DecoderFailureSubtype.FLAC_PARSER_BUDGET,failure.subtype);assertTrue(fetched<=8)
    }

}
