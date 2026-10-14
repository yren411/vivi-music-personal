package com.music.vivi.playback.automix

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Also used by fake storage tests. Publish only a successfully closed/written document. */
interface MixLogDestination {
    fun create(filename: String): String
    fun write(document: String,bytes: ByteArray)
    fun publish(document: String)
    fun remove(document: String)
}
class MixLogExportGate {
    private val busy=AtomicBoolean(false)
    fun begin()=busy.compareAndSet(false,true)
    fun finish() { busy.set(false) }
}
object MixLogWriter {
    fun filename(time: LocalDateTime=LocalDateTime.now(), unique: String=UUID.randomUUID().toString().take(8)) =
        "tinium-automix-log-${time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"))}-${unique.filter { it.isLetterOrDigit() }.take(12)}.txt"
    fun save(destination: MixLogDestination,filename: String,report: String,checkActive: ()->Unit={}): String {
        checkActive()
        val document=destination.create(filename)
        try {
            checkActive();destination.write(document,report.toByteArray(Charsets.UTF_8))
            checkActive();destination.publish(document)
            return document
        } catch(error: Throwable) {
            runCatching { destination.remove(document) }
            throw error
        }
    }
}

/** API 29+ scoped Downloads. Each insert owns a fresh URI; no broad storage permission. */
class DownloadsMixLogDestination(private val resolver: ContentResolver): MixLogDestination {
    override fun create(filename: String): String {
        check(Build.VERSION.SDK_INT>=29)
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,filename)
            put(MediaStore.MediaColumns.MIME_TYPE,"text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        return (resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
            ?: throw IOException("Could not create diagnostic document")).toString()
    }
    override fun write(document: String,bytes: ByteArray) {
        (resolver.openOutputStream(Uri.parse(document),"w") ?: throw IOException("No diagnostic output stream"))
            .use { it.write(bytes);it.flush() }
    }
    override fun publish(document: String) {
        val values=ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) }
        if(resolver.update(Uri.parse(document),values,null,null)!=1) throw IOException("Could not publish diagnostic document")
    }
    override fun remove(document: String) { resolver.delete(Uri.parse(document),null,null) }
}

/** API 26–28 user-selected CreateDocument URI. The picker grants just that document. */
class SelectedMixLogDestination(private val resolver: ContentResolver,private val uri: Uri): MixLogDestination {
    override fun create(filename: String)=uri.toString()
    override fun write(document: String,bytes: ByteArray) {
        (resolver.openOutputStream(uri,"w") ?: throw IOException("No selected diagnostic stream"))
            .use { it.write(bytes);it.flush() }
    }
    override fun publish(document: String) {}
    override fun remove(document: String) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver,uri) } }
}
