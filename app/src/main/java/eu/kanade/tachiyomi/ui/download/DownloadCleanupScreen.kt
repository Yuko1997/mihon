package eu.kanade.tachiyomi.ui.download

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.download.DownloadCleanupContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen

object DownloadCleanupScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<DownloadCleanupViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val canClean by viewModel.canClean.collectAsStateWithLifecycle()

        DownloadCleanupContent(
            state = state,
            canClean = canClean,
            onBack = navigator::pop,
            onRefresh = viewModel::refresh,
            onOpenManga = { navigator.push(MangaScreen(it)) },
            onDeleteManga = viewModel::deleteManga,
            onDeleteIncomplete = viewModel::deleteIncompleteDownloads,
            onDeleteNotInLibrary = viewModel::deleteDownloadsNotInLibrary,
            onDeleteDuplicateChapters = viewModel::deleteDuplicateChapterCopies,
            onConsumeOutcome = viewModel::consumeOutcome,
        )
    }
}
