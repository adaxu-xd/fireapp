package pl.fireapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build

class FirewallWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("fireapp", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return
        if (VpnService.prepare(context) != null) {
            prefs.edit().putBoolean("active", false).apply()
            return
        }
        val serviceIntent = Intent(context, FirewallVpnService::class.java)
            .setAction(FirewallVpnService.ACTION_CHECK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // Android/MIUI może czasowo zablokować uruchomienie usługi w tle.
        }
    }
}
