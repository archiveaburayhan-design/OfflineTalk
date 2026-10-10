package com.example.offlinetalk

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File
import kotlin.concurrent.thread

data class Msg(val from: String, val text: String, val mine: Boolean, val file: File? = null)

/** সব নেটওয়ার্ক ও অডিও লজিক এক জায়গায় */
object Hub {
    private const val SERVICE = "com.example.offlinetalk"
    private const val RATE = 16000

    lateinit var app: Context
    lateinit var myName: String

    val messages = mutableStateListOf<Msg>()
    val found = mutableStateMapOf<String, String>()      // endpointId -> name
    val connected = mutableStateMapOf<String, String>()  // endpointId -> name
    val status = mutableStateOf("বন্ধ")
    val talking = mutableStateOf(false)

    private val client get() = Nearby.getConnectionsClient(app)
    private val pendingFiles = mutableMapOf<Long, String>() // payloadId -> filename
    private val incomingFiles = mutableMapOf<Long, Payload>()
    private var micThread: Thread? = null

    fun init(ctx: Context, name: String) { app = ctx.applicationContext; myName = name }

    // ---------- আবিষ্কার ও সংযোগ ----------
    fun start() {
    stop()
    val strategy = Strategy.P2P_CLUSTER
    client.startAdvertising(myName, SERVICE, lifecycle,
        AdvertisingOptions.Builder().setStrategy(strategy).build())
        .addOnFailureListener { status.value = "Advertise ত্রুটি: ${it.message}" }
    client.startDiscovery(SERVICE, object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, i: DiscoveredEndpointInfo) { found[id] = i.endpointName }
        override fun onEndpointLost(id: String) { found.remove(id) }
    }, DiscoveryOptions.Builder().setStrategy(strategy).build())
        .addOnSuccessListener { status.value = "খুঁজছে..." }
        .addOnFailureListener { status.value = "Discovery ত্রুটি: ${it.message}" }
    }
    fun startOld() {
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(myName, SERVICE, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
        client.startDiscovery(SERVICE, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(id: String, i: DiscoveredEndpointInfo) { found[id] = i.endpointName }
            override fun onEndpointLost(id: String) { found.remove(id) }
        }, DiscoveryOptions.Builder().setStrategy(strategy).build())
        status.value = "খুঁজছে..."
    }

    fun stop() {
        client.stopAllEndpoints(); client.stopAdvertising(); client.stopDiscovery()
        found.clear(); connected.clear(); status.value = "বন্ধ"
    }

    fun connect(id: String) { client.requestConnection(myName, id, lifecycle) }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            client.acceptConnection(id, payloadCb)          // অটো-একসেপ্ট (চাইলে ডায়ালগ দিন)
            found[id] = info.endpointName
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (r.status.isSuccess) { connected[id] = found[id] ?: id; status.value = "${connected.size} জন সংযুক্ত" }
        }
        override fun onDisconnected(id: String) {
            connected.remove(id); status.value = "${connected.size} জন সংযুক্ত"
        }
    }

    // ---------- রিসিভ ----------
    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            val who = connected[id] ?: id
            when (p.type) {
                Payload.Type.BYTES -> {
                    val s = String(p.asBytes()!!)
                    when {
                        s.startsWith("M:") -> messages.add(Msg(who, s.drop(2), false))
                        s.startsWith("F:") -> { val (pid, name) = s.drop(2).split("|", limit = 2); pendingFiles[pid.toLong()] = name }
                    }
                }
                Payload.Type.FILE -> incomingFiles[p.id] = p
                Payload.Type.STREAM -> playStream(p)
            }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {
            if (u.status != PayloadTransferUpdate.Status.SUCCESS) return
            val p = incomingFiles.remove(u.payloadId) ?: return
            val name = pendingFiles.remove(u.payloadId) ?: "file_${u.payloadId}"
            val dir = app.getExternalFilesDir("Received")!!.apply { mkdirs() }
            val dest = File(dir, name)
            p.asFile()!!.asUri()?.let { uri ->
                app.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            }
            messages.add(Msg(connected[id] ?: id, "📎 $name", false, dest))
        }
    }

    // ---------- মেসেজ ----------
    fun sendText(text: String) {
        if (text.isBlank() || connected.isEmpty()) return
        client.sendPayload(connected.keys.toList(), Payload.fromBytes("M:$text".toByteArray()))
        messages.add(Msg(myName, text, true))
    }

    // ---------- ফাইল (যেকোনো ধরন) ----------
    fun sendFile(uri: Uri) {
        if (connected.isEmpty()) return
        val cr = app.contentResolver
        val name = cr.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "file"
        val pfd = cr.openFileDescriptor(uri, "r") ?: return
        val payload = Payload.fromFile(pfd)
        val ids = connected.keys.toList()
        client.sendPayload(ids, Payload.fromBytes("F:${payload.id}|$name".toByteArray()))
        client.sendPayload(ids, payload)
        messages.add(Msg(myName, "📎 $name", true))
    }

    // ---------- ভয়েস (Push-to-talk) ----------
    @Suppress("MissingPermission")
    fun startTalk() {
        if (connected.isEmpty() || talking.value) return
        val pipe = ParcelFileDescriptor.createPipe()
        client.sendPayload(connected.keys.toList(), Payload.fromStream(pipe[0]))
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        talking.value = true
        micThread = thread {
            val buf = ByteArray(min)
            rec.startRecording()
            try { while (talking.value) { val n = rec.read(buf, 0, buf.size); if (n > 0) out.write(buf, 0, n) } }
            catch (_: Exception) {}
            finally { rec.stop(); rec.release(); try { out.close() } catch (_: Exception) {} }
        }
    }

    fun stopTalk() { talking.value = false }

    private fun playStream(p: Payload) {
        thread {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(min * 2).build()
            track.play()
            try {
                p.asStream()!!.asInputStream().use { ins ->
                    val buf = ByteArray(min)
                    while (true) { val n = ins.read(buf); if (n <= 0) break; track.write(buf, 0, n) }
                }
            } catch (_: Exception) {}
            track.stop(); track.release()
        }
    }
}
