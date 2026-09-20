package app.mihon.ftp.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MainActivity.KEY_ENABLED, false)) {
            context.startForegroundService(Intent(context, WorkerService::class.java))
        }
    }
}
