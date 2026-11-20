/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.about.AboutDependencyLicenseCollection
import moe.rukamori.archivetune.about.FetchAboutDependencyLicensesUseCase
import javax.inject.Inject

sealed interface AboutDependencyLicensesUiState {
    data object Loading : AboutDependencyLicensesUiState

    data class Success(
        val licenses: AboutDependencyLicenseCollection,
    ) : AboutDependencyLicensesUiState

    data object Error : AboutDependencyLicensesUiState
}

@HiltViewModel
class AboutViewModel
    @Inject
    constructor(
        private val fetchDependencyLicenses: FetchAboutDependencyLicensesUseCase,
    ) : ViewModel() {
        private val dependencyLicensesState =
            MutableStateFlow<AboutDependencyLicensesUiState>(AboutDependencyLicensesUiState.Loading)
        val dependencyLicenses = dependencyLicensesState.asStateFlow()
        private var dependencyLicensesJob: Job? = null

        fun loadDependencyLicenses() {
            if (dependencyLicensesJob?.isActive == true ||
                dependencyLicensesState.value is AboutDependencyLicensesUiState.Success
            ) {
                return
            }

            dependencyLicensesState.value = AboutDependencyLicensesUiState.Loading
            dependencyLicensesJob =
                viewModelScope.launch {
                    dependencyLicensesState.value =
                        fetchDependencyLicenses().fold(
                            onSuccess = { AboutDependencyLicensesUiState.Success(it) },
                            onFailure = { AboutDependencyLicensesUiState.Error },
                        )
                }
        }
    }
