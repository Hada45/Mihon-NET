package app.mihon.ftp.worker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var folderText: TextView
    private lateinit var portInput: EditText
    private lateinit var concurrencyInput: EditText
    private lateinit var minFreeInput: EditText
    private lateinit var historyInput: EditText
    private lateinit var cbzSwitch: Switch
    private lateinit var cleanupSwitch: Switch
    private lateinit var ftpEnabledSwitch: Switch
    private lateinit var ftpPortInput: EditText
    private lateinit var ftpUserInput: EditText
    private lateinit var ftpPasswordInput: EditText
    private lateinit var ftpInfoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(16, 20, 22))
        }
        fun label(value: String, size: Float = 16f, color: Int = Color.WHITE) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setPadding(0, 12, 0, 8)
        }
        fun header(value: String) = TextView(this).apply {
            text = value
            textSize = 20f
            setTextColor(Color.rgb(100, 200, 255))
            setPadding(0, 20, 0, 8)
        }

        layout.addView(label(getString(R.string.worker_title), 26f))
        layout.addView(label(getString(R.string.worker_desc)))

        val ip = getLocalIp()
        layout.addView(label("Device IP: $ip", 18f, Color.rgb(120, 255, 120)))

        // Prominent FTP-Only notice
        layout.addView(TextView(this).apply {
            text = getString(R.string.network_storage_notice)
            textSize = 15f
            setTextColor(Color.rgb(255, 200, 60))
            setBackgroundColor(Color.rgb(45, 35, 15))
            setPadding(30, 20, 30, 20)
            gravity = Gravity.CENTER
        })

        ftpInfoText = label(ftpLabel(ip), 18f, Color.rgb(255, 200, 100))
        layout.addView(ftpInfoText)

        folderText = label(folderLabel())
        layout.addView(folderText)
        layout.addView(Button(this).apply {
            text = getString(R.string.btn_choose_folder)
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, REQ_FOLDER)
            }
        })

        layout.addView(header("Download Worker (REST API)"))
        portInput = EditText(this).apply {
            hint = getString(R.string.hint_port)
            setText(prefs.getInt(KEY_PORT, 2223).toString())
            inputType = 2
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        concurrencyInput = numberInput(getString(R.string.hint_concurrency), prefs.getInt(KEY_CONCURRENCY, 2))
        minFreeInput = numberInput(getString(R.string.hint_min_free), prefs.getInt(KEY_MIN_FREE_GB, 2))
        historyInput = numberInput(getString(R.string.hint_history), prefs.getInt(KEY_HISTORY_DAYS, 7))
        cbzSwitch = Switch(this).apply {
            text = getString(R.string.switch_cbz)
            isChecked = prefs.getBoolean(KEY_DEFAULT_CBZ, false)
            setTextColor(Color.WHITE)
        }
        cleanupSwitch = Switch(this).apply {
            text = getString(R.string.switch_cleanup)
            isChecked = prefs.getBoolean(KEY_AUTO_CLEANUP, false)
            setTextColor(Color.WHITE)
        }

        layout.addView(portInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layout.addView(concurrencyInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layout.addView(minFreeInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layout.addView(historyInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layout.addView(cbzSwitch)
        layout.addView(cleanupSwitch)

        layout.addView(header("Built-in FTP Server (For Mihon)"))
        ftpEnabledSwitch = Switch(this).apply {
            text = getString(R.string.switch_ftp_enabled)
            isChecked = prefs.getBoolean(KEY_FTP_ENABLED, true)
            setTextColor(Color.WHITE)
        }
        ftpPortInput = EditText(this).apply {
            hint = getString(R.string.hint_ftp_port)
            setText(prefs.getInt(KEY_FTP_PORT, 2222).toString())
            inputType = 2
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        ftpUserInput = EditText(this).apply {
            hint = getString(R.string.hint_ftp_user)
            setText(prefs.getString(KEY_FTP_USER, "mihon"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }
        ftpPasswordInput = EditText(this).apply {
            hint = getString(R.string.hint_ftp_password)
            setText(prefs.getString(KEY_FTP_PASSWORD, "mihon"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }

        layout.addView(ftpEnabledSwitch)
        layout.addView(ftpPortInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layout.addView(ftpUserInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        layout.addView(ftpPasswordInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        layout.addView(Button(this).apply {
            text = getString(R.string.btn_save_start)
            setOnClickListener {
                val port = portInput.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 2223
                val ftpPort = ftpPortInput.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 2222
                val ftpUser = ftpUserInput.text.toString().trim()
                val ftpPass = ftpPasswordInput.text.toString().trim()

                prefs.edit()
                    .putInt(KEY_PORT, port)
                    .putInt(KEY_CONCURRENCY, concurrencyInput.text.toString().toIntOrNull()?.coerceIn(1, 6) ?: 2)
                    .putInt(KEY_MIN_FREE_GB, minFreeInput.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 2)
                    .putInt(KEY_HISTORY_DAYS, historyInput.text.toString().toIntOrNull()?.coerceIn(1, 90) ?: 7)
                    .putBoolean(KEY_DEFAULT_CBZ, cbzSwitch.isChecked)
                    .putBoolean(KEY_AUTO_CLEANUP, cleanupSwitch.isChecked)
                    .putBoolean(KEY_FTP_ENABLED, ftpEnabledSwitch.isChecked)
                    .putInt(KEY_FTP_PORT, ftpPort)
                    .putString(KEY_FTP_USER, ftpUser)
                    .putString(KEY_FTP_PASSWORD, ftpPass)
                    .putBoolean(KEY_ENABLED, true)
                    .apply()

                stopService(Intent(this@MainActivity, WorkerService::class.java))
                startForegroundService(Intent(this@MainActivity, WorkerService::class.java))

                val currentIp = getLocalIp()
                folderText.text = folderLabel() + getString(R.string.worker_listening_port, currentIp, port)
                ftpInfoText.text = ftpLabel(currentIp)
            }
        })

        layout.addView(Button(this).apply {
            text = getString(R.string.btn_battery_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })

        setContentView(ScrollView(this).apply { addView(layout) })
        if (prefs.getBoolean(KEY_ENABLED, false)) startForegroundService(Intent(this, WorkerService::class.java))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
                folderText.text = folderLabel()
            }
        }
    }

    private fun folderLabel(): String {
        val selectedPath = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)?.path ?: getString(R.string.storage_not_selected)
        return getString(R.string.storage_root_label, selectedPath)
    }

    private fun ftpLabel(ip: String): String {
        val enabled = prefs.getBoolean(KEY_FTP_ENABLED, true)
        if (!enabled) return getString(R.string.ftp_server_disabled)
        val port = prefs.getInt(KEY_FTP_PORT, 2222)
        val user = prefs.getString(KEY_FTP_USER, "mihon").orEmpty()
        val pass = prefs.getString(KEY_FTP_PASSWORD, "mihon").orEmpty()
        return getString(R.string.ftp_server_label, ip, port, user, pass)
    }

    private fun numberInput(label: String, value: Int) = EditText(this).apply {
        hint = label
        setText(value.toString())
        inputType = 2
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
    }

    private fun getLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    companion object {
        const val PREFS = "worker"
        const val KEY_FOLDER = "folder"
        const val KEY_PORT = "port"
        const val KEY_ENABLED = "enabled"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_MIN_FREE_GB = "minimum_free_gb"
        const val KEY_HISTORY_DAYS = "history_days"
        const val KEY_DEFAULT_CBZ = "default_cbz"
        const val KEY_AUTO_CLEANUP = "auto_cleanup"

        const val KEY_FTP_ENABLED = "ftp_enabled"
        const val KEY_FTP_PORT = "ftp_port"
        const val KEY_FTP_USER = "ftp_user"
        const val KEY_FTP_PASSWORD = "ftp_password"

        private const val REQ_FOLDER = 7
    }
}
