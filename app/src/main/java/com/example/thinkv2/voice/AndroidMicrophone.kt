package com.example.thinkv2.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

// Permission is checked immediately before construction/start and before every read.
@android.annotation.SuppressLint("MissingPermission")
class AndroidMicrophone(private val context: Context): PcmSource {
    private val record: AudioRecord
    init {
        permission()
        val minimum=AudioRecord.getMinBufferSize(VoiceBudget.RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        if(minimum<=0) throw VoiceProblem("microphone")
        record=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(VoiceBudget.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum,VoiceBudget.CHUNK*8)).build()
    }
    private fun permission() {
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) throw VoiceProblem("permission")
    }
    override fun start() {
        permission()
        if(record.state!=AudioRecord.STATE_INITIALIZED) throw VoiceProblem("microphone")
        record.startRecording()
        if(record.recordingState!=AudioRecord.RECORDSTATE_RECORDING) throw VoiceProblem("microphone")
    }
    override fun read(buffer: ShortArray): Int { permission();return record.read(buffer,0,buffer.size,AudioRecord.READ_NON_BLOCKING) }
    override fun close() { try { record.stop() } finally { record.release() } }
}
