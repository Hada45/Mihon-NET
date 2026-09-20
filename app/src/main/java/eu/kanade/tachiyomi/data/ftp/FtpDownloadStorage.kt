package eu.kanade.tachiyomi.data.ftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

data class FtpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val path: String,
    val passive: Boolean,
)

object FtpDownloadStorage {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl")

    fun config(preferences: DownloadPreferences): FtpConfig {
        val port = preferences.ftpPort.get().toIntOrNull()
        require(port != null && port in 1..65535) { "FTP port must be between 1 and 65535" }
        val host = preferences.ftpHost.get().trim()
        require(host.isNotBlank()) { "FTP host is required" }
        return FtpConfig(
            host,
            port,
            preferences.ftpUsername.get(),
            preferences.ftpPassword.get(),
            preferences.ftpPath.get().ifBlank { "/" },
            preferences.ftpPassiveMode.get(),
        )
    }

    fun chapterPath(config: FtpConfig, source: String, manga: String, chapter: String = ""): String =
        path(config.path, listOf(source, manga, chapter).filter { it.isNotEmpty() }.joinToString("/"))

    private fun path(base: String, child: String): String =
        "/" + listOf(base.trim('/'), child.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    private fun client(config: FtpConfig): FTPClient {
        val client = FTPClient().apply {
            connectTimeout = 10_000
            defaultTimeout = 10_000
            controlEncoding = "UTF-8"
        }
        client.connect(config.host, config.port)
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            client.disconnect()
            throw IOException("FTP server refused connection: ${client.replyString}")
        }
        if (!client.login(config.username, config.password)) {
            client.disconnect()
            throw IOException("FTP login failed")
        }
        client.setFileType(FTP.BINARY_FILE_TYPE)
        client.setDataTimeout(20_000)
        if (config.passive) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
        return client
    }

    private inline fun <T> withClient(config: FtpConfig, block: (FTPClient) -> T): T {
        val client = client(config)
        try {
            return block(client)
        } finally {
            try {
                if (client.isConnected) client.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun testConnection(config: FtpConfig): String = withContext(Dispatchers.IO) {
        withClient(config) { client ->
            val root = path(config.path, "")
            if (!client.changeWorkingDirectory(root)) throw IOException("FTP directory not found: $root")
            val count = client.listFiles().orEmpty().size
            val probe = path(root, ".mihon_write_test_${System.nanoTime()}")
            if (!client.makeDirectory(probe)) throw IOException("FTP directory is not writable: $root")
            if (!client.removeDirectory(probe)) throw IOException("Cannot remove FTP test directory: $probe")
            "Connected to $root ($count entries); write access verified"
        }
    }

    private fun ensureDirectory(client: FTPClient, fullPath: String) {
        var current = ""
        for (part in fullPath.split('/').filter { it.isNotEmpty() }) {
            current += "/$part"
            if (!client.changeWorkingDirectory(current) && !client.makeDirectory(current)) {
                throw IOException("Cannot create FTP directory: $current; ${client.replyString}")
            }
        }
    }

    suspend fun uploadChapter(config: FtpConfig, source: String, manga: String, chapter: String, directory: File) =
        withContext(Dispatchers.IO) {
            withClient(config) { client ->
                val parent = chapterPath(config, source, manga)
                ensureDirectory(client, parent)
                val target = chapterPath(config, source, manga, chapter)
                val temporary = "$target.uploading"
                deleteDirectory(client, temporary)
                if (!client.makeDirectory(temporary)) throw IOException("FTP temporary directory failed: ${client.replyString}")
                try {
                    val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()
                    if (files.isEmpty()) throw IOException("No pages to upload")
                    for (file in files) {
                        file.inputStream().use { input ->
                            if (!client.storeFile("$temporary/${file.name}", input)) {
                                throw IOException("FTP upload failed: ${client.replyString}")
                            }
                        }
                    }
                    deleteDirectory(client, target)
                    if (!client.rename(temporary, target)) throw IOException("FTP rename failed: ${client.replyString}")
                } catch (error: Exception) {
                    deleteDirectory(client, temporary)
                    throw error
                }
            }
        }

    suspend fun listDownloads(
        config: FtpConfig,
        allowedSources: Set<String>? = null,
    ): Map<String, Map<String, List<String>>> = withContext(Dispatchers.IO) {
        withClient(config) { client ->
            val root = path(config.path, "")
            val rawSources = runCatching {
                client.listFiles(root)?.filter { it.isDirectory }.orEmpty()
            }.getOrDefault(emptyList())

            val sources = if (allowedSources != null) {
                rawSources.filter { it.name.lowercase() in allowedSources }
            } else {
                rawSources
            }

            sources.associate { source ->
                val sourcePath = "$root/${source.name}"
                val mangas = runCatching {
                    client.listFiles(sourcePath)?.filter { it.isDirectory }.orEmpty()
                }.getOrDefault(emptyList())

                source.name to mangas.associate { manga ->
                    val chapters = runCatching {
                        client.listFiles("$sourcePath/${manga.name}")?.filter {
                            (it.isDirectory && !it.name.endsWith(".uploading")) ||
                                (it.isFile && it.name.endsWith(".cbz", ignoreCase = true))
                        }?.map { it.name.removeSuffix(".cbz") }.orEmpty()
                    }.getOrDefault(emptyList())
                    manga.name to chapters
                }
            }
        }
    }

    suspend fun listPages(config: FtpConfig, chapterPath: String): List<String> = withContext(Dispatchers.IO) {
        withClient(config) { client ->
            if (client.changeWorkingDirectory(chapterPath)) {
                client.listFiles().orEmpty()
                    .filter { it.isFile && it.name.substringAfterLast('.', "").lowercase() in imageExtensions }
                    .map { it.name }.sorted().map { "$chapterPath/$it" }
            } else {
                val archivePath = "$chapterPath.cbz"
                val input = client.retrieveFileStream(archivePath)
                    ?: throw IOException("FTP chapter not found: $chapterPath")
                val entries = ZipInputStream(input).use { zip ->
                    buildList {
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (!entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in imageExtensions) add(entry.name)
                        }
                    }
                }
                if (!client.completePendingCommand()) throw IOException("FTP CBZ listing failed")
                entries.sorted().map { "$archivePath#$it" }
            }
        }
    }

    suspend fun downloadPage(config: FtpConfig, remotePath: String, file: File) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            withClient(config) { client ->
                temporary.outputStream().use { output ->
                    if (!client.retrieveFile(remotePath, output)) throw IOException("FTP page download failed: $remotePath")
                }
            }
            if (temporary.length() == 0L) throw IOException("FTP page is empty")
            if (file.exists()) file.delete()
            if (!temporary.renameTo(file)) throw IOException("Cannot save FTP page")
        } finally {
            temporary.delete()
        }
    }

    fun openPageStream(config: FtpConfig, remotePath: String): InputStream {
        if ('#' in remotePath) {
            val archivePath = remotePath.substringBefore('#')
            val entryName = remotePath.substringAfter('#')
            return withClient(config) { client ->
                val input = client.retrieveFileStream(archivePath)
                    ?: throw IOException("FTP CBZ stream failed: ${client.replyString}")
                val bytes = ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: throw IOException("CBZ page not found: $entryName")
                        if (entry.name == entryName) return@use zip.readBytes()
                    }
                    @Suppress("UNREACHABLE_CODE")
                    ByteArray(0)
                }
                if (!client.completePendingCommand()) throw IOException("FTP CBZ read failed")
                ByteArrayInputStream(bytes)
            }
        }
        val client = client(config)
        try {
            val input = client.retrieveFileStream(remotePath)
                ?: throw IOException("FTP page stream failed: ${client.replyString}")
            return object : FilterInputStream(input) {
                override fun close() {
                    try {
                        super.close()
                        client.completePendingCommand()
                    } finally {
                        client.disconnect()
                    }
                }
            }
        } catch (error: Exception) {
            client.disconnect()
            throw error
        }
    }

    suspend fun deleteChapter(config: FtpConfig, source: String, manga: String, chapter: String) =
        withContext(Dispatchers.IO) {
            withClient(config) {
                val remote = chapterPath(config, source, manga, chapter)
                deleteDirectory(it, remote)
                it.deleteFile("$remote.cbz")
            }
        }

    suspend fun deleteManga(config: FtpConfig, source: String, manga: String) =
        withContext(Dispatchers.IO) { withClient(config) { deleteDirectory(it, chapterPath(config, source, manga)) } }

    suspend fun renameChapter(config: FtpConfig, source: String, manga: String, oldNames: List<String>, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            withClient(config) { client ->
                val parent = chapterPath(config, source, manga)
                val oldName = client.listFiles(parent).orEmpty().firstOrNull { it.isDirectory && it.name in oldNames }?.name
                if (oldName != null) return@withClient client.rename("$parent/$oldName", "$parent/$newName")
                val oldCbz = client.listFiles(parent).orEmpty().firstOrNull {
                    it.isFile && it.name.endsWith(".cbz") && it.name.removeSuffix(".cbz") in oldNames
                }?.name ?: return@withClient false
                client.rename("$parent/$oldCbz", "$parent/$newName.cbz")
            }
        }

    suspend fun renameManga(config: FtpConfig, source: String, oldManga: String, newManga: String): Boolean =
        withContext(Dispatchers.IO) {
            withClient(config) { it.rename(chapterPath(config, source, oldManga), chapterPath(config, source, newManga)) }
        }

    suspend fun renameSource(config: FtpConfig, oldSource: String, newSource: String): Boolean =
        withContext(Dispatchers.IO) {
            if (oldSource == newSource) return@withContext false
            withClient(config) {
                it.rename(path(config.path, oldSource), path(config.path, newSource))
            }
        }

    private fun deleteDirectory(client: FTPClient, directory: String) {
        client.listFiles(directory).orEmpty()
            .filter { it.name != "." && it.name != ".." }
            .forEach { file ->
                val child = "$directory/${file.name}"
                if (file.isDirectory) deleteDirectory(client, child) else client.deleteFile(child)
            }
        client.removeDirectory(directory)
    }
}
