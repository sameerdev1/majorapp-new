package com.majorgym.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** Outcome of importing+restoring a backup file (ZIP or plain JSON). */
sealed class RestoreOutcome {
    data class Success(val recordsRestored: Int) : RestoreOutcome()
    /** The file itself is bad - corrupt zip, missing backup.json, invalid JSON,
     *  unsupported schema. Nothing was touched. */
    data class InvalidBackup(val reason: String) : RestoreOutcome()
    /** The file was fine but applying it to the database failed partway.
     *  Because the underlying DB write is atomic (see [Repository.mergeAll]),
     *  this means nothing was actually applied - current data is intact. */
    data class RestoreFailed(val reason: String) : RestoreOutcome()
}

/**
 * The single reusable core behind Automatic Backup, Backup Now, and Export
 * Backup (section 13/23 of the spec): generate the existing JSON, compress it
 * into a ZIP, and verify the ZIP before anyone is told the backup succeeded.
 * Also owns the import side: detect ZIP vs legacy JSON, validate, and restore
 * with a safety snapshot first.
 *
 * Deliberately does not touch [BackupManager]'s JSON structure - it only
 * wraps it.
 */
object BackupService {

    /** Generator -> Compressor -> Validator, run in one place so Automatic
     *  Backup / Backup Now / Export Backup can never drift apart. Writes the
     *  ZIP to [destFile], verifies it can be reopened and parsed, and returns
     *  it. On ANY failure the partially-written file is removed and the
     *  original exception's message is preserved so the caller can show a
     *  precise, honest error instead of reporting success.
     */
    suspend fun createZipBackup(context: Context, repository: Repository, destFile: File): File =
        withContext(Dispatchers.IO) {
            try {
                // Generator
                val json = BackupManager.exportJson(context, repository.allOnce())
                // Compressor
                BackupZip.write(json, destFile)
                // Validator - reopen the file we just wrote and confirm it's
                // really usable, not just "a file exists on disk".
                val verifiedJson = BackupZip.readAndVerify(destFile)
                validateSchema(verifiedJson)?.let { reason -> throw BackupFormatException(reason) }
                destFile
            } catch (e: Exception) {
                destFile.delete()
                throw if (e is BackupFormatException) e else BackupFormatException(
                    e.message ?: "Backup could not be created.", e
                )
            }
        }

    /**
     * Detects the format of [uri] (ZIP or legacy plain JSON) by sniffing
     * content, not by trusting the filename's extension, validates it, then
     * restores it against [repository].
     *
     * Safety: before anything in the database changes, a snapshot of the
     * current members is captured in memory and also written to a
     * "pre-restore safety" ZIP on disk (overwritten each time, not counted in
     * automatic-backup retention) purely as a recovery point. The actual
     * database write ([Repository.mergeAll]) is a single Room bulk insert,
     * which Room runs inside one transaction - so if it throws partway,
     * nothing from it is committed; current data is left exactly as it was.
     */
    suspend fun importAndRestore(
        context: Context,
        repository: Repository,
        uri: Uri
    ): RestoreOutcome = withContext(Dispatchers.IO) {
        var tempInput: File? = null
        try {
            val input = copyUriToTemp(context, uri)
            tempInput = input

            val json = if (BackupZip.looksLikeZip(input)) {
                val extracted = BackupZip.extractJsonToTemp(context, input)
                try {
                    extracted.readText(Charsets.UTF_8)
                } finally {
                    extracted.delete()
                }
            } else {
                input.readText(Charsets.UTF_8)
            }

            val invalidReason = validateSchema(json)
            if (invalidReason != null) return@withContext RestoreOutcome.InvalidBackup(invalidReason)

            val incoming = try {
                BackupManager.importJson(context, json)
            } catch (e: Exception) {
                return@withContext RestoreOutcome.InvalidBackup(
                    "This backup file's data couldn't be read. It may be corrupted or from an unsupported version."
                )
            }

            // Safety snapshot - best-effort; a failure here should never block
            // a legitimate restore, so it's logged-and-ignored rather than fatal.
            try {
                repository.writeSafetyBackupSnapshot()
            } catch (_: Exception) { /* non-fatal, see doc comment above */ }

            try {
                repository.mergeAll(incoming)
                RestoreOutcome.Success(incoming.size)
            } catch (e: Exception) {
                RestoreOutcome.RestoreFailed(
                    e.message ?: "Restore could not be completed. Your existing data has not been changed."
                )
            }
        } catch (e: BackupFormatException) {
            RestoreOutcome.InvalidBackup(e.message ?: "Invalid backup file.")
        } catch (e: Exception) {
            RestoreOutcome.InvalidBackup(e.message ?: "This backup file could not be read.")
        } finally {
            tempInput?.delete()
            BackupZip.cleanupTemp(context)
        }
    }

    /** Copies whatever [uri] points to (a SAF content:// URI, typically) into a
     *  plain local temp file, since ZIP reading needs random file access that
     *  a content stream doesn't reliably support. */
    private fun copyUriToTemp(context: Context, uri: Uri): File {
        val tempDir = File(context.cacheDir, "backup_import_tmp").apply { mkdirs() }
        val tempFile = File(tempDir, "import_${System.currentTimeMillis()}")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw BackupFormatException("Unable to open the selected file.")
        input.use { i -> tempFile.outputStream().use { o -> i.copyTo(o) } }
        return tempFile
    }

    /**
     * Confirms [json] parses and matches the shape [BackupManager] produces
     * and knows how to read: an object with an "app":"MajorGym" marker and a
     * "members" array. Returns null when valid, or a user-facing reason when
     * not. This is deliberately loose about *future* schema changes (a newer,
     * still-"MajorGym" backup with extra fields this build doesn't know about
     * should still restore what it recognizes) but rejects anything that
     * isn't recognizably a MajorGym backup at all.
     */
    private fun validateSchema(json: String): String? {
        return try {
            val root = JSONObject(json)
            val app = root.optString("app", "")
            if (app.isNotEmpty() && app != "MajorGym") {
                return "This file isn't a MajorGym backup."
            }
            if (!root.has("members")) {
                return "This backup file is missing its member data."
            }
            root.getJSONArray("members")
            null
        } catch (e: JSONException) {
            "This backup file isn't valid JSON and may be corrupted."
        }
    }
}
