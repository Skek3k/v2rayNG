package com.v2ray.ang.ui.main

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    private data class ServerNode(
        val rawUri: String,
        val title: String,
        val pingMs: Int
    )

    private lateinit var btnConnect: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvServer: TextView
    private lateinit var btnSwitch: TextView
    private lateinit var btnShare: TextView
    private lateinit var btnRefreshList: TextView
    private lateinit var listContainer: LinearLayout

    private var isConnected = false
    private var isConnecting = false
    private var pendingReconnect = false
    private var refreshJob: Job? = null
    private var nodes: List<ServerNode> = emptyList()
    private var currentIndex = 0

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            launchSelectedNode()
        } else {
            isConnecting = false
            updateUiState()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val key = intent?.getIntExtra("key", 0) ?: return
            when (key) {
                AppConfig.MSG_STATE_RUNNING,
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    pendingReconnect = false
                    isConnected = true
                    isConnecting = false
                    updateUiState()
                }
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isConnected = false
                    if (pendingReconnect) {
                        pendingReconnect = false
                        startVpnFlow()
                        return
                    }
                    isConnecting = false
                    updateUiState()
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    pendingReconnect = false
                    isConnected = false
                    isConnecting = false
                    updateUiState()
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось запустить узел, попробуйте другой",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupRussianBypassOnce()
        ensureGeoAssets()
        buildUi()
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        refreshServers(autoConnect = false)
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(dp(20), dp(28), dp(20), dp(20))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "RUROA"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
        }
        root.addView(title)

        tvStatus = TextView(this).apply {
            text = "Отключено"
            setTextColor(Color.parseColor("#8B949E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(20))
        }
        root.addView(tvStatus)

        btnConnect = TextView(this).apply {
            text = "ПОДКЛЮЧИТЬ"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundBg("#1F6FEB", 16)
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener { onConnectClicked() }
        }
        root.addView(btnConnect, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        tvServer = TextView(this).apply {
            text = "Загрузка узлов..."
            setTextColor(Color.parseColor("#C9D1D9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(10))
        }
        root.addView(tvServer)

        val rowActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnSwitch = TextView(this).apply {
            text = "⇄ Сменить узел"
            setTextColor(Color.parseColor("#58A6FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            background = roundBg("#161B22", 10)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { switchNextNode() }
        }
        btnShare = TextView(this).apply {
            text = "Поделиться APK"
            setTextColor(Color.parseColor("#C9D1D9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            background = roundBg("#161B22", 10)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { shareApkFile() }
        }
        val lpBtn = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        rowActions.addView(btnSwitch, LinearLayout.LayoutParams(lpBtn).apply { marginEnd = dp(8) })
        rowActions.addView(btnShare, LinearLayout.LayoutParams(lpBtn).apply { marginStart = dp(8) })
        root.addView(rowActions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }
        val listHeader = TextView(this).apply {
            text = "Доступные серверы (по пингу)"
            setTextColor(Color.parseColor("#8B949E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        btnRefreshList = TextView(this).apply {
            text = "↻ Обновить"
            setTextColor(Color.parseColor("#58A6FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { refreshServers(autoConnect = false) }
        }
        headerRow.addView(listHeader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(btnRefreshList)
        root.addView(headerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        setContentView(root)
    }

    private fun onConnectClicked() {
        if (isConnecting) return
        if (isConnected) {
            stopVpnService()
            return
        }
        if (nodes.isEmpty()) {
            refreshServers(autoConnect = true)
            return
        }
        startVpnFlow()
    }

    private fun startVpnFlow() {
        if (nodes.getOrNull(currentIndex) == null) {
            isConnecting = false
            updateUiState()
            Toast.makeText(this, "Сначала выберите доступный узел", Toast.LENGTH_SHORT).show()
            return
        }
        isConnecting = true
        updateUiState()
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermLauncher.launch(intent)
        } else {
            launchSelectedNode()
        }
    }

    private fun launchSelectedNode() {
        val node = nodes.getOrNull(currentIndex)
        if (node == null) {
            isConnecting = false
            updateUiState()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                MmkvManager.removeAllServer()
                AngConfigManager.importBatchConfig(server = node.rawUri, subid = "", append = false)
                val uuids = MmkvManager.decodeServerList("")
                val guid = uuids.firstOrNull()
                if (guid == null || MmkvManager.decodeServerConfig(guid) == null) {
                    withContext(Dispatchers.Main) {
                        isConnecting = false
                        updateUiState()
                        Toast.makeText(this@MainActivity, "Узел не поддерживается, выберите другой", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                MmkvManager.setSelectServer(guid)
                val dohUrl = listOf("https://", "1.1.1.1", "/dns-query").joinToString("")
                MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, dohUrl)
                MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
                withContext(Dispatchers.Main) {
                    LauncherManager.startService(this@MainActivity, guid)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isConnecting = false
                    updateUiState()
                    Toast.makeText(this@MainActivity, "Сбой запуска: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun stopVpnService() {
        pendingReconnect = false
        isConnected = false
        isConnecting = false
        updateUiState()
        LauncherManager.stopService(this)
    }

    private fun reconnectToSelectedNode() {
        pendingReconnect = true
        isConnected = false
        isConnecting = true
        updateUiState()
        LauncherManager.stopService(this)
        lifecycleScope.launch {
            delay(1500)
            if (pendingReconnect) {
                pendingReconnect = false
                startVpnFlow()
            }
        }
    }

    private fun switchNextNode() {
        if (isConnecting) return
        if (nodes.isEmpty()) {
            refreshServers(autoConnect = isConnected)
            return
        }
        currentIndex = (currentIndex + 1) % nodes.size
        renderServerList()
        if (isConnected) {
            reconnectToSelectedNode()
        }
    }

    private fun refreshServers(autoConnect: Boolean) {
        refreshJob?.cancel()
        tvServer.text = "Поиск быстрых серверов..."
        btnRefreshList.isEnabled = false
        btnRefreshList.text = "Поиск..."

        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            val rawLines = fetchSubscriptionLines()
            val liveNodes = mutableListOf<ServerNode>()
            val chunks = rawLines.take(125).chunked(25)
            for (batch in chunks) {
                val batchRes = batch.map { line ->
                    async {
                        val ms = measureTcpPing(line)
                        if (ms in 1..1500) ServerNode(line, parseNodeTitle(line), ms) else null
                    }
                }.awaitAll().filterNotNull()
                liveNodes.addAll(batchRes)
                if (liveNodes.size >= 15) break
            }

            val sorted = liveNodes.sortedBy { it.pingMs }
            withContext(Dispatchers.Main) {
                btnRefreshList.isEnabled = true
                btnRefreshList.text = "↻ Обновить"
                val prevUri = nodes.getOrNull(currentIndex)?.rawUri
                nodes = sorted
                currentIndex = if (prevUri != null && !autoConnect) {
                    nodes.indexOfFirst { it.rawUri == prevUri }.takeIf { it >= 0 } ?: 0
                } else {
                    0
                }
                renderServerList()
                if (autoConnect && nodes.isNotEmpty()) {
                    startVpnFlow()
                } else if (nodes.isEmpty()) {
                    isConnecting = false
                    updateUiState()
                    tvServer.text = "Нет доступных узлов"
                }
            }
        }
    }

    private fun fetchSubscriptionLines(): List<String> {
        val prefs = getSharedPreferences("ruroa_cache", MODE_PRIVATE)
        val urls = listOf(RuroaNative.getPrimarySubUrl(), RuroaNative.getCdnSubUrl())
        for (u in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText().trim()
                    val lines = decodeSubscriptionBody(body)
                    if (lines.isNotEmpty()) {
                        prefs.edit().putString("sub_b64", body).apply()
                        return lines
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
        val cached = prefs.getString("sub_b64", null) ?: return emptyList()
        return decodeSubscriptionBody(cached)
    }

    private fun decodeSubscriptionBody(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        if (body.contains("vless://")) {
            return body.lines().map { it.trim() }.filter { it.startsWith("vless://") }.distinct()
        }
        val cleaned = body.replace("\n", "").replace("\r", "").trim()
        for (flags in listOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            try {
                val decoded = String(Base64.decode(cleaned, flags))
                val lines = decoded.lines().map { it.trim() }.filter { it.startsWith("vless://") }.distinct()
                if (lines.isNotEmpty()) return lines
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun extractHostPort(vlessUri: String): Pair<String, Int>? {
        val atPart = vlessUri.substringAfter("@", "").substringBefore("?").substringBefore("#").trim()
        if (atPart.isEmpty()) return null
        return if (atPart.startsWith("[")) {
            val closeIdx = atPart.indexOf(']')
            if (closeIdx <= 1) return null
            val host = atPart.substring(1, closeIdx)
            val portPart = atPart.substring(closeIdx + 1).removePrefix(":")
            host to (portPart.toIntOrNull() ?: 443)
        } else {
            val colonIdx = atPart.lastIndexOf(':')
            if (colonIdx > 0) {
                val host = atPart.substring(0, colonIdx)
                val port = atPart.substring(colonIdx + 1).toIntOrNull() ?: 443
                host to port
            } else {
                atPart to 443
            }
        }
    }

    private fun measureTcpPing(vlessUri: String): Int {
        return try {
            val (host, port) = extractHostPort(vlessUri) ?: return -1
            val start = System.currentTimeMillis()
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 1200)
            }
            (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        }
    }

    private fun parseNodeTitle(vlessUri: String): String {
        val tag = vlessUri.substringAfter("#", "").trim()
        val raw = if (tag.isNotEmpty()) {
            try {
                URLDecoder.decode(tag, "UTF-8")
            } catch (_: Exception) {
                tag
            }
        } else {
            extractHostPort(vlessUri)?.first ?: "RUROA"
        }
        return raw.replace(Regex("""\s*[•|-]?\s*\d+\s*m[sс]\b""", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun showNodeInfoDialog(node: ServerNode) {
        val uri = node.rawUri
        val hostPort = uri.substringAfter("@").substringBefore("?").substringBefore("#")
        val query = uri.substringAfter("?", "").substringBefore("#")
        val params = query.split("&").mapNotNull {
            val idx = it.indexOf('=')
            if (idx > 0) it.substring(0, idx) to it.substring(idx + 1) else null
        }.toMap()
        val security = params["security"] ?: "none"
        val type = params["type"] ?: "tcp"
        val sni = params["sni"] ?: params["host"] ?: "не указан"
        val fp = params["fp"] ?: "по умолчанию"
        val pbk = params["pbk"]
        val protoLabel = when {
            security.equals("reality", true) -> "VLESS + Reality ($type)"
            security.equals("tls", true) -> "VLESS + TLS ($type)"
            else -> "VLESS ($type / $security)"
        }
        val details = buildString {
            appendLine("Узел: ${node.title}")
            appendLine("Адрес: $hostPort")
            appendLine("Протокол: $protoLabel")
            appendLine("Маскировка (SNI): $sni")
            appendLine("Отпечаток (FP): $fp")
            if (!pbk.isNullOrEmpty()) {
                appendLine("Public Key: ${pbk.take(12)}...")
            }
            append("Пинг с устройства: ${node.pingMs} мс")
        }
        AlertDialog.Builder(this)
            .setTitle("Информация о ключе")
            .setMessage(details)
            .setPositiveButton("Скопировать ключ") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Ruroa VLESS", uri))
                Toast.makeText(this, "Ключ скопирован в буфер", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun renderServerList() {
        listContainer.removeAllViews()
        val active = nodes.getOrNull(currentIndex)
        tvServer.text = if (active != null) {
            "${active.title} • ${active.pingMs} мс"
        } else {
            "Сервер не выбран"
        }
        nodes.forEachIndexed { idx, node ->
            val selected = idx == currentIndex
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundBg(if (selected) "#1F6FEB" else "#161B22", 10)
                setPadding(dp(14), dp(10), dp(10), dp(10))
                setOnClickListener {
                    if (isConnecting || currentIndex == idx) return@setOnClickListener
                    currentIndex = idx
                    renderServerList()
                    if (isConnected) {
                        reconnectToSelectedNode()
                    }
                }
            }
            val tvName = TextView(this).apply {
                text = node.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val tvPing = TextView(this).apply {
                text = "${node.pingMs} мс"
                setTextColor(
                    when {
                        selected -> Color.WHITE
                        node.pingMs < 120 -> Color.parseColor("#3FB950")
                        node.pingMs < 250 -> Color.parseColor("#D29922")
                        else -> Color.parseColor("#F85149")
                    }
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, dp(8), 0)
            }
            val btnInfo = TextView(this).apply {
                text = "ⓘ"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                background = roundBg(if (selected) "#1158C7" else "#21262D", 8)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { showNodeInfoDialog(node) }
            }
            item.addView(tvName, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            item.addView(tvPing)
            item.addView(btnInfo)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            listContainer.addView(item, lp)
        }
    }

    private fun updateUiState() {
        when {
            isConnecting -> {
                btnConnect.text = "ПОДКЛЮЧЕНИЕ..."
                btnConnect.background = roundBg("#9E6A03", 16)
                tvStatus.text = "Установка защищённого соединения..."
            }
            isConnected -> {
                btnConnect.text = "ОТКЛЮЧИТЬ"
                btnConnect.background = roundBg("#238636", 16)
                tvStatus.text = "Защищено • DoH 1.1.1.1"
            }
            else -> {
                btnConnect.text = "ПОДКЛЮЧИТЬ"
                btnConnect.background = roundBg("#1F6FEB", 16)
                tvStatus.text = "Отключено"
            }
        }
    }

    private fun ensureGeoAssets() {
        lifecycleScope.launch(Dispatchers.IO) {
            listOf("geoip.dat", "geosite.dat").forEach { name ->
                try {
                    val outFile = File(filesDir, name)
                    if (!outFile.exists()) {
                        assets.open(name).use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setupRussianBypassOnce() {
        val prefs = getSharedPreferences("ruroa_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("bypass_init_v2", false)) return
        val bypassPkgs = mutableSetOf(
            "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.vtb24.mobilebanking.android",
            "ru.alfabank.mobile.android", "ru.gazprombank.android.mobilebank.app",
            "ru.raiffeisennews", "ru.ozon.app.android", "com.wildberries.ru",
            "ru.rostel", "ru.gosuslugi.pos", "ru.yandex.taxi", "ru.beru.android"
        )
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, bypassPkgs)
        prefs.edit().putBoolean("bypass_init_v2", true).apply()
    }

    private fun shareApkFile() {
        try {
            val srcPath = applicationInfo.publicSourceDir ?: applicationInfo.sourceDir
            val srcApk = File(srcPath)
            if (!srcApk.exists()) {
                Toast.makeText(this, "Файл установки не найден", Toast.LENGTH_SHORT).show()
                return
            }
            val outApk = File(cacheDir, "Ruroa.apk")
            srcApk.inputStream().use { input ->
                outApk.outputStream().use { output -> input.copyTo(output) }
            }
            val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.cache", outApk)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "Отправить Ruroa.apk"))
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось отправить APK: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun roundBg(hex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hex))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
