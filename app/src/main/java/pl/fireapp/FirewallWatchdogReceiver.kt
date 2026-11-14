package pl.fireapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.SystemClock
import android.net.VpnService
import android.os.Build

class FirewallWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences("fireapp", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return
        // Re-arm before attempting recovery so a failed background start does not end monitoring.
        scheduleNextCheck(context)
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

    private fun scheduleNextCheck(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, FirewallWatchdogReceiver::class.java)
            .setAction(FirewallVpnService.ACTION_CHECK)
        val pending = PendingIntent.getBroadcast(
            context, 7302, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = SystemClock.elapsedRealtime() + 15 * 60 * 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        } else {
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        }
    }
}
