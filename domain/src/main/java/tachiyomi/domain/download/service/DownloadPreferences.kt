package tachiyomi.domain.download.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class DownloadPreferences(
    preferenceStore: PreferenceStore,
) {

    val downloadOnlyOverWifi: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_download_only_over_wifi_key",
        true,
    )

    val saveChaptersAsCBZ: Preference<Boolean> = preferenceStore.getBoolean("save_chapter_as_cbz", true)

    val ftpDownloadLocation: Preference<Boolean> = preferenceStore.getBoolean("ftp_download_location", false)
    val ftpHost: Preference<String> = preferenceStore.getString("ftp_host", "")
    val ftpPort: Preference<String> = preferenceStore.getString("ftp_port", "21")
    val ftpUsername: Preference<String> = preferenceStore.getString("ftp_username", "")
    val ftpPassword: Preference<String> = preferenceStore.getString("ftp_password", "")
    val ftpPath: Preference<String> = preferenceStore.getString("ftp_path", "/")
    val ftpPassiveMode: Preference<Boolean> = preferenceStore.getBoolean("ftp_passive_mode", true)

    val downloadStorageType: Preference<String> = preferenceStore.getString("download_storage_type", if (ftpDownloadLocation.get()) "ftp" else "local")
    val smbHost: Preference<String> = preferenceStore.getString("smb_host", "")
    val smbPort: Preference<String> = preferenceStore.getString("smb_port", "445")
    val smbShareName: Preference<String> = preferenceStore.getString("smb_share_name", "")
    val smbUsername: Preference<String> = preferenceStore.getString("smb_username", "")
    val smbPassword: Preference<String> = preferenceStore.getString("smb_password", "")
    val smbDomain: Preference<String> = preferenceStore.getString("smb_domain", "")
    val smbPath: Preference<String> = preferenceStore.getString("smb_path", "/")

    fun isFtpStorage(): Boolean = downloadStorageType.get() == "ftp" || (downloadStorageType.get() == "local" && ftpDownloadLocation.get())
    fun isSmbStorage(): Boolean = downloadStorageType.get() == "smb"
    fun isRemoteStorage(): Boolean = isFtpStorage() || isSmbStorage()

    val downloadWorkerEnabled: Preference<Boolean> = preferenceStore.getBoolean("download_worker_enabled", true)
    val stbWorkerHost: Preference<String> = preferenceStore.getString("stb_worker_host", "192.168.1.2")
    val stbWorkerPort: Preference<String> = preferenceStore.getString("stb_worker_port", "2223")
    val stbWorkerStoragePath: Preference<String> = preferenceStore.getString("stb_worker_storage_path", "")
    val stbWorkerToken: Preference<String> = preferenceStore.getString("stb_worker_token", "")
    val stbWorkerPairCode: Preference<String> = preferenceStore.getString("stb_worker_pair_code", "")
    val stbWorkerSaveCbz: Preference<Boolean> = preferenceStore.getBoolean("stb_worker_save_cbz", false)
    val stbWorkerSelection: Preference<String> = preferenceStore.getString("stb_worker_selection", "most_free")
    val stbWorker2Enabled: Preference<Boolean> = preferenceStore.getBoolean("stb_worker_2_enabled", false)
    val stbWorker2Host: Preference<String> = preferenceStore.getString("stb_worker_2_host", "")
    val stbWorker2Port: Preference<String> = preferenceStore.getString("stb_worker_2_port", "2223")
    val stbWorker2Token: Preference<String> = preferenceStore.getString("stb_worker_2_token", "")
    val stbWorker2PairCode: Preference<String> = preferenceStore.getString("stb_worker_2_pair_code", "")

    val splitTallImages: Preference<Boolean> = preferenceStore.getBoolean("split_tall_images", true)

    val autoDownloadWhileReading: Preference<Int> = preferenceStore.getInt("auto_download_while_reading", 0)

    val removeAfterReadSlots: Preference<Int> = preferenceStore.getInt("remove_after_read_slots", -1)

    val removeAfterMarkedAsRead: Preference<Boolean> = preferenceStore.getBoolean(
        "pref_remove_after_marked_as_read_key",
        false,
    )

    val removeBookmarkedChapters: Preference<Boolean> = preferenceStore.getBoolean("pref_remove_bookmarked", false)

    val removeExcludeCategories: Preference<Set<String>> = preferenceStore.getStringSet(
        REMOVE_EXCLUDE_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    val downloadNewChapters: Preference<Boolean> = preferenceStore.getBoolean("download_new", false)

    val downloadNewChapterCategories: Preference<Set<String>> = preferenceStore.getStringSet(
        DOWNLOAD_NEW_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    val downloadNewChapterCategoriesExclude: Preference<Set<String>> = preferenceStore.getStringSet(
        DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY,
        emptySet(),
    )

    val downloadNewUnreadChaptersOnly: Preference<Boolean> = preferenceStore.getBoolean(
        "download_new_unread_chapters_only",
        false,
    )

    val parallelSourceLimit: Preference<Int> = preferenceStore.getInt("download_parallel_source_limit", 5)

    val parallelPageLimit: Preference<Int> = preferenceStore.getInt("download_parallel_page_limit", 5)

    companion object {
        private const val REMOVE_EXCLUDE_CATEGORIES_PREF_KEY = "remove_exclude_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_PREF_KEY = "download_new_categories"
        private const val DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY = "download_new_categories_exclude"
        val categoryPreferenceKeys = setOf(
            REMOVE_EXCLUDE_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_CATEGORIES_EXCLUDE_PREF_KEY,
        )
    }
}
