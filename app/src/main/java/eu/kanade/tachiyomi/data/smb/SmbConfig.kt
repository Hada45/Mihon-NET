package eu.kanade.tachiyomi.data.smb

data class SmbConfig(
    val host: String,
    val port: Int,
    val shareName: String,
    val username: String,
    val password: String,
    val domain: String,
    val path: String,
)