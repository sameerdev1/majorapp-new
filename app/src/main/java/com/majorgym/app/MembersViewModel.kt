package com.majorgym.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.majorgym.app.data.BackupManager
import com.majorgym.app.data.Member
import com.majorgym.app.data.Repository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MembersViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)

    val members: StateFlow<List<Member>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(member: Member) = viewModelScope.launch { repo.save(member) }
    fun delete(member: Member) = viewModelScope.launch { repo.delete(member) }

    /** Copies the picked image into permanent app storage; safe to call synchronously. */
    fun savePhoto(memberId: String, uri: Uri): String = repo.savePhoto(memberId, uri)

    fun exportJson(onResult: (String) -> Unit) = viewModelScope.launch {
        onResult(BackupManager.exportJson(getApplication(), repo.allOnce()))
    }

    fun importJson(json: String) = viewModelScope.launch {
        repo.replaceAll(BackupManager.importJson(getApplication(), json))
    }
}
