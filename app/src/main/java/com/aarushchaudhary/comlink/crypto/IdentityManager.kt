package com.aarushchaudhary.comlink.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest

class IdentityManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "comlink_identity_prefs"
        private const val PREF_ENCRYPTED_PRIVATE_KEY = "encrypted_x25519_priv"
        private const val PREF_PUBLIC_KEY = "x25519_pub"
        private const val PREF_DEVICE_ID = "device_id"
        private const val MASTER_KEY_URI = "android-keystore://comlink_master_key"
        private const val KEYSET_NAME = "comlink_keyset"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val aead: Aead

    init {
        AeadConfig.register()
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
     * Generates a SHA-256 fingerprint for visual confirmation during the QR Handshake.
     * The fingerprint is derived by hashing the lexicographically sorted public keys to ensure
     * both parties generate the exact same fingerprint regardless of who scanned who.
     */
    fun generateFingerprint(peerPublicKey: ByteArray): String {
        val myPubKey = getPublicKey()
        val md = MessageDigest.getInstance("SHA-256")
        
        // Sort to ensure determinism
        val isMyKeyFirst = compareByteArrays(myPubKey, peerPublicKey) <= 0
        if (isMyKeyFirst) {
            md.update(myPubKey)
            md.update(peerPublicKey)
        } else {
            md.update(peerPublicKey)
            md.update(myPubKey)
        }
        
        val hash = md.digest()
        
        // Format as a hex string grouped for easy reading (e.g., "A1B2-C3D4-...")
        val hex = hash.joinToString("") { "%02X".format(it) }
        return hex.chunked(4).joinToString("-")
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val aByte = a[i].toInt() and 0xFF
            val bByte = b[i].toInt() and 0xFF
            if (aByte != bByte) return aByte.compareTo(bByte)
        }
        return a.size.compareTo(b.size)
    }
}
