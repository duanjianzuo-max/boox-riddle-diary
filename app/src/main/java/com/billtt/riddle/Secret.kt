package com.billtt.riddle

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the API key at rest with a key that never leaves the Android Keystore.
 *
 * Upstream stored the key as a plain string in SharedPreferences. MODE_PRIVATE keeps other
 * apps out, but it does not help against anything that can read the app's data directory --
 * an authorised ADB host via `run-as` on a debuggable build, a device backup, or a
 * filesystem dump. Storing ciphertext means the file alone is useless: the AES key is held
 * by the Keystore, is not exportable, and is destroyed when the app is uninstalled.
 *
 * Format: base64( 12-byte GCM IV || ciphertext||tag ), prefixed with [PREFIX].
 *
 * The prefix is what makes migration free. A value without it is a legacy plaintext key: it
 * is returned as-is and re-written encrypted the next time settings are saved, so an
 * already-working key survives the upgrade without the user re-entering it.
 */
object Secret {

    private const val TAG = "RiddleDiary"
    private const val ALIAS = "riddle_api_key"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Whether a stored value is already ciphertext produced by [encrypt]. */
    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        }.getOrElse {
            // Never silently drop the user's key: fall back to storing it as it was.
            Log.e(TAG, "key encryption failed, storing unencrypted", it)
            plain
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        // Legacy plaintext from before this class existed.
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val blob = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
            )
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        }.getOrElse {
            Log.e(TAG, "key decryption failed; re-enter it in settings", it)
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NOT setUserAuthenticationRequired: the diary must be able to
                // send a page without a lock-screen prompt mid-write.
                .build()
        )
        return generator.generateKey()
    }
}
