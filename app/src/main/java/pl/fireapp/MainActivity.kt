package pl.fireapp

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("fireapp", MODE_PRIVATE) }
    private val selected = linkedSetOf<String>()
    private lateinit var appList: LinearLayout
    private lateinit var status: TextView
    private lateinit var modeDescription: TextView
    private var mode = FirewallVpnService.MODE_BLOCK_SELECTED
    private val vpnRequestCode = 4102
    private var permissionRequestInProgress = false
    private var startRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(
            prefs.getStringSet(
                "selected_apps",
                prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()
            ) ?: emptySet()
        )
        mode = prefs.getString("mode", FirewallVpnService.MODE_BLOCK_SELECTED)
            ?: FirewallVpnService.MODE_BLOCK_SELECTED
        buildScreen()
        renderApps()
        status.text = if (prefs.getBoolean("active", false)) {
            "Zapora działa. Zmiany są stosowane na bieżąco."
        } else {
            "Uruchamianie zapory…"
        }
        autoStartFirewall()
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
            text = "Wybierz sposób kontrolowania dostępu aplikacji do internetu:"
            textSize = 15f
            setTextColor(0xFF4B5565.toInt())
            setPadding(0, 8, 0, 8)
        })

        val modes = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@MainActivity).apply {
                id = 1
                text = "Blokuj internet wybranym aplikacjom"
                isChecked = mode == FirewallVpnService.MODE_BLOCK_SELECTED
            })
            addView(RadioButton(this@MainActivity).apply {
                id = 2
                text = "Dawaj internet tylko wybranym aplikacjom"
                isChecked = mode == FirewallVpnService.MODE_ALLOW_SELECTED
            })
            setOnCheckedChangeListener { _, checkedId ->
                mode = if (checkedId == 2) FirewallVpnService.MODE_ALLOW_SELECTED
                    else FirewallVpnService.MODE_BLOCK_SELECTED
                prefs.edit().putString("mode", mode).apply()
                updateModeDescription()
                applyChangesIfRunning()
            }
        }
        root.addView(modes)

        modeDescription = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF4B5565.toInt())
            setPadding(0, 4, 0, 12)
        }
        root.addView(modeDescription)
        updateModeDescription()

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF14532D.toInt())
            setPadding(12, 12, 12, 12)
            setBackgroundColor(0xFFE2F3E9.toInt())
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener { disableFirewall() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(TextView(this).apply {
            text = "Zainstalowane aplikacje"
            textSize = 19f
            setTextColor(0xFF172033.toInt())
            setPadding(0, 12, 0, 6)
        })
        val scroll = ScrollView(this)
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(appList)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun updateModeDescription() {
        modeDescription.text = if (mode == FirewallVpnService.MODE_ALLOW_SELECTED) {
            "Zaznaczone aplikacje mają internet. Nowo zainstalowane aplikacje dostają internet automatycznie."
        } else {
            "Zaznaczone aplikacje są blokowane. Pozostałe, także nowe, mają internet."
        }
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
                    prefs.edit().putStringSet("selected_apps", selected.toSet()).apply()
                    applyChangesIfRunning()
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

    private fun autoStartFirewall() {
        if (prefs.getBoolean("active", false)) return
        requestPermissionAndStart()
    }

    private fun requestPermissionAndStart() {
        if (permissionRequestInProgress) return
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            permissionRequestInProgress = true
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
            permissionRequestInProgress = false
            if (resultCode == RESULT_OK) startFirewall()
            else status.text = "Nie przyznano zgody VPN. Zapora nie została uruchomiona."
        }
    }

    private fun startFirewall() {
        prefs.edit()
            .putStringSet("selected_apps", selected.toSet())
            .putString("mode", mode)
            .apply()
        if (mode == FirewallVpnService.MODE_BLOCK_SELECTED && selected.isEmpty()) {
            status.text = "Zaznacz aplikacje, które mają być blokowane."
            return
        }
        val serviceIntent = Intent(this, FirewallVpnService::class.java)
            .setAction(FirewallVpnService.ACTION_START)
            .putStringArrayListExtra(FirewallVpnService.EXTRA_PACKAGES, ArrayList(selected))
            .putExtra(FirewallVpnService.EXTRA_MODE, mode)
        try {
            startForegroundService(serviceIntent)
            startRequested = true
            status.text = "Uruchamianie zapory…"
        } catch (e: Exception) {
            status.text = "Błąd uruchamiania: ${e.localizedMessage ?: "nieznany błąd"}"
        }
    }

    private fun applyChangesIfRunning() {
        if (prefs.getBoolean("active", false) || startRequested) {
            startFirewall()
            status.text = "Zastosowano zmiany…"
        }
    }

    private fun disableFirewall() {
        startRequested = false
        prefs.edit().putBoolean("active", false).apply()
        try {
            startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
        } catch (_: Exception) { }
        status.text = "Zapora zatrzymana. Otwórz FireApp ponownie, aby uruchomić ją automatycznie."
    }
}
