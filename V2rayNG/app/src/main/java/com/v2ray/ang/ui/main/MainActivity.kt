package com.v2ray.ang.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.ui.BaseActivity
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MainActivity : BaseActivity() {

    val mainViewModel: MainViewModel by viewModels()

    private lateinit var btnConnect: TextView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var btnSwitchServer: TextView

    private var isConnecting = false
    private var currentServerIndex = 0
    private var watchdogJob: Job? = null
    private val failedIndices = mutableSetOf<Int>()

    private val primaryUrls by lazy {
        listOf(
            RuroaNative.getPrimarySubUrl(),
            RuroaNative.getCdnSubUrl()
        )
    }

    private val ruBypassPackages = mutableSetOf(
        "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.alfabank.mobile.android",
        "ru.vtb24.mobilebanking.android", "ru.gazprombank.android.mobilebank.app",
        "ru.raiffeisennews", "ru.sovcomcard.halva.v1", "ru.psbank.mobile",
        "ru.rostel", "ru.gosuslugi.pos", "ru.nalog.lkfl", "ru.russianpost.client.android",
        "ru.ozon.app.android", "com.wildberries.ru", "ru.beru.android",
        "ru.yandex.taxi", "ru.yandex.searchplugin", "ru.yandex.yandexmaps",
        "com.vkontakte.android", "ru.ok.android", "ru.avito", "ru.kinopoisk"
    )

    private val requestVpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            connectWithAutoServer()
        } else {
            isConnecting = false
            updateUiState(false)
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RuroaNative.verifyAppIntegrity(this)) {
            finishAffinity()
            return
        }
        setupMinimalUi()
        setupViewModel()
        if (!MmkvManager.decodeSettingsBool("ruroa_bypass_init", false)) {
            MmkvManager.encodeSettings("ruroa_bypass_init", true)
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true)
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, ruBypassPackages)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
// === КОНЕЦ ЧАСТИ 1 ===
    @SuppressLint("SetTextI18n")
    private fun setupMinimalUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val centerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val tvLogo = TextView(this).apply {
            text = "RUROA"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.25f
            gravity = Gravity.CENTER
        }
        centerBox.addView(tvLogo)

        val btnSize = dp(186)
        btnConnect = TextView(this).apply {
            text = "ПОДКЛЮЧИТЬ"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createCircleDrawable("#161B22", "#30363D", dp(3))
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                topMargin = dp(42)
                bottomMargin = dp(28)
            }
            setOnClickListener { onConnectClicked() }
        }
        centerBox.addView(btnConnect)

        tvStatusTitle = TextView(this).apply {
            text = "Защита отключена"
            setTextColor(Color.parseColor("#8B949E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        centerBox.addView(tvStatusTitle)

        tvStatusSub = TextView(this).apply {
            text = "Нажмите кнопку для безопасного соединения"
            setTextColor(Color.parseColor("#6E7681"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        centerBox.addView(tvStatusSub)

        btnSwitchServer = TextView(this).apply {
            text = "↻ Сменить узел"
            setTextColor(Color.parseColor("#58A6FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = createRoundRectDrawable("#161B22", "#30363D", dp(1), dp(20))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22) }
            setOnClickListener { switchToNextServer() }
        }
        centerBox.addView(btnSwitchServer)

        root.addView(centerBox)

        val btnShare = TextView(this).apply {
            text = "Поделиться APK"
            setTextColor(Color.parseColor("#8B949E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = createRoundRectDrawable("#161B22", "#21262D", dp(1), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(28) }
            setOnClickListener { shareApkFile() }
        }
        root.addView(btnShare)

        setContentView(root)
    }
// === КОНЕЦ ЧАСТИ 2 ===
    private fun setupViewModel() {
        mainViewModel.isRunning.observe(this) { running ->
            if (!isConnecting) {
                updateUiState(running)
            }
            if (running) startWatchdog() else watchdogJob?.cancel()
        }
        mainViewModel.startListenBroadcast()
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(15_000)
            var fails = 0
            while (isActive && mainViewModel.isRunning.value == true) {
                if (!isConnecting) {
                    val ok = try {
                        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 10808))
                        val url = URL(listOf("http://", "www.gstatic", ".com/generate_204").joinToString(""))
                        val conn = (url.openConnection(proxy) as HttpURLConnection).apply {
                            connectTimeout = 4500
                            readTimeout = 4500
                            instanceFollowRedirects = false
                        }
                        val code = conn.responseCode
                        conn.disconnect()
                        code == 204 || code == 200
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) {
                        fails = 0
                    } else if (++fails >= 2) {
                        fails = 0
                        withContext(Dispatchers.Main) {
                            if (mainViewModel.isRunning.value == true && !isConnecting) {
                                failedIndices.add(currentServerIndex)
                                switchToNextServer()
                            }
                        }
                    }
                }
                delay(35_000)
            }
        }
    }

    private fun onConnectClicked() {
        if (isConnecting) return

        if (mainViewModel.isRunning.value == true) {
            watchdogJob?.cancel()
            V2RayServiceManager.stopV2Ray(this)
            updateUiState(false)
            return
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            requestVpnPermission.launch(vpnIntent)
        } else {
            connectWithAutoServer()
        }
    }

    private fun switchToNextServer() {
        if (isConnecting) return
        val serverList = MmkvManager.decodeServerList("")
        if (serverList.isEmpty()) {
            connectWithAutoServer()
            return
        }
        failedIndices.add(currentServerIndex)
        currentServerIndex = (currentServerIndex + 1) % serverList.size
        connectWithAutoServer(useExistingList = true)
    }
// === КОНЕЦ ЧАСТИ 3 ===
    @SuppressLint("SetTextI18n")
    private fun connectWithAutoServer(useExistingList: Boolean = false) {
        isConnecting = true
        btnConnect.text = "ПОИСК..."
        btnConnect.background = createCircleDrawable("#1F2937", "#D29922", dp(3))
        tvStatusTitle.text = "Подбор лучшего узла"
        tvStatusTitle.setTextColor(Color.parseColor("#D29922"))
        tvStatusSub.text = "Проверка доступных каналов..."
        btnSwitchServer.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!useExistingList) {
                    failedIndices.clear()
                    val rawSub = fetchSubscriptionWithFallback()
                    if (!rawSub.isNullOrBlank()) {
                        MmkvManager.removeAllServer()
                        AngConfigManager.importBatchConfig(rawSub, "", false)
                        currentServerIndex = 0
                    }
                }

                val serverList = MmkvManager.decodeServerList("")
                if (serverList.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isConnecting = false
                        updateUiState(false)
                        tvStatusSub.text = "Узлы недоступны. Проверьте интернет"
                    }
                    return@launch
                }

                var selectedGuid: String? = null
                var selectedName = "Оптимальный узел"
                val total = serverList.size

                for (i in 0 until total) {
                    val idx = (currentServerIndex + i) % total
                    if (idx in failedIndices && failedIndices.size < total) continue
                    val guid = serverList[idx]
                    val config = MmkvManager.decodeServerConfig(guid) ?: continue
                    if (isServerAlive(config)) {
                        currentServerIndex = idx
                        selectedGuid = guid
                        selectedName = config.remarks.ifBlank { "Узел #${idx + 1}" }
                        break
                    }
                }

                if (selectedGuid == null) {
                    selectedGuid = serverList[0]
                    selectedName = MmkvManager.decodeServerConfig(selectedGuid)?.remarks ?: "Резервный узел"
                }

                MmkvManager.setSelectServer(selectedGuid)
                MmkvManager.encodeSettings(AppConfig.PREF_REMOTE_DNS, "https://1.1.1")
                MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, "223.5.5.5")

                withContext(Dispatchers.Main) {
                    if (mainViewModel.isRunning.value == true) {
                        V2RayServiceManager.restartV2Ray(this@MainActivity)
                    } else {
                        V2RayServiceManager.startV2Ray(this@MainActivity)
                    }
                    delay(1200)
                    isConnecting = false
                    updateUiState(true, selectedName)
                }
            } catch (_: CancellationException) {
                isConnecting = false
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isConnecting = false
                    updateUiState(false)
                    tvStatusSub.text = "Сбой соединения. Попробуйте ещё раз"
                }
            }
        }
    }

    private fun isServerAlive(config: ProfileItem): Boolean {
        if (config.configType == EConfigType.CUSTOM) return true
        val host = config.server ?: return false
        val port = config.serverPort?.toIntOrNull() ?: return false
        val sni = config.sni?.takeIf { it.isNotBlank() } ?: host
        val isTls = config.security.equals("reality", true) || config.security.equals("tls", true)
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), 1400)
                if (!isTls) return true
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(sock, host, port, true) as SSLSocket).use { ssl ->
                    ssl.soTimeout = 1400
                    try {
                        val params = ssl.sslParameters
                        params.serverNames = listOf(SNIHostName(sni))
                        ssl.sslParameters = params
                    } catch (_: Exception) {
                    }
                    ssl.startHandshake()
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchSubscriptionWithFallback(): String? {
        for (url in primaryUrls) {
            val content = downloadText(url)
            if (!content.isNullOrBlank()) {
                val decoded = tryDecodeBase64(content)
                if (decoded.contains("vless://", ignoreCase = true)) {
                    MmkvManager.encodeSettings("ruroa_cached_sub", decoded)
                    return decoded
                }
            }
        }
        return MmkvManager.decodeSettingsString("ruroa_cached_sub")
    }

    private fun downloadText(urlStr: String): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("User-Agent", "Ruroa/1.1")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText().trim() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryDecodeBase64(raw: String): String {
        val clean = raw.trim()
        if (clean.contains("vless://")) return clean
        return try {
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            clean
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUiState(connected: Boolean, serverName: String? = null) {
        if (connected) {
            btnConnect.text = "ОТКЛЮЧИТЬ"
            btnConnect.background = createCircleDrawable("#0F291E", "#238636", dp(3))
            tvStatusTitle.text = "Защита активна"
            tvStatusTitle.setTextColor(Color.parseColor("#3FB950"))
            val activeName = serverName
                ?: MmkvManager.decodeServerConfig(MmkvManager.getSelectServer() ?: "")?.remarks
                ?: "Оптимальный маршрут"
            tvStatusSub.text = activeName
            tvStatusSub.maxLines = 1
            tvStatusSub.ellipsize = TextUtils.TruncateAt.END
            btnSwitchServer.visibility = View.VISIBLE
        } else {
            btnConnect.text = "ПОДКЛЮЧИТЬ"
            btnConnect.background = createCircleDrawable("#161B22", "#30363D", dp(3))
            tvStatusTitle.text = "Защита отключена"
            tvStatusTitle.setTextColor(Color.parseColor("#8B949E"))
            tvStatusSub.text = "Нажмите кнопку для безопасного соединения"
            btnSwitchServer.visibility = View.GONE
        }
    }

    private fun shareApkFile() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val outApk = File(cacheDir, "Ruroa.apk")
                File(applicationInfo.sourceDir).copyTo(outApk, overwrite = true)
                val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.cache", outApk)
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Поделиться Ruroa.apk"))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun createCircleDrawable(fillHex: String, strokeHex: String, strokePx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            color = ColorStateList.valueOf(Color.parseColor(fillHex))
            setStroke(strokePx, Color.parseColor(strokeHex))
        }
    }

    private fun createRoundRectDrawable(fillHex: String, strokeHex: String, strokePx: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            color = ColorStateList.valueOf(Color.parseColor(fillHex))
            setStroke(strokePx, Color.parseColor(strokeHex))
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
