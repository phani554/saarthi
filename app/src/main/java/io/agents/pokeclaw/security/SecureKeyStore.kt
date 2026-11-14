// Copyright 2026 Saarthi. Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tencent.mmkv.MMKV
import io.agents.pokeclaw.utils.XLog
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted MMKV for API keys, bot tokens, the care profile and remembered facts.
 *
 * - A random 32-character MMKV crypt key (AES-256, `aes256 = true`) encrypts the MMKV file.
 * - That crypt key is itself wrapped with an AES-GCM key that never leaves the Android Keystore
 *   (alias [KEYSTORE_ALIAS]); only the wrapped bytes (IV + ciphertext) are kept, in a private
 *   SharedPreferences file.
 * - If the Keystore key is gone (device restore, Keystore reset) the old encrypted file cannot be
 *   read: it is deleted and a new one is created, so saved keys have to be entered again.
 * - If the Keystore does not work at all on this device, [open] returns null and the caller keeps
 *   using plain storage (better than losing every key); the reason is logged.
 *
 * Call [open] once, after MMKV.initialize, on the thread that initialises KVUtils.
 */
object SecureKeyStore {
    private const val TAG = "SecureKeyStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEYSTORE_ALIAS = "saathi_secure_store_v1"
    const val MMKV_ID = "saathi_secure_v1"
    private const val PREFS = "saathi_secure_meta"
    private const val PREF_WRAPPED = "wrapped_mmkv_key"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    @JvmStatic
    fun open(context: Context): MMKV? {
        return try {
            val cryptKey = loadOrCreateCryptKey(context) ?: return null
            MMKV.mmkvWithID(MMKV_ID, MMKV.SINGLE_PROCESS_MODE, cryptKey, true)
        } catch (t: Throwable) {
            XLog.e(TAG, "Encrypted store unavailable, falling back to plain storage: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun loadOrCreateCryptKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(PREF_WRAPPED, null)
        val keystoreKey = getOrCreateKeystoreKey()
        if (wrapped != null) {
            val unwrapped = try {
                unwrap(keystoreKey, Base64.decode(wrapped, Base64.NO_WRAP))
            } catch (t: Throwable) {
                XLog.w(TAG, "Could not unwrap the store key (${t.javaClass.simpleName}); starting a new encrypted store")
                null
            }
            if (unwrapped != null) return unwrapped
            // The file was encrypted with a key we can no longer recover: drop it rather than read garbage.
            try { MMKV.removeStorage(MMKV_ID) } catch (t: Throwable) { XLog.w(TAG, "removeStorage failed: ${t.javaClass.simpleName}") }
        }
        val fresh = newCryptKey()
        val blob = wrap(keystoreKey, fresh)
        // commit(): the wrapped key must be on disk before anything is written with it.
        if (!prefs.edit().putString(PREF_WRAPPED, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
            XLog.e(TAG, "Could not save the wrapped store key")
            return null
        }
        return fresh
    }

    /** 24 random bytes as 32 Base64 characters = the 32-byte AES-256 MMKV key. */
    private fun newCryptKey(): String {
        val raw = ByteArray(24)
        SecureRandom().nextBytes(raw)
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Returns IV (12 bytes) followed by the GCM ciphertext+tag. The Keystore picks the IV. */
    private fun wrap(key: SecretKey, plain: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv != null && iv.size == GCM_IV_BYTES) { "unexpected IV size" }
        return iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    private fun unwrap(key: SecretKey, blob: ByteArray): String {
        require(blob.size > GCM_IV_BYTES) { "wrapped key too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES))
        return String(cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES), Charsets.UTF_8)
    }
}
