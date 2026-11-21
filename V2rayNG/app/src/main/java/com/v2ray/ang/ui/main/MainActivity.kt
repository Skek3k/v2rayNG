package com.v2ray.ang.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.service.CoreVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val prefsName = "ruroa_prefs"
    private lateinit var rootContainer: LinearLayout
    private var serverList: List<String> = emptyList()
    private var currentServerIndex = 0
    private var isConnected = false

    private fun getBaseRawUrl(): String {
        val host = listOf("raw", "githubusercontent", "com").joinToString(".")
        return listOf("https:/", host, "Skek3k", "ruroa-core", "main").joinToString("/")
    }

    private fun getMirrorCdnUrl(): String {
        val host = listOf("cdn", "jsdelivr", "net").joinToString(".")
        return listOf("https:/", host, "gh", "Skek3k", "ruroa-core@main").joinToString("/")
    }

    private fun getTelegramUrl(): String {
        return try {
            RuroaNative.getChannelUrl()
        } catch (_: Exception) {
            val host = listOf("t", "me").joinToString(".")
            listOf("https:/", host, "ruroa").joinToString("/")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        RuroaNative.initGuard(this)
        super.onCreate(savedInstanceState)
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)
        configureSafeRouting()
        loadCachedServers()
        ensureMyReferralCode()
        renderScreen()
    }

    private fun configureSafeRouting() {
        try {
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_MAINLAND, true)
            MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, "https://1.1.1")
            MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, "223.5.5.5")
        } catch (_: Exception) {
        }
    }

    private fun ensureMyReferralCode(): String {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        var code = prefs.getString("my_ref_code", null)
        if (code.isNullOrEmpty() || code.length != 8 || !RuroaNative.validateReferralCode(code, "")) {
            code = RuroaNative.generateSignedCode()
            prefs.edit().putString("my_ref_code", code).apply()
        }
        return code
    }

    private fun loadCachedServers() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val cached = prefs.getString("cached_servers", null)
        if (!cached.isNullOrEmpty()) {
            val list = cached.lines().map { it.trim() }.filter { it.startsWith("vless://") }
            if (list.isNotEmpty()) serverList = list
        }
    }

    private fun renderScreen() {
        rootContainer.removeAllViews()
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val unlocked = prefs.getBoolean("tg_subscribed", false) &&
            prefs.getBoolean("ref_activated", false)
        if (unlocked) buildMainVpnScreen() else buildLockScreen()
    }
    private fun buildLockScreen() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val myCode = ensureMyReferralCode()
        val tgDone = prefs.getBoolean("tg_subscribed", false)
        val refDone = prefs.getBoolean("ref_activated", false)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        content.addView(TextView(this).apply {
            text = "Доступ к Ruroa VPN"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        content.addView(TextView(this).apply {
            text = "Выполните 2 шага для бесплатного доступа:"
            textSize = 14f
            setTextColor(Color.parseColor("#9AA4B2"))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 40)
        })

        content.addView(TextView(this).apply {
            text = if (tgDone) "✅ Шаг 1: Подписка оформлена" else "1. Подпишитесь на канал @ruroa"
            textSize = 16f
            setTextColor(if (tgDone) Color.parseColor("#4ADE80") else Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 16)
        })

        content.addView(createStyledButton("Перейти в Telegram-канал", "#2563EB") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getTelegramUrl())))
            } catch (_: Exception) {
                Toast.makeText(this, "Не удалось открыть Telegram", Toast.LENGTH_SHORT).show()
            }
            prefs.edit().putBoolean("tg_subscribed", true).apply()
            renderScreen()
        })

        content.addView(TextView(this).apply {
            text = if (refDone) "✅ Шаг 2: Код активирован" else "2. Пригласите друга или введите его код"
            textSize = 16f
            setTextColor(if (refDone) Color.parseColor("#4ADE80") else Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 48, 0, 12)
        })

        content.addView(TextView(this).apply {
            text = "Ваш код: $myCode (нажмите, чтобы скопировать)"
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, 20)
            setOnClickListener {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Ruroa Code", myCode))
                Toast.makeText(this@MainActivity, "Код $myCode скопирован", Toast.LENGTH_SHORT).show()
            }
        })

        content.addView(createStyledButton("Поделиться с другом", "#1E293B") {
            val shareText = "Попробуй бесплатный VPN Ruroa!\n" +
                "Канал: ${getTelegramUrl()}\n" +
                "Мой код активации: $myCode"
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(sendIntent, "Поделиться"))
        })

        val codeInput = EditText(this).apply {
            hint = "Введите 8-значный код друга"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(8), InputFilter.AllCaps())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181C23"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#334155"))
            }
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }
        content.addView(codeInput)

        val activateBtn = createStyledButton("Активировать", "#16A34A") {
            val entered = codeInput.text.toString().trim().uppercase()
            when {
                entered.length != 8 ->
                    Toast.makeText(this, "Нужно ровно 8 символов", Toast.LENGTH_SHORT).show()
                entered == myCode ->
                    Toast.makeText(this, "Нельзя ввести свой собственный код", Toast.LENGTH_SHORT).show()
                !RuroaNative.validateReferralCode(entered, myCode) ->
                    Toast.makeText(this, "Неверный или несуществующий код", Toast.LENGTH_SHORT).show()
                else -> {
                    prefs.edit().putBoolean("ref_activated", true).apply()
                    Toast.makeText(this, "Доступ открыт!", Toast.LENGTH_SHORT).show()
                    renderScreen()
                }
            }
        }
        (activateBtn.layoutParams as LinearLayout.LayoutParams).topMargin = 16
        content.addView(activateBtn)

        scroll.addView(content)
        rootContainer.addView(scroll)
    }
    private fun buildMainVpnScreen() {
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        mainLayout.addView(TextView(this).apply {
            text = "RUROA VPN"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        val statusText = TextView(this).apply {
            text = if (isConnected) "Статус: Подключено" else "Статус: Отключено"
            textSize = 15f
            setTextColor(if (isConnected) Color.parseColor("#4ADE80") else Color.parseColor("#9AA4B2"))
            setPadding(0, 12, 0, 60)
        }
        mainLayout.addView(statusText)

        val connectBtn = Button(this).apply {
            text = if (isConnected) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isConnected) Color.parseColor("#DC2626") else Color.parseColor("#2563EB"))
            }
            layoutParams = LinearLayout.LayoutParams(420, 420).apply { bottomMargin = 48 }
            setOnClickListener {
                if (!isConnected) {
                    if (serverList.isEmpty()) {
                        Toast.makeText(context, "Загружаем ключи...", Toast.LENGTH_SHORT).show()
                        fetchServersAndConfig(null)
                        return@setOnClickListener
                    }
                    val vpnIntent = VpnService.prepare(this@MainActivity)
                    if (vpnIntent != null) {
                        startActivityForResult(vpnIntent, 1001)
                        return@setOnClickListener
                    }
                    connectWithAutoServer(this, statusText)
                } else {
                    sendBroadcast(Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
                        `package` = packageName
                        putExtra("key", AppConfig.MSG_STATE_STOP)
                    })
                    isConnected = false
                    text = "ПОДКЛЮЧИТЬ"
                    (background as? GradientDrawable)?.setColor(Color.parseColor("#2563EB"))
                    statusText.text = "Статус: Отключено"
                    statusText.setTextColor(Color.parseColor("#9AA4B2"))
                }
            }
        }
        mainLayout.addView(connectBtn)

        mainLayout.addView(createStyledButton("Сменить сервер", "#1E293B") {
            if (serverList.isNotEmpty()) {
                currentServerIndex = (currentServerIndex + 1) % serverList.size
                applySelectedServer()
                Toast.makeText(this, "Сервер #${currentServerIndex + 1}", Toast.LENGTH_SHORT).show()
            }
        })

        mainLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })

        val bannerText = TextView(this).apply {
            text = "Загрузка..."
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181C23"))
                cornerRadius = 20f
            }
        }
        mainLayout.addView(bannerText)
        rootContainer.addView(mainLayout)
        fetchServersAndConfig(bannerText)
    }

    private fun isServerAlive(vlessUri: String): Boolean {
        return try {
            val afterAt = vlessUri.substringAfter("@", "")
            val hostPort = afterAt.substringBefore("?").substringBefore("#")
            val host = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":", "443").toIntOrNull() ?: 443
            if (host.isEmpty()) return false
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun connectWithAutoServer(btn: Button, statusText: TextView) {
        btn.isEnabled = false
        statusText.text = "Подбор лучшего сервера..."
        CoroutineScope(Dispatchers.IO).launch {
            var chosenIndex = currentServerIndex
            val total = serverList.size
            for (step in 0 until minOf(total, 10)) {
                val idx = (currentServerIndex + step) % total
                if (isServerAlive(serverList[idx])) {
                    chosenIndex = idx
                    break
                }
            }
            currentServerIndex = chosenIndex
            withContext(Dispatchers.Main) {
                btn.isEnabled = true
                applySelectedServer()
                val intent = Intent(this@MainActivity, CoreVpnService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                isConnected = true
                btn.text = "ОТКЛЮЧИТЬ"
                (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#DC2626"))
                statusText.text = "Статус: Подключено (Сервер #${currentServerIndex + 1})"
                statusText.setTextColor(Color.parseColor("#4ADE80"))
            }
        }
    }

    private fun applySelectedServer() {
        if (serverList.isEmpty()) return
        AngConfigManager.importBatchConfig(server = serverList[currentServerIndex], subid = "", append = false)
        val uuids = MmkvManager.decodeServerList("")
        if (uuids.isNotEmpty()) MmkvManager.setSelectServer(uuids.first())
    }

    private fun decodeSubscription(raw: String): String {
        val clean = raw.trim()
        if (clean.contains("vless://")) return clean
        return try {
            String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            clean
        }
    }

    private fun fetchServersAndConfig(bannerView: TextView?) {
        CoroutineScope(Dispatchers.IO).launch {
            val bases = listOf(getBaseRawUrl(), getMirrorCdnUrl())
            for (base in bases) {
                try {
                    val rawSub = URL(listOf(base, "sub.txt").joinToString("/")).readText()
                    val decodedSub = decodeSubscription(rawSub)
                    val parsed = decodedSub.lines()
                        .map { it.trim() }
                        .filter { it.startsWith("vless://") && !it.contains("allowInsecure=1", ignoreCase = true) }

                    if (parsed.isNotEmpty()) {
                        serverList = parsed
                        getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                            .edit()
                            .putString("cached_servers", parsed.joinToString("\n"))
                            .apply()
                    }

                    val json = JSONObject(URL(listOf(base, "config.json").joinToString("/")).readText())
                    val adText = json.optString("banner_text", "Наш Telegram: @ruroa")
                    val adLink = json.optString("banner_url", getTelegramUrl())

                    withContext(Dispatchers.Main) {
                        bannerView?.text = adText
                        bannerView?.setOnClickListener {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(adLink)))
                        }
                    }
                    break
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun createStyledButton(label: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(24, 28, 24, 28)
            setOnClickListener { onClick() }
        }
    }
}
