package com.music.vivi.playback.automix

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Checks the committed encoded fixture, not a mock format or generator's return value. */
class AacEncodedFixtureContractTest {
    @Test fun actualMp4DeclaresFourteenTailFramesWithoutChangingPresentationOrEncodedPayload() {
        val path="src/androidTest/assets/automix/boundary-padding14-44100-stereo.m4a"
        val bytes=listOf(File(path),File("app/$path")).first{it.isFile}.readBytes()
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun box(parent:IntRange,name:String):IntRange {
            var i=parent.first
            while(i+8<=parent.last+1) {
                val n=b.getInt(i);assertTrue(n>=8 && n<=parent.last+1-i)
                if(String(bytes,i+4,4,Charsets.US_ASCII)==name)return i+8 until i+n
                i+=n
            }
            throw AssertionError("Missing generated MP4 box: $name")
        }
        val root=bytes.indices;val moov=box(root,"moov");val trak=box(moov,"trak")
        val mdia=box(trak,"mdia");val mdhd=box(mdia,"mdhd");val elst=box(box(trak,"edts"),"elst")
        val stts=box(box(box(mdia,"minf"),"stbl"),"stts")
        assertEquals(0,bytes[mdhd.first].toInt());assertEquals(44100,b.getInt(mdhd.first+12))
        val mediaFrames=b.getInt(mdhd.first+16)
        assertEquals(0,bytes[elst.first].toInt());assertEquals(1,b.getInt(elst.first+4))
        val presentationFrames=b.getInt(elst.first+8);val primingFrames=b.getInt(elst.first+12)
        assertEquals(1_572_850,presentationFrames);assertEquals(1024,primingFrames)
        assertEquals(14,mediaFrames-presentationFrames-primingFrames)
        assertEquals(2,b.getInt(stts.first+4))
        assertEquals(1536,b.getInt(stts.first+8));assertEquals(1024,b.getInt(stts.first+12))
        assertEquals(1,b.getInt(stts.first+16));assertEquals(1024,b.getInt(stts.first+20))
        assertEquals(1537*1024,mediaFrames)
        val mdat=box(root,"mdat")
        val sha=MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(mdat.first,mdat.last+1)).joinToString(""){"%02x".format(it)}
        assertEquals("53e073218980ab635f1d42e0e5a3b322d43533fe4f568fab2e216d5e6966a481",sha)
        println("AAC_ENCODED_FIXTURE mediaFrames=$mediaFrames presentationFrames=$presentationFrames primingFrames=$primingFrames tailPaddingFrames=14 mdatSha256=$sha")
    }
}
