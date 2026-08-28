package com.majorgym.app.data

import java.io.File
import java.io.IOException

/**
 * Central place every backup/restore/import/sync file write goes through.
 * Two layers of defense, not just "strip a few characters":
 *  1. [CryptoUtils.sanitizeFileToken] reduces the untrusted id to a safe
 *     allow-listed token before it ever becomes part of a path.
 *  2. [resolveWithin] then re-verifies, via canonical paths, that the file it
 *     built still resolves inside the intended directory - so even a bug in
 *     step 1, an unexpected filesystem quirk, or a symlink already present in
 *     [dir] can never result in a write outside [dir].
 */
object FileSafety {

    class UnsafePathException(message: String) : IOException(message)

    /**
     * Builds `dir/<sanitized id>.<extension>` and guarantees the result is
     * really inside [dir]. Throws [UnsafePathException] rather than silently
     * substituting a different name - callers must skip/reject that record,
     * never guess.
     */
    fun resolveWithin(dir: File, rawId: String, extension: String): File {
        val safeId = CryptoUtils.sanitizeFileToken(rawId)
            ?: throw UnsafePathException("Invalid record id in backup/sync data.")
        dir.mkdirs()
        val candidate = File(dir, "$safeId.$extension")

        val dirCanonical = dir.canonicalFile
        val candidateCanonical = candidate.canonicalFile
        if (candidateCanonical.parentFile?.path != dirCanonical.path) {
            throw UnsafePathException("Resolved path escaped the intended directory.")
        }
        return candidate
    }
}
