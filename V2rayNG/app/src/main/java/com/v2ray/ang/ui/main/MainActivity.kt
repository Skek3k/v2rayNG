package com.v2ray.ang.ui.main

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        const val GITHUB_USER = "Skek3k"
        const val GH_BASE = "https://githubusercontent.com"
        const val SUB_URL = GH_BASE + "sub.txt"
        const val CONFIG_URL = GH_BASE + "config.json"
        const val TG_BASE = "https://t.me"
        const val CURRENT_APP_VERSION = "1.0.0"
    }

    private var channelName = "Ruroa"
    private var channelUrl = TG_BASE + "ruroa"
    private var updateAvailable = false
    private var bannerEnabled = true
    private var bannerTitle = "Подписывайся на Ruroa"
    private var bannerSubtitle = "Свежие обновления и посты"
    private var bannerButton = "Перейти"
    private var bannerLink = TG_BASE + "ruroa"
    private var isConnected = false
    private var serverList: List<String> = emptyList()
    private var currentServerIndex = 0
    private var clickedChannel = false

    private val requestVpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == RESULT_OK) startVpnTunnel() }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val prefs = getSharedPreferences("ruroa_prefs", Context.MODE_PRIVATE)
        var myRefCode = prefs.getString("my_ref_code", null)
        if (myRefCode == null) {
            myRefCode = UUID.randomUUID().toString().take(8).uppercase()
            prefs.edit().putString("my_ref_code", myRefCode).apply()
        }

        var isRefDone = prefs.getBoolean("ref_activated", false)
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "ruroa" && data.host == "invite") {
            val inviter = data.getQueryParameter("ref")
            if (!inviter.isNullOrEmpty() && inviter != myRefCode && !isRefDone) {
                prefs.edit().putBoolean("ref_activated", true).putString("invited_by", inviter).apply()
                isRefDone = true
                Toast.makeText(this, "Реферальная ссылка засчитана!", Toast.LENGTH_LONG).show()
            }
        }

        val safeCode = myRefCode ?: "RUROA001"
        renderScreen(prefs, safeCode, prefs.getBoolean("sub_activated", false), isRefDone)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject(URL(CONFIG_URL).readText())
                updateAvailable = json.optString("latest_version", CURRENT_APP_VERSION) != CURRENT_APP_VERSION
                channelName = json.optString("channel_name", "Ruroa")
                channelUrl = TG_BASE + json.optString("channel_tg", "ruroa")
                val banner = json.optJSONObject("banner")
                var link = banner?.optString("link", "t.me/ruroa") ?: "t.me/ruroa"
                if (link.isNotEmpty() && !link.startsWith("http")) link = "https://$link"
                bannerEnabled = banner?.optBoolean("enabled", true) ?: true
                bannerTitle = banner?.optString("title", "Подписывайся на Ruroa") ?: "Подписывайся на Ruroa"
                bannerSubtitle = banner?.optString("subtitle", "") ?: ""
                bannerButton = banner?.optString("button_text", "Перейти") ?: "Перейти"
                bannerLink = link
            } catch (_: Exception) { }

            try {
                val parsed = URL(SUB_URL).readText().lines().map { it.trim() }.filter { it.startsWith("vless://") }
                if (parsed.isNotEmpty()) serverList = parsed
            } catch (_: Exception) { }

            withContext(Dispatchers.Main) {
                renderScreen(prefs, safeCode, prefs.getBoolean("sub_activated", false), prefs.getBoolean("ref_activated", false))
            }
        }
    }
    private fun renderScreen(prefs: android.content.SharedPreferences, code: String, sub: Boolean, ref: Boolean) {
        if (!sub || !ref) showLockScreen(prefs, code, sub, ref) else showMainScreen()
    }

    private fun showLockScreen(prefs: android.content.SharedPreferences, code: String, sub: Boolean, ref: Boolean) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Доступ к $channelName"; setTextColor(Color.WHITE); textSize = 24f
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Выполните 2 обязательных условия для запуска:"; setTextColor(Color.GRAY)
            textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(24))
        })
        root.addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = if (sub) "1. Подписка на @ruroa выполнена" else "1. Подпишитесь на канал @ruroa"
                setTextColor(if (sub) Color.parseColor("#22C55E") else Color.WHITE)
                textSize = 16f; setTypeface(null, Typeface.BOLD)
            })
            if (!sub) {
                addView(btn("Открыть канал $channelName", "#38BDF8", Color.BLACK) {
                    clickedChannel = true; openUrl(channelUrl); renderScreen(prefs, code, false, ref)
                })
                if (clickedChannel) addView(btn("Я подписался, проверить", "#334155", Color.WHITE) {
                    prefs.edit().putBoolean("sub_activated", true).apply(); renderScreen(prefs, code, true, ref)
                })
            }
        })
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(16)) })
        root.addView(card().apply {
            addView(TextView(this@MainActivity).apply {
                text = if (ref) "2. Реферальная ссылка активирована" else "2. Активируйте реферальную ссылку"
                setTextColor(if (ref) Color.parseColor("#22C55E") else Color.WHITE)
                textSize = 16f; setTypeface(null, Typeface.BOLD)
            })
            if (!ref) {
                addView(TextView(this@MainActivity).apply {
                    text = "Перейдите по ссылке друга или отправьте свою ссылку другу:"
                    setTextColor(Color.parseColor("#A1A1AA")); textSize = 13f; setPadding(0, dp(6), 0, dp(6))
                })
                addView(btn("Скопировать мою реф-ссылку", "#FACC15", Color.BLACK) {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Ruroa", "ruroa://invite?ref=$code"))
                    Toast.makeText(this@MainActivity, "Ссылка скопирована!", Toast.LENGTH_SHORT).show()
                })
            }
        })
        setContentView(root)
    }

    private fun showMainScreen() {
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.parseColor("#121212")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = channelName; setTextColor(Color.parseColor("#38BDF8")); textSize = 22f
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16))
            setOnClickListener { openUrl(channelUrl) }
        })
        if (updateAvailable) {
            root.addView(LinearLayout(this).apply {
                background = GradientDrawable().apply { setColor(Color.parseColor("#1E3A8A")); cornerRadius = dp(12).toFloat() }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) }
                setOnClickListener { openUrl(channelUrl) }
                addView(TextView(this@MainActivity).apply {
                    text = "Доступна новая версия! Скачать в $channelName"; setTextColor(Color.WHITE); textSize = 13f
                })
            })
        }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val status = TextView(this).apply {
            text = if (isConnected) "Активен: Быстрый сервер #${currentServerIndex + 1}" else "Нажмите для запуска защиты"
            setTextColor(Color.GRAY); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(12))
        }
        center.addView(TextView(this).apply {
            text = if (isConnected) "ВКЛЮЧЕНО" else "ПОДКЛЮЧИТЬ"; setTextColor(Color.WHITE)
            textSize = 20f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor(if (isConnected) "#22C55E" else "#2563EB"))
            }
            setOnClickListener {
                if (!isConnected) {
                    val i = VpnService.prepare(this@MainActivity)
                    if (i != null) requestVpnPermission.launch(i) else startVpnTunnel()
                } else stopVpnTunnel()
            }
        })
        center.addView(status)
        center.addView(btn("Сменить сервер", "#1E293B", Color.WHITE) {
            if (serverList.isEmpty()) {
                Toast.makeText(this, "Серверы ещё загружаются...", Toast.LENGTH_SHORT).show()
            } else {
                currentServerIndex = (currentServerIndex + 1) % serverList.size
                status.text = if (isConnected) "Активен: Быстрый сервер #${currentServerIndex + 1}" else "Выбран: Быстрый сервер #${currentServerIndex + 1}"
                if (isConnected) { stopVpnTunnel(); startVpnTunnel() }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) }
            setPadding(dp(24), dp(10), dp(24), dp(10))
        })
        root.addView(center)

        if (bannerEnabled && bannerTitle.isNotEmpty()) {
            root.addView(card().apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) }
                setOnClickListener { openUrl(bannerLink) }
                addView(TextView(this@MainActivity).apply { text = "РЕКЛАМА"; setTextColor(Color.GRAY); textSize = 10f; setTypeface(null, Typeface.BOLD) })
                addView(TextView(this@MainActivity).apply { text = bannerTitle; setTextColor(Color.WHITE); textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, dp(4)) })
                if (bannerSubtitle.isNotEmpty()) addView(TextView(this@MainActivity).apply { text = bannerSubtitle; setTextColor(Color.parseColor("#A1A1AA")); textSize = 13f })
                addView(btn(bannerButton, "#38BDF8", Color.BLACK) { openUrl(bannerLink) })
            })
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun startVpnTunnel() {
        if (serverList.isEmpty()) { Toast.makeText(this, "Нет ключей с GitHub", Toast.LENGTH_SHORT).show(); return }
        try {
            AngConfigManager.importBatchConfig(serverList[currentServerIndex], "", false, "")
            val guids = MmkvManager.decodeServerList()
            if (guids.isNotEmpty()) MmkvManager.setSelectServer(guids.last())
            CoreServiceManager.startCoreService(this)
            isConnected = true
            showMainScreen()
        } catch (e: Exception) { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun stopVpnTunnel() {
        try { CoreServiceManager.stopCoreService(this) } catch (_: Exception) { }
        isConnected = false; showMainScreen()
    }

    private fun openUrl(url: String) {
        if (url.isNotEmpty()) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Color.parseColor("#1E1E1E")); cornerRadius = dp(16).toFloat() }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun btn(label: String, bg: String, tc: Int, click: () -> Unit) = Button(this).apply {
        text = label; setTextColor(tc); isAllCaps = false; setTypeface(null, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = dp(10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        setOnClickListener { click() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
