package com.majorgym.app.data

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Thrown for anything wrong with a backup's ZIP/JSON container - corrupt zip,
 *  missing backup.json, invalid JSON, unsupported schema. Callers show this
 *  message to the owner and must not modify any app data when it's thrown. */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal, safe ZIP container for a single JSON backup entry.
 *
 * The only entry this ever reads or writes is literally named "backup.json"
 * at the archive root. On read, every other entry in the archive is ignored
 * outright - we never iterate-and-extract arbitrary entries, so a malicious
 * "../../etc/whatever" entry name is never followed or written anywhere; it's
 * simply not the entry we're looking for.
 */
object BackupZip {
    const val ENTRY_NAME = "backup.json"

    /** Compresses [json] into a new ZIP at [destFile] containing just backup.json.
     *  Overwrites destFile if it already exists. Uses standard lossless DEFLATE
     *  (java.util.zip default) - never lossy. */
    fun write(json: String, destFile: File) {
        destFile.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destFile))).use { zos ->
            zos.putNextEntry(ZipEntry(ENTRY_NAME))
            zos.write(json.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    /** True if [file]'s first bytes look like a ZIP (local file header magic
     *  "PK\3\4"). Used to route an imported file to the ZIP or plain-JSON path
     *  regardless of what extension it happens to have. */
    fun looksLikeZip(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return file.inputStream().use { input ->
            val header = ByteArray(4)
            val read = input.read(header)
            read == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        }
    }

    /**
     * Opens [zipFile] and copies just the backup.json entry into a fresh
     * temporary file under [context]'s cache dir, returning it. Throws
     * [BackupFormatException] if the zip can't be opened or contains no
     * backup.json. Callers must delete the returned file (and ideally the
     * temp dir) once they're done reading it - see [cleanupTemp].
     */
    fun extractJsonToTemp(context: Context, zipFile: File): File {
        val tempDir = File(context.cacheDir, "backup_restore_tmp").apply { mkdirs() }
        val tempFile = File(tempDir, "backup_${System.currentTimeMillis()}.json")
        try {
            ZipFile(zipFile).use { zf ->
                val entry = zf.entries().asSequence().firstOrNull { e ->
                    !e.isDirectory && sanitizedName(e.name) == ENTRY_NAME
                } ?: throw BackupFormatException("This ZIP doesn't contain a backup.json file.")
                zf.getInputStream(entry).use { input ->
                    tempFile.outputStream().use { out -> input.copyTo(out) }
                }
            }
        } catch (e: BackupFormatException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            throw BackupFormatException("This backup file is corrupted and couldn't be opened.", e)
        }
        return tempFile
    }

    /** Opens [zipFile] purely to confirm it's a valid, readable archive containing
     *  backup.json - used right after writing a fresh backup to make sure it's
     *  actually usable before it's ever reported as "successful". Returns the
     *  entry's bytes so the caller can also validate the JSON itself. */
    fun readAndVerify(zipFile: File): String {
        try {
            ZipFile(zipFile).use { zf ->
                val entry = zf.entries().asSequence().firstOrNull { e ->
                    !e.isDirectory && sanitizedName(e.name) == ENTRY_NAME
                } ?: throw BackupFormatException("backup.json is missing from the newly created ZIP.")
                return zf.getInputStream(entry).use { it.bufferedReader(Charsets.UTF_8).readText() }
            }
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: Exception) {
            throw BackupFormatException("The ZIP that was just created could not be reopened.", e)
        }
    }

    /** Best-effort cleanup of everything under the temp-restore cache dir. Safe
     *  to call even if nothing was ever extracted. */
    fun cleanupTemp(context: Context) {
        File(context.cacheDir, "backup_restore_tmp").deleteRecursively()
    }

    /** Normalizes a zip entry name and rejects anything that isn't a plain,
     *  same-directory filename - defense in depth against zip-slip style
     *  entries, even though we only ever look up one specific name. */
    private fun sanitizedName(name: String): String {
        val normalized = name.replace('\\', '/').removePrefix("/")
        return if (normalized.contains("..")) "" else normalized
    }
}
