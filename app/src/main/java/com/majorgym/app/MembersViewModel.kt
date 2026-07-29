package com.majorgym.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.majorgym.app.data.BackupManager
import com.majorgym.app.data.Member
import com.majorgym.app.data.PairedDevice
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

    /** Copies the picked image into permanent app storage; safe to call synchronously. */
    fun savePhoto(memberId: String, uri: Uri): String = repo.savePhoto(memberId, uri)

    fun exportJson(onResult: (String) -> Unit) = viewModelScope.launch {
        onResult(BackupManager.exportJson(getApplication(), repo.allOnce()))
    }

    /** Merges a restored backup in rather than replacing the whole table, so it can never
     *  silently delete local-only records that weren't in the backup file. */
    fun importJson(json: String) = viewModelScope.launch {
        repo.mergeAll(BackupManager.importJson(getApplication(), json))
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
