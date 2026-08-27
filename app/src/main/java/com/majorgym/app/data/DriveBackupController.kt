package com.majorgym.app.data

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DriveBackupUiState(
    val connectedEmail: String? = null,
    val autoBackupEnabled: Boolean = false,
    val backupTimeMinutes: Int = DriveBackupPrefs.DEFAULT_BACKUP_TIME_MINUTES,
    val retentionDays: Int = 30,
    val lastBackupMillis: Long = 0L,
    val lastBackupSizeBytes: Long = 0L,
    val lastBackupStatus: DriveBackupStatus = DriveBackupStatus.NONE,
    val lastBackupError: String? = null,
    val nextBackupMillis: Long = 0L,
    val history: List<DriveBackupHistoryEntry> = emptyList(),
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false
) {
    val isConnected: Boolean get() = connectedEmail != null
}

/**
 * Everything the Backup screen's new Google Drive section needs, in one
 * place — kept deliberately separate from MembersViewModel's existing
 * manual Export/Restore/Share-Backup code, which this never touches (spec
 * section 23: "keep the change modular").
 */
class DriveBackupController(private val context: Context, private val repository: Repository) {
    private val prefs = DriveBackupPrefs(context)
    private val drive = DriveBackupManager(context)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<DriveBackupUiState> = _state.asStateFlow()

    init {
        DriveBackupScheduler.ensureScheduled(context)
        refresh()
    }

    private fun loadState() = DriveBackupUiState(
        connectedEmail = prefs.connectedAccountEmail,
        autoBackupEnabled = prefs.autoBackupEnabled,
        backupTimeMinutes = prefs.backupTimeMinutes,
        retentionDays = prefs.retentionDays,
        lastBackupMillis = prefs.lastBackupMillis,
        lastBackupSizeBytes = prefs.lastBackupSizeBytes,
        lastBackupStatus = prefs.lastBackupStatus,
        lastBackupError = prefs.lastBackupError,
        nextBackupMillis = prefs.nextBackupMillis,
        history = prefs.history()
    )

    private fun refresh() {
        val busy = _state.value
        _state.value = loadState().copy(isBackingUp = busy.isBackingUp, isRestoring = busy.isRestoring)
    }

    fun signInClient() = drive.signInClient()

    /** Call once Google Sign-In returns successfully (requestEmail() is set,
     *  so account.email is always populated here). */
    fun onAccountConnected(account: GoogleSignInAccount) {
        prefs.connectedAccountEmail = account.email
        refresh()
    }

    /** Change Account: fully disconnects first so the sign-in picker is
     *  forced to show account choice again (spec section 13), then the
     *  caller launches sign-in and calls [onAccountConnected] on success. */
    fun beginChangeAccount() {
        drive.disconnect()
        prefs.clearConnection()
        DriveBackupScheduler.reschedule(context)
        refresh()
    }

    fun disconnect() {
        drive.disconnect()
        prefs.clearConnection()
        DriveBackupScheduler.reschedule(context)
        refresh()
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        // Spec 14: can't be turned on without a connected account.
        if (enabled && prefs.connectedAccountEmail == null) return
        prefs.autoBackupEnabled = enabled
        DriveBackupScheduler.reschedule(context)
        refresh()
    }

    fun setBackupTimeMinutes(minutes: Int) {
        prefs.backupTimeMinutes = minutes
        DriveBackupScheduler.reschedule(context)
        refresh()
    }

    fun setRetentionDays(days: Int) {
        prefs.retentionDays = days
        refresh()
    }

    suspend fun backupNow(): DriveResult<DriveBackupHistoryEntry> {
        _state.value = _state.value.copy(isBackingUp = true)
        val result = drive.performBackup(repository, prefs)
        refresh()
        _state.value = _state.value.copy(isBackingUp = false)
        return result
    }

    suspend fun listRemoteBackups() = drive.listRemoteBackups()

    suspend fun restoreBackup(fileId: String): DriveResult<Int> {
        _state.value = _state.value.copy(isRestoring = true)
        val result = drive.restoreBackup(fileId) { members ->
            // Safety backup of current data BEFORE restoring (spec section 12).
            repository.createBackupNow()
            repository.mergeAll(members)
        }
        refresh()
        _state.value = _state.value.copy(isRestoring = false)
        return result
    }

    suspend fun deleteRemoteBackup(fileId: String): DriveResult<Unit> {
        val result = drive.deleteRemoteBackup(fileId)
        if (result is DriveResult.Ok) {
            prefs.removeHistoryEntryByFileId(fileId)
            refresh()
        }
        return result
    }
}
