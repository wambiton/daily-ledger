package com.personal.ledger.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AccountVault(context: Context, name: String = "account") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$name.enc"))
    private val alias = "ledger-$name"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): JSONObject? {
        if(!file.baseFile.exists()) return null
        val data = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
    }
    fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch(e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() = file.delete()
    companion object {
        fun databaseKey(server: String, user: String): String = MessageDigest.getInstance("SHA-256")
            .digest("$server|$user".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
