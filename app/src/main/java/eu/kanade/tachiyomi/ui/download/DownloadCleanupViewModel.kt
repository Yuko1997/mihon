package eu.kanade.tachiyomi.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.download.DownloadCleanupActionResult
import eu.kanade.tachiyomi.data.download.DownloadCleanupOutcome
import eu.kanade.tachiyomi.data.download.DownloadCleanupScanner
import eu.kanade.tachiyomi.data.download.DownloadCleanupSummary
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadedManga
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class DownloadCleanupViewModel(
    private val scanner: DownloadCleanupScanner,
    downloadManager: DownloadManager,
) : ViewModel() {

    private val _state = MutableStateFlow(DownloadCleanupUiState())
    val state: StateFlow<DownloadCleanupUiState> = _state.asStateFlow()

    val canClean: StateFlow<Boolean> = downloadManager.queueState
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), true)

    private var scanJob: Job? = null

    init {
        load(force = false)
    }

    fun refresh() {
        load(force = true)
    }

    private fun load(force: Boolean) {
        if (_state.value.isCleaning) return

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { it.copy(isScanning = true, error = false) }
            val result = runCatching { scanner.scan(force) }
            _state.update {
                it.copy(
                    isScanning = false,
                    summary = result.getOrNull() ?: it.summary,
                    error = result.isFailure,
                )
            }
        }
    }

    fun deleteManga(item: DownloadedManga) {
        runCleanup { scanner.deleteManga(item) }
    }

    fun deleteIncompleteDownloads() {
        runCleanup(scanner::deleteIncompleteDownloads)
    }

    fun deleteDownloadsNotInLibrary() {
        runCleanup(scanner::deleteDownloadsNotInLibrary)
    }

    fun deleteDuplicateChapterCopies() {
        runCleanup(scanner::deleteDuplicateChapterCopies)
    }

    fun consumeOutcome() {
        _state.update { it.copy(outcome = null) }
    }

    private fun runCleanup(operation: suspend () -> DownloadCleanupActionResult) {
        if (_state.value.isCleaning || !canClean.value) return

        viewModelScope.launch {
            _state.update { it.copy(isCleaning = true, outcome = null, error = false) }

            val operationResult = runCatching { operation() }
            _state.update {
                it.copy(
                    isCleaning = false,
                    summary = operationResult.getOrNull()?.summary ?: it.summary,
                    error = operationResult.isFailure,
                    outcome = operationResult.getOrNull()?.outcome,
                )
            }
        }
    }
}

data class DownloadCleanupUiState(
    val isScanning: Boolean = true,
    val isCleaning: Boolean = false,
    val summary: DownloadCleanupSummary? = null,
    val outcome: DownloadCleanupOutcome? = null,
    val error: Boolean = false,
) {
    val isBusy: Boolean
        get() = isScanning || isCleaning
}
