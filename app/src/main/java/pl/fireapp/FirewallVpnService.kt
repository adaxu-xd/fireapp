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
import android.os.Handler
import android.os.Looper
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
        private const val RECOVERY_DELAY_MS = 2500L
    }

    private val prefs by lazy { getSharedPreferences("fireapp", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunnelGeneration = 0
    private var reader: Thread? = null
    private val running = AtomicBoolean(false)
    private var recoveryScheduled = false
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
            recoveryScheduled = false
            mainHandler.removeCallbacksAndMessages(null)
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

    @Synchronized
    private fun startFirewall(packages: List<String>, mode: String) {
        // Keep the current tunnel alive until Android successfully establishes its replacement.
        if (mode != MODE_ALLOW_SELECTED && packages.isEmpty()) {
            stopTunnel()
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
                        builder.addDisallowedApplication(pkg)
                    } else {
                        builder.addAllowedApplication(pkg)
                    }
                    validPackages++
                } catch (_: Exception) {
                    // The app may have been uninstalled after selection.
                }
            }

            if (mode != MODE_ALLOW_SELECTED && validPackages == 0) {
                stopTunnel()
                prefs.edit().putBoolean("active", false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            val descriptor = builder.establish()
            if (descriptor == null) {
                scheduleRecovery(tunnelGeneration)
                return
            }

            val previousTun = tun
            tunnelGeneration++
            running.set(false)
            try { previousTun?.close() } catch (_: Exception) { }
            tun = descriptor
            prefs.edit().putBoolean("active", true).apply()
            running.set(true)
            val thisGeneration = tunnelGeneration
            reader = Thread({
                val packetBuffer = ByteArray(32767)
                try {
                    FileInputStream(descriptor.fileDescriptor).use { input ->
                        while (running.get() && thisGeneration == tunnelGeneration) {
                            if (input.read(packetBuffer) < 0) break
                        }
                    }
                } catch (_: Exception) {
                    // The descriptor can close when Android revokes or replaces the VPN.
                } finally {
                    if (thisGeneration == tunnelGeneration) {
                        running.set(false)
                        if (prefs.getBoolean("active", false)) {
                            scheduleRecovery(thisGeneration)
                        }
                    }
                }
            }, "FireApp-PacketDropper").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            // If replacement fails, leave the previous established tunnel untouched.
            scheduleRecovery(tunnelGeneration)
        }
    }

    @Synchronized
    private fun scheduleRecovery(expectedGeneration: Int) {
        if (recoveryScheduled || !prefs.getBoolean("active", false)) return
        recoveryScheduled = true
        mainHandler.postDelayed({
            recoveryScheduled = false
            if (!prefs.getBoolean("active", false) || expectedGeneration != tunnelGeneration) return@postDelayed
            val packages = prefs.getStringSet("selected_apps", emptySet())?.toList() ?: emptyList()
            val mode = prefs.getString("mode", MODE_BLOCK_SELECTED) ?: MODE_BLOCK_SELECTED
            startFirewall(packages, mode)
        }, RECOVERY_DELAY_MS)
    }

    @Synchronized
    private fun stopTunnel() {
        tunnelGeneration++
        running.set(false)
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        reader = null
    }

    override fun onRevoke() {
        prefs.edit().putBoolean("active", false).apply()
        recoveryScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
        stopTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        recoveryScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
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
        val accentColor = when (prefs.getString("accent", "purple")) {
            "blue" -> android.graphics.Color.rgb(45, 130, 255)
            "green" -> android.graphics.Color.rgb(35, 190, 125)
            "red" -> android.graphics.Color.rgb(235, 65, 75)
            "pink" -> android.graphics.Color.rgb(245, 75, 165)
            "orange" -> android.graphics.Color.rgb(245, 135, 45)
            else -> android.graphics.Color.rgb(174, 42, 255)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("FireApp działa")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openApp)
                .setColor(accentColor)
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
