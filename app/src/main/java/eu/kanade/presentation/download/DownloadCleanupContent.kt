package eu.kanade.presentation.download

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.download.DownloadCleanupFilter.ALL
import eu.kanade.presentation.download.DownloadCleanupFilter.INCOMPLETE
import eu.kanade.presentation.download.DownloadCleanupFilter.MULTIPLE_COPIES
import eu.kanade.presentation.download.DownloadCleanupFilter.NOT_IN_LIBRARY
import eu.kanade.tachiyomi.data.download.DownloadCleanupSummary
import eu.kanade.tachiyomi.data.download.DownloadedManga
import eu.kanade.tachiyomi.ui.download.DownloadCleanupUiState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun DownloadCleanupContent(
    state: DownloadCleanupUiState,
    canClean: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenManga: (Long) -> Unit,
    onDeleteManga: (DownloadedManga) -> Unit,
    onDeleteIncomplete: () -> Unit,
    onDeleteNotInLibrary: () -> Unit,
    onDeleteDuplicateChapters: () -> Unit,
    onConsumeOutcome: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val summary = state.summary
    var sortModeName by rememberSaveable { mutableStateOf(DownloadCleanupSort.ALPHABETICAL.name) }
    val sortMode = DownloadCleanupSort.valueOf(sortModeName)
    var filterName by rememberSaveable { mutableStateOf(ALL.name) }
    val filter = DownloadCleanupFilter.valueOf(filterName)
    var sortExpanded by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DownloadCleanupDialog?>(null) }

    val visibleItems = remember(summary, filter, sortMode) {
        val filtered = summary?.items.orEmpty().filter { item ->
            when (filter) {
                ALL -> true
                NOT_IN_LIBRARY -> !item.inLibrary
                MULTIPLE_COPIES -> item.hasMultipleCopies
                INCOMPLETE -> item.incompleteCount > 0
            }
        }
        when (sortMode) {
            DownloadCleanupSort.ALPHABETICAL -> filtered.sortedWith { first, second ->
                first.title.compareToWithCollator(second.title)
            }
            DownloadCleanupSort.SIZE -> filtered.sortedWith { first, second ->
                second.sizeBytes.compareTo(first.sizeBytes).takeIf { it != 0 }
                    ?: first.title.compareToWithCollator(second.title)
            }
        }
    }

    val outcomeMessage = state.outcome?.let { outcome ->
        val freedBytes = Formatter.formatFileSize(context, outcome.freedBytes)
        val deletedItems = pluralStringResource(
            MR.plurals.download_cleanup_deleted_items,
            outcome.deletedCount,
            outcome.deletedCount,
        )
        if (outcome.failedCount > 0) {
            val failedItems = pluralStringResource(
                MR.plurals.download_cleanup_failed_items,
                outcome.failedCount,
                outcome.failedCount,
            )
            stringResource(
                MR.strings.download_cleanup_complete_with_failures,
                deletedItems,
                freedBytes,
                failedItems,
            )
        } else {
            stringResource(MR.strings.download_cleanup_complete, deletedItems, freedBytes)
        }
    }
    LaunchedEffect(state.outcome) {
        if (state.outcome != null && outcomeMessage != null) {
            snackbarHostState.showSnackbar(outcomeMessage)
            onConsumeOutcome()
        }
    }
    val errorMessage = stringResource(MR.strings.internal_error)
    LaunchedEffect(state.error, state.isCleaning, state.outcome) {
        if (state.error && !state.isCleaning && state.outcome == null) {
            snackbarHostState.showSnackbar(errorMessage)
        }
    }

    Scaffold(
        topBar = {
            Column {
                AppBar(
                    title = stringResource(MR.strings.download_cleanup_title),
                    navigateUp = onBack,
                    actions = {
                        DropdownMenu(
                            expanded = sortExpanded,
                            onDismissRequest = { sortExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_sort_alpha)) },
                                onClick = {
                                    sortModeName = DownloadCleanupSort.ALPHABETICAL.name
                                    sortExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_sort_size)) },
                                onClick = {
                                    sortModeName = DownloadCleanupSort.SIZE.name
                                    sortExpanded = false
                                },
                            )
                        }

                        AppBarActions(
                            actions = buildList {
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.download_cleanup_rescan),
                                        icon = MaterialSymbols.Rounded.Refresh,
                                        enabled = !state.isBusy,
                                        onClick = onRefresh,
                                    ),
                                )
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_sort),
                                        icon = MaterialSymbols.AutoMirroredRounded.Sort,
                                        enabled = !state.isBusy,
                                        onClick = { sortExpanded = true },
                                    ),
                                )

                                if (summary != null && canClean && !state.isBusy) {
                                    if (summary.incompleteCount > 0) {
                                        add(
                                            AppBar.OverflowAction(
                                                title = stringResource(
                                                    MR.strings.download_cleanup_delete_incomplete,
                                                    pluralStringResource(
                                                        MR.plurals.download_cleanup_temp_files,
                                                        summary.incompleteCount,
                                                        summary.incompleteCount,
                                                    ),
                                                ),
                                                onClick = { dialog = DownloadCleanupDialog.DeleteIncomplete },
                                            ),
                                        )
                                    }
                                    if (summary.notInLibraryCount > 0) {
                                        add(
                                            AppBar.OverflowAction(
                                                title = stringResource(
                                                    MR.strings.download_cleanup_delete_not_in_library,
                                                    pluralStringResource(
                                                        MR.plurals.download_cleanup_works,
                                                        summary.notInLibraryCount,
                                                        summary.notInLibraryCount,
                                                    ),
                                                ),
                                                onClick = { dialog = DownloadCleanupDialog.DeleteNotInLibrary },
                                            ),
                                        )
                                    }
                                    if (summary.duplicateChapterCount > 0) {
                                        add(
                                            AppBar.OverflowAction(
                                                title = stringResource(
                                                    MR.strings.download_cleanup_delete_duplicates,
                                                    pluralStringResource(
                                                        MR.plurals.download_cleanup_duplicate_copies,
                                                        summary.duplicateChapterCount,
                                                        summary.duplicateChapterCount,
                                                    ),
                                                ),
                                                onClick = { dialog = DownloadCleanupDialog.DeleteDuplicateChapters },
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                    },
                )

                if (state.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        when {
            summary == null && state.isBusy -> {
                EmptyScreen(
                    stringRes = MR.strings.download_cleanup_scanning,
                    modifier = Modifier.padding(contentPadding),
                )
            }
            summary == null -> {
                EmptyScreen(
                    stringRes = MR.strings.internal_error,
                    modifier = Modifier.padding(contentPadding),
                )
            }
            else -> {
                LazyColumn(
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        CleanupSummaryHeader(
                            summary = summary,
                            canClean = canClean,
                            selectedFilter = filter,
                            onFilterSelected = { filterName = it.name },
                        )
                    }

                    if (visibleItems.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(
                                    if (summary.items.isEmpty()) {
                                        MR.strings.information_no_downloads
                                    } else {
                                        MR.strings.download_cleanup_no_matches
                                    },
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        items(
                            items = visibleItems,
                            key = { "${it.sourceDirName}/${it.mangaDirName}" },
                        ) { item ->
                            DownloadCleanupItem(
                                item = item,
                                canClean = canClean && !state.isBusy,
                                onClick = { item.mangaId?.let(onOpenManga) },
                                onDelete = { dialog = DownloadCleanupDialog.DeleteItem(item) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    val dialogState = dialog
    val activeSummary = summary
    if (dialogState != null && activeSummary != null) {
        DownloadCleanupDialog(
            dialog = dialogState,
            summary = activeSummary,
            onDismiss = { dialog = null },
            onDeleteManga = {
                dialog = null
                onDeleteManga(it)
            },
            onDeleteIncomplete = {
                dialog = null
                onDeleteIncomplete()
            },
            onDeleteNotInLibrary = {
                dialog = null
                onDeleteNotInLibrary()
            },
            onDeleteDuplicateChapters = {
                dialog = null
                onDeleteDuplicateChapters()
            },
        )
    }
}

@Composable
private fun CleanupSummaryHeader(
    summary: DownloadCleanupSummary,
    canClean: Boolean,
    selectedFilter: DownloadCleanupFilter,
    onFilterSelected: (DownloadCleanupFilter) -> Unit,
) {
    val context = LocalContext.current
    val totalSize = remember(summary.totalSize) {
        Formatter.formatFileSize(context, summary.totalSize)
    }
    val works = pluralStringResource(
        MR.plurals.download_cleanup_works,
        summary.items.size,
        summary.items.size,
    )
    val chapters = pluralStringResource(
        MR.plurals.manga_num_chapters,
        summary.totalChapters,
        summary.totalChapters,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(
                MR.strings.download_cleanup_summary,
                works,
                chapters,
                totalSize,
            ),
            style = MaterialTheme.typography.titleMedium,
        )

        if (!canClean) {
            Text(
                text = stringResource(MR.strings.download_cleanup_queue_blocked),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            DownloadCleanupFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { onFilterSelected(filter) },
                    label = {
                        Text(
                            text = when (filter) {
                                ALL -> stringResource(MR.strings.download_cleanup_all, summary.items.size)
                                NOT_IN_LIBRARY -> stringResource(
                                    MR.strings.download_cleanup_not_in_library,
                                    summary.notInLibraryCount,
                                )
                                MULTIPLE_COPIES -> stringResource(
                                    MR.strings.download_cleanup_multiple_copies,
                                    summary.multipleCopiesCount,
                                )
                                INCOMPLETE -> stringResource(
                                    MR.strings.download_cleanup_incomplete,
                                    summary.incompleteCount,
                                )
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun DownloadCleanupItem(
    item: DownloadedManga,
    canClean: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val size = remember(item.sizeBytes) {
        Formatter.formatFileSize(context, item.sizeBytes)
    }
    val chapters = pluralStringResource(
        MR.plurals.manga_num_chapters,
        item.chapterCount,
        item.chapterCount,
    )
    val isClickable = item.mangaId != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isClickable, onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Folder,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    MR.strings.download_cleanup_item_details,
                    item.sourceName,
                    chapters,
                    size,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.secondaryItemAlpha(),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (
                !item.inLibrary ||
                item.duplicateCopyCount > 1 ||
                item.duplicateChapterCount > 0 ||
                item.incompleteCount > 0
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (!item.inLibrary) {
                        Badge(
                            text = stringResource(MR.strings.download_cleanup_not_in_library_badge),
                            color = MaterialTheme.colorScheme.errorContainer,
                            textColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                    if (item.duplicateCopyCount > 1) {
                        Badge(
                            text = pluralStringResource(
                                MR.plurals.download_cleanup_work_copies,
                                item.duplicateCopyCount,
                                item.duplicateCopyCount,
                            ),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                    if (item.duplicateChapterCount > 0) {
                        Badge(
                            text = pluralStringResource(
                                MR.plurals.download_cleanup_duplicate_copies,
                                item.duplicateChapterCount,
                                item.duplicateChapterCount,
                            ),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                    if (item.incompleteCount > 0) {
                        Badge(
                            text = pluralStringResource(
                                MR.plurals.download_cleanup_temp_files,
                                item.incompleteCount,
                                item.incompleteCount,
                            ),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        )
                    }
                }
            }
        }

        if (isClickable) {
            Icon(
                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = onDelete,
            enabled = canClean,
        ) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
            )
        }
    }
}

@Composable
private fun DownloadCleanupDialog(
    dialog: DownloadCleanupDialog,
    summary: DownloadCleanupSummary,
    onDismiss: () -> Unit,
    onDeleteManga: (DownloadedManga) -> Unit,
    onDeleteIncomplete: () -> Unit,
    onDeleteNotInLibrary: () -> Unit,
    onDeleteDuplicateChapters: () -> Unit,
) {
    val context = LocalContext.current
    val title = when (dialog) {
        is DownloadCleanupDialog.DeleteItem -> stringResource(MR.strings.download_cleanup_delete_item_title)
        DownloadCleanupDialog.DeleteIncomplete ->
            stringResource(MR.strings.download_cleanup_delete_incomplete_title)
        DownloadCleanupDialog.DeleteNotInLibrary ->
            stringResource(MR.strings.download_cleanup_delete_not_in_library_title)
        DownloadCleanupDialog.DeleteDuplicateChapters ->
            stringResource(MR.strings.download_cleanup_delete_duplicates_title)
    }
    val body = when (dialog) {
        is DownloadCleanupDialog.DeleteItem -> stringResource(
            MR.strings.download_cleanup_delete_item_message,
            dialog.item.title,
            Formatter.formatFileSize(context, dialog.item.sizeBytes),
        )
        DownloadCleanupDialog.DeleteIncomplete -> stringResource(
            MR.strings.download_cleanup_delete_incomplete_message,
            summary.incompleteCount,
            Formatter.formatFileSize(context, summary.incompleteSize),
        )
        DownloadCleanupDialog.DeleteNotInLibrary -> stringResource(
            MR.strings.download_cleanup_delete_not_in_library_message,
            summary.notInLibraryCount,
            Formatter.formatFileSize(
                context,
                summary.items.filterNot { it.inLibrary }.sumOf { it.sizeBytes },
            ),
        )
        DownloadCleanupDialog.DeleteDuplicateChapters -> stringResource(
            MR.strings.download_cleanup_delete_duplicates_message,
            summary.duplicateChapterCount,
            Formatter.formatFileSize(context, summary.duplicateChapterSize),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = {
                    when (dialog) {
                        is DownloadCleanupDialog.DeleteItem -> onDeleteManga(dialog.item)
                        DownloadCleanupDialog.DeleteIncomplete -> onDeleteIncomplete()
                        DownloadCleanupDialog.DeleteNotInLibrary -> onDeleteNotInLibrary()
                        DownloadCleanupDialog.DeleteDuplicateChapters -> onDeleteDuplicateChapters()
                    }
                },
            ) {
                Text(stringResource(MR.strings.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

private enum class DownloadCleanupFilter {
    ALL,
    NOT_IN_LIBRARY,
    MULTIPLE_COPIES,
    INCOMPLETE,
}

private enum class DownloadCleanupSort {
    ALPHABETICAL,
    SIZE,
}

private sealed interface DownloadCleanupDialog {
    data class DeleteItem(val item: DownloadedManga) : DownloadCleanupDialog
    data object DeleteIncomplete : DownloadCleanupDialog
    data object DeleteNotInLibrary : DownloadCleanupDialog
    data object DeleteDuplicateChapters : DownloadCleanupDialog
}
