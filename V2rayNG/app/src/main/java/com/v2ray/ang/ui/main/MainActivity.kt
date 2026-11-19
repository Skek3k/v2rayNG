package com.ruroa.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.UUID

const val GITHUB_USER = "Skek3k"
const val PROTO = "https://"
const val GH_BASE = PROTO + "://githubusercontent.com" + GITHUB_USER + "/ruroa-core/main/"
const val SUB_URL = GH_BASE + "sub.txt"
const val CONFIG_URL = GH_BASE + "config.json"
const val TG_BASE = PROTO + "t.me/"
const val CURRENT_APP_VERSION = "1.0.0"

data class AppConfig(
    val updateAvailable: Boolean = false,
    val channelName: String = "Ruroa",
    val channelUrl: String = TG_BASE + "ruroa",
    val bannerEnabled: Boolean = true,
    val bannerTitle: String = "Подписывайся на Ruroa",
    val bannerSubtitle: String = "Свежие обновления и посты",
    val bannerButton: String = "Перейти",
    val bannerLink: String = TG_BASE + "ruroa"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("ruroa_prefs", Context.MODE_PRIVATE)

        var myRefCode = prefs.getString("my_ref_code", null)
        if (myRefCode == null) {
            myRefCode = UUID.randomUUID().toString().take(8).uppercase()
            prefs.edit().putString("my_ref_code", myRefCode).apply()
        }

        var isReferralDone = prefs.getBoolean("ref_activated", false)
        val incomingData: Uri? = intent?.data
        if (incomingData != null && incomingData.scheme == "ruroa" && incomingData.host == "invite") {
            val inviterCode = incomingData.getQueryParameter("ref")
            if (!inviterCode.isNullOrEmpty() && inviterCode != myRefCode && !isReferralDone) {
                prefs.edit()
                    .putBoolean("ref_activated", true)
                    .putString("invited_by", inviterCode)
                    .apply()
                isReferralDone = true
                Toast.makeText(this, "Реферальная ссылка засчитана!", Toast.LENGTH_LONG).show()
            }
        }

        val isSubDone = prefs.getBoolean("sub_activated", false)
        val safeRefCode = myRefCode ?: "RUROA001"

        setContent {
            var subCompleted by remember { mutableStateOf(isSubDone) }
            val refCompleted by remember { mutableStateOf(isReferralDone) }
            var config by remember { mutableStateOf(AppConfig()) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    try {
                        val jsonStr = URL(CONFIG_URL).readText()
                        val json = JSONObject(jsonStr)
                        val latestVer = json.optString("latest_version", CURRENT_APP_VERSION)
                        val channelTg = json.optString("channel_tg", "ruroa")
                        val banner = json.optJSONObject("banner")

                        var rawLink = banner?.optString("link", "t.me/ruroa") ?: "t.me/ruroa"
                        if (rawLink.isNotEmpty() && !rawLink.startsWith("http")) {
                            rawLink = PROTO + rawLink
                        }

                        config = AppConfig(
                            updateAvailable = latestVer != CURRENT_APP_VERSION,
                            channelName = json.optString("channel_name", "Ruroa"),
                            channelUrl = TG_BASE + channelTg,
                            bannerEnabled = banner?.optBoolean("enabled", true) ?: true,
                            bannerTitle = banner?.optString("title", "Подписывайся на Ruroa") ?: "Подписывайся на Ruroa",
                            bannerSubtitle = banner?.optString("subtitle", "") ?: "",
                            bannerButton = banner?.optString("button_text", "Перейти") ?: "Перейти",
                            bannerLink = rawLink
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            if (!subCompleted || !refCompleted) {
                LockScreen(
                    config = config,
                    myRefCode = safeRefCode,
                    isSubDone = subCompleted,
                    isRefDone = refCompleted,
                    onConfirmSub = {
                        prefs.edit().putBoolean("sub_activated", true).apply()
                        subCompleted = true
                    }
                )
            } else {
                RuroaMainScreen(
                    config = config,
                    onToggleVpn = { isConnected ->
                        // Здесь подключается запуск ядра из шаблона
                    },
                    onSwitchServer = {
                        // Здесь подключается переключение сервера
                    }
                )
            }
        }
    }
}

@Composable
fun LockScreen(
    config: AppConfig,
    myRefCode: String,
    isSubDone: Boolean,
    isRefDone: Boolean,
    onConfirmSub: () -> Unit
) {
    val context = LocalContext.current
    var clickedChannel by remember { mutableStateOf(false) }
    val myInviteLink = "ruroa://invite?ref=$myRefCode"

    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Доступ к ${config.channelName}",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Выполните 2 обязательных условия для запуска:",
            color = Color.Gray,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isSubDone) "1. Подписка на @ruroa выполнена" else "1. Подпишитесь на канал @ruroa",
                    color = if (isSubDone) Color(0xFF22C55E) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                if (!isSubDone) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            clickedChannel = true
                            openUrl(config.channelUrl)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                    ) {
                        Text("Открыть канал ${config.channelName}", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    if (clickedChannel) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onConfirmSub,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Я подписался, проверить", color = Color.White)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp)
        ) {
Column(modifier = Modifier.padding(16.dp)) {
Text(
text = if (isRefDone) "2. Реферальная ссылка активирована" else "2. Активируйте реферальную ссылку",
color = if (isRefDone) Color(0xFF22C55E) else Color.White,
fontWeight = FontWeight.Bold,
fontSize = 16.sp
)
if (!isRefDone) {
Spacer(modifier = Modifier.height(6.dp))
Text(
text = "Перейдите по ссылке друга или отправьте свою ссылку другу:",
color = Color(0xFFA1A1AA),
fontSize = 13.sp
)
Spacer(modifier = Modifier.height(10.dp))
Button(
onClick = {
val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
clipboard.setPrimaryClip(ClipData.newPlainText("Ruroa Invite", myInviteLink))
Toast.makeText(context, "Ссылка скопирована!", Toast.LENGTH_SHORT).show()
},
modifier = Modifier.fillMaxWidth(),
colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFACC15))
) {
Text("Скопировать мою реф-ссылку", color = Color.Black, fontWeight = FontWeight.Bold)
}
}
}
}
}
}
@Composable
fun RuroaMainScreen(
config: AppConfig,
onToggleVpn: (Boolean) -> Unit,
onSwitchServer: () -> Unit
) {
val context = LocalContext.current
var isConnected by remember { mutableStateOf(false) }
var serverIndex by remember { mutableIntStateOf(1) }
fun openUrl(url: String) {
if (url.isNotEmpty()) {
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
context.startActivity(intent)
}
}
Column(
modifier = Modifier
.fillMaxSize()
.background(Color(0xFF121212))
.padding(20.dp),
horizontalAlignment = Alignment.CenterHorizontally,
verticalArrangement = Arrangement.SpaceBetween
) {
Row(
modifier = Modifier.fillMaxWidth(),
horizontalArrangement = Arrangement.Center,
verticalAlignment = Alignment.CenterVertically
) {
TextButton(onClick = { openUrl(config.channelUrl) }) {
Text(
text = config.channelName,
color = Color(0xFF38BDF8),
fontWeight = FontWeight.ExtraBold,
fontSize = 22.sp
)
}
}
if (config.updateAvailable) {
Card(
modifier = Modifier
.fillMaxWidth()
.clickable { openUrl(config.channelUrl) },
colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3A8A))
) {
Text(
text = "Доступна новая версия! Скачать в ${config.channelName}",
color = Color.White,
modifier = Modifier.padding(12.dp),
fontSize = 13.sp
)
}
}
Column(horizontalAlignment = Alignment.CenterHorizontally) {
Box(
modifier = Modifier
.size(180.dp)
.clip(CircleShape)
.background(if (isConnected) Color(0xFF22C55E) else Color(0xFF2563EB))
.clickable {
isConnected = !isConnected
onToggleVpn(isConnected)
},
contentAlignment = Alignment.Center
) {
Text(
text = if (isConnected) "ВКЛЮЧЕНО" else "ПОДКЛЮЧИТЬ",
color = Color.White,
fontWeight = FontWeight.ExtraBold,
fontSize = 20.sp
)
}
Spacer(modifier = Modifier.height(16.dp))
Text(
text = if (isConnected) "Активен: Быстрый сервер #$serverIndex" else "Нажмите для запуска защиты",
color = Color.Gray,
fontSize = 14.sp
)
Spacer(modifier = Modifier.height(12.dp))
OutlinedButton(
onClick = {
serverIndex = if (serverIndex >= 30) 1 else serverIndex + 1
onSwitchServer()
},
shape = RoundedCornerShape(12.dp)
) {
Text(text = "Сменить сервер", color = Color.White)
}
}
if (config.bannerEnabled && config.bannerTitle.isNotEmpty()) {
Card(
modifier = Modifier
.fillMaxWidth()
.clickable { openUrl(config.bannerLink) },
shape = RoundedCornerShape(16.dp),
colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
) {
Column(modifier = Modifier.padding(16.dp)) {
Text(
text = "РЕКЛАМА",
color = Color.Gray,
fontSize = 10.sp,
fontWeight = FontWeight.Bold
)
Spacer(modifier = Modifier.height(4.dp))
Text(
text = config.bannerTitle,
color = Color.White,
fontWeight = FontWeight.Bold,
fontSize = 16.sp
)
if (config.bannerSubtitle.isNotEmpty()) {
Spacer(modifier = Modifier.height(4.dp))
Text(
text = config.bannerSubtitle,
color = Color(0xFFA1A1AA),
fontSize = 13.sp
)
}
Spacer(modifier = Modifier.height(12.dp))
Button(
onClick = { openUrl(config.bannerLink) },
modifier = Modifier.fillMaxWidth(),
colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
shape = RoundedCornerShape(10.dp)
) {
Text(
text = config.bannerButton,
color = Color.Black,
fontWeight = FontWeight.Bold
)
}
}
}
} else {
Spacer(modifier = Modifier.height(10.dp))
}
}
}
