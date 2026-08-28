package com.majorgym.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real (not obfuscation/Base64) cryptographic primitives shared by:
 *  - at-rest fingerprint template encryption (Android Keystore-backed, never
 *    leaves this device), and
 *  - the LAN sync channel (key derived from the gym's Sync Code, used to
 *    authenticate peers and encrypt every byte exchanged).
 *
 * Fingerprint templates embedded in manual backup files are intentionally
 * NOT portable-encrypted here: they're written out the same way they travel
 * over an already-encrypted LAN sync frame (see [SyncManager]), as plain
 * Base64 inside the backup JSON, so a restore never depends on a Sync Code or
 * any other piece of app state that a full data clear would wipe out. See
 * [BackupManager] for the full rationale.
 *
 * All AES use is AES/GCM/NoPadding (authenticated encryption - confidentiality
 * AND tamper detection in one primitive), 256-bit keys, random 12-byte IVs
 * (GCM's recommended size), and a 128-bit authentication tag. Never hashing,
 * never Base64, never a hardcoded key.
 */
object CryptoUtils {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "majorgym_fingerprint_key_v1"
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** First byte of anything this class has encrypted. Chosen well outside the
     *  printable-ASCII "FIR\0" magic that a real ISO 19794-2 template always
     *  starts with, so legacy plaintext templates (pre-encryption installs)
     *  and encrypted ones can always be told apart safely - never guessed. */
    private const val MARKER_KEYSTORE: Byte = 0x01

    // ---------------- At-rest (Android Keystore, device-bound) ----------------

    private fun getOrCreateKeystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** Encrypts [plain] with the device's non-exportable Keystore key. Output:
     *  [marker(1)] [iv(12)] [ciphertext+tag]. Safe to store directly in the
     *  Room BLOB column - never decryptable outside this device/app install,
     *  which is exactly what we want for data that should never leave the
     *  phone except through the explicitly-protected paths below. */
    fun encryptAtRest(plain: ByteArray): ByteArray {
        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance(AES_MODE).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv // Keystore-generated random IV, guaranteed unique per call
        val ciphertext = cipher.doFinal(plain)
        return byteArrayOf(MARKER_KEYSTORE) + iv + ciphertext
    }

    /**
     * Reverses [encryptAtRest]. If [stored] doesn't carry our marker (i.e. it's
     * a template written before this encryption existed), it's returned as-is
     * - the caller is expected to write it back through [encryptAtRest] on its
     * next save, migrating it transparently. Returns null only if the bytes
     * are marked as encrypted but fail to decrypt (corrupted data, or a
     * Keystore key that no longer exists e.g. after a factory reset) - callers
     * must treat that as "template lost", never crash or silently invent data.
     */
    fun decryptAtRestOrLegacy(stored: ByteArray): ByteArray? {
        if (stored.isEmpty() || stored[0] != MARKER_KEYSTORE) return stored // legacy plaintext
        if (stored.size < 1 + GCM_IV_BYTES) return null
        return try {
            val iv = stored.copyOfRange(1, 1 + GCM_IV_BYTES)
            val ciphertext = stored.copyOfRange(1 + GCM_IV_BYTES, stored.size)
            val key = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- Generic AES-GCM (used for the sync channel) ----------------

    /** Derives a raw 256-bit [SecretKey] from an arbitrary passphrase - used by
     *  [SyncManager] to turn the shared Sync Code into a channel-encryption key. */
    fun deriveSyncChannelKey(syncCode: String): SecretKey {
        val salt = "MajorGym-Sync-Channel-v1".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(syncCode.toCharArray(), salt, 150_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    /** Encrypts [plain] under [key]. Output: [iv(12)] [ciphertext+tag]. */
    fun aesGcmEncrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    /** Reverses [aesGcmEncrypt]. Throws on any tampering/auth failure or wrong
     *  key - callers (the sync channel) must treat that as "this connection is
     *  not trustworthy" and abort rather than proceed with garbage data. */
    fun aesGcmDecrypt(ivAndCiphertext: ByteArray, key: SecretKey): ByteArray {
        require(ivAndCiphertext.size > GCM_IV_BYTES) { "Ciphertext too short" }
        val iv = ivAndCiphertext.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = ivAndCiphertext.copyOfRange(GCM_IV_BYTES, ivAndCiphertext.size)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** HMAC-SHA256(key, message) - used for the sync handshake's challenge/
     *  response proof-of-code-possession. The code itself is never sent; only
     *  this keyed proof over a random, single-use nonce is. */
    fun hmacSha256(key: SecretKey, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.encoded, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    /** Constant-time comparison - avoids leaking how many leading bytes of an
     *  HMAC/tag matched via response-time differences. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }

    /** Sanitizes a value (typically a member/record ID from imported/synced
     *  data) so it can never be used to escape an intended directory when
     *  building a filename from it - rejects path separators, ".." traversal,
     *  null bytes, and anything else that isn't a plain safe token. Returns
     *  null if [raw] can't be made safe at all (caller must skip that record
     *  rather than guess a replacement). */
    fun sanitizeFileToken(raw: String): String? {
        if (raw.isBlank()) return null
        // Strip to a conservative allow-list: letters, digits, dash, underscore.
        // Member IDs in this app are UUID-shaped, so this never legitimately
        // truncates a real ID - it only ever neuters something hostile.
        val cleaned = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (cleaned.isBlank()) return null
        if (cleaned.length > 128) return null
        return cleaned
    }
}
