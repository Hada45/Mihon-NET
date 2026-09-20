package eu.kanade.tachiyomi.data.download

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.data.ftp.FtpDownloadStorage
import eu.kanade.tachiyomi.data.smb.SmbDownloadStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Cache where we dump the downloads directory from the filesystem. This class is needed because
 * directory checking is expensive and it slows down the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadCache(
    private val context: Context,
    private val provider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val storageManager: StorageManager,
    private val downloadPreferences: DownloadPreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .shareIn(scope, SharingStarted.Lazily, 1)

    /**
     * The interval after which this cache should be invalidated. 1 hour shouldn't cause major
     * issues, as the cache is only used for UI feedback.
     */
    private val renewInterval = 1.hours.inWholeMilliseconds

    /**
     * The last time the cache was refreshed.
     */
    private var lastRenew = 0L
    private var renewalJob: Job? = null

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing
        .debounce(1.seconds) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val diskCacheFile: File
        get() = File(context.cacheDir, if (downloadPreferences.isSmbStorage()) "dl_index_cache_smb_v1" else if (downloadPreferences.isFtpStorage()) "dl_index_cache_ftp_v1" else "dl_index_cache_v3")

    private val rootDownloadsDirMutex = Mutex()
    private var rootDownloadsDir = RootDirectory(storageManager.getDownloadsDirectory())

    private val initDiskCacheJob = scope.launch {
        rootDownloadsDirMutex.withLock {
            try {
                if (diskCacheFile.exists()) {
                    val diskCache = diskCacheFile.inputStream().use {
                        ProtoBuf.decodeFromByteArray<RootDirectory>(it.readBytes())
                    }
                    if ((diskCache.dir == null) != downloadPreferences.isRemoteStorage()) return@withLock
                    rootDownloadsDir = diskCache
                    lastRenew = System.currentTimeMillis()
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to initialize from disk cache" }
                diskCacheFile.delete()
            }
        }
    }

    init {
        storageManager.changes
            .onEach { invalidateCache() }
            .launchIn(scope)

        listOf(
            downloadPreferences.ftpDownloadLocation,
            downloadPreferences.ftpHost,
            downloadPreferences.ftpPort,
            downloadPreferences.ftpPath,
            downloadPreferences.downloadStorageType,
            downloadPreferences.smbHost,
            downloadPreferences.smbPort,
            downloadPreferences.smbShareName,
            downloadPreferences.smbPath,
        ).forEach { pref ->
            pref.changes()
                .drop(1)
                .distinctUntilChanged()
                .onEach { invalidateCache() }
                .launchIn(scope)
        }
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param chapterUrl the url of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     */
    fun findExistingChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
    ): String? {
        renewCache()
        val sourceDir = rootDownloadsDir.sourceDirs[sourceId] ?: return null
        val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(mangaTitle)] ?: return null
        val validNames = provider.getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl)
        return validNames.firstOrNull { it in mangaDir.chapterDirs }
            ?: validNames.firstOrNull { it.removeSuffix(".cbz") in mangaDir.chapterDirs }
            ?: validNames.firstOrNull { it.removeSuffix(".zip") in mangaDir.chapterDirs }
    }

    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[sourceId]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(mangaTitle)]
            if (mangaDir != null) {
                return provider.getValidChapterDirNames(
                    chapterName,
                    chapterScanlator,
                    chapterUrl,
                ).any { it in mangaDir.chapterDirs }
            }
        }
        return false
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getTotalDownloadCount(): Int {
        renewCache()

        return rootDownloadsDir.sourceDirs.values.sumOf { sourceDir ->
            sourceDir.mangaDirs.values.sumOf { mangaDir ->
                mangaDir.chapterDirs.size
            }
        }
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)]
            if (mangaDir != null) {
                return mangaDir.chapterDirs.size
            }
        }
        return 0
    }

    /**
     * Adds a chapter that has just been download to this cache.
     *
     * @param chapterDirName the downloaded chapter's directory name.
     * @param mangaUniFile the directory of the manga.
     * @param manga the manga of the chapter.
     */
    suspend fun addChapter(chapterDirName: String, mangaUniFile: UniFile, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            if (downloadPreferences.isRemoteStorage()) {
                val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: SourceDirectory(null).also {
                    rootDownloadsDir.sourceDirs += manga.source to it
                }
                val mangaName = provider.getMangaDirName(manga.title)
                val mangaDir = sourceDir.mangaDirs[mangaName] ?: MangaDirectory(null).also {
                    sourceDir.mangaDirs += mangaName to it
                }
                mangaDir.chapterDirs += chapterDirName
                // Bump lastRenew to prevent immediate SMB re-scan from overwriting the freshly-added entry
                lastRenew = System.currentTimeMillis()
                return@withLock
            }
            // Retrieve the cached source directory or cache a new one
            var sourceDir = rootDownloadsDir.sourceDirs[manga.source]
            if (sourceDir == null) {
                val source = sourceManager.get(manga.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
                rootDownloadsDir.sourceDirs += manga.source to sourceDir
            }

            // Retrieve the cached manga directory or cache a new one
            val mangaDirName = provider.getMangaDirName(manga.title)
            var mangaDir = sourceDir.mangaDirs[mangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += mangaDirName to mangaDir
            }

            // Save the chapter directory
            mangaDir.chapterDirs += chapterDirName
        }

        notifyChanges()
    }

    /**
     * Removes a chapter that has been deleted from this cache.
     *
     * @param chapter the chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapter(chapter: Chapter, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)] ?: return
            provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).forEach {
                if (it in mangaDir.chapterDirs) {
                    mangaDir.chapterDirs -= it
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes a list of chapters that have been deleted from this cache.
     *
     * @param chapters the list of chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapters(chapters: List<Chapter>, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)] ?: return
            chapters.forEach { chapter ->
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).forEach {
                    if (it in mangaDir.chapterDirs) {
                        mangaDir.chapterDirs -= it
                    }
                }
            }
        }

        notifyChanges()
    }

    /**
     * Removes a manga that has been deleted from this cache.
     *
     * @param manga the manga to remove.
     */
    suspend fun removeManga(manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDirName = provider.getMangaDirName(manga.title)
            if (sourceDir.mangaDirs.containsKey(mangaDirName)) {
                sourceDir.mangaDirs -= mangaDirName
            }
        }

        notifyChanges()
    }

    /**
     * Renames a manga in this cache.
     *
     * @param manga the manga being renamed.
     * @param mangaUniFile the manga's new directory.
     * @param newTitle the manga's new title.
     */
    suspend fun renameManga(manga: Manga, mangaUniFile: UniFile?, newTitle: String) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val oldMangaDirName = provider.getMangaDirName(manga.title)
            var oldChapterDirs: MutableSet<String>? = null
            // Save the old name's cached chapter dirs
            if (sourceDir.mangaDirs.containsKey(oldMangaDirName)) {
                oldChapterDirs = sourceDir.mangaDirs[oldMangaDirName]?.chapterDirs
                sourceDir.mangaDirs -= oldMangaDirName
            }

            // Retrieve/create the cached manga directory for new name
            val newMangaDirName = provider.getMangaDirName(newTitle)
            var mangaDir = sourceDir.mangaDirs[newMangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += newMangaDirName to mangaDir
            }

            // Add the old chapters to new name's cache
            if (!oldChapterDirs.isNullOrEmpty()) {
                mangaDir.chapterDirs += oldChapterDirs
            }
        }

        notifyChanges()
    }

    suspend fun removeSource(source: Source) {
        rootDownloadsDirMutex.withLock {
            rootDownloadsDir.sourceDirs -= source.id
        }

        notifyChanges()
    }

    fun invalidateCache() {
        lastRenew = 0L
        renewalJob?.cancel()
        diskCacheFile.delete()
        renewCache()
    }

    suspend fun awaitRemoteIndex() {
        if (!downloadPreferences.isRemoteStorage()) return
        renewCache()
        renewalJob?.join()
    }

    suspend fun awaitFtpIndex() = awaitRemoteIndex()

    /**
     * Renews the downloads cache.
     */
    private fun renewCache() {
        // Avoid renewing cache if in the process nor too often
        if (lastRenew + renewInterval >= System.currentTimeMillis() || renewalJob?.isActive == true) {
            return
        }

        renewalJob = scope.launchIO {
            initDiskCacheJob.join()
            if (lastRenew + renewInterval >= System.currentTimeMillis()) {
                return@launchIO
            }

            try {
                if (lastRenew == 0L) {
                    _isInitializing.emit(true)
                }

                // Try to wait until extensions and sources have loaded
                var sources = emptyList<Source>()
                withTimeoutOrNull(30.seconds) {
                    sources = getSources()
                }

                val sourceMap = sources.associate { provider.getSourceDirName(it).lowercase() to it.id }

                rootDownloadsDirMutex.withLock {
                    val isFtp = downloadPreferences.isFtpStorage()
                    val isSmb = downloadPreferences.isSmbStorage()
                    val useRemote = isFtp || isSmb
                    val updatedRootDir = RootDirectory(if (useRemote) null else storageManager.getDownloadsDirectory())

                    if (useRemote) {
                        val remote = try {
                            if (isSmb) {
                                val config = SmbDownloadStorage.config(downloadPreferences)
                                SmbDownloadStorage.listDownloads(config, allowedSources = sourceMap.keys)
                            } else {
                                val config = FtpDownloadStorage.config(downloadPreferences)
                                FtpDownloadStorage.listDownloads(config, allowedSources = sourceMap.keys)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "DownloadCache: failed to refresh remote index" }
                            return@withLock
                        }
                        updatedRootDir.sourceDirs = remote.mapNotNull { (sourceName, mangas) ->
                            val sourceId = sourceMap[sourceName.lowercase()] ?: return@mapNotNull null
                            sourceId to SourceDirectory(
                                null,
                                mangas.mapValues { (_, chapters) -> MangaDirectory(null, chapters.toMutableSet()) },
                            )
                        }.toMap()
                        rootDownloadsDir = updatedRootDir
                        return@withLock
                    }

                    updatedRootDir.sourceDirs = updatedRootDir.dir?.listFiles().orEmpty()
                        .filter { it.isDirectory && !it.name.isNullOrBlank() }
                        .mapNotNull { dir ->
                            val sourceId = sourceMap[dir.name!!.lowercase()]
                            sourceId?.let { it to SourceDirectory(dir) }
                        }
                        .toMap()

                    updatedRootDir.sourceDirs.values.map { sourceDir ->
                        async {
                            sourceDir.mangaDirs = sourceDir.dir?.listFiles().orEmpty()
                                .filter { it.isDirectory && !it.name.isNullOrBlank() }
                                .associate { it.name!! to MangaDirectory(it) }

                            sourceDir.mangaDirs.values.forEach { mangaDir ->
                                val chapterDirs = mangaDir.dir?.listFiles().orEmpty()
                                    .mapNotNull {
                                        when {
                                            // Ignore incomplete downloads
                                            it.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == true -> null
                                            // Folder of images
                                            it.isDirectory -> it.name
                                            // CBZ files
                                            it.isFile && it.extension == "cbz" -> it.nameWithoutExtension
                                            // Anything else is irrelevant
                                            else -> null
                                        }
                                    }
                                    .toMutableSet()

                                mangaDir.chapterDirs = chapterDirs
                            }
                        }
                    }
                        .awaitAll()

                    rootDownloadsDir = updatedRootDir
                }
            } finally {
                _isInitializing.emit(false)
            }
        }.also {
            it.invokeOnCompletion(onCancelling = true) { exception ->
                _isInitializing.tryEmit(false)
                if (exception != null && exception !is CancellationException) {
                    logcat(LogPriority.ERROR, exception) { "DownloadCache: failed to create cache" }
                }
                lastRenew = System.currentTimeMillis()
                notifyChanges()
            }
        }

        // Mainly to notify the indexing notifier UI
        notifyChanges()
    }

    private suspend fun getSources(): List<Source> {
        return sourceManager.getOnlineSources() + sourceManager.getStubSources()
    }

    private fun notifyChanges() {
        scope.launchNonCancellable {
            _changes.send(Unit)
        }
        updateDiskCache()
    }

    private var updateDiskCacheJob: Job? = null
    private fun updateDiskCache() {
        updateDiskCacheJob?.cancel()
        updateDiskCacheJob = scope.launchIO {
            delay(1.seconds)
            ensureActive()
            val bytes = ProtoBuf.encodeToByteArray(rootDownloadsDir)
            ensureActive()
            try {
                diskCacheFile.writeBytes(bytes)
            } catch (e: Throwable) {
                logcat(
                    priority = LogPriority.ERROR,
                    throwable = e,
                    message = { "Failed to write disk cache file" },
                )
            }
        }
    }
}

/**
 * Class to store the files under the root downloads directory.
 */
@Serializable
private class RootDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var sourceDirs: Map<Long, SourceDirectory> = mapOf(),
)

/**
 * Class to store the files under a source directory.
 */
@Serializable
private class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var mangaDirs: Map<String, MangaDirectory> = mapOf(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
private class MangaDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    var chapterDirs: MutableSet<String> = mutableSetOf(),
)

private object UniFileAsStringSerializer : KSerializer<UniFile?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UniFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UniFile?) {
        encoder.encodeString(value?.uri?.toString().orEmpty())
    }

    override fun deserialize(decoder: Decoder): UniFile? {
        val uri = decoder.decodeString()
        return if (uri.isBlank()) null else UniFile.fromUri(Injekt.get<Context>(), uri.toUri())
    }
}
