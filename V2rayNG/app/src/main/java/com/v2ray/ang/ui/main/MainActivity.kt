package com.v2ray.ang.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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

    private fun getShareDownloadUrl(): String {
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
        buildMainScreen()
    }

    private fun configureSafeRouting() {
        try {
            MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, "https://1.1.1")
            MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, "223.5.5.5")
        } catch (_: Exception) {
        }
    }

    private fun loadCachedServers() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val cached = prefs.getString("cached_servers", null)
        if (!cached.isNullOrEmpty()) {
            val list = cached.lines().map { it.trim() }.filter { it.startsWith("vless://") }
            if (list.isNotEmpty()) serverList = list
        }
    }
    private fun buildMainScreen() {
        rootContainer.removeAllViews()
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 72, 48, 56)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        mainLayout.addView(TextView(this).apply {
            text = "RUROA"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        mainLayout.addView(TextView(this).apply {
            text = "Шифрование трафика и защита DNS"
            textSize = 13f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 8, 0, 8)
        })

        val statusText = TextView(this).apply {
            text = if (isConnected) "Статус: Защищено" else "Статус: Отключено"
            textSize = 15f
            setTextColor(if (isConnected) Color.parseColor("#4ADE80") else Color.parseColor("#9AA4B2"))
            setPadding(0, 16, 0, 64)
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
            layoutParams = LinearLayout.LayoutParams(420, 420).apply { bottomMargin = 56 }
            setOnClickListener {
                if (!isConnected) {
                    if (serverList.isEmpty()) {
                        Toast.makeText(context, "Обновляем список узлов...", Toast.LENGTH_SHORT).show()
                        fetchServers()
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

        mainLayout.addView(createStyledButton("Сменить узел", "#1E293B") {
            if (serverList.isNotEmpty()) {
                currentServerIndex = (currentServerIndex + 1) % serverList.size
                applySelectedServer()
                Toast.makeText(this, "Выбран узел #${currentServerIndex + 1}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Список узлов загружается...", Toast.LENGTH_SHORT).show()
                fetchServers()
            }
        })

        mainLayout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })

        mainLayout.addView(createStyledButton("Поделиться APK", "#181C23") {
            val shareText = "Ruroa — лёгкий клиент для шифрования трафика и защиты соединения в публичных Wi-Fi сетях.\n" +
                "Скачать APK: ${getShareDownloadUrl()}"
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(sendIntent, "Поделиться APK"))
        })

        rootContainer.addView(mainLayout)
        fetchServers()
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
        statusText.text = "Подбор стабильного узла..."
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
                statusText.text = "Статус: Защищено (Узел #${currentServerIndex + 1})"
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

    private fun fetchServers() {
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
                        break
                    }
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
