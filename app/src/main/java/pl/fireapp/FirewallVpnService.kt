package pl.fireapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        const val EXTRA_MODE = "mode"
        const val MODE_BLOCK_SELECTED = "block_selected"
        const val MODE_ALLOW_SELECTED = "allow_selected"
        private const val CHANNEL_ID = "fireapp_firewall"
        private const val NOTIFICATION_ID = 7301
    }

    private val prefs by lazy { getSharedPreferences("fireapp", MODE_PRIVATE) }
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private val running = AtomicBoolean(false)
    private var packageReceiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val addedPackage = intent.data?.schemeSpecificPart ?: return
            if (addedPackage == packageName || !prefs.getBoolean("active", false)) return

            val currentMode = prefs.getString("mode", MODE_BLOCK_SELECTED) ?: MODE_BLOCK_SELECTED
            if (currentMode == MODE_ALLOW_SELECTED) {
                val selected = prefs.getStringSet("selected_apps", emptySet())?.toMutableSet()
                    ?: mutableSetOf()
                if (selected.add(addedPackage)) {
                    prefs.edit().putStringSet("selected_apps", selected).apply()
                    // In allow-list mode, a newly installed app is immediately added
                    // to the apps that bypass the VPN and therefore keep internet access.
                    startFirewall(selected.toList(), currentMode)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(packageReceiver, filter)
        }
        packageReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean("active", false).apply()
            stopTunnel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val packages: List<String>
        val mode: String
        if (intent?.action == ACTION_START) {
            packages = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
            mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BLOCK_SELECTED
            prefs.edit()
                .putStringSet("selected_apps", packages.toSet())
                .putString("mode", mode)
                .apply()
        } else if (prefs.getBoolean("active", false)) {
            packages = ArrayList(prefs.getStringSet("selected_apps", emptySet()) ?: emptySet())
            mode = prefs.getString("mode", MODE_BLOCK_SELECTED) ?: MODE_BLOCK_SELECTED
        } else {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(mode))
        startFirewall(packages, mode)
        return START_STICKY
    }

    private fun startFirewall(packages: List<String>, mode: String) {
        stopTunnel()
        if (mode != MODE_ALLOW_SELECTED && packages.isEmpty()) {
            prefs.edit().putBoolean("active", false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            val builder = Builder()
                .setSession("FireApp — zapora aplikacji")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.111.0.2")
                .addAddress("fd00:1:fd00:1::1", 128)
                .addRoute("::", 0)

            var validPackages = 0
            for (pkg in packages.distinct()) {
                if (pkg == packageName) continue
                try {
                    if (mode == MODE_ALLOW_SELECTED) {
                        // Selected apps bypass the VPN and keep normal network access.
                        builder.addDisallowedApplication(pkg)
                    } else {
                        // Only selected apps enter the VPN; their packets are discarded.
                        builder.addAllowedApplication(pkg)
                    }
                    validPackages++
                } catch (_: Exception) {
                    // Skip an app that was uninstalled after it was selected.
                }
            }

            if (mode != MODE_ALLOW_SELECTED && validPackages == 0) {
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
                            // Traffic routed into this local VPN is intentionally dropped.
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
        } catch (_: Exception) {
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
        if (packageReceiverRegistered) {
            try { unregisterReceiver(packageReceiver) } catch (_: Exception) { }
            packageReceiverRegistered = false
        }
        stopTunnel()
        super.onDestroy()
    }

    private fun buildNotification(mode: String): Notification {
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
        val message = if (mode == MODE_ALLOW_SELECTED) {
            "Internet mają tylko zaznaczone aplikacje."
        } else {
            "Zaznaczone aplikacje nie mają internetu."
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("FireApp działa")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("FireApp działa")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openApp)
                .setOngoing(true)
                .build()
        }
    }
}
