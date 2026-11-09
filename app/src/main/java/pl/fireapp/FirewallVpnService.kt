package pl.fireapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class FirewallVpnService : VpnService() {
    companion object {
        const val ACTION_START = "pl.fireapp.START"
        const val ACTION_STOP = "pl.fireapp.STOP"
        const val EXTRA_PACKAGES = "packages"
        private const val CHANNEL_ID = "fireapp_firewall"
        private const val NOTIFICATION_ID = 7301
    }

    private val prefs by lazy { getSharedPreferences("fireapp", MODE_PRIVATE) }
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean("active", false).apply()
            stopTunnel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val packages = if (intent?.action == ACTION_START) {
            intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
        } else if (prefs.getBoolean("active", false)) {
            ArrayList(prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet())
        } else {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startFirewall(packages)
        return START_STICKY
    }

    private fun startFirewall(packages: List<String>) {
        stopTunnel()
        if (packages.isEmpty()) {
            prefs.edit().putBoolean("active", false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            val builder = Builder()
                .setSession("FireApp — blokada aplikacji")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.111.0.2")
                .addAddress("fd00:1:fd00:1::1", 128)
                .addRoute("::", 0)

            var count = 0
            for (pkg in packages.distinct()) {
                if (pkg == packageName) continue
                try {
                    builder.addAllowedApplication(pkg)
                    count++
                } catch (_: Exception) {
                    // Skip an app that was uninstalled after it was selected.
                }
            }
            if (count == 0) {
                prefs.edit().putBoolean("active", false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            val descriptor = builder.establish()
            if (descriptor == null) {
                prefs.edit().putBoolean("active", false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            tun = descriptor
            prefs.edit().putBoolean("active", true).apply()
            running.set(true)
            reader = Thread({
                val packetBuffer = ByteArray(32767)
                try {
                    FileInputStream(descriptor.fileDescriptor).use { input ->
                        while (running.get()) {
                            // This is the firewall: packets from the selected apps are discarded,
                            // never forwarded to a physical network interface.
                            if (input.read(packetBuffer) < 0) break
                        }
                    }
                } catch (_: Exception) {
                    // Expected when the VPN descriptor is closed.
                } finally {
                    running.set(false)
                }
            }, "FireApp-PacketDropper").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            prefs.edit().putBoolean("active", false).apply()
            stopTunnel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopTunnel() {
        running.set(false)
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        reader = null
    }

    override fun onRevoke() {
        prefs.edit().putBoolean("active", false).apply()
        stopTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "FireApp — zapora", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("FireApp działa")
                .setContentText("Wybrane aplikacje nie mają dostępu do internetu.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("FireApp działa")
                .setContentText("Wybrane aplikacje nie mają dostępu do internetu.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }
}
