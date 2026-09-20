package eu.kanade.tachiyomi.data.smb

import android.content.Context
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.cancellation.CancellationException
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig as SmbjConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

object SmbDownloadStorage {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl")

    data class CbzEntry(
        val name: String,
        val method: Int,
        val compSize: Long,
        val uncompSize: Long,
        val localOffset: Long,
    )

    private val archiveIndexCache: MutableMap<String, Map<String, CbzEntry>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Map<String, CbzEntry>>(30, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, CbzEntry>>?): Boolean {
                return size > 30
            }
        },
    )

    // Cache hasil listPages: cacheKey -> list of page URLs
    // Simpan hingga 200 chapter - buka ulang chapter yang sama = 0ms
    private val listPagesCache: MutableMap<String, List<String>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<String>>(200, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
                return size > 200
            }
        },
    )

    private val smbjConfig: SmbjConfig = SmbjConfig.builder()
        .withTimeout(15_000, TimeUnit.MILLISECONDS)
        .withSoTimeout(15_000, TimeUnit.MILLISECONDS)
        .withBufferSize(1024 * 1024)
        .withDfsEnabled(false)
        .build()

    // SINGLETON SMBClient: hindari kebocoran thread & rate-limiting server Windows
    private val smbClient: SMBClient by lazy { SMBClient(smbjConfig) }

    // Dedicated metadata share: khusus listPages, listDownloads, delete, dll.
    // Terpisah dari data gambar sehingga pembukaan chapter TIDAK PERNAH terblokir!
    private var metaShare: PooledShare? = null
    private val metaLock = Any()

    // Data share pool: khusus pembacaan streaming file/gambar (fetchPageBytes)
    // Dibatasi Semaphore agar tidak membanjiri server SMB Windows dengan koneksi TCP baru
    private const val DATA_POOL_SIZE = 2
    private val dataPool = ArrayDeque<PooledShare>(DATA_POOL_SIZE)
    private val dataPoolLock = Any()
    private val dataSemaphore = java.util.concurrent.Semaphore(DATA_POOL_SIZE, true)

    private class PooledShare(
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
        val config: SmbConfig,
    ) {
        var lastUsed: Long = System.currentTimeMillis()

        fun isValid(targetConfig: SmbConfig): Boolean {
            return config == targetConfig &&
                connection.isConnected &&
                share.isConnected &&
                (System.currentTimeMillis() - lastUsed < 120_000L)
        }

        fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
        }
    }

    private fun createShare(config: SmbConfig): PooledShare {
        val t0 = System.currentTimeMillis()
        var conn: Connection? = null
        var sess: Session? = null
        var sh: DiskShare? = null
        try {
            conn = smbClient.connect(config.host, config.port)
            val auth = if (config.username.isNotBlank()) {
                AuthenticationContext(config.username, config.password.toCharArray(), config.domain.ifBlank { null })
            } else {
                AuthenticationContext.anonymous()
            }
            sess = conn.authenticate(auth)
            sh = sess.connectShare(config.shareName) as? DiskShare
                ?: throw IOException("Share '${config.shareName}' is not a disk share")
            SmbPerfLogger.log("SMB_CONN", "Koneksi baru share '${config.shareName}' tersambung dalam ${System.currentTimeMillis() - t0}ms")
            return PooledShare(conn, sess, sh, config)
        } catch (e: Throwable) {
            runCatching { sh?.close() }
            runCatching { sess?.close() }
            runCatching { conn?.close() }
            SmbPerfLogger.log("SMB_CONN", "!!! Gagal sambung share '${config.shareName}' setelah ${System.currentTimeMillis() - t0}ms: ${e.message}")
            throw e
        }
    }

    private fun <T> withMetaShare(config: SmbConfig, block: (DiskShare) -> T): T {
        return synchronized(metaLock) {
            val share = try {
                val current = metaShare
                if (current != null && current.isValid(config)) {
                    current.lastUsed = System.currentTimeMillis()
                    current
                } else {
                    current?.close()
                    metaShare = null
                    val t0 = System.currentTimeMillis()
                    SmbPerfLogger.log("SMB_CONN", "Menghubungkan metadata share ke ${config.host}...")
                    val newShare = createShare(config)
                    SmbPerfLogger.log("SMB_CONN", "Metadata share tersambung dalam ${System.currentTimeMillis() - t0}ms")
                    metaShare = newShare
                    newShare
                }
            } catch (e: Throwable) {
                metaShare?.close()
                metaShare = null
                throw e
            }

            var success = false
            try {
                val result = block(share.share)
                success = true
                share.lastUsed = System.currentTimeMillis()
                result
            } finally {
                if (!success || !share.connection.isConnected || !share.share.isConnected) {
                    share.close()
                    if (metaShare === share) {
                        metaShare = null
                    }
                }
            }
        }
    }

    private fun getDataShare(config: SmbConfig): PooledShare {
        synchronized(dataPoolLock) {
            val valid = dataPool.firstOrNull { it.isValid(config) }
            if (valid != null) {
                dataPool.remove(valid)
                valid.lastUsed = System.currentTimeMillis()
                return valid
            }
            val toClose = dataPool.filter { !it.isValid(config) }
            toClose.forEach { it.close() }
            dataPool.removeAll(toClose.toSet())
        }
        return createShare(config)
    }

    private fun returnDataShare(pooled: PooledShare) {
        synchronized(dataPoolLock) {
            if (dataPool.size < DATA_POOL_SIZE && pooled.connection.isConnected && pooled.share.isConnected) {
                pooled.lastUsed = System.currentTimeMillis()
                dataPool.addFirst(pooled)
            } else {
                pooled.close()
            }
        }
    }

    private fun <T> withDataShare(config: SmbConfig, block: (DiskShare) -> T): T {
        dataSemaphore.acquire()
        val pooled = try {
            getDataShare(config)
        } catch (e: Throwable) {
            dataSemaphore.release()
            throw e
        }

        var success = false
        return try {
            val res = block(pooled.share)
            success = true
            res
        } finally {
            try {
                if (success && pooled.connection.isConnected && pooled.share.isConnected) {
                    returnDataShare(pooled)
                } else {
                    SmbPerfLogger.log("SMB_CONN", "Koneksi data socket kotor ditutup (success=$success)")
                    pooled.close()
                }
            } finally {
                dataSemaphore.release()
            }
        }
    }

    private fun <T> withPooledShare(config: SmbConfig, block: (DiskShare) -> T): T = withDataShare(config, block)

    fun config(preferences: DownloadPreferences): SmbConfig {
        val host = preferences.smbHost.get().trim()
        require(host.isNotBlank()) { "SMB host is required" }
        val port = preferences.smbPort.get().toIntOrNull()
        require(port != null && port in 1..65535) { "SMB port must be between 1 and 65535" }
        val shareName = preferences.smbShareName.get().trim().trim('/', '\\')
        require(shareName.isNotBlank()) { "SMB share name is required" }
        return SmbConfig(
            host = host,
            port = port,
            shareName = shareName,
            username = preferences.smbUsername.get().trim(),
            password = preferences.smbPassword.get(),
            domain = preferences.smbDomain.get().trim(),
            path = preferences.smbPath.get().ifBlank { "/" },
        )
    }

    fun chapterPath(config: SmbConfig, source: String, manga: String, chapter: String = ""): String =
        smbPath(config.path, listOf(source, manga, chapter).filter { it.isNotEmpty() }.joinToString("\\"))

    private fun smbPath(base: String, child: String = ""): String {
        val b = base.replace('/', '\\').trim('\\')
        val c = child.replace('/', '\\').trim('\\')
        return listOf(b, c).filter { it.isNotEmpty() }.joinToString("\\")
    }

    private inline fun <T> withShare(config: SmbConfig, block: (DiskShare) -> T): T {
        val client = SMBClient(smbjConfig)
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        try {
            connection = client.connect(config.host, config.port)
            val auth = if (config.username.isNotBlank()) {
                AuthenticationContext(config.username, config.password.toCharArray(), config.domain.ifBlank { null })
            } else {
                AuthenticationContext.anonymous()
            }
            session = connection.authenticate(auth)
            share = session.connectShare(config.shareName) as? DiskShare
                ?: throw IOException("Share '${config.shareName}' is not a disk share")
            return block(share)
        } finally {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection?.close() }
        }
    }

    private fun isDirectory(info: FileIdBothDirectoryInformation): Boolean {
        return (info.fileAttributes and 0x10L) != 0L
    }

    private fun readFully(
        file: com.hierynomus.smbj.share.File,
        buffer: ByteArray,
        fileOffset: Long,
        length: Int,
    ) {
        var totalRead = 0
        while (totalRead < length) {
            val count = file.read(buffer, fileOffset + totalRead, totalRead, length - totalRead)
            if (count <= 0) break
            totalRead += count
        }
        if (totalRead < length) {
            throw IOException("Premature EOF reading SMB file: expected $length bytes, got $totalRead")
        }
    }

    private fun parseCentralDirectory(
        share: DiskShare,
        archivePath: String,
    ): Map<String, CbzEntry> {
        val cached = archiveIndexCache[archivePath]
        if (cached != null) return cached

        val smbFile = share.openFile(
            archivePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        smbFile.use { sf ->
            val fileSize = sf.fileInformation.standardInformation.endOfFile
            if (fileSize < 22) throw IOException("File too small to be a ZIP archive: $archivePath")

            val searchLen = minOf(fileSize, 65536L).toInt()
            val tailOffset = fileSize - searchLen
            val tailBuf = ByteArray(searchLen)
            readFully(sf, tailBuf, tailOffset, searchLen)

            var eocdPos = -1
            for (i in searchLen - 22 downTo 0) {
                if (tailBuf[i] == 0x50.toByte() &&
                    tailBuf[i + 1] == 0x4b.toByte() &&
                    tailBuf[i + 2] == 0x05.toByte() &&
                    tailBuf[i + 3] == 0x06.toByte()
                ) {
                    eocdPos = i
                    break
                }
            }
            if (eocdPos == -1) {
                throw IOException("End of Central Directory record (EOCD) not found in $archivePath")
            }

            val eocd = ByteBuffer.wrap(tailBuf, eocdPos, searchLen - eocdPos).order(ByteOrder.LITTLE_ENDIAN)
            eocd.getInt() // 0x06054b50
            eocd.getShort() // disk number
            eocd.getShort() // cd disk
            eocd.getShort() // entries on disk
            eocd.getShort() // total entries
            val cdSize = eocd.getInt().toLong() and 0xffffffffL
            val cdOffset = eocd.getInt().toLong() and 0xffffffffL

            if (cdSize > 32 * 1024 * 1024L) {
                throw IOException("Central Directory size unusually large: $cdSize bytes")
            }

            val cdBuf = ByteArray(cdSize.toInt())
            readFully(sf, cdBuf, cdOffset, cdSize.toInt())
            val cd = ByteBuffer.wrap(cdBuf).order(ByteOrder.LITTLE_ENDIAN)

            val map = LinkedHashMap<String, CbzEntry>()
            while (cd.remaining() >= 46) {
                val sig = cd.getInt()
                if (sig != 0x02014b50) break
                cd.getShort() // ver made
                cd.getShort() // ver need
                cd.getShort() // flag
                val method = cd.getShort().toInt() and 0xffff
                cd.getShort() // time
                cd.getShort() // date
                cd.getInt() // crc
                val compSize = cd.getInt().toLong() and 0xffffffffL
                val uncompSize = cd.getInt().toLong() and 0xffffffffL
                val nameLen = cd.getShort().toInt() and 0xffff
                val extraLen = cd.getShort().toInt() and 0xffff
                val commLen = cd.getShort().toInt() and 0xffff
                cd.getShort() // disk
                cd.getShort() // int attr
                cd.getInt() // ext attr
                val localOffset = cd.getInt().toLong() and 0xffffffffL

                val nameBytes = ByteArray(nameLen)
                cd.get(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)

                if (extraLen > 0 && cd.remaining() >= extraLen) {
                    cd.position(cd.position() + extraLen)
                }
                if (commLen > 0 && cd.remaining() >= commLen) {
                    cd.position(cd.position() + commLen)
                }

                val ext = name.substringAfterLast('.', "").lowercase()
                if (!name.endsWith('/') && ext in imageExtensions) {
                    map[name] = CbzEntry(name, method, compSize, uncompSize, localOffset)
                }
            }

            archiveIndexCache[archivePath] = map
            return map
        }
    }

    suspend fun testConnection(config: SmbConfig): String = withContext(Dispatchers.IO) {
        val client = SMBClient(smbjConfig)
        var connection: Connection? = null
        var session: Session? = null
        var share: DiskShare? = null
        try {
            connection = client.connect(config.host, config.port)
            val auth = if (config.username.isNotBlank()) {
                AuthenticationContext(config.username, config.password.toCharArray(), config.domain.ifBlank { null })
            } else {
                AuthenticationContext.anonymous()
            }
            session = connection.authenticate(auth)
            share = session.connectShare(config.shareName) as? DiskShare
                ?: throw IOException("Share '${config.shareName}' is not a disk share")

            val basePath = config.path.replace('/', '\\').trim('\\')
            if (basePath.isNotEmpty() && !share.folderExists(basePath)) {
                createDirectories(share, basePath)
            }

            val testDir = if (basePath.isEmpty()) "mihon_probe_${System.currentTimeMillis()}" else "$basePath\\mihon_probe_${System.currentTimeMillis()}"
            share.mkdir(testDir)
            share.rmdir(testDir, false)

            "OK"
        } finally {
            runCatching { share?.close() }
            runCatching { session?.close() }
            runCatching { connection?.close() }
        }
    }

    private fun createDirectories(share: DiskShare, path: String) {
        val parts = path.replace('/', '\\').split('\\').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current\\$part"
            if (!share.folderExists(current)) {
                runCatching { share.mkdir(current) }
            }
        }
    }

    suspend fun uploadChapter(config: SmbConfig, source: String, manga: String, chapter: String, tempDir: File) =
        withContext(Dispatchers.IO) {
            withShare(config) { share ->
                val finalDir = chapterPath(config, source, manga, chapter)
                val uploadingDir = "$finalDir.uploading"
                createDirectories(share, chapterPath(config, source, manga))
                if (share.folderExists(uploadingDir)) {
                    deleteRecursive(share, uploadingDir)
                }
                createDirectories(share, uploadingDir)

                val files = tempDir.listFiles() ?: emptyArray()
                for (file in files) {
                    if (file.isDirectory) continue
                    val remoteFile = "$uploadingDir\\${file.name}"
                    val smbFile = share.openFile(
                        remoteFile,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        null,
                    )
                    smbFile.use { sf ->
                        file.inputStream().use { input ->
                            sf.outputStream.use { output ->
                                input.copyTo(output, bufferSize = 64 * 1024)
                            }
                        }
                    }
                }

                if (share.folderExists(finalDir)) {
                    deleteRecursive(share, finalDir)
                }
                deleteRecursive(share, "$finalDir.cbz")
                deleteRecursive(share, "$finalDir.zip")

                share.openDirectory(
                    uploadingDir,
                    EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                ).use { it.rename(finalDir, true) }
            }
        }

        private fun resolveCandidatePaths(config: SmbConfig, path: String): List<String> {
        val raw = path.replace('/', '\\').trim('\\')
        val p = config.path.replace('/', '\\').trim('\\')
        val s = config.shareName.replace('/', '\\').trim('\\')

        val strippedP = if (p.isNotEmpty() && raw.startsWith(p, ignoreCase = true)) {
            raw.substring(p.length).trim('\\')
        } else raw

        val strippedS = if (s.isNotEmpty() && strippedP.startsWith(s, ignoreCase = true)) {
            strippedP.substring(s.length).trim('\\')
        } else strippedP

        val list = mutableListOf<String>()
        fun add(cand: String) {
            val clean = cand.replace('/', '\\').trim('\\')
            if (clean.isNotEmpty() && clean !in list) {
                list.add(clean)
            }
        }

        // Prioritize the actual download root (downloads\\...) first
        if (strippedS.isNotEmpty()) {
            add("downloads\\$strippedS")
            add(strippedS)
        }
        if (strippedP.isNotEmpty() && strippedP != strippedS) {
            add("downloads\\$strippedP")
            add(strippedP)
        }
        if (p.isNotEmpty() && !p.equals(s, ignoreCase = true)) {
            add("$p\\downloads\\$strippedS")
            add("$p\\$strippedS")
        }
        add(raw)
        add("downloads\\$raw")

        return list
    }

    suspend fun listDownloads(
        config: SmbConfig,
        allowedSources: Set<String>? = null,
    ): Map<String, Map<String, List<String>>> = withContext(Dispatchers.IO) {
        withShare(config) { share ->
            val p = config.path.replace('/', '\\').trim('\\')
            val s = config.shareName.replace('/', '\\').trim('\\')

            val strippedP = if (s.isNotEmpty() && p.startsWith(s, ignoreCase = true)) {
                p.substring(s.length).trim('\\')
            } else {
                p
            }

            val possibleRoots = listOf(
                if (strippedP.isNotEmpty()) "$strippedP\\downloads" else "downloads",
                strippedP,
                if (p.isNotEmpty()) "$p\\downloads" else "downloads",
                p,
                "downloads",
                "",
            ).distinct()

            // Find the single best target root that actually contains manga sources
            val targetRoot = possibleRoots.firstOrNull { r ->
                if (r.isNotEmpty() && !share.folderExists(r)) return@firstOrNull false
                val list = runCatching { share.list(r) }.getOrNull() ?: return@firstOrNull false
                if (!allowedSources.isNullOrEmpty()) {
                    list.any { isDirectory(it) && it.fileName.lowercase() in allowedSources }
                } else {
                    list.any {
                        isDirectory(it) && it.fileName != "." && it.fileName != ".." &&
                            it.fileName.lowercase() !in setOf("downloads", "backup", "autobackup", "fonts", "local", "localanime", "mihon")
                    }
                }
            } ?: possibleRoots.firstOrNull { it.isNotEmpty() && share.folderExists(it) } ?: ""

            val rawSources = runCatching {
                share.list(targetRoot).filter { isDirectory(it) && it.fileName != "." && it.fileName != ".." && it.fileName != "downloads" }
            }.getOrDefault(emptyList())

            val sources = if (!allowedSources.isNullOrEmpty()) {
                rawSources.filter { it.fileName.lowercase() in allowedSources }
            } else {
                rawSources
            }

            if (sources.isEmpty()) return@withShare emptyMap()

            val semaphore = Semaphore(32)

            coroutineScope {
                sources.map { source ->
                    async {
                        val sourcePath = if (targetRoot.isEmpty()) source.fileName else "$targetRoot\\${source.fileName}"
                        val mangas = runCatching {
                            share.list(sourcePath).filter { isDirectory(it) && it.fileName != "." && it.fileName != ".." }
                        }.getOrDefault(emptyList())

                        val mangaEntries = mangas.map { manga ->
                            async {
                                semaphore.withPermit {
                                    val mangaPath = "$sourcePath\\${manga.fileName}"
                                    val chapters = runCatching {
                                        share.list(mangaPath).filter { it.fileName != "." && it.fileName != ".." }
                                            .filter {
                                                (isDirectory(it) && !it.fileName.endsWith(".uploading")) ||
                                                    (!isDirectory(it) && (it.fileName.endsWith(".cbz", ignoreCase = true) || it.fileName.endsWith(".zip", ignoreCase = true)))
                                            }
                                            .map {
                                                it.fileName.removeSuffix(".cbz").removeSuffix(".zip")
                                            }
                                    }.getOrDefault(emptyList())
                                    manga.fileName to chapters
                                }
                            }
                        }.awaitAll()

                        source.fileName to mangaEntries.toMap()
                    }
                }.awaitAll().toMap()
            }
        }
    }


    private fun resolveRemoteArchive(share: DiskShare, config: SmbConfig, chapterPath: String): String? {
        val candidates = resolveCandidatePaths(config, chapterPath)
        for (cand in candidates) {
            val directArchive = when {
                cand.endsWith(".cbz", ignoreCase = true) || cand.endsWith(".zip", ignoreCase = true) -> {
                    if (share.fileExists(cand)) cand else null
                }
                share.fileExists("$cand.cbz") -> "$cand.cbz"
                share.fileExists("$cand.zip") -> "$cand.zip"
                share.fileExists(cand) && !share.folderExists(cand) -> cand
                else -> null
            }
            if (directArchive != null) return directArchive

            val parentPath = cand.substringBeforeLast('\\', "")
            val chapterName = cand.substringAfterLast('\\')
            if (parentPath.isNotEmpty() && share.folderExists(parentPath)) {
                val items = runCatching { share.list(parentPath) }.getOrNull() ?: emptyList()
                val matched = items.firstOrNull { item ->
                    val name = item.fileName.removeSuffix(".cbz").removeSuffix(".zip")
                    name.equals(chapterName, ignoreCase = true) ||
                        (chapterName.contains('_') && name.equals(chapterName.substringBeforeLast('_'), ignoreCase = true))
                }
                if (matched != null && !isDirectory(matched) &&
                    (matched.fileName.endsWith(".cbz", ignoreCase = true) || matched.fileName.endsWith(".zip", ignoreCase = true))
                ) {
                    return "$parentPath\\${matched.fileName}"
                }
            }
        }
        return null
    }

    suspend fun getOrDownloadChapterArchive(
        context: Context,
        config: SmbConfig,
        remoteChapterPath: String,
    ): File? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "smb_chapter_cache").apply { mkdirs() }
        val safeBase = remoteChapterPath
            .replace('/', '_')
            .replace('\\', '_')
            .replace(':', '_')
            .replace('*', '_')
            .replace('?', '_')
            .replace('"', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('|', '_')
            .replace(' ', '_')
            .takeLast(60)
        val hash = Integer.toHexString(remoteChapterPath.hashCode())
        val targetName = "${safeBase}_${hash}.cbz"
        val cachedFile = File(cacheDir, targetName)

        if (cachedFile.exists() && cachedFile.length() > 0) {
            cachedFile.setLastModified(System.currentTimeMillis())
            android.util.Log.e("SMB_DEBUG", "getOrDownloadChapterArchive: CACHE HIT for $remoteChapterPath (${cachedFile.length()} bytes)")
            return@withContext cachedFile
        }

        val t0 = System.currentTimeMillis()
        android.util.Log.e("SMB_DEBUG", "getOrDownloadChapterArchive: START download for $remoteChapterPath")
        val tempFile = File(cacheDir, "$targetName.${System.currentTimeMillis()}.tmp")

        try {
            val downloaded = withPooledShare(config) { share ->
                val remoteArchive = resolveRemoteArchive(share, config, remoteChapterPath)
                if (remoteArchive == null) {
                    android.util.Log.e("SMB_DEBUG", "getOrDownloadChapterArchive: remote archive not found for $remoteChapterPath")
                    return@withPooledShare false
                }
                android.util.Log.e("SMB_DEBUG", "getOrDownloadChapterArchive: streaming remote archive $remoteArchive")

                val smbFile = share.openFile(
                    remoteArchive,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                smbFile.use { sf ->
                    sf.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 256 * 1024)
                            output.flush()
                        }
                    }
                }
                true
            }

            if (!downloaded || !tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                return@withContext null
            }

            if (cachedFile.exists()) {
                cachedFile.delete()
            }
            if (!tempFile.renameTo(cachedFile)) {
                tempFile.copyTo(cachedFile, overwrite = true)
                tempFile.delete()
            }

            cachedFile.setLastModified(System.currentTimeMillis())
            val duration = System.currentTimeMillis() - t0
            val speedMBs = if (duration > 0) (cachedFile.length() * 1000.0 / duration / (1024 * 1024)) else 0.0
            android.util.Log.e("SMB_DEBUG", "getOrDownloadChapterArchive: SUCCESS in ${duration}ms, size=${cachedFile.length()} bytes (${String.format("%.2f", speedMBs)} MB/s)")

            pruneCache(cacheDir)
            cachedFile
        } catch (e: Throwable) {
            tempFile.delete()
            android.util.Log.e("SMB_DEBUG", "getOrDownloadChapterArchive: FAILED with exception: ${e.message}", e)
            throw e
        }
    }

    private fun pruneCache(cacheDir: File, maxSizeBytes: Long = 500L * 1024 * 1024) {
        runCatching {
            val files = cacheDir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxSizeBytes) return

            val sorted = files.sortedBy { it.lastModified() }
            for (file in sorted) {
                val len = file.length()
                if (file.delete()) {
                    totalSize -= len
                    if (totalSize <= maxSizeBytes * 0.8) break
                }
            }
        }
    }

    fun getChapterPagesCacheFile(context: Context, cacheKey: String): File {
        val cacheDir = File(context.cacheDir, "smb_page_list_cache").apply { mkdirs() }
        val hash = Integer.toHexString(cacheKey.hashCode())
        val cleanName = cacheKey.substringAfterLast('/', cacheKey.substringAfterLast('\\'))
            .replace('/', '_')
            .replace('\\', '_')
            .replace(':', '_')
            .replace('*', '_')
            .replace('?', '_')
            .replace('"', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('|', '_')
            .replace(' ', '_')
            .takeLast(50)
        return File(cacheDir, "${cleanName}_${hash}.txt")
    }

    suspend fun listPages(config: SmbConfig, chapterPath: String): List<String> = listPages(config, chapterPath, null)

    suspend fun listPages(
        config: SmbConfig,
        chapterPath: String,
        context: Context? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        // Fast path 1: memory cache hit -> langsung return, 0ms
        val cacheKey = "${config.host}:${config.port}/${config.shareName}/$chapterPath"
        listPagesCache[cacheKey]?.let { cached ->
            val d = System.currentTimeMillis() - t0
            android.util.Log.e("SMB_DEBUG", "listPages MEM CACHE HIT: ${cached.size} pages in ${d}ms")
            SmbPerfLogger.log("METADATA", "MEM CACHE HIT: ${cached.size} halaman dalam ${d}ms")
            return@withContext cached
        }

        // Fast path 2: disk cache hit -> langsung return, 0ms
        val diskCache = context?.let { getChapterPagesCacheFile(it, cacheKey) }
        if (diskCache != null && diskCache.exists() && diskCache.length() > 0) {
            val lines = runCatching { diskCache.readLines().filter { it.isNotBlank() } }.getOrNull()
            if (!lines.isNullOrEmpty()) {
                listPagesCache[cacheKey] = lines
                val d = System.currentTimeMillis() - t0
                android.util.Log.e("SMB_DEBUG", "listPages DISK CACHE HIT: ${lines.size} pages in ${d}ms")
                SmbPerfLogger.log("METADATA", "DISK CACHE HIT: ${lines.size} halaman dalam ${d}ms")
                return@withContext lines
            }
        }

        android.util.Log.e("SMB_DEBUG", "listPages START: $chapterPath")
        val result = withMetaShare(config) { share ->
            val candidates = resolveCandidatePaths(config, chapterPath)

            // 1. FAST PATH: Check directly if candidate .cbz or .zip exists (1-2ms roundtrip)
            // 99% of downloaded manga chapters are stored in .cbz / .zip archives
            for (cand in candidates) {
                val cbzPath = "$cand.cbz"
                if (share.fileExists(cbzPath)) {
                    val tCbz = System.currentTimeMillis()
                    val entries = parseCentralDirectory(share, cbzPath)
                    if (entries.isNotEmpty()) {
                        val dCbz = System.currentTimeMillis() - tCbz
                        SmbPerfLogger.log("METADATA", "FAST-PATH CBZ HIT: $cbzPath (${entries.size} file dalam ${dCbz}ms)")
                        return@withMetaShare entries.keys
                            .sortedWith { f1, f2 -> f1.compareToCaseInsensitiveNaturalOrder(f2) }
                            .map { "$cbzPath#$it" }
                    }
                }
                val zipPath = "$cand.zip"
                if (share.fileExists(zipPath)) {
                    val entries = parseCentralDirectory(share, zipPath)
                    if (entries.isNotEmpty()) {
                        return@withMetaShare entries.keys
                            .sortedWith { f1, f2 -> f1.compareToCaseInsensitiveNaturalOrder(f2) }
                            .map { "$zipPath#$it" }
                    }
                }
            }

            // 2. FALLBACK: parent folder listing for loose image folders or fuzzy match
            val parentListings = mutableMapOf<String, List<FileIdBothDirectoryInformation>>()
            fun getParentItems(parentPath: String): List<FileIdBothDirectoryInformation> {
                return parentListings.getOrPut(parentPath) {
                    runCatching { share.list(parentPath) }.getOrDefault(emptyList())
                }
            }

            for (cand in candidates) {
                val parentPath = cand.substringBeforeLast('\\', "")
                val chapterName = cand.substringAfterLast('\\')

                val parentItems = getParentItems(parentPath)
                if (parentItems.isEmpty()) continue

                // Folder berisi loose images
                val folderEntry = parentItems.firstOrNull {
                    isDirectory(it) && it.fileName.equals(chapterName, ignoreCase = true)
                }
                if (folderEntry != null) {
                    val folderPath = if (parentPath.isEmpty()) folderEntry.fileName else "$parentPath\\${folderEntry.fileName}"
                    val images = runCatching { share.list(folderPath) }.getOrDefault(emptyList())
                        .filter { !isDirectory(it) && it.fileName.substringAfterLast('.', "").lowercase() in imageExtensions }
                        .map { it.fileName }
                        .sortedWith { f1, f2 -> f1.compareToCaseInsensitiveNaturalOrder(f2) }
                    if (images.isNotEmpty()) {
                        return@withMetaShare images.map { "$folderPath\\$it" }
                    }
                }

                // Fuzzy match nama chapter (_NNN)
                val fuzzyEntry = parentItems.firstOrNull { item ->
                    val baseName = item.fileName.removeSuffix(".cbz").removeSuffix(".zip")
                    !isDirectory(item) &&
                        (item.fileName.endsWith(".cbz", ignoreCase = true) || item.fileName.endsWith(".zip", ignoreCase = true)) &&
                        (chapterName.contains('_') && baseName.equals(chapterName.substringBeforeLast('_'), ignoreCase = true))
                }
                if (fuzzyEntry != null) {
                    val archivePath = if (parentPath.isEmpty()) fuzzyEntry.fileName else "$parentPath\\${fuzzyEntry.fileName}"
                    val entries = parseCentralDirectory(share, archivePath)
                    if (entries.isNotEmpty()) {
                        return@withMetaShare entries.keys
                            .sortedWith { f1, f2 -> f1.compareToCaseInsensitiveNaturalOrder(f2) }
                            .map { "$archivePath#$it" }
                    }
                }
            }
            throw IOException("SMB chapter not found: $chapterPath")
        }

        android.util.Log.e("SMB_DEBUG", "listPages DONE in " + (System.currentTimeMillis() - t0) + "ms, found " + result.size + " pages")
        listPagesCache[cacheKey] = result
        if (diskCache != null && result.isNotEmpty()) {
            runCatching {
                diskCache.parentFile?.mkdirs()
                diskCache.writeText(result.joinToString("\n"))
            }
        }
        result
    }

    fun getPageCacheFile(context: Context, pageUrl: String): File {
        val cacheDir = File(context.cacheDir, "smb_page_cache").apply { mkdirs() }
        val hash = Integer.toHexString(pageUrl.hashCode())
        val cleanName = pageUrl.substringAfterLast('#', pageUrl.substringAfterLast('\\'))
            .replace('/', '_')
            .replace('\\', '_')
            .replace(':', '_')
            .replace('*', '_')
            .replace('?', '_')
            .replace('"', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('|', '_')
            .replace(' ', '_')
            .takeLast(40)
        return File(cacheDir, "${cleanName}_${hash}.img")
    }

    fun isPageCached(context: Context, pageUrl: String): Boolean {
        val file = getPageCacheFile(context, pageUrl)
        return file.exists() && file.length() > 0
    }

    fun openCachedOrStreamPage(context: Context, config: SmbConfig, remotePath: String): InputStream {
        SmbPerfLogger.init(context)
        val t0 = System.currentTimeMillis()
        val pageName = remotePath.substringAfterLast('#', remotePath.substringAfterLast('\\')).takeLast(25)
        val cached = getPageCacheFile(context, remotePath)
        if (cached.exists() && cached.length() > 0) {
            cached.setLastModified(System.currentTimeMillis())
            val duration = System.currentTimeMillis() - t0
            android.util.Log.d("SMB_DEBUG", "openPage DISK CACHE: ${cached.length()} bytes in ${duration}ms for $pageName")
            SmbPerfLogger.log("PAGE_CACHE", "DISK HIT ($pageName): ${cached.length()} bytes dalam ${duration}ms")
            return cached.inputStream()
        }

        val bytes = fetchPageBytes(config, remotePath)
        val duration = System.currentTimeMillis() - t0
        val speedMBs = (bytes.size.toDouble() / (1024 * 1024)) / (duration.coerceAtLeast(1) / 1000.0)
        android.util.Log.d("SMB_DEBUG", "openPage SMB FETCH DONE: ${bytes.size} bytes in ${duration}ms")
        SmbPerfLogger.log("PAGE_SMB", "SMB FETCH ($pageName): ${bytes.size} bytes dalam ${duration}ms (speed: ${String.format(java.util.Locale.US, "%.2f", speedMBs)} MB/s)")
        runCatching {
            val tmp = File(cached.parentFile, "${cached.name}.${System.currentTimeMillis()}.tmp")
            tmp.parentFile?.mkdirs()
            tmp.writeBytes(bytes)
            if (cached.exists()) cached.delete()
            if (!tmp.renameTo(cached)) {
                tmp.copyTo(cached, overwrite = true)
                tmp.delete()
            }
            cached.setLastModified(System.currentTimeMillis())
            prunePageCache(context)
        }
        return ByteArrayInputStream(bytes)
    }

    fun cachePage(context: Context, config: SmbConfig, remotePath: String) {
        val cached = getPageCacheFile(context, remotePath)
        if (cached.exists() && cached.length() > 0) return

        val bytes = fetchPageBytes(config, remotePath)
        runCatching {
            val tmp = File(cached.parentFile, "${cached.name}.${System.currentTimeMillis()}.tmp")
            tmp.writeBytes(bytes)
            if (cached.exists()) cached.delete()
            if (!tmp.renameTo(cached)) {
                tmp.copyTo(cached, overwrite = true)
                tmp.delete()
            }
            cached.setLastModified(System.currentTimeMillis())
            prunePageCache(context)
        }
    }

    private fun prunePageCache(context: Context, maxSizeBytes: Long = 500L * 1024 * 1024) {
        runCatching {
            val cacheDir = File(context.cacheDir, "smb_page_cache")
            if (!cacheDir.exists()) return
            val files = cacheDir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize <= maxSizeBytes) return

            val sorted = files.sortedBy { it.lastModified() }
            for (file in sorted) {
                val len = file.length()
                if (file.delete()) {
                    totalSize -= len
                    if (totalSize <= maxSizeBytes * 0.8) break
                }
            }
        }
    }

    private fun fetchPageBytes(config: SmbConfig, remotePath: String): ByteArray {
        val t0 = System.currentTimeMillis()
        if ('#' in remotePath) {
            val origArchivePath = remotePath.substringBefore('#').replace('/', '\\')
            val entryName = remotePath.substringAfter('#')
            val (rawData, method) = withPooledShare(config) { share ->
                val archivePath = if (archiveIndexCache.containsKey(origArchivePath) || share.fileExists(origArchivePath)) {
                    origArchivePath
                } else {
                    resolveCandidatePaths(config, origArchivePath).firstOrNull { share.fileExists(it) }
                        ?: origArchivePath
                }
                val entries = archiveIndexCache[archivePath] ?: parseCentralDirectory(share, archivePath)
                if (archivePath != origArchivePath) {
                    archiveIndexCache[origArchivePath] = entries
                }
                val entry = entries[entryName] ?: throw IOException("Entry not found in $archivePath: $entryName")

                val smbFile = share.openFile(
                    archivePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                smbFile.use { sf ->
                    val locHdr = ByteArray(30)
                    readFully(sf, locHdr, entry.localOffset, 30)
                    val loc = ByteBuffer.wrap(locHdr).order(ByteOrder.LITTLE_ENDIAN)
                    loc.position(26)
                    val locNameLen = loc.getShort().toInt() and 0xffff
                    val locExtraLen = loc.getShort().toInt() and 0xffff
                    val dataOffset = entry.localOffset + 30L + locNameLen + locExtraLen

                    val raw = ByteArray(entry.compSize.toInt())
                    readFully(sf, raw, dataOffset, raw.size)
                    Pair(raw, entry.method)
                }
            }

            val decompressed = when (method) {
                0 -> rawData
                8 -> {
                    val inflater = Inflater(true)
                    val infInput = InflaterInputStream(ByteArrayInputStream(rawData), inflater)
                    infInput.readBytes()
                }
                else -> throw IOException("Unsupported compression method: $method")
            }
            android.util.Log.d("SMB_DEBUG", "fetchPageBytes CBZ: ${decompressed.size} bytes in ${System.currentTimeMillis() - t0}ms for $entryName")
            return decompressed
        }

        val bytes = withPooledShare(config) { share ->
            var normalized = remotePath.replace('/', '\\').trim('\\')
            if (!share.fileExists(normalized)) {
                val candidates = resolveCandidatePaths(config, normalized)
                for (cand in candidates) {
                    if (share.fileExists(cand)) {
                        normalized = cand
                        break
                    }
                }
            }
            val smbFile = share.openFile(
                normalized,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            smbFile.use { sf ->
                val size = sf.fileInformation.standardInformation.endOfFile
                val buf = ByteArray(size.toInt())
                readFully(sf, buf, 0, buf.size)
                buf
            }
        }
        android.util.Log.d("SMB_DEBUG", "fetchPageBytes file: ${bytes.size} bytes in ${System.currentTimeMillis() - t0}ms for $remotePath")
        return bytes
    }

    fun openPageStream(config: SmbConfig, remotePath: String): InputStream {
        return ByteArrayInputStream(fetchPageBytes(config, remotePath))
    }

    suspend fun deleteChapter(config: SmbConfig, source: String, manga: String, chapter: String) =
        withContext(Dispatchers.IO) {
            withShare(config) { share ->
                val remote = chapterPath(config, source, manga, chapter)
                val candidates = resolveCandidatePaths(config, remote)
                for (cand in candidates) {
                    deleteRecursive(share, cand)
                    deleteRecursive(share, "$cand.cbz")
                    deleteRecursive(share, "$cand.zip")
                    archiveIndexCache.remove("$cand.cbz")
                    archiveIndexCache.remove("$cand.zip")
                }
            }
        }

    suspend fun deleteManga(config: SmbConfig, source: String, manga: String) =
        withContext(Dispatchers.IO) {
            withShare(config) { share ->
                val remote = chapterPath(config, source, manga)
                val candidates = resolveCandidatePaths(config, remote)
                for (cand in candidates) {
                    deleteRecursive(share, cand)
                }
            }
        }

    suspend fun renameChapter(
        config: SmbConfig,
        source: String,
        manga: String,
        oldNames: List<String>,
        newName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        withShare(config) { share ->
            val remote = chapterPath(config, source, manga)
            val parentCandidates = resolveCandidatePaths(config, remote).filter { share.folderExists(it) }
            for (parent in parentCandidates) {
                val entries = share.list(parent).filter { it.fileName != "." && it.fileName != ".." }
                val oldDir = entries.firstOrNull { isDirectory(it) && it.fileName in oldNames }?.fileName
                if (oldDir != null) {
                    val oldPath = "$parent\\$oldDir"
                    val newPath = "$parent\\$newName"
                    val dir = share.openDirectory(
                        oldPath,
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    )
                    dir.use { it.rename(newPath, true) }
                    return@withShare true
                }
                val oldCbz = entries.firstOrNull {
                    !isDirectory(it) && it.fileName.endsWith(".cbz", ignoreCase = true) && it.fileName.removeSuffix(".cbz") in oldNames
                }?.fileName
                if (oldCbz != null) {
                    val oldCbzPath = "$parent\\$oldCbz"
                    val newCbzPath = "$parent\\$newName.cbz"
                    val file = share.openFile(
                        oldCbzPath,
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    )
                    file.use { it.rename(newCbzPath, true) }
                    archiveIndexCache.remove(oldCbzPath)
                    return@withShare true
                }
                val oldZip = entries.firstOrNull {
                    !isDirectory(it) && it.fileName.endsWith(".zip", ignoreCase = true) && it.fileName.removeSuffix(".zip") in oldNames
                }?.fileName
                if (oldZip != null) {
                    val oldZipPath = "$parent\\$oldZip"
                    val newZipPath = "$parent\\$newName.zip"
                    val file = share.openFile(
                        oldZipPath,
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    )
                    file.use { it.rename(newZipPath, true) }
                    archiveIndexCache.remove(oldZipPath)
                    return@withShare true
                }
            }
            false
        }
    }

suspend fun renameManga(config: SmbConfig, source: String, oldManga: String, newManga: String): Boolean =
        withContext(Dispatchers.IO) {
            if (oldManga == newManga) return@withContext false
            withShare(config) { share ->
                val oldPath = chapterPath(config, source, oldManga)
                val newPath = chapterPath(config, source, newManga)
                if (!share.folderExists(oldPath)) return@withShare false
                val dir = share.openDirectory(
                    oldPath,
                    EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                dir.use { it.rename(newPath, true) }
                true
            }
        }

    suspend fun renameSource(config: SmbConfig, oldSource: String, newSource: String): Boolean =
        withContext(Dispatchers.IO) {
            if (oldSource == newSource) return@withContext false
            withShare(config) { share ->
                val oldPath = smbPath(config.path, oldSource)
                val newPath = smbPath(config.path, newSource)
                if (!share.folderExists(oldPath)) return@withShare false
                val dir = share.openDirectory(
                    oldPath,
                    EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                dir.use { it.rename(newPath, true) }
                true
            }
        }

    private fun deleteRecursive(share: DiskShare, path: String) {
        val normalized = path.replace('/', '\\').trim('\\')
        if (share.folderExists(normalized)) {
            val list = runCatching { share.list(normalized) }.getOrNull() ?: emptyList()
            for (item in list) {
                if (item.fileName == "." || item.fileName == "..") continue
                val child = "$normalized\\${item.fileName}"
                if (isDirectory(item)) {
                    deleteRecursive(share, child)
                } else {
                    runCatching { share.rm(child) }
                }
            }
            runCatching { share.rmdir(normalized, true) }
        } else if (share.fileExists(normalized)) {
            runCatching { share.rm(normalized) }
        }
    }
}

