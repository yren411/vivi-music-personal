package com.music.vivi.playback.automix

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Inspect only committed generated fixtures; no runtime parser or real-song claims. */
class AacBoundaryEncodedFixtureTest {
    private class Mp4(name:String) {
        private val relative="src/androidTest/assets/automix/$name"
        val data=listOf(File(relative),File("app/$relative")).first {it.isFile}.readBytes()
        val b=ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val root=data.indices
        fun box(parent:IntRange,name:String):IntRange {
            var p=parent.first
            while(p+8<=parent.last+1) {
                val n=b.getInt(p);check(n>=8 && n<=parent.last+1-p)
                if(String(data,p+4,4,Charsets.US_ASCII)==name)return p+8 until p+n
                p+=n
            }
            error("Missing generated fixture box $name")
        }
        val moov=box(root,"moov");val trak=box(moov,"trak")
        fun edit()=box(box(trak,"edts"),"elst").first
        fun media()=box(box(trak,"mdia"),"mdhd").first
        fun payloadSha():ByteArray {val m=box(root,"mdat");return MessageDigest.getInstance("SHA-256").digest(data.copyOfRange(m.first,m.last+1))}
    }
    @Test fun genuineEmptyEditAdds6042PresentationFramesWithoutRemovingEncodedAudio() {
        val a=Mp4("boundary-empty-edit6042-44100-stereo.m4a")
        val old=Mp4("boundary-padding14-44100-stereo.m4a")
        val movie=a.box(a.moov,"mvhd").first
        assertEquals(44100,a.b.getInt(movie+12));assertEquals(44100,a.b.getInt(a.media()+12))
        val e=a.edit();assertEquals(2,a.b.getInt(e+4))
        assertEquals(6042,a.b.getInt(e+8));assertEquals(-1,a.b.getInt(e+12))
        assertEquals(0,a.b.getInt(e+24))
        assertArrayEquals(old.payloadSha(),a.payloadSha())
        assertEquals(137006L,6042*1_000_000L/44100)
    }
    @Test fun roundedMovieDurationDoesNotCreateSixAdditionalDecodedSourceFrames() {
        val a=Mp4("boundary-rounded-end-44100-stereo.m4a")
        assertEquals(1000,a.b.getInt(a.box(a.moov,"mvhd").first+12))
        assertEquals(44100,a.b.getInt(a.media()+12))
        assertEquals(6_839_296,a.b.getInt(a.media()+16))
        val e=a.edit();assertEquals(1,a.b.getInt(e+4));assertEquals(155063,a.b.getInt(e+8));assertEquals(1024,a.b.getInt(e+12))
        val realFrames=a.b.getInt(a.media()+16)-1024
        assertEquals(6_838_272,realFrames)
        assertEquals(155_062_857L,realFrames*1_000_000L/44100)
        assertTrue(155_063_000-realFrames*1_000_000.0/44100>140.0)
    }
}
