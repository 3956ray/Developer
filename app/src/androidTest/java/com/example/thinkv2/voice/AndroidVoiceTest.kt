package com.example.thinkv2.voice

import android.net.LocalServerSocket
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AndroidVoiceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun actualEngineOfflineSilenceAndRelease() {
        val requested=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(requested.contains(android.Manifest.permission.INTERNET))
        OfflineEngine(context).use { engine ->
            engine.prepare()
            repeat(2) { engine.decoder().use { decoder ->
                val silence=ShortArray(1600);repeat(30) { decoder.accept(silence,silence.size) };assertTrue(decoder.finish().isBlank());silence.fill(0)
            } }
        }
    }
    /** Explicitly selected test only. ADB localabstract socket receives synthetic PCM in RAM.
     * No audio files, assets, logs or network permission. Only text/identity/timing report persists. */
    @Test fun syntheticMemoryFixtureServer() {
        val output=JSONArray()
        OfflineEngine(context).use { engine ->
            val load=SystemClock.elapsedRealtime();engine.prepare();val loadMs=SystemClock.elapsedRealtime()-load
            LocalServerSocket("thinkv2-voice-fixture").use { server ->
                server.accept().use { socket ->
                    socket.soTimeout=180000
                    val input=DataInputStream(socket.inputStream);val reply=DataOutputStream(socket.outputStream)
                    val cases=input.readInt();require(cases in 1..100)
                    repeat(cases) {
                        val headerSize=input.readInt();require(headerSize in 1..8192)
                        val header=ByteArray(headerSize);input.readFully(header);val metadata=JSONObject(String(header,Charsets.UTF_8))
                        val count=input.readInt();require(count in 1..VoiceBudget.LIMIT)
                        val record=if(metadata.optString("mode")=="realtime") realtime(engine,input,count,metadata,loadMs) else {
                        val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(VoiceBudget.CHUNK*2);val samples=ShortArray(VoiceBudget.CHUNK)
                        var remaining=count;val started=SystemClock.elapsedRealtime();var lastInput=started
                        val text=engine.decoder().use { decoder ->
                            while(remaining>0) {
                                val size=minOf(samples.size,remaining);input.readFully(bytes,0,size*2);digest.update(bytes,0,size*2)
                                for(i in 0 until size) samples[i]=((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort()
                                if(remaining==size) lastInput=SystemClock.elapsedRealtime()
                                decoder.accept(samples,size);samples.fill(0);bytes.fill(0);remaining-=size
                            };decoder.finish()
                        }
                        val ended=SystemClock.elapsedRealtime()
                        JSONObject().put("identity",metadata).put("samples",count).put("sampleRate",16000)
                            .put("pcmSha256",digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                            .put("raw",text).put("corrected",JSONObject.NULL).put("engineLoadMs",loadMs)
                            .put("totalMs",ended-started).put("lastReadToResultMs",ended-lastInput)
                        }
                        output.put(record)
                        // Only explicitly synthetic output is written by this test fixture.
                        context.getFileStreamPath("voice-synthetic-results.json").writeText(output.toString(2))
                        val response=record.toString().toByteArray(Charsets.UTF_8);reply.writeInt(response.size);reply.write(response);reply.flush()
                    }
                }
            }
        }
    }
}
