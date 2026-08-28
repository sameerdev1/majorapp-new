package com.majorgym.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.majorgym.app.data.BackupService
import com.majorgym.app.data.BackupStatusPrefs
import com.majorgym.app.data.Member
import com.majorgym.app.data.PairedDevice
import com.majorgym.app.data.PasskeyUtils
import com.majorgym.app.data.Repository
import com.majorgym.app.data.RestoreOutcome
import com.majorgym.app.data.SyncManager
import com.majorgym.app.data.SyncOutcome
import com.majorgym.app.data.SyncPrefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MembersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val syncPrefs = SyncPrefs(app)
    private val syncManager = SyncManager(app, repo, syncPrefs)
    private val backupStatusPrefs = BackupStatusPrefs(app)

    val members: StateFlow<List<Member>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(member: Member) = viewModelScope.launch { repo.save(member) }
    fun delete(member: Member) = viewModelScope.launch { repo.deleteWithFiles(member) }

    /** Stores/replaces a member's fingerprint template captured via [com.majorgym.app.data.FingerprintScanner]. */
    fun saveFingerprintTemplate(member: Member, template: ByteArray) = viewModelScope.launch {
        repo.save(member.copy(fingerprintTemplate = template, updatedAtMillis = System.currentTimeMillis()))
    }

    fun clearFingerprintTemplate(member: Member) = viewModelScope.launch {
        repo.save(member.copy(fingerprintTemplate = null, updatedAtMillis = System.currentTimeMillis()))
    }

    /** True the instant a matching member's attendance is recorded at check-in.
     *  Also bumps [Member.updatedAtMillis] (fix #4/#12) so the attendance
     *  itself - not just profile edits - propagates correctly through sync's
     *  most-recently-edited-wins merge instead of being silently lost if the
     *  other device happens to sync afterward. */
    fun recordAttendance(member: Member) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        repo.save(member.copy(lastAttendanceMillis = now, updatedAtMillis = now))
    }

    /** Checks the "phone number already registered" rule before saving (spec section 1). */
    suspend fun isPhoneTaken(phone: String, excludingId: String = ""): Boolean =
        repo.isPhoneTaken(phone, excludingId)

    /** Generates a fresh plaintext passkey. Callers must hash it via [PasskeyUtils.hash]
     *  before it's ever stored — the plaintext is only for on-screen display and the
     *  one-time WhatsApp welcome message. */
    fun generatePasskey(): String = PasskeyUtils.generate()

    /**
     * Copies the picked image into permanent app storage. Decoding/scaling/
     * compressing runs off the main thread (fix #6 - see Repository.savePhoto);
     * the result comes back via [onResult] instead of a direct return value so
     * callers never block the UI thread waiting on it.
     */
    fun savePhoto(memberId: String, uri: Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        onResult(repo.savePhoto(memberId, uri))
    }

    /** Compresses and saves an ID proof photo (Feature 3), off the main thread;
     *  "" if the image couldn't be read. */
    fun saveIdProofPhoto(memberId: String, uri: Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        onResult(repo.saveIdProofPhoto(memberId, uri))
    }
    fun deleteIdProofPhoto(memberId: String) = repo.deleteIdProofPhoto(memberId)

    // ---- Local ZIP backup system ----

    private fun timestampLabel(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))

    /** Export Backup: builds a fresh ZIP (Generator -> Compressor -> Validator,
     *  the same core Automatic Backup and Backup Now use) and hands the
     *  finished file to [onResult] so the caller can copy its bytes to
     *  wherever the owner picked via the Android file picker. Also stashes an
     *  internal copy so Share Backup File always has a recent backup to work
     *  with. Null result means backup creation itself failed. */
    fun exportZipBackup(onResult: (File?, String?) -> Unit) = viewModelScope.launch {
        try {
            val app: Application = getApplication()
            val temp = File(app.cacheDir, "export_${System.currentTimeMillis()}.zip")
            val file = BackupService.createZipBackup(app, repo, temp)
            // Keep an internal copy for Share Backup File, then clean up the temp export copy.
            file.copyTo(repo.newManualBackupFile(timestampLabel()), overwrite = true)
            onResult(file, null)
        } catch (e: Exception) {
            onResult(null, e.message ?: "The backup could not be completed.")
        }
    }

    /** Backup Now: immediately creates and verifies a local ZIP backup using
     *  the same core as Automatic Backup / Export Backup. This is a *manual*
     *  local backup - stored separately from automatic backups so it's never
     *  touched by the 30-backup automatic retention policy. */
    fun backupNow(onResult: (File?, String?) -> Unit) = viewModelScope.launch {
        try {
            val app: Application = getApplication()
            val dest = repo.newManualBackupFile(timestampLabel())
            val file = BackupService.createZipBackup(app, repo, dest)
            onResult(file, null)
        } catch (e: Exception) {
            onResult(null, e.message ?: "The backup could not be completed.")
        }
    }

    /** Import / Restore: accepts either a new ZIP backup or a legacy plain
     *  JSON backup - format is detected from the file's actual content, not
     *  its extension - validates it, snapshots current data as a safety net,
     *  then merges it in. Never deletes local-only records that aren't in the
     *  imported file. */
    fun importBackup(uri: Uri, onResult: (RestoreOutcome) -> Unit) = viewModelScope.launch {
        onResult(BackupService.importAndRestore(getApplication(), repo, uri))
    }

    fun autoBackupCount(): Int = repo.listAutoBackups().size
    fun autoBackupStorageBytes(): Long = repo.autoBackupStorageBytes()
    fun lastAutoBackupMillis(): Long = backupStatusPrefs.lastAutoBackupMillis
    fun lastAutoBackupSuccess(): Boolean = backupStatusPrefs.lastAutoBackupSuccess
    fun lastAutoBackupError(): String? = backupStatusPrefs.lastAutoBackupError

    // ---- Share Backup File (Feature 1) ----

    /** The newest backup on disk, without creating one. Used just to show
     *  filename/size/date on the Share Backup card. */
    fun latestBackupFile(): java.io.File? = repo.latestInternalBackupFile()

    /** What the Share Backup button calls: returns the latest backup, silently
     *  generating one first if none exists yet. Null only if generation itself fails. */
    fun getOrCreateLatestBackup(onResult: (java.io.File?) -> Unit) = viewModelScope.launch {
        try {
            onResult(repo.getOrCreateLatestBackup())
        } catch (e: Exception) {
            onResult(null)
        }
    }

    // ---- Device sync (local Wi-Fi/hotspot only, up to 3 authorized devices) ----

    fun deviceName(): String = syncPrefs.deviceName
    fun setDeviceName(name: String) { syncPrefs.deviceName = name }
    fun syncCode(): String? = syncPrefs.syncCode
    fun setSyncCode(code: String) { syncPrefs.syncCode = code }
    fun pairedDevices(): List<PairedDevice> = syncPrefs.pairedDevices()

    fun startSync(onStatus: (String) -> Unit, onDone: (SyncOutcome) -> Unit) = viewModelScope.launch {
        onDone(syncManager.runSync(onStatus = onStatus))
    }
}
