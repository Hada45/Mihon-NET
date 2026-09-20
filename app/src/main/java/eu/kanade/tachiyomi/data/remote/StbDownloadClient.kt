package eu.kanade.tachiyomi.data.remote

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import java.util.UUID

@Inject
@SingleIn(AppScope::class)
class StbDownloadClient(
    private val preferences: DownloadPreferences,
    private val provider: DownloadProvider,
    private val network: NetworkHelper,
) {
    data class Server(val name: String, val host: String, val port: Int, val token: String, val index: Int) {
        val url: String get() = "http://$host:$port"
    }

    data class JobRef(val id: String, val server: Server)

    private fun isValidCustomStoragePath(path: String): Boolean {
        val trimmed = path.trim()
        return trimmed.isNotBlank() &&
            trimmed != "/" &&
            trimmed != "\\" &&
            !trimmed.startsWith("Belum diatur", ignoreCase = true)
    }

    suspend fun testConnection(): String {
        val server = servers().firstOrNull() ?: throw IOException("No Download Worker configured")
        val targetPath = preferences.stbWorkerStoragePath.get().trim()
        if (isValidCustomStoragePath(targetPath)) {
            runCatching {
                post(server, "/api/v2/config/storage", JSONObject().put("storageRoot", targetPath))
            }
        }
        val status = get(server, "/api/v2/status")
        val root = status.optString("storageRoot", "")
        val rootMsg = if (root.isNotBlank()) " [$root]" else ""
        return "${server.name}: ${status.optString("message")}$rootMsg; free ${formatBytes(status.optLong("freeBytes"))}"
    }

    suspend fun dashboard(): String {
        val sections = mutableListOf<String>()
        for (server in servers()) {
            sections += try {
            val status = get(server, "/api/v2/status")
            val jobs = get(server, "/api/v2/jobs").optJSONArray("jobs") ?: JSONArray()
            buildString {
                append("${server.name} (${server.host}:${server.port})\n")
                append("Free: ${formatBytes(status.optLong("freeBytes"))} • Active: ${status.optInt("activeJobs")}\n")
                if (jobs.length() == 0) append("No jobs")
                for (i in 0 until jobs.length().coerceAtMost(12)) {
                    val job = jobs.getJSONObject(i)
                    append("${if (i == 0) "" else "\n"}${job.optString("status")}: ${job.optString("title")} ${job.optInt("completed")}/${job.optInt("total")}")
                }
            }
            } catch (error: Throwable) {
                "${server.name}: ${error.message}"
            }
        }
        return sections.joinToString("\n\n")
    }

    suspend fun pair(serverIndex: Int = 1): String {
        val server = configuredServer()
            ?: throw IOException("Worker is not configured")
        val codePreference = preferences.stbWorkerPairCode
        val code = codePreference.get().trim()
        if (code.length != 6) throw IOException("Enter the 6 digit pairing code shown on the Worker")
        val result = post(server.copy(token = ""), "/api/v2/pair", JSONObject().put("code", code), false)
        preferences.stbWorkerToken.set(result.getString("token"))
        codePreference.set("")
        return "${server.name} paired"
    }

    fun hasConfiguredServer(): Boolean = servers().isNotEmpty()

    suspend fun cancel(ref: JobRef) {
        runCatching {
            post(ref.server, "/api/v2/jobs/${ref.id}/cancel", JSONObject())
        }
    }

    suspend fun controlAll(action: String): String {
        val results = mutableListOf<String>()
        for (server in servers()) {
            results += try {
                post(server, "/api/v2/jobs/$action", JSONObject()).optString("message")
            } catch (error: Throwable) {
                error.message ?: "failed"
            }
        }
        return results.joinToString("\n")
    }

    suspend fun enqueue(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pages: List<Page>? = null,
    ): Pair<JobRef, List<Page>> {
        val server = selectServer()
        val targetPath = preferences.stbWorkerStoragePath.get().trim()
        if (isValidCustomStoragePath(targetPath)) {
            runCatching {
                post(server, "/api/v2/config/storage", JSONObject().put("storageRoot", targetPath))
            }
        }
        val (body, resolvedPages) = buildJob(manga, chapter, source, UUID.randomUUID().toString(), pages)
        val result = post(server, "/api/v2/jobs", body)
        return Pair(JobRef(result.optString("jobId", body.getString("jobId")), server), resolvedPages)
    }

    suspend fun awaitCompletion(
        ref: JobRef,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ) {
        repeat(10_800) {
            delay(1_000)
            val result = get(ref.server, "/api/v2/jobs/${ref.id}")
            val completed = result.optInt("completed", 0)
            val total = result.optInt("total", 0)
            if (total > 0) {
                onProgress?.invoke(completed, total)
            }
            when (result.optString("status")) {
                "completed" -> {
                    if (total > 0) onProgress?.invoke(total, total)
                    return
                }
                "failed", "cancelled" -> throw IOException(result.optString("error", "Worker download failed"))
                "needs_refresh" -> {
                    val (refreshed, _) = buildJob(manga, chapter, source, ref.id)
                    post(ref.server, "/api/v2/jobs/${ref.id}/refresh", JSONObject().put("pages", refreshed.getJSONArray("pages")))
                }
            }
        }
        throw IOException("Timed out waiting for Worker download")
    }

    private suspend fun buildJob(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        jobId: String,
        pages: List<Page>? = null,
    ): Pair<JSONObject, List<Page>> =
        withContext(Dispatchers.IO) {
            val sourcePages = pages ?: source.getPageList(chapter.toSChapter()).mapIndexed { index, original ->
                Page(index, original.url, original.imageUrl, original.uri)
            }
            if (sourcePages.isEmpty()) throw IOException("Chapter has no pages")
            val pagesJson = JSONArray()
            sourcePages.forEachIndexed { index, page ->
                if (page.imageUrl.isNullOrBlank()) page.imageUrl = source.getImageUrl(page)
                val imageUrl = page.imageUrl ?: throw IOException("Page ${index + 1} has no image URL")
                val headers = JSONObject()
                source.headers.forEach { headers.put(it.first, it.second) }
                runCatching {
                    val cookies = network.cookieJar.loadForRequest(imageUrl.toHttpUrl())
                    if (cookies.isNotEmpty()) headers.put("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
                }
                pagesJson.put(JSONObject().put("index", index + 1).put("url", imageUrl).put("headers", headers))
            }
            val json = JSONObject()
                .put("jobId", jobId)
                .put("mangaTitle", manga.title)
                .put("chapterTitle", chapter.name)
                .put("sourceDir", provider.getSourceDirName(source))
                .put("mangaDir", provider.getMangaDirName(manga.title))
                .put("chapterDir", provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url))
                .put("outputMode", if (preferences.stbWorkerSaveCbz.get()) "cbz" else "folder")
                .put("pages", pagesJson)
            Pair(json, sourcePages)
        }

    private suspend fun selectServer(): Server {
        val available = servers()
        if (available.isEmpty()) throw IOException("No Download Worker configured")
        return available.first()
    }

    private fun servers(): List<Server> = listOfNotNull(configuredServer())

    private fun configuredServer(): Server? {
        val host = preferences.stbWorkerHost.get()
            .trim().removePrefix("http://").removePrefix("https://")
        if (host.isBlank()) return null
        val port = preferences.stbWorkerPort.get()
            .toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IOException("Invalid Worker port")
        val token = preferences.stbWorkerToken.get()
        return Server("Worker", host, port, token, 1)
    }

    private suspend fun get(server: Server, path: String): JSONObject = request(server, path, null, true)

    private suspend fun post(server: Server, path: String, body: JSONObject, authenticated: Boolean = true): JSONObject =
        request(server, path, body, authenticated)

    private suspend fun request(server: Server, path: String, body: JSONObject?, authenticated: Boolean): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(server.url + path)
            if (authenticated && server.token.isNotBlank()) builder.header(TOKEN_HEADER, server.token)
            if (body == null) builder.get() else builder.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            network.client.newCall(builder.build()).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw IOException("${server.name} error ${response.code}: $text")
                JSONObject(text)
            }
        }

    private fun formatBytes(value: Long): String = when {
        value >= 1L shl 30 -> "%.1f GB".format(value.toDouble() / (1L shl 30))
        value >= 1L shl 20 -> "%.1f MB".format(value.toDouble() / (1L shl 20))
        else -> "$value B"
    }

    companion object {
        private const val TOKEN_HEADER = "X-Mihon-Worker-Token"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
