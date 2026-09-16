package com.example.thinkv2.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.security.MessageDigest

/** Test APK only. Frozen candidate for synthetic reference evaluation; never a normal app engine. */
class SenseVoiceCandidate(private val assetsContext: Context,private val storageContext: Context): AutoCloseable {
    private var model: OfflineRecognizer?=null
    fun prepare() {
        check(model==null)
        val root=File(storageContext.noBackupFilesDir,"sensevoice-evaluation").apply { mkdirs() }
        val expected=mapOf("model.int8.onnx" to "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            "tokens.txt" to "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc")
        for((name,hash) in expected) {
            val file=File(root,name)
            fun digest(f: File): String {
                val d=MessageDigest.getInstance("SHA-256")
                f.inputStream().use { input -> val b=ByteArray(65536);while(true) { val n=input.read(b);if(n<0) break;d.update(b,0,n) } }
                return d.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            if(!file.isFile || digest(file)!=hash) {
                val partial=File(root,"$name.installing")
                try {
                    assetsContext.assets.open("sensevoice/$name").use { input -> partial.outputStream().use { input.copyTo(it) } }
                    check(digest(partial)==hash);check(partial.renameTo(file))
                } finally { partial.delete() }
            }
        }
        model=OfflineRecognizer(config=OfflineRecognizerConfig(
            featConfig=FeatureConfig(sampleRate=16000,featureDim=80),
            modelConfig=OfflineModelConfig(
                senseVoice=OfflineSenseVoiceModelConfig(model=File(root,"model.int8.onnx").path,language="zh",useInverseTextNormalization=true),
                tokens=File(root,"tokens.txt").path,numThreads=2,debug=false,provider="cpu"),
            decodingMethod="greedy_search"))
    }
    fun decoder(): VoiceDecoder {
        val recognizer=checkNotNull(model)
        return object: VoiceDecoder {
            private val pcm=ShortArray(VoiceBudget.LIMIT)
            private var count=0
            private var finished=false
            private var closed=false
            override fun accept(samples: ShortArray,count: Int) {
                check(!closed && !finished);require(count in 0..samples.size)
                require(count<=pcm.size-this.count)
                samples.copyInto(pcm,this.count,0,count);this.count+=count
            }
            override fun finish(): String {
                check(!closed && !finished);finished=true
                if(count==0) return ""
                val floats=FloatArray(count) { pcm[it]/32768.0f };pcm.fill(0)
                try {
                    val stream=recognizer.createStream()
                    try {
                        stream.acceptWaveform(floats,VoiceBudget.RATE)
                        floats.fill(0f)
                        recognizer.decode(stream)
                        // Ignore language/emotion/event metadata. No correction, name bias or postprocessing.
                        return recognizer.getResult(stream).text.also { check(it.length<=32000) }
                    } finally { stream.release() }
                } finally { floats.fill(0f) }
            }
            override fun close() { pcm.fill(0);count=0;closed=true }
        }
    }
    override fun close() { model?.release();model=null }
}
