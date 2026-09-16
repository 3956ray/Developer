package com.example.thinkv2.voice

import android.os.SystemClock
import org.json.JSONObject
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Synthetic PCM preloaded only in RAM. Real 100ms clock pacing through product VoiceCapture. */
internal fun realtime(engine: OfflineEngine,input: DataInputStream,count: Int,metadata: JSONObject,loadMs: Long): JSONObject {
    val pcm=ByteArray(count*2);input.readFully(pcm)
    val hash=MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it.toInt() and 255) }
    val release=AtomicLong(0);var started=0L;var closed=false;var failure=""
    lateinit var capture: VoiceCapture
    val source=object: PcmSource {
        var position=0
        override fun start() { started=SystemClock.elapsedRealtime() }
        override fun read(buffer: ShortArray): Int {
            val size=minOf(buffer.size,count-position)
            if(size==0) { capture.release();return 0 }
            val deadline=started+(position+size)*1000L/VoiceBudget.RATE
            val delay=deadline-SystemClock.elapsedRealtime();if(delay>0) Thread.sleep(delay)
            for(i in 0 until size) buffer[i]=((pcm[(position+i)*2].toInt() and 255) or (pcm[(position+i)*2+1].toInt() shl 8)).toShort()
            position+=size
            if(position==count) { release.set(SystemClock.elapsedRealtime());capture.release() }
            return size
        }
        override fun close() { pcm.fill(0);closed=true }
    }
    capture=VoiceCapture({ source })
    val text=try { engine.decoder().use { capture.run(it) {} } }
    catch(e: Exception) { failure=(e as? VoiceProblem)?.code ?: "decoder";"" }
    finally { pcm.fill(0) }
    val ended=SystemClock.elapsedRealtime()
    return JSONObject().put("identity",metadata).put("mode","realtime-product-capture")
        .put("samples",count).put("capturedSamples",capture.samples).put("sampleRate",VoiceBudget.RATE)
        .put("pcmSha256",hash).put("raw",text).put("corrected",JSONObject.NULL).put("engineLoadMs",loadMs)
        .put("wallMs",ended-started).put("releaseToResultMs",if(release.get()>0) ended-release.get() else JSONObject.NULL)
        .put("failure",failure).put("sourceClosed",closed)
}
