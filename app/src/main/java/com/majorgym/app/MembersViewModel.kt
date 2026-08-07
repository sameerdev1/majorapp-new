package com.majorgym.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.majorgym.app.data.BackupManager
import com.majorgym.app.data.Member
import com.majorgym.app.data.PairedDevice
import com.majorgym.app.data.PasskeyUtils
import com.majorgym.app.data.Repository
import com.majorgym.app.data.SyncManager
import com.majorgym.app.data.SyncOutcome
import com.majorgym.app.data.SyncPrefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MembersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    private val syncPrefs = SyncPrefs(app)
    private val syncManager = SyncManager(app, repo, syncPrefs)

    val members: StateFlow<List<Member>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(member: Member) = viewModelScope.launch { repo.save(member) }
    fun delete(member: Member) = viewModelScope.launch { repo.delete(member) }

    /** Stores/replaces a member's fingerprint template captured via [com.majorgym.app.data.FingerprintScanner]. */
    fun saveFingerprintTemplate(member: Member, template: ByteArray) = viewModelScope.launch {
        repo.save(member.copy(fingerprintTemplate = template, updatedAtMillis = System.currentTimeMillis()))
    }

    fun clearFingerprintTemplate(member: Member) = viewModelScope.launch {
        repo.save(member.copy(fingerprintTemplate = null, updatedAtMillis = System.currentTimeMillis()))
    }

    /** True the instant a matching member's attendance is recorded at check-in. */
    fun recordAttendance(member: Member) = viewModelScope.launch {
        repo.save(member.copy(lastAttendanceMillis = System.currentTimeMillis()))
    }

    /** Checks the "phone number already registered" rule before saving (spec section 1). */
    suspend fun isPhoneTaken(phone: String, excludingId: String = ""): Boolean =
        repo.isPhoneTaken(phone, excludingId)

    /** Generates a fresh plaintext passkey. Callers must hash it via [PasskeyUtils.hash]
     *  before it's ever stored — the plaintext is only for on-screen display and the
     *  one-time WhatsApp welcome message. */
    fun generatePasskey(): String = PasskeyUtils.generate()

    /** Copies the picked image into permanent app storage; safe to call synchronously. */
    fun savePhoto(memberId: String, uri: Uri): String = repo.savePhoto(memberId, uri)

    /** Compresses and saves an ID proof photo (Feature 3); "" if the image couldn't be read. */
    fun saveIdProofPhoto(memberId: String, uri: Uri): String = repo.saveIdProofPhoto(memberId, uri)
    fun deleteIdProofPhoto(memberId: String) = repo.deleteIdProofPhoto(memberId)

    fun exportJson(onResult: (String) -> Unit) = viewModelScope.launch {
        val json = BackupManager.exportJson(getApplication(), repo.allOnce())
        // Also stash a copy internally so Share Backup File always has the
        // latest export to work with, without changing what this button does.
        repo.saveInternalBackupCopy(json)
        onResult(json)
    }

    /** Merges a restored backup in rather than replacing the whole table, so it can never
     *  silently delete local-only records that weren't in the backup file. */
    fun importJson(json: String) = viewModelScope.launch {
        repo.mergeAll(BackupManager.importJson(getApplication(), json))
    }

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
