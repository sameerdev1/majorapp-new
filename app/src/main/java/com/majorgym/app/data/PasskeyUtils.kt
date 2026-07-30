package com.majorgym.app.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Generates and hashes member passkeys (spec section 1: "Generate Passkey").
 *
 * The plaintext passkey is only ever held in memory long enough to display it
 * once to the owner and hand it to the WhatsApp share step — it is never
 * written to disk or the database. Only [hash] output is persisted, in
 * [Member.passwordHash].
 */
object PasskeyUtils {
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ" // no I/O to avoid confusion with 1/0
    private const val LOWER = "abcdefghijkmnpqrstuvwxyz"
    private const val DIGITS = "23456789"
    private val ALL = UPPER + LOWER + DIGITS
    private val random = SecureRandom()

    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    /** Generates an 8-character passkey guaranteed to contain upper, lower, and digit characters. */
    fun generate(length: Int = 8): String {
        require(length >= 8) { "Passkey must be at least 8 characters" }
        val chars = CharArray(length)
        // Guarantee at least one of each required character class first.
        chars[0] = UPPER[random.nextInt(UPPER.length)]
        chars[1] = LOWER[random.nextInt(LOWER.length)]
        chars[2] = DIGITS[random.nextInt(DIGITS.length)]
        for (i in 3 until length) {
            chars[i] = ALL[random.nextInt(ALL.length)]
        }
        // Shuffle so the guaranteed characters aren't always in the same position.
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        return String(chars)
    }

    /** Hashes [passkey] with a random salt, returning "salt:hash" (both Base64) for storage. */
    fun hash(passkey: String): String {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
        val digest = pbkdf2(passkey, salt)
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(digest)
    }

    /** Verifies [passkey] against a previously stored [storedHash] from [hash]. */
    fun verify(passkey: String, storedHash: String): Boolean {
        val parts = storedHash.split(":")
        if (parts.size != 2) return false
        val salt = Base64.getDecoder().decode(parts[0])
        val expected = Base64.getDecoder().decode(parts[1])
        val actual = pbkdf2(passkey, salt)
        return actual.contentEquals(expected)
    }

    private fun pbkdf2(passkey: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passkey.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}
