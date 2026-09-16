package com.example.thinkv2.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.security.MessageDigest

interface VoiceDecoder : AutoCloseable {
    fun accept(samples: ShortArray,count: Int)
    fun finish(): String
}

/** Only this reviewed, APK-packaged model is loaded. No URL, user model, grammar or auto-download. */
class OfflineEngine(context: Context) : AutoCloseable {
    private val app=context.applicationContext
    private var model: Model?=null
    fun prepare() {
        if(model!=null) return
        System.setProperty("jna.nounpack","true")
        System.setProperty("jna.noclasspath","true")
        System.loadLibrary("jnidispatch")
        System.loadLibrary("vosk")
        LibVosk.vosk_set_log_level(-1)
        val root=File(app.noBackupFilesDir,"voice-model-cn-0.22")
        val entries=JSONArray(app.assets.open("voice-model-manifest.json").bufferedReader().use { it.readText() })
        // These are immutable model assets, never microphone or decoder buffers.
        for(i in 0 until entries.length()) {
            val row=entries.getJSONObject(i);val path=row.getString("path")
            require(!path.startsWith("/") && path.split('/').none { it==".." })
            val file=File(root,path);val expected=row.getString("sha256")
            fun digest(f: File): String {
                val hash=MessageDigest.getInstance("SHA-256")
                f.inputStream().use { stream -> val b=ByteArray(16384);while(true) { val n=stream.read(b);if(n<0) break;hash.update(b,0,n) } }
                return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            if(!file.isFile || file.length()!=row.getLong("bytes") || digest(file)!=expected) {
                file.parentFile!!.mkdirs();val partial=File(file.path+".installing")
                try {
                    app.assets.open("voice-model/$path").use { input -> partial.outputStream().use { input.copyTo(it) } }
                    require(partial.length()==row.getLong("bytes") && digest(partial)==expected)
                    check(partial.renameTo(file))
                } finally { partial.delete() }
            }
        }
        model=Model(root.absolutePath)
    }
    fun decoder(): VoiceDecoder {
        val recognizer=Recognizer(checkNotNull(model),VoiceBudget.RATE.toFloat())
        return object: VoiceDecoder {
            private val text=StringBuilder()
            private fun append(json: String) {
                val next=JSONObject(json).optString("text","")
                // Preserve the raw ASR words. No name corrections or accuracy normalization here.
                if(next.isNotBlank()) { if(text.isNotEmpty()) text.append(' ');text.append(next) }
                check(text.length<=32000)
            }
            override fun accept(samples: ShortArray,count: Int) { if(recognizer.acceptWaveForm(samples,count)) append(recognizer.result) }
            override fun finish(): String { append(recognizer.finalResult);return text.toString() }
            override fun close() { recognizer.close();text.setLength(0) }
        }
    }
    override fun close() { model?.close();model=null }
}
