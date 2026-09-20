package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.ftp.FtpConfig
import eu.kanade.tachiyomi.data.ftp.FtpDownloadStorage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

internal class FtpDownloadPageLoader(
    private val config: FtpConfig,
    private val remoteChapterPath: String,
) : PageLoader() {
    override var isLocal = true

    override suspend fun getPages(): List<ReaderPage> =
        FtpDownloadStorage.listPages(config, remoteChapterPath).mapIndexed { index, path ->
            ReaderPage(index, path, path)
        }

    override suspend fun loadPage(page: ReaderPage) {
        page.stream = { FtpDownloadStorage.openPageStream(config, page.url) }
        page.status = Page.State.Ready
    }

    override fun retryPage(page: ReaderPage) {
        page.stream = { FtpDownloadStorage.openPageStream(config, page.url) }
        page.status = Page.State.Ready
    }
}
