package app.mihon.ftp.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

class WorkerService : Service() {
    private val running = AtomicBoolean(false)
    private val connections = Executors.newCachedThreadPool()
    private val jobs = ConcurrentHashMap<String, JobRecord>()
    private val executing = ConcurrentHashMap.newKeySet<String>()
    private lateinit var downloads: java.util.concurrent.ExecutorService
    private var serverSocket: ServerSocket? = null
    private var ftpServer: EmbeddedFtpServer? = null

    override fun onCreate() {
        super.onCreate()
        WorkerSecurity.ensure(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Worker starting…"))
        val concurrency = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(MainActivity.KEY_CONCURRENCY, 2).coerceIn(1, 6)
        downloads = Executors.newFixedThreadPool(concurrency)
        restoreJobs()
        cleanupHistory()
        jobs.values.filter { it.state.status in setOf("queued", "downloading") }.forEach {
            it.state.status = "queued"
            submit(it)
        }
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running.set(false)
        runCatching { serverSocket?.close() }
        runCatching { ftpServer?.stop() }
        ftpServer = null
        connections.shutdownNow()
        downloads.shutdownNow()
        persistJobs()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            val port = prefs.getInt(MainActivity.KEY_PORT, 2223)
            val ftpEnabled = prefs.getBoolean(MainActivity.KEY_FTP_ENABLED, true)
            val ftpPort = prefs.getInt(MainActivity.KEY_FTP_PORT, 2121)

            if (ftpEnabled) {
                val ftpUser = prefs.getString(MainActivity.KEY_FTP_USER, "mihon").orEmpty()
                val ftpPass = prefs.getString(MainActivity.KEY_FTP_PASSWORD, "mihon").orEmpty()
                ftpServer = EmbeddedFtpServer(
                    context = this,
                    port = ftpPort,
                    rootProvider = { storageRoot() },
                    authProvider = { Pair(ftpUser, ftpPass) }
                ).also { it.start() }
            }

            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
                val ftpInfo = if (ftpEnabled) " | FTP: $ftpPort" else ""
                notify("Worker: $port$ftpInfo")
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: break
                    connections.execute { handle(socket) }
                }
            } catch (e: Throwable) {
                if (running.get()) notify("Server failed: ${e.message}")
            }
        }.apply { name = "mihon-worker-server" }.start()
    }

    private fun handle(socket: Socket) {
        socket.use {
            it.soTimeout = 20_000
            val input = BufferedInputStream(it.getInputStream())
            val output = BufferedOutputStream(it.getOutputStream())
            try {
                val requestLine = readLine(input) ?: return
                val request = requestLine.split(' ')
                if (request.size < 2) return
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                }
                val body = readBody(input, headers)
                val method = request[0]
                val path = request[1].substringBefore('?')
                route(method, path, body, output)
            } catch (e: Throwable) {
                runCatching { respond(output, 500, JSONObject().put("error", e.message ?: "server error")) }
            }
        }
    }

    private fun route(method: String, path: String, body: JSONObject, output: BufferedOutputStream) {
        when {
            method == "GET" && path in setOf("/api/v2/status", "/api/v1/status") -> respond(output, 200, statusJson())
            method == "GET" && path == "/api/v2/jobs" -> respond(
                output,
                200,
                JSONObject().put("jobs", JSONArray(jobs.values.sortedByDescending { it.state.updatedAt }.map { it.state.json() })),
            )
            method == "GET" && (path.startsWith("/api/v2/jobs/") || path.startsWith("/api/v1/jobs/")) -> {
                val id = path.substringAfterLast('/')
                val record = jobs[id]
                if (record == null) respond(output, 404, JSONObject().put("error", "job not found"))
                else respond(output, 200, record.state.json())
            }
            method == "POST" && path in setOf("/api/v2/jobs", "/api/v1/jobs") -> createJob(output, body)
            method == "POST" && path.matches(Regex("/api/v2/jobs/[^/]+/refresh")) -> refreshJob(output, path.split('/')[4], body)
            method == "POST" && path.matches(Regex("/api/v2/jobs/[^/]+/(pause|resume|cancel|retry)")) -> {
                val parts = path.split('/')
                controlJob(output, parts[4], parts[5])
            }
            method == "POST" && path.matches(Regex("/api/v2/jobs/(pause|resume|cancel|retry)")) -> controlAll(output, path.substringAfterLast('/'))
            method == "POST" && path == "/api/v2/config/storage" -> {
                val hasRoot = storageRoot() != null
                respond(output, 200, JSONObject().put("success", hasRoot).put("message", if (hasRoot) "Storage managed by Android SAF" else "Please select storage folder in Android Worker UI"))
            }
            else -> respond(output, 404, JSONObject().put("error", "not found"))
        }
    }

    private fun statusJson(): JSONObject {
        val active = jobs.values.count { it.state.status in ACTIVE_STATES }
        return JSONObject()
            .put("message", "Android Worker connected (FTP only); storage=${if (storageRoot() != null) "ready" else "not selected"}")
            .put("version", 2)
            .put("activeJobs", active)
            .put("totalJobs", jobs.size)
            .put("freeBytes", availableBytes())
            .put("minimumFreeBytes", minimumFreeBytes())
    }

    private fun createJob(output: BufferedOutputStream, body: JSONObject) {
        val id = body.optString("jobId").ifBlank { UUID.randomUUID().toString() }
        if (jobs.containsKey(id)) {
            respond(output, 409, JSONObject().put("error", "job already exists"))
            return
        }
        val pages = body.optJSONArray("pages") ?: throw IOException("Missing pages")
        if (pages.length() == 0) throw IOException("Empty page list")
        val state = JobState(id, body.optString("chapterTitle", "Chapter"), "queued", total = pages.length())
        val record = JobRecord(JSONObject(body.toString()).put("jobId", id), state)
        jobs[id] = record
        persistJobs()
        submit(record)
        respond(output, 202, JSONObject().put("jobId", id).put("status", "queued"))
    }

    private fun refreshJob(output: BufferedOutputStream, id: String, body: JSONObject) {
        val record = jobs[id] ?: return respond(output, 404, JSONObject().put("error", "job not found"))
        record.body.put("pages", body.getJSONArray("pages"))
        record.state.apply {
            status = "queued"
            error = null
            completed = 0
            total = body.getJSONArray("pages").length()
            cancelled = false
            paused = false
            updatedAt = System.currentTimeMillis()
        }
        persistJobs()
        submit(record)
        respond(output, 202, record.state.json())
    }

    private fun controlJob(output: BufferedOutputStream, id: String, action: String) {
        val record = jobs[id] ?: return respond(output, 404, JSONObject().put("error", "job not found"))
        applyAction(record, action)
        respond(output, 200, record.state.json())
    }

    private fun controlAll(output: BufferedOutputStream, action: String) {
        jobs.values.forEach { record ->
            val applicable = when (action) {
                "pause" -> record.state.status in ACTIVE_STATES
                "resume" -> record.state.status == "paused"
                "cancel" -> record.state.status in ACTIVE_STATES || record.state.status == "paused"
                "retry" -> record.state.status in setOf("failed", "cancelled", "needs_refresh")
                else -> false
            }
            if (applicable) applyAction(record, action)
        }
        respond(output, 200, JSONObject().put("message", "$action applied").put("jobs", jobs.size))
    }

    private fun applyAction(record: JobRecord, action: String) {
        when (action) {
            "pause" -> {
                record.state.paused = true
                record.state.status = "paused"
            }
            "resume" -> {
                record.state.paused = false
                record.state.status = "queued"
                submit(record)
            }
            "cancel" -> {
                record.state.cancelled = true
                record.state.status = "cancelled"
                record.state.error = "Cancelled by user"
            }
            "retry" -> {
                record.state.cancelled = false
                record.state.paused = false
                record.state.error = null
                record.state.status = "queued"
                submit(record)
            }
        }
        record.state.updatedAt = System.currentTimeMillis()
        persistJobs()
    }

    private fun submit(record: JobRecord) {
        if (!executing.add(record.state.id)) return
        downloads.execute {
            try {
                runJob(record)
            } finally {
                executing.remove(record.state.id)
            }
        }
    }

    private fun runJob(record: JobRecord) {
        val state = record.state
        try {
            if (state.cancelled) throw CancelledException()
            state.status = "downloading"
            state.startedAt = if (state.startedAt == 0L) System.currentTimeMillis() else state.startedAt
            val root = storageRoot() ?: throw IOException("Select a storage folder in Mihon Worker")
            val downloadsDir = childDirectory(root, "downloads")
            val sourceDir = childDirectory(downloadsDir, safe(record.body.getString("sourceDir")))
            val mangaDir = childDirectory(sourceDir, safe(record.body.getString("mangaDir")))
            val finalName = safe(record.body.getString("chapterDir"))
            val tempName = ".$finalName.${state.id.take(8)}.downloading"
            val temp = childDirectory(mangaDir, tempName)
            val pages = record.body.getJSONArray("pages")
            state.total = pages.length()
            val digits = pages.length().toString().length.coerceAtLeast(3)
            for (i in 0 until pages.length()) {
                waitIfPaused(state)
                if (state.cancelled) throw CancelledException()
                ensureStorageThreshold()
                val page = pages.getJSONObject(i)
                val basename = page.getInt("index").toString().padStart(digits, '0')
                if (temp.listFiles().orEmpty().none { it.name?.startsWith("$basename.") == true && it.length() >= MIN_IMAGE_BYTES }) {
                    state.bytesDownloaded += downloadPageWithRetry(temp, page, basename)
                }
                state.completed = i + 1
                state.updatedAt = System.currentTimeMillis()
                persistJobs()
                notify("${state.title}: ${state.completed}/${state.total}")
            }
            validateChapter(temp, pages.length())
            if (outputMode(record) == "cbz") finishCbz(mangaDir, temp, finalName, state.id)
            else finishFolder(mangaDir, temp, finalName)
            state.status = "completed"
            state.error = null
            state.updatedAt = System.currentTimeMillis()
            persistJobs()
            notify("Completed: ${state.title}")
        } catch (e: NeedsRefreshException) {
            state.status = "needs_refresh"
            state.error = e.message
            state.updatedAt = System.currentTimeMillis()
            persistJobs()
            notify("Waiting for refreshed URL: ${state.title}")
        } catch (_: CancelledException) {
            state.status = "cancelled"
            state.error = "Cancelled by user"
            state.updatedAt = System.currentTimeMillis()
            persistJobs()
        } catch (e: Throwable) {
            state.status = "failed"
            state.error = e.message ?: e.javaClass.simpleName
            state.updatedAt = System.currentTimeMillis()
            persistJobs()
            notify("Failed: ${state.title} — ${state.error}")
        }
    }

    private fun waitIfPaused(state: JobState) {
        while (state.paused && !state.cancelled) Thread.sleep(500)
        if (!state.cancelled && state.status == "paused") state.status = "downloading"
    }

    private fun downloadPageWithRetry(directory: DocumentFile, page: JSONObject, basename: String): Long {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                return downloadPage(directory, page, basename)
            } catch (e: HttpStatusException) {
                if (e.code == 401 || e.code == 403 || e.code == 410) throw NeedsRefreshException("Page ${page.getInt("index")}: HTTP ${e.code}")
                last = e
            } catch (e: Throwable) {
                last = e
            }
            Thread.sleep(1_000L shl attempt)
        }
        throw IOException(last?.message ?: "Page download failed", last)
    }

    private fun downloadPage(directory: DocumentFile, page: JSONObject, basename: String): Long {
        val connection = URL(page.getString("url")).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 90_000
        connection.instanceFollowRedirects = true
        val headers = page.optJSONObject("headers") ?: JSONObject()
        headers.keys().forEach { key -> connection.setRequestProperty(key, headers.optString(key)) }
        connection.connect()
        if (connection.responseCode !in 200..299) throw HttpStatusException(connection.responseCode)
        val mime = connection.contentType?.substringBefore(';') ?: "application/octet-stream"
        val extension = imageExtension(mime, page.getString("url"))
        val file = directory.createFile(mime, basename + extension) ?: throw IOException("Cannot create page file")
        return try {
            connection.inputStream.use { input ->
                contentResolver.openOutputStream(file.uri, "w")?.use { output -> input.copyTo(output, 64 * 1024) }
                    ?: throw IOException("Cannot write page file")
            }
            validateImage(file, mime)
            file.length()
        } catch (e: Throwable) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun validateImage(file: DocumentFile, mime: String) {
        if (file.length() < MIN_IMAGE_BYTES) throw IOException("Downloaded image is empty or truncated")
        val header = ByteArray(12)
        val count = contentResolver.openInputStream(file.uri)?.use { it.read(header) } ?: 0
        val magic = count >= 3 && (
            (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) ||
                header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) ||
                String(header, 0, max(0, count.coerceAtMost(6))).startsWith("GIF8") ||
                (String(header, 0, max(0, count.coerceAtMost(4))) == "RIFF")
            )
        if (!mime.startsWith("image/") && !magic) throw IOException("Server returned non-image data")
    }

    private fun validateChapter(directory: DocumentFile, expected: Int) {
        val valid = directory.listFiles().orEmpty().count { it.isFile && it.length() >= MIN_IMAGE_BYTES }
        if (valid != expected) throw IOException("Chapter validation failed: $valid/$expected pages")
    }

    private fun finishFolder(parent: DocumentFile, temp: DocumentFile, finalName: String) {
        parent.findFile(finalName)?.delete()
        if (!temp.renameTo(finalName)) throw IOException("Cannot finalize chapter folder")
    }

    private fun finishCbz(parent: DocumentFile, temp: DocumentFile, finalName: String, id: String) {
        val targetName = "$finalName.cbz"
        parent.findFile(targetName)?.delete()
        val pending = parent.createFile("application/vnd.comicbook+zip", ".$targetName.${id.take(8)}.downloading")
            ?: throw IOException("Cannot create CBZ")
        try {
            contentResolver.openOutputStream(pending.uri, "w")?.use { output ->
                ZipOutputStream(output).use { zip ->
                    temp.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { page ->
                        zip.putNextEntry(ZipEntry(page.name ?: "page"))
                        contentResolver.openInputStream(page.uri)?.use { it.copyTo(zip, 64 * 1024) }
                            ?: throw IOException("Cannot read temporary page")
                        zip.closeEntry()
                    }
                }
            } ?: throw IOException("Cannot write CBZ")
            if (!pending.renameTo(targetName)) throw IOException("Cannot finalize CBZ")
            temp.delete()
        } catch (e: Throwable) {
            pending.delete()
            throw e
        }
    }

    private fun imageExtension(mime: String, url: String): String = when (mime.lowercase()) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/avif" -> ".avif"
        else -> URL(url).path.substringAfterLast('.', "img").takeIf { it.length in 2..5 }?.let { ".$it" } ?: ".img"
    }

    private fun childDirectory(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
        ?: throw IOException("Cannot create folder $name")

    private fun storageRoot(): DocumentFile? {
        val value = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString(MainActivity.KEY_FOLDER, null) ?: return null
        return DocumentFile.fromTreeUri(this, Uri.parse(value))
    }

    private fun availableBytes(): Long {
        val value = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString(MainActivity.KEY_FOLDER, null) ?: return -1
        return runCatching {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(value))
            val volume = id.substringBefore(':')
            val path = if (volume.equals("primary", true)) android.os.Environment.getExternalStorageDirectory() else File("/storage/$volume")
            path.usableSpace
        }.getOrDefault(-1)
    }

    private fun minimumFreeBytes(): Long =
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(MainActivity.KEY_MIN_FREE_GB, 2).toLong() * 1024 * 1024 * 1024

    private fun ensureStorageThreshold() {
        val minimum = minimumFreeBytes()
        var free = availableBytes()
        if (free < 0 || free >= minimum) return
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(MainActivity.KEY_AUTO_CLEANUP, false)) {
            val completed = jobs.values
                .filter { it.state.status == "completed" }
                .sortedBy { it.state.updatedAt }
            for (old in completed) {
                if (free >= minimum) break
                    if (deleteCompletedOutput(old)) {
                        jobs.remove(old.state.id)
                        free = availableBytes()
                    }
            }
            persistJobs()
        }
        if (free in 0 until minimum) throw IOException("Minimum free space reached")
    }

    private fun deleteCompletedOutput(record: JobRecord): Boolean {
        val root = storageRoot() ?: return false
        val downloadsDir = root.findFile("downloads") ?: return false
        val sourceDir = downloadsDir.findFile(safe(record.body.optString("sourceDir"))) ?: return false
        val mangaDir = sourceDir.findFile(safe(record.body.optString("mangaDir"))) ?: return false
        val chapter = safe(record.body.optString("chapterDir")) + if (outputMode(record) == "cbz") ".cbz" else ""
        return mangaDir.findFile(chapter)?.delete() == true
    }

    private fun outputMode(record: JobRecord): String {
        val explicit = record.body.optString("outputMode")
        if (explicit in setOf("folder", "cbz")) return explicit
        return if (getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(MainActivity.KEY_DEFAULT_CBZ, false)) "cbz" else "folder"
    }


    @Synchronized
    private fun persistJobs() {
        val array = JSONArray()
        jobs.values.forEach { array.put(JSONObject().put("body", it.body).put("state", it.state.json())) }
        runCatching { File(filesDir, JOBS_FILE).writeText(array.toString()) }
    }

    private fun restoreJobs() {
        val file = File(filesDir, JOBS_FILE)
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val body = item.getJSONObject("body")
                val state = JobState.from(item.getJSONObject("state"))
                jobs[state.id] = JobRecord(body, state)
            }
        }
    }

    private fun cleanupHistory() {
        val days = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(MainActivity.KEY_HISTORY_DAYS, 7).coerceIn(1, 90)
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        jobs.entries.removeIf { entry ->
            val expired = entry.value.state.status in TERMINAL_STATES && entry.value.state.updatedAt < cutoff
            if (expired && entry.value.state.status != "completed") deleteTemporaryOutput(entry.value)
            expired
        }
        persistJobs()
    }

    private fun deleteTemporaryOutput(record: JobRecord) {
        val root = storageRoot() ?: return
        val downloadsDir = root.findFile("downloads") ?: return
        val sourceDir = downloadsDir.findFile(safe(record.body.optString("sourceDir"))) ?: return
        val mangaDir = sourceDir.findFile(safe(record.body.optString("mangaDir"))) ?: return
        val finalName = safe(record.body.optString("chapterDir"))
        mangaDir.findFile(".$finalName.${record.state.id.take(8)}.downloading")?.delete()
        mangaDir.findFile(".$finalName.cbz.${record.state.id.take(8)}.downloading")?.delete()
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): JSONObject {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length == 0) return JSONObject()
        if (length !in 1..MAX_BODY) throw IOException("Invalid request size")
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw IOException("Incomplete request")
            offset += count
        }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8192) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.UTF_8)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun respond(output: BufferedOutputStream, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 202 -> "Accepted"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error" }
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Mihon Worker", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Mihon Worker")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    private fun safe(value: String): String = value.replace('/', '_').replace('\\', '_').take(180).ifBlank { "unnamed" }

    private data class JobRecord(val body: JSONObject, val state: JobState)

    private data class JobState(
        val id: String,
        val title: String,
        @Volatile var status: String,
        @Volatile var completed: Int = 0,
        @Volatile var total: Int = 0,
        @Volatile var error: String? = null,
        @Volatile var bytesDownloaded: Long = 0,
        @Volatile var startedAt: Long = 0,
        @Volatile var updatedAt: Long = System.currentTimeMillis(),
        @Volatile var paused: Boolean = false,
        @Volatile var cancelled: Boolean = false,
    ) {
        fun json() = JSONObject()
            .put("jobId", id).put("title", title).put("status", status)
            .put("completed", completed).put("total", total).put("bytesDownloaded", bytesDownloaded)
            .put("startedAt", startedAt).put("updatedAt", updatedAt)
            .also { if (error != null) it.put("error", error) }

        companion object {
            fun from(json: JSONObject) = JobState(
                id = json.getString("jobId"),
                title = json.optString("title", "Chapter"),
                status = json.optString("status", "queued"),
                completed = json.optInt("completed"),
                total = json.optInt("total"),
                error = json.optString("error").ifBlank { null },
                bytesDownloaded = json.optLong("bytesDownloaded"),
                startedAt = json.optLong("startedAt"),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                paused = json.optString("status") == "paused",
                cancelled = json.optString("status") == "cancelled",
            )
        }
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")
    private class NeedsRefreshException(message: String) : IOException(message)
    private class CancelledException : IOException()

    companion object {
        private const val CHANNEL_ID = "worker"
        private const val NOTIFICATION_ID = 2023
        private const val MAX_BODY = 8 * 1024 * 1024
        private const val MIN_IMAGE_BYTES = 128L
        private const val JOBS_FILE = "jobs-v2.json"
        private val ACTIVE_STATES = setOf("queued", "downloading", "paused", "needs_refresh")
        private val TERMINAL_STATES = setOf("completed", "failed", "cancelled")
    }
}
