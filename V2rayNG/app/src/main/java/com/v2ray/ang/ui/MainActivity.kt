package com.v2ray.ang.ui

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
import com.v2ray.ang.AppConfig as V2AppConfig
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
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
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnTunnel()
        } else {
            Toast.makeText(this, "Разрешите VPN-подключение в системе", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val prefs = getSharedPreferences("ruroa_prefs", Context.MODE_PRIVATE)
        var myRefCode = prefs.getString("my_ref_code", null)
        if (myRefCode == null) {
            myRefCode = UUID.randomUUID().toString().take(8).uppercase()
            prefs.edit().putString("my_ref_code", myRefCode).apply()
        }

        var isRefDone = prefs.getBoolean("ref_activated", false)
        val incomingData: Uri? = intent?.data
        if (incomingData != null && incomingData.scheme == "ruroa" && incomingData.host == "invite") {
            val inviterCode = incomingData.getQueryParameter("ref")
            if (!inviterCode.isNullOrEmpty() && inviterCode != myRefCode && !isRefDone) {
                prefs.edit()
                    .putBoolean("ref_activated", true)
                    .putString("invited_by", inviterCode)
                    .apply()
                isRefDone = true
                Toast.makeText(this, "Реферальная ссылка засчитана!", Toast.LENGTH_LONG).show()
            }
        }

        val isSubDone = prefs.getBoolean("sub_activated", false)
        renderCurrentScreen(prefs, myRefCode ?: "RUROA001", isSubDone, isRefDone)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = URL(CONFIG_URL).readText()
                val json = JSONObject(jsonStr)
                val latestVer = json.optString("latest_version", CURRENT_APP_VERSION)
                val channelTg = json.optString("channel_tg", "ruroa")
                val banner = json.optJSONObject("banner")

                var rawLink = banner?.optString("link", "t.me/ruroa") ?: "t.me/ruroa"
                if (rawLink.isNotEmpty() && !rawLink.startsWith("http")) {
                    rawLink = "https://$rawLink"
                }

                updateAvailable = latestVer != CURRENT_APP_VERSION
                channelName = json.optString("channel_name", "Ruroa")
                channelUrl = TG_BASE + channelTg
                bannerEnabled = banner?.optBoolean("enabled", true) ?: true
                bannerTitle = banner?.optString("title", "Подписывайся на Ruroa") ?: "Подписывайся на Ruroa"
                bannerSubtitle = banner?.optString("subtitle", "") ?: ""
                bannerButton = banner?.optString("button_text", "Перейти") ?: "Перейти"
                bannerLink = rawLink
            } catch (_: Exception) {
            }

            try {
                val rawKeys = URL(SUB_URL).readText()
                val parsed = rawKeys.lines().map { it.trim() }.filter { it.startsWith("vless://") }
                if (parsed.isNotEmpty()) {
                    serverList = parsed
                }
            } catch (_: Exception) {
            }

            withContext(Dispatchers.Main) {
                renderCurrentScreen(
                    prefs,
                    myRefCode ?: "RUROA001",
                    prefs.getBoolean("sub_activated", false),
                    prefs.getBoolean("ref_activated", false)
                )
            }
        }
    }

    private fun renderCurrentScreen(
        prefs: android.content.SharedPreferences,
        myRefCode: String,
        isSubDone: Boolean,
        isRefDone: Boolean
    ) {
        if (!isSubDone || !isRefDone) {
            showLockScreen(prefs, myRefCode, isSubDone, isRefDone)
        } else {
            showMainScreen()
        }
    }

    private fun showLockScreen(
        prefs: android.content.SharedPreferences,
        myRefCode: String,
        isSubDone: Boolean,
        isRefDone: Boolean
    ) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "Доступ к $channelName"
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Выполните 2 обязательных условия для запуска:"
            setTextColor(Color.GRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        }
        root.addView(subtitle)

        // Карточка 1: Подписка
        val card1 = createCardLayout().apply {
            val step1Title = TextView(this@MainActivity).apply {
                text = if (isSubDone) "1. Подписка на @ruroa выполнена" else "1. Подпишитесь на канал @ruroa"
                setTextColor(if (isSubDone) Color.parseColor("#22C55E") else Color.WHITE)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
            }
            addView(step1Title)

            if (!isSubDone) {
                val openBtn = createStyledButton("Открыть канал $channelName", "#38BDF8", Color.BLACK).apply {
                    setOnClickListener {
                        clickedChannel = true
                        openUrl(channelUrl)
                        renderCurrentScreen(prefs, myRefCode, false, isRefDone)
                    }
                }
                addView(openBtn)

                if (clickedChannel) {
                    val confirmBtn = createStyledButton("Я подписался, проверить", "#334155", Color.WHITE).apply {
                        setOnClickListener {
                            prefs.edit().putBoolean("sub_activated", true).apply()
                            renderCurrentScreen(prefs, myRefCode, true, isRefDone)
                        }
                    }
                    addView(confirmBtn)
                }
            }
        }
        root.addView(card1)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16))
        }
        root.addView(spacer)

        // Карточка 2: Рефералка
        val card2 = createCardLayout().apply {
            val step2Title = TextView(this@MainActivity).apply {
                text = if (isRefDone) "2. Реферальная ссылка активирована" else "2. Активируйте реферальную ссылку"
                setTextColor(if (isRefDone) Color.parseColor("#22C55E") else Color.WHITE)
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
            }
            addView(step2Title)

            if (!isRefDone) {
                val desc = TextView(this@MainActivity).apply {
                    text = "Перейдите по ссылке друга или отправьте свою ссылку другу:"
                    setTextColor(Color.parseColor("#A1A1AA"))
                    textSize = 13f
                    setPadding(0, dp(6), 0, dp(6))
                }
                addView(desc)

val inviteLink = "ruroa://invite?ref=$myRefCode"
val copyBtn = createStyledButton("Скопировать мою реф-ссылку", "#FACC15", Color.BLACK).apply {
setOnClickListener {
val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
clipboard.setPrimaryClip(ClipData.newPlainText("Ruroa Invite", inviteLink))
Toast.makeText(this@MainActivity, "Ссылка скопирована!", Toast.LENGTH_SHORT).show()
}
}
addView(copyBtn)
}
}
root.addView(card2)
setContentView(root)
}
private fun showMainScreen() {
val scroll = ScrollView(this).apply {
isFillViewport = true
setBackgroundColor(Color.parseColor("#121212"))
}
val root = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
gravity = Gravity.CENTER_HORIZONTAL
setPadding(dp(20), dp(24), dp(20), dp(24))
}
// Заголовок канала
val header = TextView(this).apply {
text = channelName
setTextColor(Color.parseColor("#38BDF8"))
textSize = 22f
setTypeface(null, Typeface.BOLD)
gravity = Gravity.CENTER
setPadding(0, dp(8), 0, dp(16))
setOnClickListener { openUrl(channelUrl) }
}
root.addView(header)
// Плашка обновления
if (updateAvailable) {
val updateCard = LinearLayout(this).apply {
background = GradientDrawable().apply {
setColor(Color.parseColor("#1E3A8A"))
cornerRadius = dp(12).toFloat()
}
setPadding(dp(14), dp(12), dp(14), dp(12))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { bottomMargin = dp(20) }
setOnClickListener { openUrl(channelUrl) }
addView(TextView(this@MainActivity).apply {
text = "Доступна новая версия! Скачать в $channelName"
setTextColor(Color.WHITE)
textSize = 13f
})
}
root.addView(updateCard)
}
// Центральный блок с круглой кнопкой
val centerBox = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
gravity = Gravity.CENTER
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
0,
1f
)
}
val statusText = TextView(this).apply {
text = if (isConnected) "Активен: Быстрый сервер #${currentServerIndex + 1}" else "Нажмите для запуска защиты"
setTextColor(Color.GRAY)
textSize = 14f
gravity = Gravity.CENTER
setPadding(0, dp(16), 0, dp(12))
}
val powerBtn = TextView(this).apply {
text = if (isConnected) "ВКЛЮЧЕНО" else "ПОДКЛЮЧИТЬ"
setTextColor(Color.WHITE)
textSize = 20f
setTypeface(null, Typeface.BOLD)
gravity = Gravity.CENTER
layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
background = GradientDrawable().apply {
shape = GradientDrawable.OVAL
setColor(Color.parseColor(if (isConnected) "#22C55E" else "#2563EB"))
}
setOnClickListener {
if (!isConnected) {
val intent = VpnService.prepare(this@MainActivity)
if (intent != null) {
requestVpnPermission.launch(intent)
} else {
startVpnTunnel()
}
} else {
stopVpnTunnel()
}
}
}
centerBox.addView(powerBtn)
centerBox.addView(statusText)
val switchBtn = createStyledButton("Сменить сервер", "#1E293B", Color.WHITE).apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.WRAP_CONTENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(8) }
setPadding(dp(24), dp(10), dp(24), dp(10))
setOnClickListener {
if (serverList.isEmpty()) {
Toast.makeText(this@MainActivity, "Список серверов ещё загружается...", Toast.LENGTH_SHORT).show()
return@setOnClickListener
}
currentServerIndex = (currentServerIndex + 1) % serverList.size
statusText.text = if (isConnected) {
"Активен: Быстрый сервер #${currentServerIndex + 1}"
} else {
"Выбран: Быстрый сервер #${currentServerIndex + 1}"
}
if (isConnected) {
stopVpnTunnel()
startVpnTunnel()
}
}
}
centerBox.addView(switchBtn)
root.addView(centerBox)
// Нижний рекламный баннер из config.json
if (bannerEnabled && bannerTitle.isNotEmpty()) {
val bannerCard = createCardLayout().apply {
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(20) }
setOnClickListener { openUrl(bannerLink) }
addView(TextView(this@MainActivity).apply {
text = "РЕКЛАМА"
setTextColor(Color.GRAY)
textSize = 10f
setTypeface(null, Typeface.BOLD)
})
addView(TextView(this@MainActivity).apply {
text = bannerTitle
setTextColor(Color.WHITE)
textSize = 16f
setTypeface(null, Typeface.BOLD)
setPadding(0, dp(4), 0, dp(4))
})
if (bannerSubtitle.isNotEmpty()) {
addView(TextView(this@MainActivity).apply {
text = bannerSubtitle
setTextColor(Color.parseColor("#A1A1AA"))
textSize = 13f
})
}
val actionBtn = createStyledButton(bannerButton, "#38BDF8", Color.BLACK).apply {
setOnClickListener { openUrl(bannerLink) }
}
addView(actionBtn)
}
root.addView(bannerCard)
}
scroll.addView(root)
setContentView(scroll)
}
private fun startVpnTunnel() {
if (serverList.isEmpty()) {
Toast.makeText(this, "Не удалось загрузить ключи с GitHub", Toast.LENGTH_SHORT).show()
return
}
val key = serverList[currentServerIndex]
try {
AngConfigManager.importBatchConfig(key, "", false)
val guids = MmkvManager.decodeServerList()
if (guids.isNotEmpty()) {
MmkvManager.setSelectServer(guids.last())
}
V2RayServiceManager.startVService(this)
isConnected = true
showMainScreen()
} catch (e: Exception) {
Toast.makeText(this, "Ошибка запуска: ${e.message}", Toast.LENGTH_SHORT).show()
}
}
private fun stopVpnTunnel() {
try {
V2RayServiceManager.stopVService(this)
} catch (_: Exception) {
}
isConnected = false
showMainScreen()
}
private fun openUrl(url: String) {
if (url.isNotEmpty()) {
try {
startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
} catch (_: Exception) {
}
}
}
private fun createCardLayout(): LinearLayout {
return LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
background = GradientDrawable().apply {
setColor(Color.parseColor("#1E1E1E"))
cornerRadius = dp(16).toFloat()
}
setPadding(dp(16), dp(16), dp(16), dp(16))
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
)
}
}
private fun createStyledButton(label: String, bgHex: String, textColor: Int): Button {
return Button(this).apply {
text = label
setTextColor(textColor)
isAllCaps = false
setTypeface(null, Typeface.BOLD)
background = GradientDrawable().apply {
setColor(Color.parseColor(bgHex))
cornerRadius = dp(10).toFloat()
}
layoutParams = LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT
).apply { topMargin = dp(12) }
}
}
private fun dp(value: Int): Int {
return (value * resources.displayMetrics.density).toInt()
}
}
