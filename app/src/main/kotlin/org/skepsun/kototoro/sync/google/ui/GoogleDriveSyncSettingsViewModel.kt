package org.skepsun.kototoro.sync.google.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncAuth
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncSettings
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncAuthorizationException
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncRepository
import org.skepsun.kototoro.sync.google.domain.GoogleDriveSyncResult
import org.skepsun.kototoro.sync.google.work.GoogleDriveSyncWorker
import org.skepsun.kototoro.core.prefs.AppSettings
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class GoogleDriveSyncUiState(
    val isEnabled: Boolean = true,
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val accountName: String? = null,
    val intervalMinutes: Int = GoogleDriveSyncSettings.DEFAULT_INTERVAL_MINUTES,
    val isWifiOnly: Boolean = false,
    val isSyncOnStart: Boolean = true,
    val isSettingsSyncEnabled: Boolean = true,
    val lastSyncTimestamp: Long = 0L,
    val lastError: String? = null,
    val isDirty: Boolean = false,
    val isSyncing: Boolean = false,
    val syncStage: String? = null,
)

@HiltViewModel
class GoogleDriveSyncSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: GoogleDriveSyncAuth,
    private val settings: GoogleDriveSyncSettings,
    private val appSettings: AppSettings,
    private val repository: GoogleDriveSyncRepository,
    private val scheduler: GoogleDriveSyncWorker.Scheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(readState())
    val uiState: StateFlow<GoogleDriveSyncUiState> = _uiState.asStateFlow()
    private val _authorizationRequests = MutableSharedFlow<PendingIntent>(extraBufferCapacity = 1)
    val authorizationRequests: SharedFlow<PendingIntent> = _authorizationRequests.asSharedFlow()
    private val isDeletingRemoteData = AtomicBoolean(false)
    private val isImportingLegacyData = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            // Also fires refresh() on every isSyncing true->false transition, so worker-driven runs update lastError/lastSync.
            combine(repository.isSyncing, repository.syncStage) { syncing, stage -> syncing to stage }.collect { (syncing, stage) ->
                _uiState.value = readState().copy(isSyncing = syncing, syncStage = stage)
            }
        }
        viewModelScope.launch {
            settings.changes(
                GoogleDriveSyncSettings.KEY_SYNC_ENABLED,
                GoogleDriveSyncSettings.KEY_INTERVAL_MINUTES,
                GoogleDriveSyncSettings.KEY_WIFI_ONLY,
                GoogleDriveSyncSettings.KEY_SYNC_ON_START,
                GoogleDriveSyncSettings.KEY_SETTINGS_SYNC_ENABLED,
            ).collect {
                refresh()
            }
        }
    }

    fun syncNow() {
        if (!settings.isSyncEnabled) {
            settings.lastSyncError = context.getString(R.string.sync_enable_required)
            refresh()
            return
        }
        if (!settings.isSignedIn) {
            requestSignIn()
            return
        }
        // Runs in WorkManager so it survives leaving the screen; state comes back through isSyncing/syncStage.
        scheduler.enqueueManual()
        refresh()
    }

    fun deleteRemoteData() {
        if (!settings.isSyncEnabled) {
            settings.lastSyncError = context.getString(R.string.sync_enable_required)
            refresh()
            return
        }
        if (!settings.isSignedIn) {
            requestSignIn()
            return
        }
        if (!isDeletingRemoteData.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                handleSyncResult(repository.deleteRemoteData())
            } finally {
                isDeletingRemoteData.set(false)
                refresh()
            }
        }
    }

    fun importLegacyRemoteData() {
        if (!settings.isSyncEnabled) {
            settings.lastSyncError = context.getString(R.string.sync_enable_required)
            refresh()
            return
        }
        if (!settings.isSignedIn) {
            requestSignIn()
            return
        }
        if (!isImportingLegacyData.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                handleSyncResult(repository.importLegacyRemoteData())
            } finally {
                isImportingLegacyData.set(false)
                refresh()
            }
        }
    }

    fun requestSignIn() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val authorization = auth.authorize(promptSelectAccount = true)
                repository.onSignedIn(authorization.email, authorization.displayName, authorization.account)
                setWebDavEnabled(false)
                settings.isSyncEnabled = true
                scheduler.schedule()
            } catch (e: GoogleDriveSyncAuthorizationException) {
                val pendingIntent = e.authorizationIntent
                if (pendingIntent != null) {
                    _authorizationRequests.tryEmit(pendingIntent)
                } else {
                    settings.lastSyncError = normalizeGoogleDriveError(e.message)
                }
            } catch (e: Exception) {
                settings.lastSyncError = normalizeGoogleDriveError(e.message ?: e.javaClass.simpleName)
            }
            refresh()
        }
    }

    fun onAuthorizationResult(data: Intent?) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val authorization = auth.authorizationFromIntent(data)
                repository.onSignedIn(authorization.email, authorization.displayName, authorization.account)
                setWebDavEnabled(false)
                settings.isSyncEnabled = true
                scheduler.schedule()
            } catch (e: Exception) {
                settings.lastSyncError = normalizeGoogleDriveError(e.message ?: e.javaClass.simpleName)
            }
            refresh()
        }
    }

    fun onAuthorizationFailed(resultCode: Int, data: Intent?) {
        viewModelScope.launch(Dispatchers.Default) {
            if (data != null) {
                try {
                    auth.authorizationFromIntent(data)
                } catch (e: Exception) {
                    settings.lastSyncError = normalizeGoogleDriveError(e.message ?: e.javaClass.simpleName)
                    refresh()
                    return@launch
                }
            }
            settings.lastSyncError = "Google Drive authorization was canceled or failed ($resultCode)"
            refresh()
        }
    }

    fun signOut() {
        viewModelScope.launch(Dispatchers.Default) {
            repository.signOut()
            scheduler.unschedule()
            refresh()
        }
    }

    fun setIntervalMinutes(value: Int) {
        settings.intervalMinutes = value
        viewModelScope.launch(Dispatchers.Default) {
            scheduler.schedule()
            refresh()
        }
    }

    fun setWifiOnly(value: Boolean) {
        settings.isWifiOnly = value
        viewModelScope.launch(Dispatchers.Default) {
            scheduler.schedule()
            refresh()
        }
    }

    fun setSyncOnStart(value: Boolean) {
        settings.isSyncOnStart = value
        refresh()
    }

    fun setSettingsSyncEnabled(value: Boolean) {
        settings.isSettingsSyncEnabled = value
        refresh()
    }

    fun setEnabled(value: Boolean) {
        settings.isSyncEnabled = value
        viewModelScope.launch(Dispatchers.Default) {
            if (value) {
                setWebDavEnabled(false)
                scheduler.schedule()
            } else {
                scheduler.unschedule()
            }
            refresh()
        }
    }

    private fun refresh() {
        _uiState.value = readState()
    }

    private fun handleSyncResult(result: GoogleDriveSyncResult) {
        when (result) {
            is GoogleDriveSyncResult.Success -> Unit
            is GoogleDriveSyncResult.AuthorizationRequired -> {
                val pendingIntent = result.error.authorizationIntent
                if (pendingIntent != null) {
                    _authorizationRequests.tryEmit(pendingIntent)
                }
            }
            is GoogleDriveSyncResult.Error -> settings.lastSyncError = normalizeGoogleDriveError(result.message)
            is GoogleDriveSyncResult.Disabled -> Unit
        }
    }

    private fun normalizeGoogleDriveError(raw: String?): String? {
        if (raw == null) return null
        if (raw.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) ||
            raw.contains("DEVELOPER_ERROR", ignoreCase = true) ||
            raw.contains(": 10", ignoreCase = true)
        ) {
            return context.getString(R.string.google_drive_sync_unregistered_build_error)
        }
        return raw
    }

    private fun setWebDavEnabled(value: Boolean) {
        appSettings.isBackupWebDavUploadEnabled = value
        if (!value) {
            appSettings.isBackupWebDavAutoSyncEnabled = false
            appSettings.isBackupWebDavAutoRestoreEnabled = false
        }
    }

    private fun readState() = GoogleDriveSyncUiState(
        isEnabled = settings.isSyncEnabled,
        isSignedIn = settings.isSignedIn,
        accountEmail = settings.accountEmail,
        accountName = settings.accountName,
        intervalMinutes = settings.intervalMinutes,
        isWifiOnly = settings.isWifiOnly,
        isSyncOnStart = settings.isSyncOnStart,
        isSettingsSyncEnabled = settings.isSettingsSyncEnabled,
        lastSyncTimestamp = settings.lastSyncTimestamp,
        lastError = settings.lastSyncError,
        isDirty = settings.isDirty,
        isSyncing = repository.isSyncing.value,
        syncStage = repository.syncStage.value,
    )
}
