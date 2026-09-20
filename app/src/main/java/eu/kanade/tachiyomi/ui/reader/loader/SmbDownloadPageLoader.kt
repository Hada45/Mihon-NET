package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.smb.SmbConfig
import eu.kanade.tachiyomi.data.smb.SmbDownloadStorage
import eu.kanade.tachiyomi.data.smb.SmbPerfLogger
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import uy.kohesive.injekt.injectLazy

internal class SmbDownloadPageLoader(
    private val config: SmbConfig,
    private val remoteChapterPath: String,
) : PageLoader() {

    private val context: Context by injectLazy()

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        SmbPerfLogger.init(context)
        val t0 = System.currentTimeMillis()
        val chapterName = remoteChapterPath.substringAfterLast('\\', remoteChapterPath.substringAfterLast('/'))
        SmbPerfLogger.log("CHAPTER_OPEN", ">>> START Buka Chapter: $chapterName ($remoteChapterPath)")

        val pageUrls = try {
            SmbDownloadStorage.listPages(config, remoteChapterPath, context)
        } catch (e: Throwable) {
            val d = System.currentTimeMillis() - t0
            SmbPerfLogger.log("CHAPTER_OPEN", "!!! GAGAL Buka Chapter dalam ${d}ms: ${e.message}")
            throw e
        }
        val duration = System.currentTimeMillis() - t0
        SmbPerfLogger.log("CHAPTER_OPEN", "<<< SUKSES Buka Chapter dalam ${duration}ms, total ${pageUrls.size} halaman")

        return pageUrls.mapIndexed { index, path ->
            ReaderPage(index, path, null)
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        page.stream = {
            val t0 = System.currentTimeMillis()
            val pageName = page.url.substringAfterLast('#', page.url.substringAfterLast('\\')).takeLast(25)
            SmbPerfLogger.log("PAGE_LOAD", "Request Halaman #${page.index + 1} ($pageName)")
            val stream = try {
                SmbDownloadStorage.openCachedOrStreamPage(context, config, page.url)
            } catch (e: Throwable) {
                val d = System.currentTimeMillis() - t0
                SmbPerfLogger.log("PAGE_LOAD", "!!! GAGAL Muat Halaman #${page.index + 1} dalam ${d}ms: ${e.message}")
                throw e
            }
            val duration = System.currentTimeMillis() - t0
            SmbPerfLogger.log("PAGE_LOAD", "Halaman #${page.index + 1} SIAP dalam ${duration}ms ($pageName)")
            stream
        }
        page.status = Page.State.Ready
    }

    override fun retryPage(page: ReaderPage) {
        page.stream = { SmbDownloadStorage.openCachedOrStreamPage(context, config, page.url) }
        page.status = Page.State.Ready
    }
}
