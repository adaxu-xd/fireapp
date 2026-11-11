package pl.fireapp

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("fireapp", MODE_PRIVATE) }
    private val selected = linkedSetOf<String>()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var appList: LinearLayout
    private var mode = FirewallVpnService.MODE_BLOCK_SELECTED
    private val vpnRequestCode = 4102
    private var permissionRequestInProgress = false
    private var startRequested = false
    private var manuallyStopped = false
    private var currentPage = "home"

    private val bg = Color.rgb(5, 4, 11)
    private val panel = Color.rgb(17, 12, 27)
    private val purple = Color.rgb(174, 42, 255)
    private val lightPurple = Color.rgb(218, 157, 255)
    private val muted = Color.rgb(174, 157, 194)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("selected_apps",
            prefs.getStringSet("blocked_apps", emptySet()) ?: emptySet()) ?: emptySet())
        mode = prefs.getString("mode", FirewallVpnService.MODE_BLOCK_SELECTED)
            ?: FirewallVpnService.MODE_BLOCK_SELECTED
        buildShell()
        showPage("home")
        autoStartFirewall()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun card(color: Int = panel, stroke: Int = 0xFF38204F.toInt(), radius: Int = 20): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun shellButton(value: String, onClick: () -> Unit, accent: Boolean = false): TextView =
        label(value, 15f, if (accent) Color.WHITE else lightPurple, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = card(if (accent) 0xFF6513A5.toInt() else panel,
                if (accent) purple else 0xFF593078.toInt(), 16)
            setOnClickListener { onClick() }
        }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        setContentView(root)
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(14))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.fireapp_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        titleBox.addView(label("FireApp", 29f, Color.WHITE, true))
        titleBox.addView(label("Zapora Internetu", 13f, lightPurple))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(shellButton("⚙", { showPage("info") }), LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = card(0xFF0D0A14.toInt(), 0xFF30213D.toInt(), 22)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        listOf(Triple("⌂", "Strona", "home"), Triple("▦", "Aplikacje", "apps"), Triple("ⓘ", "Informacje", "info")).forEach { item ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, dp(3))
                setOnClickListener { showPage(item.third) }
            }
            cell.addView(label(item.first, 23f, lightPurple, true).apply { gravity = Gravity.CENTER })
            cell.addView(label(item.second, 11f, muted).apply { gravity = Gravity.CENTER })
            nav.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(62)))
    }

    private fun showPage(page: String) {
        currentPage = page
        content.removeAllViews()
        when (page) {
            "apps" -> buildAppsPage()
            "info" -> buildInfoPage()
            else -> buildHomePage()
        }
    }

    private fun buildHomePage() {
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(12)) }
        scroll.addView(body)
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(12))
        }
        val heroIcon = ImageView(this).apply {
            setImageResource(R.drawable.fireapp_icon); scaleType = ImageView.ScaleType.FIT_CENTER
        }
        hero.addView(heroIcon, LinearLayout.LayoutParams(dp(210), dp(190)))
        hero.addView(label("Twoja sieć. Twoje zasady.", 16f, lightPurple, true))
        body.addView(hero)

        status = label("", 14f, Color.rgb(63, 245, 145), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(6))
        }
        val statusSub = label("", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(15)) }
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card(0xFF100D18.toInt(), 0xFF512477.toInt(), 20)
            addView(status); addView(statusSub)
        }
        body.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        updateStatus(statusSub)

        body.addView(shellButton("■   STOP", { disableFirewall() }, true),
            LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(18) })
        body.addView(label("Tryb działania:", 14f, lightPurple, true).apply { setPadding(dp(4), 0, 0, dp(8)) })
        body.addView(modeCard("◉", "Blokuj internet wybranym aplikacjom",
            "Zaznaczone aplikacje nie mają internetu.", FirewallVpnService.MODE_BLOCK_SELECTED))
        body.addView(modeCard("⌁", "Dawaj internet tylko wybranym aplikacjom",
            "Tylko zaznaczone aplikacje mają internet.", FirewallVpnService.MODE_ALLOW_SELECTED))
        body.addView(shellButton("▦   Wybierz aplikacje", { showPage("apps") }),
            LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        content.addView(scroll)
    }

    private fun modeCard(symbol: String, title: String, subtitle: String, value: String): View {
        val chosen = mode == value
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = card(if (chosen) 0xFF21102F.toInt() else 0xFF100D18.toInt(),
                if (chosen) purple else 0xFF30213D.toInt(), 18)
            setOnClickListener {
                mode = value
                prefs.edit().putString("mode", mode).apply()
                showPage("home")
                applyChangesIfRunning()
            }
        }
        box.addView(label(symbol, 28f, if (chosen) purple else 0xFF26A8FF.toInt(), true),
            LinearLayout.LayoutParams(dp(48), -2))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 14f, Color.WHITE, true))
        texts.addView(label(subtitle, 11f, muted))
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(label(if (chosen) "◉" else "○", 25f, if (chosen) purple else muted))
        val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        box.layoutParams = params
        return box
    }

    private fun updateStatus(sub: TextView? = null) {
        val active = prefs.getBoolean("active", false)
        status.text = if (active) "●  Zapora działa" else "○  Zapora zatrzymana"
        sub?.text = if (active) {
            if (mode == FirewallVpnService.MODE_ALLOW_SELECTED) "Internet mają tylko zaznaczone aplikacje."
            else "Internet zablokowany dla wybranych aplikacji."
        } else "Wybierz aplikacje i uruchom ochronę."
    }

    private fun buildAppsPage() {
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        top.addView(label("Aplikacje", 27f, Color.WHITE, true).apply { setPadding(dp(4), dp(8), 0, dp(10)) })
        top.addView(label(if (mode == FirewallVpnService.MODE_ALLOW_SELECTED)
            "Nowe aplikacje automatycznie dostają internet."
            else "Zaznacz aplikacje, którym chcesz zablokować internet.",
            13f, lightPurple).apply {
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = card(0xFF21102F.toInt(), 0xFF63308C.toInt(), 16)
            })
        top.addView(label("Zainstalowane aplikacje", 14f, muted, true).apply { setPadding(dp(4), dp(14), 0, dp(8)) })
        val scroll = ScrollView(this)
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(appList)
        top.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(top)
        renderApps()
    }

    private fun renderApps() {
        if (!::appList.isInitialized) return
        appList.removeAllViews()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
        for (app in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = card(0xFF100D18.toInt(), 0xFF30213D.toInt(), 12)
            }
            val appIcon = ImageView(this).apply {
                try { setImageDrawable(app.loadIcon(packageManager)) } catch (_: Exception) {}
            }
            row.addView(appIcon, LinearLayout.LayoutParams(dp(42), dp(42)))
            val name = packageManager.getApplicationLabel(app).toString()
            row.addView(label(name, 14f, Color.WHITE, true).apply { setPadding(dp(12), 0, 0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            val check = CheckBox(this).apply {
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(purple, 0xFF8C6AA5.toInt()))
                isChecked = selected.contains(app.packageName)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                    prefs.edit().putStringSet("selected_apps", selected.toSet()).apply()
                    applyChangesIfRunning()
                }
            }
            row.addView(check)
            appList.addView(row, LinearLayout.LayoutParams(-1, dp(60)).apply { bottomMargin = dp(3) })
        }
        if (apps.isEmpty()) appList.addView(label("Nie znaleziono aplikacji.", 14f, muted))
    }

    private fun buildInfoPage() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label("Informacje", 27f, Color.WHITE, true).apply { setPadding(dp(4), dp(8), 0, dp(18)) })
        listOf(
            "FireApp kontroluje dostęp aplikacji do internetu przez lokalne połączenie VPN.",
            "Zmiany zaznaczenia i trybu są przekazywane do usługi na bieżąco.",
            "Android lub producent telefonu może zatrzymać usługę. Nie ma gwarancji nieprzerwanej pracy w każdych warunkach.",
            "Aby zatrzymać zaporę, użyj przycisku STOP na stronie głównej."
        ).forEach { message ->
            box.addView(label(message, 14f, lightPurple).apply {
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = card(0xFF100D18.toInt(), 0xFF43215D.toInt(), 16)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        content.addView(box)
    }

    private fun autoStartFirewall() {
        if (prefs.getBoolean("active", false)) { updateStatus(); return }
        if (mode == FirewallVpnService.MODE_BLOCK_SELECTED && selected.isEmpty()) {
            updateStatus(); return
        }
        requestPermissionAndStart()
    }

    private fun requestPermissionAndStart() {
        if (permissionRequestInProgress) return
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            permissionRequestInProgress = true
            @Suppress("DEPRECATION")
            startActivityForResult(permissionIntent, vpnRequestCode)
        } else startFirewall()
    }

    @Deprecated("Kept for compatibility with supported Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode) {
            permissionRequestInProgress = false
            if (resultCode == RESULT_OK) startFirewall()
            else if (::status.isInitialized) status.text = "Nie przyznano zgody VPN."
        }
    }

    private fun startFirewall() {
        prefs.edit().putStringSet("selected_apps", selected.toSet()).putString("mode", mode).apply()
        if (mode == FirewallVpnService.MODE_BLOCK_SELECTED && selected.isEmpty()) {
            startRequested = false
            try { startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP)) } catch (_: Exception) {}
            if (::status.isInitialized) status.text = "Wybierz aplikacje do blokowania."
            return
        }
        val serviceIntent = Intent(this, FirewallVpnService::class.java)
            .setAction(FirewallVpnService.ACTION_START)
            .putStringArrayListExtra(FirewallVpnService.EXTRA_PACKAGES, ArrayList(selected))
            .putExtra(FirewallVpnService.EXTRA_MODE, mode)
        try {
            startForegroundService(serviceIntent); startRequested = true
            if (::status.isInitialized) status.text = "Zapora uruchamia się…"
        } catch (e: Exception) {
            if (::status.isInitialized) status.text = "Błąd uruchamiania: ${e.localizedMessage ?: "nieznany błąd"}"
        }
    }

    private fun applyChangesIfRunning() {
        if (prefs.getBoolean("active", false) || startRequested) startFirewall()
        else if (!manuallyStopped && (mode == FirewallVpnService.MODE_ALLOW_SELECTED || selected.isNotEmpty()))
            requestPermissionAndStart()
    }

    private fun disableFirewall() {
        startRequested = false; manuallyStopped = true
        prefs.edit().putBoolean("active", false).apply()
        try { startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP)) } catch (_: Exception) {}
        updateStatus()
    }
}