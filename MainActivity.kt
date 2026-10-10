package com.example.offlinetalk

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider

private val Bg = Color(0xFF0A0F1E)
private val Surf = Color(0xFF131B2F)
private val Surf2 = Color(0xFF1E2A47)
private val C1 = Color(0xFF22D3EE)
private val C2 = Color(0xFF6366F1)
private val Green = Color(0xFF22C55E)
private val Amber = Color(0xFFF59E0B)
private val Red = Color(0xFFEF4444)
private val Slate = Color(0xFF64748B)
private val TextMain = Color(0xFFF1F5F9)
private val TextSub = Color(0xFF94A3B8)
private val Grad = Brush.linearGradient(listOf(C1, C2))

class MainActivity : ComponentActivity() {

    private fun perms(): Array<String> {
        val l = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            l += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT <= 32) l += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.NEARBY_WIFI_DEVICES
        return l.toTypedArray()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        Hub.init(this, Build.MODEL)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C1, background = Bg, surface = Surf)) { App() }
        }
    }

    @Composable
    private fun App() {
        var tab by remember { mutableIntStateOf(0) }
        var running by remember { mutableStateOf(false) }
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val denied = res.filterValues { ok -> !ok }.keys
            if (denied.isEmpty()) {
                running = true
                Hub.start()
            } else {
                running = false
                Hub.status.value = "অনুমতি নেই: " + denied.joinToString { n -> n.substringAfterLast('.') }
            }
        }
        Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
            Header(running) {
                if (running) { Hub.stop(); running = false } else permLauncher.launch(perms())
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> DevicesTab(running)
                    1 -> ChatTab()
                    else -> TalkTab()
                }
            }
            BottomBar(tab) { tab = it }
        }
    }

    @Composable
    private fun ChatTab() {
        var text by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val count = Hub.messages.size
        LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let(Hub::sendFile)
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (count == 0) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("💬", fontSize = 52.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("এখনো কোনো মেসেজ নেই", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("ডিভাইস ট্যাব থেকে কানেক্ট করে চ্যাট শুরু করুন",
                            color = TextSub, fontSize = 14.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp))
                    }
                } else {
                    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(Hub.messages) { m -> Bubble(m) { m.file?.let { f -> openFile(f) } } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RoundIcon("📎", Surf2) { filePicker.launch(arrayOf("*/*")) }
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(Surf)
                    .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = text, onValueChange = { text = it }, maxLines = 4,
                        textStyle = TextStyle(color = TextMain, fontSize = 16.sp),
                        cursorBrush = SolidColor(C1),
                        modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) Text("মেসেজ লিখুন…", color = TextSub, fontSize = 16.sp)
                                inner()
                            }
                        }
                    )
                }
                Spacer(Modifier.width(8.dp))
                RoundIcon("➤", null) {
                    if (Hub.connected.isNotEmpty() && text.isNotBlank()) { Hub.sendText(text); text = "" }
                }
            }
        }
    }

    private fun openFile(f: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "খুলুন"))
    }
}

@Composable
private fun Header(running: Boolean, onToggle: () -> Unit) {
    val status by Hub.status
    val dot = when {
        Hub.connected.isNotEmpty() -> Green
        running -> Amber
        else -> Slate
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("FCommun", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(status, color = TextSub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (running) PillButton("■  বন্ধ", false, onToggle) else PillButton("▶  শুরু", true, onToggle)
    }
}

@Composable
private fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .then(if (filled) Modifier.background(Grad)
                  else Modifier.background(Red.copy(alpha = 0.15f)).border(1.dp, Red, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (filled) Color.White else Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun BottomBar(sel: Int, onSel: (Int) -> Unit) {
    val items = listOf("📡" to "ডিভাইস", "💬" to "চ্যাট", "🎙️" to "টক")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
        .clip(RoundedCornerShape(28.dp)).background(Surf).padding(6.dp)) {
        items.forEachIndexed { i, item ->
            val on = sel == i
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
                    .then(if (on) Modifier.background(Grad) else Modifier)
                    .clickable { onSel(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(item.first, fontSize = 20.sp)
                Text(item.second, color = if (on) Color.White else TextSub, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DevicesTab(running: Boolean) {
    val list = Hub.found.entries.toList()
    if (list.isEmpty()) {
        Radar(running)
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.key }) { e ->
                DeviceCard(e.value, Hub.connected.containsKey(e.key)) { Hub.connect(e.key) }
            }
        }
    }
}

@Composable
private fun Radar(running: Boolean) {
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p"
    )
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
            if (running) {
                Canvas(Modifier.fillMaxSize()) {
                    for (k in 0..2) {
                        val q = (p + k / 3f) % 1f
                        drawCircle(C1.copy(alpha = (1f - q) * 0.5f),
                            radius = size.minDimension / 2 * q, style = Stroke(3.dp.toPx()))
                    }
                }
            }
            Box(Modifier.size(88.dp).clip(CircleShape).background(Grad), contentAlignment = Alignment.Center) {
                Text("📡", fontSize = 38.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(if (running) "কাছের ডিভাইস খোঁজা হচ্ছে…" else "শুরু করতে প্রস্তুত",
            color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(if (running) "অন্য ফোনেও অ্যাপ খুলে শুরু চাপুন" else "দুই ফোনেই ওপরের ▶ শুরু বাটন চাপুন",
            color = TextSub, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp))
    }
}

@Composable
private fun DeviceCard(name: String, connected: Boolean, onConnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surf).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Grad), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (connected) "সংযুক্ত" else "কাছাকাছি",
                color = if (connected) Green else TextSub, fontSize = 12.sp)
        }
        if (connected) Text("✔", color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        else PillButton("কানেক্ট", true, onConnect)
    }
}

@Composable
private fun Bubble(m: Msg, onOpen: () -> Unit) {
    val mine = m.mine
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 4.dp, bottomEnd = if (mine) 4.dp else 20.dp
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .then(if (mine) Modifier.background(Grad) else Modifier.background(Surf2))
                .then(if (m.file != null) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (!mine) Text(m.from, color = C1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(m.text, color = Color.White, fontSize = 16.sp)
            if (!mine && m.file != null) Text("খুলতে ট্যাপ করুন", color = TextSub, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RoundIcon(icon: String, bg: Color?, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .then(if (bg == null) Modifier.background(Grad) else Modifier.background(bg))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(icon, fontSize = 20.sp, color = Color.White) }
}

@Composable
private fun TalkTab() {
    val talking by Hub.talking
    val n = Hub.connected.size
    val t = rememberInfiniteTransition(label = "talk")
    val pulse by t.animateFloat(
        initialValue = 1f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse"
    )
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(if (n == 0) "কোনো ডিভাইস সংযুক্ত নেই" else "$n জন সংযুক্ত",
            color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(if (n == 0) "আগে ডিভাইস ট্যাব থেকে কানেক্ট করুন" else "চেপে ধরে কথা বলুন, ছাড়লেই শেষ",
            color = TextSub, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp))
        Spacer(Modifier.height(40.dp))
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            if (talking) {
                Box(Modifier.size(150.dp).scale(pulse).clip(CircleShape).background(Red.copy(alpha = 0.25f)))
            }
            Box(
                Modifier.size(150.dp).clip(CircleShape)
                    .background(if (talking) Brush.linearGradient(listOf(Red, Color(0xFFF97316))) else Grad)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = { Hub.startTalk(); tryAwaitRelease(); Hub.stopTalk() })
                    },
                contentAlignment = Alignment.Center
            ) { Text("🎙️", fontSize = 60.sp) }
        }
        Spacer(Modifier.height(24.dp))
        Text(if (talking) "বলছেন…" else "কথা বলতে ধরে রাখুন",
            color = if (talking) Red else TextMain, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
