package com.aarushchaudhary.comlink.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.subtle.X25519
import java.security.SecureRandom

class IdentityManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "comlink_identity_prefs"
        private const val PREF_ENCRYPTED_PRIVATE_KEY = "encrypted_x25519_priv"
        private const val PREF_PUBLIC_KEY = "x25519_pub"
        private const val PREF_DEVICE_ID = "device_id"
        private const val MASTER_KEY_URI = "android-keystore://comlink_master_key"
        private const val KEYSET_NAME = "comlink_keyset"
        private const val FILE_KEYSET_NAME = "comlink_file_keyset"
        private const val PREF_ENCRYPTED_DB_PASSPHRASE = "encrypted_db_passphrase"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val aead: Aead

    init {
        AeadConfig.register()
        StreamingAeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREFS_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        aead = keysetHandle.getPrimitive(Aead::class.java)
        
        ensureIdentityExists()
    }

    private fun ensureIdentityExists() {
        if (!prefs.contains(PREF_ENCRYPTED_PRIVATE_KEY)) {
            // Generate a long-term X25519 identity keypair
            val privateKey = X25519.generatePrivateKey()
            val publicKey = X25519.publicFromPrivate(privateKey)
            
            // Wrap the private key using Android Keystore backed Tink AEAD
            val encryptedPrivateKey = aead.encrypt(privateKey, ByteArray(0))
            
            // Generate a random device ID for routing
            val deviceId = Base64.encodeToString(X25519.generatePrivateKey().copyOfRange(0, 16), Base64.NO_WRAP)
            
            prefs.edit()
                .putString(PREF_ENCRYPTED_PRIVATE_KEY, Base64.encodeToString(encryptedPrivateKey, Base64.NO_WRAP))
                .putString(PREF_PUBLIC_KEY, Base64.encodeToString(publicKey, Base64.NO_WRAP))
                .putString(PREF_DEVICE_ID, deviceId)
                .apply()
        }
    }

    fun getPublicKey(): ByteArray {
        val b64 = prefs.getString(PREF_PUBLIC_KEY, null) ?: throw IllegalStateException("Identity not initialized")
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    fun getPrivateKey(): ByteArray {
        val b64 = prefs.getString(PREF_ENCRYPTED_PRIVATE_KEY, null) ?: throw IllegalStateException("Identity not initialized")
        val encrypted = Base64.decode(b64, Base64.NO_WRAP)
        return aead.decrypt(encrypted, ByteArray(0))
    }

    fun getDeviceId(): String {
        return prefs.getString(PREF_DEVICE_ID, null) ?: throw IllegalStateException("Identity not initialized")
    }

    /**
     * The SQLCipher passphrase for the chat database: 32 random bytes as 64 hex characters, created
     * once and stored wrapped by the same Keystore-backed key as the identity. Saved with commit()
     * (not apply()), so it is on disk before any database is encrypted with it.
     */
    @Synchronized
    fun databasePassphrase(): String {
        prefs.getString(PREF_ENCRYPTED_DB_PASSPHRASE, null)?.let { stored ->
            return String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), ByteArray(0)), Charsets.UTF_8)
        }
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = random.joinToString("") { "%02x".format(it) }
        val wrapped = aead.encrypt(passphrase.toByteArray(Charsets.UTF_8), ByteArray(0))
        val saved = prefs.edit()
            .putString(PREF_ENCRYPTED_DB_PASSPHRASE, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .commit()
        check(saved) { "Could not save the database key" }
        return passphrase
    }

    /**
     * Streaming AEAD (AES-256-GCM-HKDF, 4 KB segments) for files at rest: received files, sent copies,
     * thumbnails and temporary parts. Its keyset is wrapped by the Keystore master key.
     */
    fun fileStreamingAead(): StreamingAead =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, FILE_KEYSET_NAME, PREFS_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(StreamingAead::class.java)
}
