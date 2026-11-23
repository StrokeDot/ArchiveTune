/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.localmedia.LocalFileAvailability
import moe.rukamori.archivetune.localmedia.LocalMediaUriHeals
import moe.rukamori.archivetune.localmedia.LocalSongScanConfig
import moe.rukamori.archivetune.localmedia.LocalSongScanSummary
import moe.rukamori.archivetune.localmedia.LocalSongScanner
import moe.rukamori.archivetune.utils.reportException
import javax.inject.Inject

@HiltViewModel
class LocalSongsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val savedState: SavedStateHandle,
        private val localSongScanner: LocalSongScanner,
    ) : ViewModel() {
        private val _scanState = MutableStateFlow(LocalSongsScanState())
        val scanState = _scanState.asStateFlow()

        val songs =
            database.localSongs().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        val missingIds = LocalFileAvailability.missingIds
        val replacementSources = LocalMediaUriHeals.sourceUris
        private val _integrity = MutableStateFlow(LocalFileIntegrityState())
        val integrity = _integrity.asStateFlow()
        private val _fileAction = MutableStateFlow(LocalFileActionState())
        val fileAction = _fileAction.asStateFlow()

        fun beginReplacement(id: String): Boolean {
            if (_fileAction.value.activeId != null || savedState.get<String>("replacementTrackId") != null) return false
            savedState["replacementTrackId"] = id
            return true
        }

        fun onReplacementPickerUnavailable() {
            savedState.remove<String>("replacementTrackId")
            _fileAction.value = LocalFileActionState(errorRes = R.string.music_missing_picker_failed)
        }

        fun onReplacementResult(resultCode: Int, data: Intent?) {
            val id = savedState.remove<String>("replacementTrackId")
            if (resultCode != Activity.RESULT_OK) return
            val clip = data?.clipData
            val uri = data?.data ?: clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
            if (id == null || uri == null) {
                _fileAction.value = LocalFileActionState(errorRes = R.string.music_missing_picker_failed)
                return
            }
            updateMissingFile(id, R.string.music_missing_replace_failed) {
                LocalMediaUriHeals.replace(context, database, id, uri)
            }
        }

        fun dismissFileActionSuccess() {
            _fileAction.value = _fileAction.value.copy(replacedTrackName = null)
        }

        fun removeFile(id: String) {
            updateMissingFile(id, R.string.music_missing_remove_failed) {
                LocalMediaUriHeals.remove(context, database, id)
                null
            }
        }

        fun dismissFileActionError() {
            _fileAction.value = _fileAction.value.copy(errorRes = null)
        }

        private fun updateMissingFile(
            id: String,
            errorRes: Int,
            update: suspend () -> String?,
        ) {
            if (_fileAction.value.activeId != null) return
            _fileAction.value = LocalFileActionState(activeId = id)
            viewModelScope.launch {
                try {
                    val replacedTrackName = update()
                    if (replacedTrackName != null) {
                        _integrity.value = _integrity.value.copy(unverifiedIds = _integrity.value.unverifiedIds - id)
                    }
                    _fileAction.value = LocalFileActionState(replacedTrackName = replacedTrackName)
                } catch (_: TimeoutCancellationException) {
                    _fileAction.value = LocalFileActionState(errorRes = errorRes)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    reportException(error)
                    _fileAction.value = LocalFileActionState(errorRes = errorRes)
                }
            }
        }

        fun checkFileIntegrity() {
            if (_integrity.value.isChecking || _fileAction.value.activeId != null) return
            _integrity.value = _integrity.value.copy(isChecking = true, failed = false)
            viewModelScope.launch {
                try {
                    val unverifiedIds = LocalFileAvailability.checkLibrary(context, database)
                    _integrity.value = LocalFileIntegrityState(hasChecked = true, unverifiedIds = unverifiedIds)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    reportException(error)
                    _integrity.value = _integrity.value.copy(isChecking = false, failed = true)
                }
            }
        }

        fun scanDevice(scanConfig: LocalSongScanConfig = LocalSongScanConfig()) {
            if (_scanState.value.isScanning) return
            viewModelScope.launch(Dispatchers.IO) {
                _scanState.value = _scanState.value.copy(isScanning = true, errorMessage = null)
                runCatching { localSongScanner.scanDevice(scanConfig) }
                    .onSuccess { summary ->
                        _scanState.value =
                            LocalSongsScanState(
                                isScanning = false,
                                lastSummary = summary,
                                errorMessage = null,
                            )
                    }.onFailure { error ->
                        reportException(error)
                        _scanState.value =
                            _scanState.value.copy(
                                isScanning = false,
                                errorMessage = error.message,
                            )
                    }
            }
        }
    }

data class LocalFileActionState(
    val activeId: String? = null,
    val errorRes: Int? = null,
    val replacedTrackName: String? = null,
)

data class LocalFileIntegrityState(
    val isChecking: Boolean = false,
    val hasChecked: Boolean = false,
    val unverifiedIds: Set<String> = emptySet(),
    val failed: Boolean = false,
)

data class LocalSongsScanState(
    val isScanning: Boolean = false,
    val lastSummary: LocalSongScanSummary? = null,
    val errorMessage: String? = null,
)
