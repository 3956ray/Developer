package com.example.thinkv2.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext in noBackupFilesDir; key material belongs to AndroidKeyStore. No plaintext fallback. */
class AiVault(context: Context) {
    private val file=AtomicFile(File(context.noBackupFilesDir,"ai-settings.enc"))
    private val alias="thinkV2.ai.settings.v1"
    private fun key(create: Boolean): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        aiCheck(create,"credential_storage")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun load(): AiConfig? {
        if(!file.baseFile.exists()) return null
        try {
            aiCheck(file.baseFile.length()<=16384,"credential_storage")
            val bytes=file.openRead().use { it.readBytes() };aiCheck(bytes.size>28 && bytes[0].toInt()==1,"credential_storage")
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(1,13)))
            val plain=cipher.doFinal(bytes.copyOfRange(13,bytes.size))
            try { val m=AiJson(plain).parse() as Map<*,*>;return AiConfig(m["endpoint"] as String,m["model"] as String,m["credential"] as String,m["enabled"] as Boolean) }
            finally { plain.fill(0) }
        } catch(_: Exception) { throw AiFailure("credential_storage") }
    }
    @Synchronized fun save(config: AiConfig) {
        try {
            val plain=aiJson(mapOf("endpoint" to config.endpoint,"model" to config.model,"credential" to config.credential,"enabled" to config.enabled))
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true))
            val encrypted=try { byteArrayOf(1)+cipher.iv+cipher.doFinal(plain) } finally { plain.fill(0) }
            val stream=file.startWrite()
            try { stream.write(encrypted);file.finishWrite(stream) } catch(e: Exception) { file.failWrite(stream);throw e }
        } catch(_: Exception) { throw AiFailure("credential_storage") }
    }
    @Synchronized fun clear() {
        file.delete()
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) };store.deleteEntry(alias)
        aiCheck(!file.baseFile.exists(),"credential_storage")
    }
}
