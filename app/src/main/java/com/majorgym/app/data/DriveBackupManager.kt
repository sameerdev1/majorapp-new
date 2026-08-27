package com.majorgym.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The one Drive folder this app ever reads/writes/deletes inside — never
 *  anything outside it (spec section 5 and 15). */
private const val BACKUP_FOLDER_NAME = "Major Gym Backups"
private const val MIME_JSON = "application/json"
private const val MIME_FOLDER = "application/vnd.google-apps.folder"

/** Result wrapper distinguishing "needs reconnect" from any other failure, so
 *  the UI can show the right recovery action (spec section 7/19). */
sealed class DriveResult<out T> {
    data class Ok<T>(val value: T) : DriveResult<T>()
    data class Err(val message: String, val authExpired: Boolean = false) : DriveResult<Nothing>()
}

/**
 * Thin wrapper around the Drive v3 REST API used by the automatic-backup
 * feature. Auth is entirely handled by Google Sign-In
 * (GoogleSignInAccount) + GoogleAccountCredential, which fetches a
 * short-lived OAuth access token on demand from Android's own
 * AccountManager/Play Services — this class, and the rest of the app, never
 * sees or persists a token or password anywhere (see DriveBackupPrefs for
 * the full note on why that satisfies spec section 16/20's key-management
 * requirement for the *connection*).
 *
 * Backup *payload* encryption before upload was deliberately NOT added — see
 * DRIVE_BACKUP_IMPLEMENTATION.md for why, and what a safe follow-up would
 * require (spec section 16 explicitly says not to invent an insecure scheme
 * when there's no existing key-management mechanism to build on).
 */
class DriveBackupManager(private val context: Context) {

    fun signInClient(): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /** The signed-in account with Drive access, if any — this IS "is Drive
     *  connected" from the app's point of view (spec section 13). */
    fun lastSignedInAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun driveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE))
        credential.selectedAccount = account.account
        return Drive.Builder(AndroidHttp.newCompatibleTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("MajorGym")
            .build()
    }

    /** Finds (or creates, the first time only) the dedicated backups folder.
     *  Reuses it on every later call — never creates a duplicate (spec 5). */
    private fun ensureBackupFolder(drive: Drive, prefs: DriveBackupPrefs): String {
        prefs.driveFolderId?.let { cached ->
            val stillValid = runCatching {
                drive.files().get(cached).setFields("id,trashed").execute()
            }.getOrNull()
            if (stillValid != null && stillValid.trashed != true) return cached
        }
        val existing = drive.files().list()
            .setQ("mimeType='$MIME_FOLDER' and name='$BACKUP_FOLDER_NAME' and trashed=false")
            .setSpaces("drive")
            .setFields("files(id,name)")
            .execute()
        val id = existing.files?.firstOrNull()?.id ?: drive.files()
            .create(DriveFile().setName(BACKUP_FOLDER_NAME).setMimeType(MIME_FOLDER))
            .setFields("id")
            .execute()
            .id
        prefs.driveFolderId = id
        return id
    }

    private fun timestampedFileName(): String =
        "MajorGym_Backup_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.json"

    private fun classifyFailure(e: Throwable): Boolean =
        e is UserRecoverableAuthException || e is UserRecoverableAuthIOException

    /**
     * Runs one full backup attempt — generate (via the SAME exporter as the
     * existing manual Export Backup, per spec section 3), upload to the
     * dedicated Drive folder, then record the outcome — and updates [prefs]
     * with the real result either way. Used by both "Backup Now" and the
     * scheduled worker, so their behavior/status reporting never diverges.
     * Never marks success unless the upload actually completed (spec 7/9).
     */
    suspend fun performBackup(repository: Repository, prefs: DriveBackupPrefs): DriveResult<DriveBackupHistoryEntry> =
        withContext(Dispatchers.IO) {
            val account = lastSignedInAccount()
            if (account == null) {
                prefs.lastBackupStatus = DriveBackupStatus.AUTH_EXPIRED
                prefs.lastBackupError = "Google Drive disconnected"
                return@withContext DriveResult.Err("Google Drive disconnected", authExpired = true)
            }
            if (!isOnline()) {
                prefs.lastBackupStatus = DriveBackupStatus.OFFLINE_PENDING
                prefs.lastBackupError = "Internet connection unavailable"
                return@withContext DriveResult.Err("Internet connection unavailable")
            }

            val result = runCatching {
                val json = BackupManager.exportJson(context, repository.allOnce())
                val drive = driveService(account)
                val folderId = ensureBackupFolder(drive, prefs)
                val fileName = timestampedFileName()
                val content = ByteArrayContent(MIME_JSON, json.toByteArray(Charsets.UTF_8))
                val metadata = DriveFile().setName(fileName).setParents(listOf(folderId))
                val uploaded = drive.files().create(metadata, content)
                    .setFields("id,name,size,createdTime")
                    .execute()
                DriveBackupHistoryEntry(
                    timestampMillis = System.currentTimeMillis(),
                    sizeBytes = uploaded.getSize() ?: content.length.toLong(),
                    status = DriveBackupStatus.SUCCESS,
                    fileName = uploaded.name ?: fileName,
                    driveFileId = uploaded.id
                )
            }

            result.fold(
                onSuccess = { entry ->
                    prefs.lastBackupMillis = entry.timestampMillis
                    prefs.lastBackupSizeBytes = entry.sizeBytes
                    prefs.lastBackupStatus = DriveBackupStatus.SUCCESS
                    prefs.lastBackupError = null
                    prefs.addHistoryEntry(entry)
                    applyRetention(prefs)
                    DriveResult.Ok(entry)
                },
                onFailure = { e ->
                    val authExpired = classifyFailure(e)
                    val status = if (authExpired) DriveBackupStatus.AUTH_EXPIRED else DriveBackupStatus.FAILED
                    val message = e.message ?: "Backup failed"
                    prefs.lastBackupStatus = status
                    prefs.lastBackupError = message
                    prefs.addHistoryEntry(
                        DriveBackupHistoryEntry(
                            timestampMillis = System.currentTimeMillis(), sizeBytes = 0L,
                            status = status, fileName = "-", driveFileId = null, errorMessage = message
                        )
                    )
                    DriveResult.Err(message, authExpired)
                }
            )
        }

    /** What's actually in the dedicated Drive folder right now, newest first —
     *  used by Restore's picker and by retention cleanup. Independent of the
     *  local history log (which is just an on-device log of past attempts). */
    suspend fun listRemoteBackups(): DriveResult<List<DriveFile>> = withContext(Dispatchers.IO) {
        val account = lastSignedInAccount()
            ?: return@withContext DriveResult.Err("Google Drive disconnected", authExpired = true)
        if (!isOnline()) return@withContext DriveResult.Err("Internet connection unavailable")
        runCatching {
            val drive = driveService(account)
            val prefs = DriveBackupPrefs(context)
            val folderId = ensureBackupFolder(drive, prefs)
            drive.files().list()
                .setQ("'$folderId' in parents and trashed=false")
                .setSpaces("drive")
                .setFields("files(id,name,size,createdTime)")
                .setOrderBy("createdTime desc")
                .execute().files ?: emptyList()
        }.fold(
            onSuccess = { DriveResult.Ok(it) },
            onFailure = { DriveResult.Err(it.message ?: "Could not load backup history", classifyFailure(it)) }
        )
    }

    /**
     * Downloads and validates a backup, then hands the parsed member list to
     * [onValidMembers] — which the caller uses to run a safety backup of
     * current data and then restore, via the EXISTING import logic (spec
     * sections 11-12). Current data is never touched if validation fails.
     */
    suspend fun restoreBackup(
        fileId: String,
        onValidMembers: suspend (List<Member>) -> Unit
    ): DriveResult<Int> = withContext(Dispatchers.IO) {
        val account = lastSignedInAccount()
            ?: return@withContext DriveResult.Err("Google Drive disconnected", authExpired = true)
        if (!isOnline()) return@withContext DriveResult.Err("Internet connection unavailable")

        val downloadResult = runCatching {
            val drive = driveService(account)
            val out = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(out)
            out.toString(Charsets.UTF_8.name())
        }
        val json = downloadResult.getOrElse {
            return@withContext DriveResult.Err(it.message ?: "Download failed", classifyFailure(it))
        }

        val parseResult = runCatching {
            val root = org.json.JSONObject(json)
            if (root.optString("app") != "MajorGym" || !root.has("members")) {
                error("Invalid or corrupted backup.")
            }
            BackupManager.importJson(context, json)
        }
        val members = parseResult.getOrElse {
            return@withContext DriveResult.Err("Invalid or corrupted backup.")
        }

        runCatching { onValidMembers(members) }.fold(
            onSuccess = { DriveResult.Ok(members.size) },
            onFailure = { DriveResult.Err(it.message ?: "Restore failed") }
        )
    }

    suspend fun deleteRemoteBackup(fileId: String): DriveResult<Unit> = withContext(Dispatchers.IO) {
        val account = lastSignedInAccount()
            ?: return@withContext DriveResult.Err("Google Drive disconnected", authExpired = true)
        if (!isOnline()) return@withContext DriveResult.Err("Internet connection unavailable")
        runCatching { driveService(account).files().delete(fileId).execute() }.fold(
            onSuccess = { DriveResult.Ok(Unit) },
            onFailure = { DriveResult.Err(it.message ?: "Could not delete backup", classifyFailure(it)) }
        )
    }

    /** Deletes backups older than the configured retention window — ONLY
     *  from the dedicated folder, never anything else in the user's Drive
     *  (spec 15). Best-effort: a failure here never fails the backup that
     *  just succeeded. */
    private suspend fun applyRetention(prefs: DriveBackupPrefs) {
        if (prefs.retentionDays == DriveBackupPrefs.RETENTION_FOREVER) return
        val cutoff = System.currentTimeMillis() - prefs.retentionDays * 24L * 60L * 60L * 1000L
        val listing = listRemoteBackups()
        if (listing is DriveResult.Ok) {
            listing.value.forEach { f ->
                val created = f.createdTime?.value ?: return@forEach
                if (created < cutoff) runCatching { deleteRemoteBackup(f.id) }
            }
        }
    }

    /** Revokes this app's Drive session. Local settings/history are cleared
     *  separately by the caller via [DriveBackupPrefs.clearConnection]. */
    fun disconnect() {
        signInClient().signOut()
    }
}
