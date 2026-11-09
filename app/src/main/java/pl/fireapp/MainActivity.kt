package pl.fireapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("fireapp", MODE_PRIVATE) }
    private val selected = linkedSetOf<String>()
    private lateinit var appList: LinearLayout
    private lateinit var status: TextView
    private val vpnRequestCode = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet())
        buildScreen()
        renderApps()
        status.text = if (prefs.getBoolean("active", false)) {
            "Blokada jest włączona dla zapisanych aplikacji."
        } else {
            "Blokada jest wyłączona."
        }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 16)
            setBackgroundColor(0xFFF5F6FA.toInt())
        }
        root.addView(TextView(this).apply {
            text = "FireApp"
            textSize = 30f
            setTextColor(0xFF172033.toInt())
            gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(TextView(this).apply {
            text = "Wybierz aplikacje, którym chcesz zablokować dostęp do internetu."
            textSize = 15f
            setTextColor(0xFF4B5565.toInt())
            setPadding(0, 8, 0, 12)
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF14532D.toInt())
            setPadding(12, 12, 12, 12)
            setBackgroundColor(0xFFE2F3E9.toInt())
        }
        root.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Włącz blokadę"
            setOnClickListener { saveAndEnable() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Wyłącz"
            setOnClickListener { disableFirewall() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        root.addView(Button(this).apply {
            text = "Odśwież aplikacje"
            setOnClickListener { renderApps() }
        })
        root.addView(TextView(this).apply {
            text = "Zainstalowane aplikacje"
            textSize = 19f
            setTextColor(0xFF172033.toInt())
            setPadding(0, 12, 0, 6)
        })
        val scroll = ScrollView(this)
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(appList)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun renderApps() {
        appList.removeAllViews()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }

        for (app in apps) {
            val label = packageManager.getApplicationLabel(app).toString()
            val row = CheckBox(this).apply {
                text = "$label\n${app.packageName}"
                textSize = 14f
                setPadding(8, 9, 8, 9)
                isChecked = selected.contains(app.packageName)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                    prefs.edit().putStringSet("blocked_apps", selected.toSet()).apply()
                }
            }
            try {
                row.setCompoundDrawablesWithIntrinsicBounds(app.loadIcon(packageManager), null, null, null)
            } catch (_: Exception) { }
            row.compoundDrawablePadding = 16
            appList.addView(row)
            appList.addView(android.view.View(this).apply {
                setBackgroundColor(0xFFE1E5EB.toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
        if (apps.isEmpty()) {
            appList.addView(TextView(this).apply {
                text = "Nie znaleziono aplikacji z ikoną w menu."
                setPadding(8, 16, 8, 16)
            })
        }
    }

    private fun saveAndEnable() {
        prefs.edit().putStringSet("blocked_apps", selected.toSet()).apply()
        if (selected.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Brak wybranych aplikacji")
                .setMessage("Zaznacz przynajmniej jedną aplikację.")
                .setPositiveButton("OK", null).show()
            return
        }
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(permissionIntent, vpnRequestCode)
        } else {
            startFirewall()
        }
    }

    @Deprecated("Kept for compatibility with the supported Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode) {
            if (resultCode == RESULT_OK) startFirewall()
            else status.text = "Nie przyznano zgody VPN. Blokada nie została włączona."
        }
    }

    private fun startFirewall() {
        val serviceIntent = Intent(this, FirewallVpnService::class.java)
            .setAction(FirewallVpnService.ACTION_START)
            .putStringArrayListExtra(FirewallVpnService.EXTRA_PACKAGES, ArrayList(selected))
        try {
            startForegroundService(serviceIntent)
            status.text = "Włączanie blokady dla ${selected.size} aplikacji…"
        } catch (e: Exception) {
            status.text = "Błąd uruchamiania: ${e.localizedMessage ?: "nieznany błąd"}"
        }
    }

    private fun disableFirewall() {
        prefs.edit().putBoolean("active", false).apply()
        try {
            startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
        } catch (_: Exception) { }
        status.text = "Blokada wyłączona."
    }
}
