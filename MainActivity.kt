package com.example.offlinetalk

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider

class MainActivity : ComponentActivity() {

    private fun perms(): Array<String> {
        val l = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            l += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT)
        } else l += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.NEARBY_WIFI_DEVICES
        return l.toTypedArray()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Hub.init(this, Build.MODEL)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Screen() } } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Screen() {
        var tab by remember { mutableIntStateOf(0) }
        var text by remember { mutableStateOf("") }
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it.values.all { ok -> ok }) Hub.start()
        }
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let(Hub::sendFile)
        }

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopAppBar(title = { Column { Text("OfflineTalk"); Text(Hub.status.value, style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    TextButton(onClick = { permLauncher.launch(perms()) }) { Text("শুরু") }
                    TextButton(onClick = { Hub.stop() }) { Text("বন্ধ") }
                })
            TabRow(selectedTabIndex = tab) {
                listOf("ডিভাইস", "চ্যাট").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }

            if (tab == 0) {
                LazyColumn(Modifier.weight(1f).padding(12.dp)) {
                    items(Hub.found.entries.toList()) { e ->
                        val isConn = Hub.connected.containsKey(e.key)
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(e.value, Modifier.weight(1f))
                                if (isConn) Text("✔ সংযুক্ত") else Button(onClick = { Hub.connect(e.key) }) { Text("কানেক্ট") }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), reverseLayout = false) {
                    items(Hub.messages) { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                            Surface(shape = RoundedCornerShape(14.dp),
                                color = if (m.mine) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                onClick = { m.file?.let { openFile(it) } }) {
                                Column(Modifier.padding(10.dp)) {
                                    if (!m.mine) Text(m.from, style = MaterialTheme.typography.labelSmall)
                                    Text(m.text)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("📎") }
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("মেসেজ লিখুন") })
                    TextButton(onClick = { Hub.sendText(text); text = "" }) { Text("পাঠান") }
                }
            }

            // Push-to-talk বাটন: চেপে ধরে কথা বলুন
            val talking by Hub.talking
            Box(Modifier.fillMaxWidth().padding(12.dp).height(64.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = { Hub.startTalk(); tryAwaitRelease(); Hub.stopTalk() })
                }) {
                Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(32.dp),
                    color = if (talking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (talking) "🎙 বলছেন..." else "চেপে ধরে কথা বলুন")
                    }
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
