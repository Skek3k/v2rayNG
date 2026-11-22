package com.v2ray.ang.ui.main

import android.app.AlertDialog
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
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    private val prefsName = "ruroa_prefs"
    private lateinit var rootContainer: LinearLayout
    private var serverList: List<String> = emptyList()
    private var currentServerIndex = 0
    private var isConnected = false
    private var nodeInfoText: TextView? = null

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

    private fun getServerDisplayName(index: Int): String {
        if (serverList.isEmpty() || index !in serverList.indices) return "Узел не выбран"
        val rawTag = serverList[index].substringAfter("#", "")
        if (rawTag.isEmpty()) return "Узел #${index + 1}"
        return try {
            URLDecoder.decode(rawTag, "UTF-8")
        } catch (_: Exception) {
            rawTag
        }
    }

    private fun updateNodeBanner() {
        nodeInfoText?.text = if (serverList.isEmpty()) {
            "Загрузка списка узлов..."
        } else {
            "Текущий узел: ${getServerDisplayName(currentServerIndex)}"
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
            setPadding(0, 16, 0, 52)
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
            layoutParams = LinearLayout.LayoutParams(420, 420).apply { bottomMargin = 44 }
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
                    stopVpnService(this, statusText)
                }
            }
        }
        mainLayout.addView(connectBtn)

        val currentNodeCard = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181C23"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#2A313D"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
            setOnClickListener { showServerPickerDialog(connectBtn, statusText) }
        }
        nodeInfoText = currentNodeCard
        updateNodeBanner()
        mainLayout.addView(currentNodeCard)

        mainLayout.addView(createStyledButton("Выбрать узел из списка", "#1E293B") {
            showServerPickerDialog(connectBtn, statusText)
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

    private fun showServerPickerDialog(connectBtn: Button, statusText: TextView) {
        if (serverList.isEmpty()) {
            Toast.makeText(this, "Список узлов ещё загружается...", Toast.LENGTH_SHORT).show()
            fetchServers()
            return
        }
        val items = serverList.indices.map { i ->
            "${i + 1}. ${getServerDisplayName(i)}"
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Выберите узел (${serverList.size})")
            .setSingleChoiceItems(items, currentServerIndex) { dialog, which ->
                currentServerIndex = which
                updateNodeBanner()
                applySelectedServer()
                dialog.dismiss()
                if (isConnected) {
                    stopVpnService(connectBtn, statusText)
                    startVpnDirect(connectBtn, statusText)
                } else {
                    Toast.makeText(this, "Выбрано: ${getServerDisplayName(which)}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    private fun startVpnDirect(btn: Button, statusText: TextView) {
        applySelectedServer()
        val intent = Intent(this, CoreVpnService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isConnected = true
        btn.text = "ОТКЛЮЧИТЬ"
        (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#DC2626"))
        statusText.text = "Статус: Защищено"
        statusText.setTextColor(Color.parseColor("#4ADE80"))
        updateNodeBanner()
    }

    private fun stopVpnService(btn: Button, statusText: TextView) {
        sendBroadcast(Intent(AppConfig.BROADCAST_ACTION_SERVICE).apply {
            `package` = packageName
            putExtra("key", AppConfig.MSG_STATE_STOP)
        })
        isConnected = false
        btn.text = "ПОДКЛЮЧИТЬ"
        (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#2563EB"))
        statusText.text = "Статус: Отключено"
        statusText.setTextColor(Color.parseColor("#9AA4B2"))
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
                startVpnDirect(btn, statusText)
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
                        withContext(Dispatchers.Main) {
                            updateNodeBanner()
                        }
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
