package eu.kanade.tachiyomi.data.smb

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

object SmbPerfLogger {
    private const val TAG = "SMB_PERF"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val memoryLog = LinkedBlockingQueue<String>(1000)
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile == null) {
            val file = File(context.filesDir, "smb_perf.log")
            if (!file.exists()) {
                runCatching { file.createNewFile() }
            }
            logFile = file
        }
    }

    @Synchronized
    fun log(category: String, message: String) {
        val now = dateFormat.format(Date())
        val formatted = "[$now] [$category] $message"
        Log.e(TAG, formatted)

        if (memoryLog.remainingCapacity() == 0) {
            memoryLog.poll()
        }
        memoryLog.offer(formatted)

        logFile?.let { file ->
            runCatching {
                FileWriter(file, true).use { writer ->
                    writer.write(formatted)
                    writer.write("\n")
                }
            }
        }
    }

    fun getLogs(): String {
        return logFile?.takeIf { it.exists() }?.readText()
            ?: memoryLog.joinToString("\n")
    }

    fun clearLogs() {
        memoryLog.clear()
        runCatching {
            logFile?.writeText("")
        }
    }
}
