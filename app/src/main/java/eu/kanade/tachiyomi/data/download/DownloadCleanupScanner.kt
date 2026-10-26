package eu.kanade.tachiyomi.data.download

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

@Inject
@SingleIn(AppScope::class)
class DownloadCleanupScanner(
    private val context: Context,
    private val storageManager: StorageManager,
    private val provider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val getFavorites: GetFavorites,
    private val downloadCache: DownloadCache,
) {

    private val scanDispatcher = Dispatchers.IO.limitedParallelism(SCAN_PARALLELISM)
    private val snapshotMutex = Mutex()

    @Volatile
    private var cachedSnapshot: TimedSnapshot? = null

    suspend fun scan(force: Boolean = false): DownloadCleanupSummary {
        return currentSnapshot(force).summary
    }

    suspend fun deleteManga(item: DownloadedManga): DownloadCleanupActionResult {
        return withContext(Dispatchers.IO) {
            val snapshot = currentSnapshot()
            val key = favoriteDirectoryKey(item.sourceDirName, item.mangaDirName)
            val folder = snapshot.mangaFolders.firstOrNull {
                favoriteDirectoryKey(it.info.sourceDirName, it.info.mangaDirName) == key
            } ?: return@withContext DownloadCleanupActionResult(
                outcome = DownloadCleanupOutcome(failedCount = 1),
                summary = snapshot.summary,
            )

            finishDeletion(
                snapshot = snapshot,
                entries = listOf(
                    ScannedEntry(
                        file = folder.dir,
                        sizeBytes = folder.info.sizeBytes,
                        sourceDirName = folder.info.sourceDirName,
                        mangaDirName = folder.info.mangaDirName,
                        kind = ScannedEntryKind.MANGA,
                    ),
                ),
            )
        }
    }

    suspend fun deleteIncompleteDownloads(): DownloadCleanupActionResult {
        return withContext(Dispatchers.IO) {
            val snapshot = currentSnapshot()
            finishDeletion(snapshot, snapshot.incompleteEntries)
        }
    }

    suspend fun deleteDownloadsNotInLibrary(): DownloadCleanupActionResult {
        return withContext(Dispatchers.IO) {
            val snapshot = currentSnapshot()
            val entries = snapshot.mangaFolders
                .filterNot { it.info.inLibrary }
                .map { folder ->
                    ScannedEntry(
                        file = folder.dir,
                        sizeBytes = folder.info.sizeBytes,
                        sourceDirName = folder.info.sourceDirName,
                        mangaDirName = folder.info.mangaDirName,
                        kind = ScannedEntryKind.MANGA,
                    )
                }
            finishDeletion(snapshot, entries)
        }
    }

    suspend fun deleteDuplicateChapterCopies(): DownloadCleanupActionResult {
        return withContext(Dispatchers.IO) {
            val snapshot = currentSnapshot()
            finishDeletion(snapshot, snapshot.duplicateChapterEntries)
        }
    }

    private suspend fun finishDeletion(
        snapshot: ScanSnapshot,
        entries: List<ScannedEntry>,
    ): DownloadCleanupActionResult {
        val deletion = deleteEntries(entries)
        val updatedSnapshot = snapshot.withRemovedEntries(deletion.deletedEntries)
        updateCache(deletion.deletedEntries)
        snapshotMutex.withLock {
            cachedSnapshot = TimedSnapshot(updatedSnapshot, System.currentTimeMillis())
        }
        return DownloadCleanupActionResult(
            outcome = deletion.outcome,
            summary = updatedSnapshot.summary,
        )
    }

    private suspend fun currentSnapshot(force: Boolean = false): ScanSnapshot {
        val cached = cachedSnapshot
        if (!force && cached?.isFresh() == true) return cached.snapshot

        return snapshotMutex.withLock {
            val current = cachedSnapshot
            if (!force && current?.isFresh() == true) {
                current.snapshot
            } else {
                scanSnapshot().also {
                    cachedSnapshot = TimedSnapshot(it, System.currentTimeMillis())
                }
            }
        }
    }

    private suspend fun scanSnapshot(): ScanSnapshot = withContext(Dispatchers.IO) {
        val root = storageManager.getDownloadsDirectory()
            ?: return@withContext ScanSnapshot.EMPTY

        val favorites = getFavorites.await()
        val sources = buildList<Source> {
            addAll(sourceManager.getOnlineSources())
            addAll(sourceManager.getStubSources())
        }
        val sourceDirectoryNamesById = sources.associate { it.id to sourceDirectoryNameVariants(it) }
        val favoritesByDirectory = favorites
            .flatMap { manga ->
                val sourceDirectoryNames = sourceDirectoryNamesById[manga.source]
                    ?: sourceDirectoryNameVariants(sourceManager.getOrStub(manga.source))
                sourceDirectoryNames.flatMap { sourceDirName ->
                    mangaDirectoryNameVariants(manga.title).map { mangaDirName ->
                        favoriteDirectoryKey(sourceDirName, mangaDirName) to manga
                    }
                }
            }
            .groupBy({ it.first }, { it.second })
        val sourcesByDirectory = sources
            .flatMap { source ->
                sourceDirectoryNameVariants(source).map { it.lowercase(Locale.ROOT) to source }
            }
            .groupBy({ it.first }, { it.second })

        val incompleteEntries = mutableListOf<ScannedEntry>()
        val rootEntries = root.listScannedFiles()
        rootEntries
            .filter { it.isIncompleteDownload() }
            .forEach { entry ->
                incompleteEntries += ScannedEntry(
                    file = entry.file,
                    sizeBytes = entry.totalSize(),
                    kind = ScannedEntryKind.INCOMPLETE,
                )
            }

        val mangaDirectoryRefs = mutableListOf<MangaDirectoryRef>()
        rootEntries
            .filter { it.isDirectory && !it.isIncompleteDownload() }
            .forEach { sourceEntry ->
                val sourceDirName = sourceEntry.name
                val sourceCandidates = sourcesByDirectory[sourceDirName.lowercase(Locale.ROOT)].orEmpty()
                val sourceEntries = sourceEntry.file.listScannedFiles()

                sourceEntries
                    .filter { it.isIncompleteDownload() }
                    .forEach { entry ->
                        incompleteEntries += ScannedEntry(
                            file = entry.file,
                            sizeBytes = entry.totalSize(),
                            kind = ScannedEntryKind.INCOMPLETE,
                        )
                    }

                sourceEntries
                    .filter { it.isDirectory && !it.isIncompleteDownload() }
                    .forEach { mangaEntry ->
                        mangaDirectoryRefs += MangaDirectoryRef(
                            sourceDirName = sourceDirName,
                            sourceCandidates = sourceCandidates,
                            entry = mangaEntry,
                        )
                    }
            }

        val folderResults = coroutineScope {
            mangaDirectoryRefs
                .map { ref ->
                    async(scanDispatcher) {
                        scanMangaDirectory(ref, favoritesByDirectory)
                    }
                }
                .awaitAll()
        }
        incompleteEntries += folderResults.flatMap { it.incompleteEntries }
        val duplicateChapterEntries = folderResults.flatMap { it.duplicateChapterEntries }

        val foldersByTitle = folderResults.groupBy { normalizeTitle(it.folder.info.title) }
        val mangaFolders = folderResults.map { result ->
            val copyCount = foldersByTitle[normalizeTitle(result.folder.info.title)].orEmpty().size
            result.folder.copy(
                info = result.folder.info.copy(duplicateCopyCount = copyCount.coerceAtLeast(1)),
            )
        }

        ScanSnapshot(
            summary = DownloadCleanupSummary(
                items = mangaFolders.map { it.info },
                totalSize = mangaFolders.sumOf { it.info.sizeBytes },
                totalChapters = mangaFolders.sumOf { it.info.chapterCount },
                incompleteCount = incompleteEntries.size,
                incompleteSize = incompleteEntries.sumOf { it.sizeBytes },
                duplicateChapterCount = duplicateChapterEntries.size,
                duplicateChapterSize = duplicateChapterEntries.sumOf { it.sizeBytes },
            ),
            mangaFolders = mangaFolders,
            incompleteEntries = incompleteEntries,
            duplicateChapterEntries = duplicateChapterEntries,
        )
    }

    private fun scanMangaDirectory(
        ref: MangaDirectoryRef,
        favoritesByDirectory: Map<String, List<Manga>>,
    ): MangaScanResult {
        val mangaDirName = ref.entry.name
        val entries = ref.entry.file.listScannedFiles()
        val entrySizes = entries.associateWith { it.totalSize() }
        val incompleteEntries = entries
            .filter { it.isIncompleteDownload() }
            .map { entry ->
                ScannedEntry(
                    file = entry.file,
                    sizeBytes = entrySizes.getValue(entry),
                    sourceDirName = ref.sourceDirName,
                    mangaDirName = mangaDirName,
                    kind = ScannedEntryKind.INCOMPLETE,
                )
            }
        val chapterEntries = entries.filter { it.isCompleteChapter() }
        val duplicateChapterEntries = chapterEntries
            .groupBy { canonicalChapterEntryName(it.name) }
            .values
            .filter { it.size > 1 }
            .flatMap { copies ->
                copies
                    .sortedWith(
                        compareByDescending<ScannedFile> { entrySizes[it] ?: 0L }
                            .thenByDescending { it.file.lastModified() },
                    )
                    .drop(1)
            }
            .map { entry ->
                ScannedEntry(
                    file = entry.file,
                    sizeBytes = entrySizes.getValue(entry),
                    sourceDirName = ref.sourceDirName,
                    mangaDirName = mangaDirName,
                    kind = ScannedEntryKind.DUPLICATE_CHAPTER,
                )
            }

        val matchingFavorites = favoritesByDirectory[
            favoriteDirectoryKey(ref.sourceDirName, mangaDirName),
        ].orEmpty()
        val favorite = matchingFavorites.minByOrNull { it.id }
        val sourceName = ref.sourceCandidates
            .firstOrNull { it.id == favorite?.source }
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: ref.sourceCandidates.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: ref.sourceDirName

        val folder = ScannedMangaFolder(
            info = DownloadedManga(
                sourceDirName = ref.sourceDirName,
                mangaDirName = mangaDirName,
                title = favorite?.title?.takeIf { it.isNotBlank() } ?: mangaDirName,
                sourceName = sourceName,
                mangaId = favorite?.id,
                inLibrary = favorite != null,
                chapterCount = chapterEntries.size,
                duplicateChapterCount = duplicateChapterEntries.size,
                duplicateCopyCount = 1,
                incompleteCount = incompleteEntries.size,
                sizeBytes = entrySizes.values.sum(),
            ),
            dir = ref.entry.file,
        )
        return MangaScanResult(
            folder = folder,
            incompleteEntries = incompleteEntries,
            duplicateChapterEntries = duplicateChapterEntries,
        )
    }

    private fun deleteEntries(entries: List<ScannedEntry>): DeletedEntries {
        var deletedCount = 0
        var freedBytes = 0L
        var failedCount = 0
        val deletedEntries = mutableListOf<ScannedEntry>()

        entries
            .distinctBy { it.file.uri.toString() }
            .forEach { entry ->
                if (runCatching { entry.file.delete() }.getOrDefault(false)) {
                    deletedCount++
                    freedBytes += entry.sizeBytes
                    deletedEntries += entry
                } else {
                    failedCount++
                }
            }

        return DeletedEntries(
            outcome = DownloadCleanupOutcome(
                deletedCount = deletedCount,
                freedBytes = freedBytes,
                failedCount = failedCount,
            ),
            deletedEntries = deletedEntries,
        )
    }

    private suspend fun updateCache(entries: List<ScannedEntry>) {
        val removedMangaFolders = entries
            .filter { it.kind == ScannedEntryKind.MANGA }
            .mapNotNull { entry ->
                val source = entry.sourceDirName ?: return@mapNotNull null
                val manga = entry.mangaDirName ?: return@mapNotNull null
                source to manga
            }
            .toSet()
        val removedChapters = entries
            .filter { it.kind == ScannedEntryKind.DUPLICATE_CHAPTER }
            .mapNotNull { entry ->
                val source = entry.sourceDirName ?: return@mapNotNull null
                val manga = entry.mangaDirName ?: return@mapNotNull null
                val chapter = canonicalChapterEntryName(entry.file.name.orEmpty())
                (source to manga) to chapter
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, chapters) -> chapters.toSet() }

        downloadCache.removeDownloadedEntries(removedMangaFolders, removedChapters)
    }

    private fun UniFile.listScannedFiles(): List<ScannedFile> {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching { queryScannedChildren() }.getOrNull()?.let { return it }
        }

        return listFiles().orEmpty().mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            val isDirectory = file.isDirectory
            ScannedFile(
                file = file,
                name = name,
                isDirectory = isDirectory,
                knownSize = if (isDirectory) 0L else file.length().coerceAtLeast(0L),
            )
        }
    }

    private fun UniFile.queryScannedChildren(): List<ScannedFile>? {
        val documentId = DocumentsContract.getDocumentId(uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        val result = ArrayList<ScannedFile>()

        val cursor = context.contentResolver.query(
            childrenUri,
            FAST_CHILD_PROJECTION,
            null,
            null,
            null,
        ) ?: return null

        cursor.use {
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val childUri = DocumentsContract.buildDocumentUriUsingTree(
                    uri,
                    cursor.getString(idIndex),
                )
                val file = UniFile.fromUri(context, childUri) ?: continue
                val isDirectory = cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR
                val knownSize = when {
                    isDirectory -> 0L
                    cursor.isNull(sizeIndex) -> -1L
                    else -> cursor.getLong(sizeIndex).coerceAtLeast(0L)
                }
                result += ScannedFile(file, name, isDirectory, knownSize)
            }
        }

        return result
    }

    private fun ScannedFile.totalSize(): Long {
        if (!isDirectory) {
            return knownSize.takeIf { it >= 0L } ?: file.length().coerceAtLeast(0L)
        }

        return file.listScannedFiles().sumOf { it.totalSize() }
    }

    private fun ScannedFile.isCompleteChapter(): Boolean {
        if (isIncompleteDownload()) return false
        return isDirectory || name.substringAfterLast('.', "").equals("cbz", ignoreCase = true)
    }

    private fun ScannedFile.isIncompleteDownload(): Boolean {
        return name.endsWith(Downloader.TMP_DIR_SUFFIX, ignoreCase = true) ||
            name.endsWith(".tmp", ignoreCase = true)
    }

    private fun sourceDirectoryNameVariants(source: Source): Set<String> {
        return buildSet {
            add(provider.getSourceDirName(source))
            add(DiskUtil.buildValidFilename(source.toString(), disallowNonAscii = false))
            add(DiskUtil.buildValidFilename(source.toString(), disallowNonAscii = true))
        }
    }

    private fun mangaDirectoryNameVariants(title: String): Set<String> {
        return buildSet {
            add(provider.getMangaDirName(title))
            add(DiskUtil.buildValidFilename(title, disallowNonAscii = false))
            add(DiskUtil.buildValidFilename(title, disallowNonAscii = true))
        }
    }
}

data class DownloadCleanupSummary(
    val items: List<DownloadedManga> = emptyList(),
    val totalSize: Long = 0L,
    val totalChapters: Int = 0,
    val incompleteCount: Int = 0,
    val incompleteSize: Long = 0L,
    val duplicateChapterCount: Int = 0,
    val duplicateChapterSize: Long = 0L,
) {
    val notInLibraryCount: Int
        get() = items.count { !it.inLibrary }

    val multipleCopiesCount: Int
        get() = items.count { it.hasMultipleCopies }
}

data class DownloadedManga(
    val sourceDirName: String,
    val mangaDirName: String,
    val title: String,
    val sourceName: String,
    val mangaId: Long?,
    val inLibrary: Boolean,
    val chapterCount: Int,
    val duplicateChapterCount: Int,
    val duplicateCopyCount: Int,
    val incompleteCount: Int,
    val sizeBytes: Long,
) {
    val hasMultipleCopies: Boolean
        get() = duplicateCopyCount > 1 || duplicateChapterCount > 0
}

data class DownloadCleanupOutcome(
    val deletedCount: Int = 0,
    val freedBytes: Long = 0L,
    val failedCount: Int = 0,
)

data class DownloadCleanupActionResult(
    val outcome: DownloadCleanupOutcome,
    val summary: DownloadCleanupSummary,
)

private data class TimedSnapshot(
    val snapshot: ScanSnapshot,
    val createdAt: Long,
) {
    fun isFresh(): Boolean {
        return System.currentTimeMillis() - createdAt < SNAPSHOT_CACHE_TTL.inWholeMilliseconds
    }
}

private data class ScanSnapshot(
    val summary: DownloadCleanupSummary,
    val mangaFolders: List<ScannedMangaFolder>,
    val incompleteEntries: List<ScannedEntry>,
    val duplicateChapterEntries: List<ScannedEntry>,
) {
    fun withRemovedEntries(deletedEntries: List<ScannedEntry>): ScanSnapshot {
        if (deletedEntries.isEmpty()) return this

        val removedMangaFolders = deletedEntries
            .filter { it.kind == ScannedEntryKind.MANGA }
            .mapNotNull { it.folderKey }
            .toSet()
        val removedUris = deletedEntries.mapTo(HashSet()) { it.file.uri.toString() }
        val remainingIncompleteEntries = incompleteEntries.filterNot {
            val folderKey = it.folderKey
            it.file.uri.toString() in removedUris ||
                (folderKey != null && folderKey in removedMangaFolders)
        }
        val remainingDuplicateEntries = duplicateChapterEntries.filterNot {
            val folderKey = it.folderKey
            it.file.uri.toString() in removedUris ||
                (folderKey != null && folderKey in removedMangaFolders)
        }
        val deletedByFolder = deletedEntries
            .filter { it.folderKey != null }
            .groupBy { it.folderKey!! }
        val remainingFolders = mangaFolders
            .filterNot { it.info.folderKey in removedMangaFolders }
            .map { folder ->
                val deletedInFolder = deletedByFolder[folder.info.folderKey].orEmpty()
                val deletedDuplicates = deletedInFolder.count { it.kind == ScannedEntryKind.DUPLICATE_CHAPTER }
                val deletedIncomplete = deletedInFolder.count { it.kind == ScannedEntryKind.INCOMPLETE }
                val deletedSize = deletedInFolder.sumOf { it.sizeBytes }
                folder.copy(
                    info = folder.info.copy(
                        chapterCount = (folder.info.chapterCount - deletedDuplicates).coerceAtLeast(0),
                        duplicateChapterCount =
                        (folder.info.duplicateChapterCount - deletedDuplicates).coerceAtLeast(0),
                        incompleteCount = (folder.info.incompleteCount - deletedIncomplete).coerceAtLeast(0),
                        sizeBytes = (folder.info.sizeBytes - deletedSize).coerceAtLeast(0L),
                    ),
                )
            }
        val copyCounts = remainingFolders.groupBy { normalizeTitle(it.info.title) }
        val foldersWithCopyCounts = remainingFolders.map { folder ->
            val copyCount = copyCounts[normalizeTitle(folder.info.title)].orEmpty().size
            folder.copy(info = folder.info.copy(duplicateCopyCount = copyCount.coerceAtLeast(1)))
        }

        return ScanSnapshot(
            summary = DownloadCleanupSummary(
                items = foldersWithCopyCounts.map { it.info },
                totalSize = foldersWithCopyCounts.sumOf { it.info.sizeBytes },
                totalChapters = foldersWithCopyCounts.sumOf { it.info.chapterCount },
                incompleteCount = remainingIncompleteEntries.size,
                incompleteSize = remainingIncompleteEntries.sumOf { it.sizeBytes },
                duplicateChapterCount = remainingDuplicateEntries.size,
                duplicateChapterSize = remainingDuplicateEntries.sumOf { it.sizeBytes },
            ),
            mangaFolders = foldersWithCopyCounts,
            incompleteEntries = remainingIncompleteEntries,
            duplicateChapterEntries = remainingDuplicateEntries,
        )
    }

    companion object {
        val EMPTY = ScanSnapshot(
            summary = DownloadCleanupSummary(),
            mangaFolders = emptyList(),
            incompleteEntries = emptyList(),
            duplicateChapterEntries = emptyList(),
        )
    }
}

private data class ScannedMangaFolder(
    val info: DownloadedManga,
    val dir: UniFile,
) {
    val key: String
        get() = info.folderKey
}

private data class ScannedEntry(
    val file: UniFile,
    val sizeBytes: Long,
    val sourceDirName: String? = null,
    val mangaDirName: String? = null,
    val kind: ScannedEntryKind,
) {
    val folderKey: String?
        get() = if (sourceDirName != null && mangaDirName != null) {
            favoriteDirectoryKey(sourceDirName, mangaDirName)
        } else {
            null
        }
}

private data class ScannedFile(
    val file: UniFile,
    val name: String,
    val isDirectory: Boolean,
    val knownSize: Long,
)

private data class MangaDirectoryRef(
    val sourceDirName: String,
    val sourceCandidates: List<Source>,
    val entry: ScannedFile,
)

private data class MangaScanResult(
    val folder: ScannedMangaFolder,
    val incompleteEntries: List<ScannedEntry>,
    val duplicateChapterEntries: List<ScannedEntry>,
)

private data class DeletedEntries(
    val outcome: DownloadCleanupOutcome,
    val deletedEntries: List<ScannedEntry>,
)

private enum class ScannedEntryKind {
    MANGA,
    INCOMPLETE,
    DUPLICATE_CHAPTER,
}

private val DownloadedManga.folderKey: String
    get() = favoriteDirectoryKey(sourceDirName, mangaDirName)

private fun favoriteDirectoryKey(sourceDirName: String, mangaDirName: String): String {
    return sourceDirName.lowercase(Locale.ROOT) + '\u0000' + mangaDirName.lowercase(Locale.ROOT)
}

internal fun canonicalChapterEntryName(name: String): String {
    val withoutArchiveExtension = if (name.endsWith(".cbz", ignoreCase = true)) {
        name.dropLast(4)
    } else {
        name
    }
    return withoutArchiveExtension.trim().lowercase(Locale.ROOT)
}

private fun normalizeTitle(title: String): String {
    return title.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)
}

private val WHITESPACE = Regex("\\s+")
private val FAST_CHILD_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
)
private val SNAPSHOT_CACHE_TTL = 2.minutes
private val SCAN_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
