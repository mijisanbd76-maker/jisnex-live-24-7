package com.jisnex.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.library.rtmp.RtmpFromFile
import java.io.File

class StreamService : Service(), ConnectChecker, VideoDecoderInterface, AudioDecoderInterface {

    interface Listener {
        fun onState(msg: String, streaming: Boolean)
        fun onBitrate(kbps: Long)
        fun onLoop(n: Int)
    }

    companion object {
        @Volatile var listener: Listener? = null
        @Volatile var active = false
        @Volatile var startMs = 0L
        @Volatile var loops = 0
        const val ACTION_STOP = "stop"
        private const val CH = "jisnex_live"
    }

    private var stream: RtmpFromFile? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll("Live stopped")
            return START_NOT_STICKY
        }
        if (intent == null || active) return START_NOT_STICKY

        goForeground()
        active = true
        loops = 0
        startMs = SystemClock.elapsedRealtime()
        acquireLocks()

        val uri = Uri.parse(intent.getStringExtra("uri"))
        val url = intent.getStringExtra("url") ?: ""
        val key = intent.getStringExtra("key") ?: ""
        val bitrate = intent.getIntExtra("bitrate", 2_500_000)
        val loop = intent.getBooleanExtra("loop", true)

        Thread { begin(uri, url, key, bitrate, loop) }.start()
        return START_NOT_STICKY
    }

    private fun begin(uri: Uri, url: String, key: String, bitrate: Int, loop: Boolean) {
        try {
            listener?.onState("Preparing video...", true)
            val f = File(cacheDir, "source_video")
            contentResolver.openInputStream(uri).use { input ->
                f.outputStream().use { out -> input!!.copyTo(out) }
            }
            val s = RtmpFromFile(this, this, this)
            s.setLoopMode(loop)
            if (!s.prepareVideo(f.absolutePath, bitrate, 0)) {
                stopAll("Video prepare failed (unsupported file?)"); return
            }
            if (!s.prepareAudio(f.absolutePath, 128 * 1024)) {
                stopAll("Audio prepare failed"); return
            }
            stream = s
            s.startStream(url.trimEnd('/') + "/" + key)
        } catch (e: Exception) {
            stopAll("Error: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun stopAll(msg: String) {
        try {
            stream?.let { if (it.isStreaming) it.stopStream() }
        } catch (_: Exception) {}
        stream = null
        active = false
        releaseLocks()
        listener?.onState(msg, false)
        stopForeground(true)
        stopSelf()
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Live", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        val n = b.setContentTitle("JISNEX Live is streaming")
            .setContentText("Live is running")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jisnex:live").also { it.acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jisnex:wifi").also { it.acquire() }
    }

    private fun releaseLocks() {
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifi?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wake = null; wifi = null
    }

    override fun onDestroy() {
        if (active) stopAll("Live stopped")
        super.onDestroy()
    }

    // ---- RTMP callbacks ----
    override fun onConnectionStarted(url: String) { listener?.onState("Connecting...", true) }
    override fun onConnectionSuccess() { listener?.onState("LIVE", true) }
    override fun onConnectionFailed(reason: String) { stopAll("Connection failed: $reason") }
    override fun onNewBitrate(bitrate: Long) { listener?.onBitrate(bitrate / 1000) }
    override fun onDisconnect() { if (active) stopAll("Disconnected") }
    override fun onAuthError() { stopAll("Auth error (check stream key)") }
    override fun onAuthSuccess() {}

    // ---- decoder callbacks ----
    override fun onVideoDecoderFinished() { loops++; listener?.onLoop(loops) }
    override fun onAudioDecoderFinished() {}
}
