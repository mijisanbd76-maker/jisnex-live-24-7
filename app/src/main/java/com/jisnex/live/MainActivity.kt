package com.jisnex.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), StreamService.Listener {

    data class Fmt(val name: String, val w: Int, val h: Int, val url: String)

    private val YT = "rtmp://a.rtmp.youtube.com/live2"
    private val FB = "rtmps://live-api-s.facebook.com:443/rtmp/"
    private val fmts = listOf(
        Fmt("YouTube Shorts", 9, 16, YT),
        Fmt("YouTube Live (16:9)", 16, 9, YT),
        Fmt("Facebook Live", 9, 16, FB),
        Fmt("Square Feed (1:1)", 1, 1, YT),
        Fmt("Classic (4:3)", 4, 3, YT),
        Fmt("Custom RTMP", 16, 9, "")
    )
    private val quals = listOf("480p SD" to 1_200_000, "720p HD" to 2_500_000, "1080p FHD" to 4_500_000)

    private val BG = Color.parseColor("#0B0F17")
    private val CARD = Color.parseColor("#121826")
    private val LINE = Color.parseColor("#1E293B")
    private val ACC = Color.parseColor("#FF4B33")
    private val GREEN = Color.parseColor("#10B981")
    private val MUTED = Color.parseColor("#94A3B8")

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    private var videoUri: Uri? = null
    private var fmtIdx = 1
    private var qualIdx = 1
    private var loopOn = true
    private var streaming = false

    private lateinit var previewTitle: TextView
    private lateinit var selectBtn: TextView
    private lateinit var previewBox: FrameLayout
    private lateinit var placeholder: LinearLayout
    private lateinit var videoView: VideoView
    private lateinit var urlEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var eye: TextView
    private lateinit var loopSwitch: SwitchCompat
    private lateinit var startBtn: TextView
    private lateinit var statsBox: LinearLayout
    private lateinit var speedTv: TextView
    private lateinit var loopTv: TextView
    private lateinit var statusTv: TextView
    private val fmtChips = ArrayList<TextView>()
    private val qualChips = ArrayList<TextView>()
    private lateinit var micBtn: TextView
    private lateinit var vidBtn: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            videoUri = uri
            showPreview(uri)
        }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { doStart() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, r: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(r).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("jisnex", Context.MODE_PRIVATE)
        fmtIdx = prefs.getInt("fmt", 1)
        qualIdx = prefs.getInt("qual", 1)
        loopOn = prefs.getBoolean("loop", true)

        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(28))
        }
        scroll.addView(root)

        // header
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "JISNEX Live"; setTextColor(Color.WHITE); textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(header, lp(bottom = 12))

        // preview card
        val c1 = card()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        previewTitle = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, Typeface.BOLD) }
        top.addView(previewTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectBtn = pill("Select Video", Color.parseColor("#38BDF8")) { if (!streaming) picker.launch(arrayOf("video/*")) }
        top.addView(selectBtn)
        c1.addView(top, lp(bottom = 10))

        previewBox = FrameLayout(this).apply { background = bg(Color.BLACK, 12, LINE) }
        placeholder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        placeholder.addView(TextView(this).apply { text = "▶"; setTextColor(ACC); textSize = 40f; gravity = Gravity.CENTER })
        placeholder.addView(TextView(this).apply { text = "Tap to Select Video File"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER })
        placeholder.addView(TextView(this).apply { text = "Supports MP4, MKV, MOV (Full HD & 4K)"; setTextColor(MUTED); textSize = 11f; gravity = Gravity.CENTER })
        previewBox.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        videoView = VideoView(this).apply { visibility = View.GONE }
        previewBox.addView(videoView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        previewBox.setOnClickListener { if (!streaming) picker.launch(arrayOf("video/*")) }
        c1.addView(previewBox, LinearLayout.LayoutParams(-1, dp(220)))
        root.addView(c1)

        // audio card
        val c2 = card()
        c2.addView(title("Audio Mixer & Narration"))
        c2.addView(sub("Independent controls for microphone voiceover and original video sound"))
        micBtn = toggleBtn("Mic: OFF", false)
        vidBtn = toggleBtn("Video: ON", true)
        micBtn.setOnClickListener { Toast.makeText(this, "Mic voiceover পরের ধাপে চালু হবে", Toast.LENGTH_SHORT).show() }
        vidBtn.setOnClickListener { Toast.makeText(this, "Video sound এখন সবসময় ON (পরের ধাপে বন্ধ করা যাবে)", Toast.LENGTH_SHORT).show() }
        c2.addView(row(micBtn, vidBtn), lp(top = 10))
        root.addView(c2)

        // destination card
        val c3 = card()
        c3.addView(title("Live Stream Destination & Ratio"))
        c3.addView(sub("Tap any format to set RTMP destination & ratio"))
        for (i in fmts.indices) fmtChips.add(chip(fmts[i].name) { if (!streaming) selectFmt(i) })
        c3.addView(row(fmtChips[0], fmtChips[1]), lp(top = 10))
        c3.addView(row(fmtChips[2], fmtChips[3]))
        c3.addView(row(fmtChips[4], fmtChips[5]))
        urlEdit = field("RTMP Server URL", false)
        c3.addView(urlEdit, lp(top = 8))
        keyEdit = field("Stream Key", true)
        eye = TextView(this).apply { text = "👁"; textSize = 18f; setPadding(dp(12), 0, dp(12), 0); gravity = Gravity.CENTER }
        eye.setOnClickListener {
            val hidden = keyEdit.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            keyEdit.inputType = InputType.TYPE_CLASS_TEXT or (if (hidden) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            keyEdit.setSelection(keyEdit.text.length)
        }
        val keyRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = bg(BG, 10, LINE) }
        keyEdit.background = null
        keyRow.addView(keyEdit, LinearLayout.LayoutParams(0, -2, 1f))
        keyRow.addView(eye)
        c3.addView(keyRow, lp(top = 8))
        root.addView(c3)

        // quality + loop card
        val c4 = card()
        c4.addView(title("Stream Quality"))
        for (i in quals.indices) qualChips.add(chip(quals[i].first) { if (!streaming) selectQual(i) })
        c4.addView(row(qualChips[0], qualChips[1], qualChips[2]), lp(top = 8))
        val lr = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val lt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lt.addView(TextView(this).apply { text = "Continuous Video Loop (24/7)"; setTextColor(Color.WHITE); textSize = 13f; setTypeface(null, Typeface.BOLD) })
        lt.addView(sub("Keeps video looping forever until you manually stop the broadcast"))
        lr.addView(lt, LinearLayout.LayoutParams(0, -2, 1f))
        loopSwitch = SwitchCompat(this).apply { isChecked = loopOn }
        loopSwitch.setOnCheckedChangeListener { _, v -> loopOn = v }
        lr.addView(loopSwitch)
        c4.addView(lr, lp(top = 14))
        root.addView(c4)

        // start button
        startBtn = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 14f
            setTypeface(null, Typeface.BOLD); setPadding(0, dp(16), 0, dp(16))
        }
        startBtn.setOnClickListener { if (streaming) stopLive() else onStartTap() }
        root.addView(startBtn, lp(top = 4))

        // stats
        statsBox = LinearLayout(this).apply { background = bg(Color.parseColor("#0F2A2A"), 12, GREEN); setPadding(dp(8), dp(10), dp(8), dp(10)); visibility = View.GONE }
        speedTv = stat("Speed", "0 kbps"); loopTv = stat("Loops", "0"); statusTv = stat("Status", "-")
        statsBox.addView(speedTv.parent as View, LinearLayout.LayoutParams(0, -2, 1f))
        statsBox.addView(loopTv.parent as View, LinearLayout.LayoutParams(0, -2, 1f))
        statsBox.addView(statusTv.parent as View, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(statsBox, lp(top = 10))

        setContentView(scroll)

        urlEdit.setText(prefs.getString("url", YT))
        keyEdit.setText(prefs.getString("key", ""))
        selectFmt(fmtIdx, load = true)
        selectQual(qualIdx)
        applyState(StreamService.active)
    }

    // ---------- UI helpers ----------
    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(CARD, 14, LINE)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = lp(bottom = 12)
    }

    private fun title(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, Typeface.BOLD) }
    private fun sub(t: String) = TextView(this).apply { text = t; setTextColor(MUTED); textSize = 11f }

    private fun pill(t: String, color: Int, click: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(color); textSize = 12f; setTypeface(null, Typeface.BOLD)
        background = bg(Color.parseColor("#0C2236"), 8, color)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { click() }
    }

    private fun chip(t: String, click: () -> Unit) = TextView(this).apply {
        text = t; textSize = 12f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        setPadding(dp(6), dp(11), dp(6), dp(11))
        setOnClickListener { click() }
    }

    private fun toggleBtn(t: String, on: Boolean) = TextView(this).apply {
        text = t; gravity = Gravity.CENTER; textSize = 13f; setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(13), 0, dp(13))
        if (on) { setTextColor(GREEN); background = bg(Color.parseColor("#0B2A22"), 10, GREEN) }
        else { setTextColor(MUTED); background = bg(BG, 10, LINE) }
    }

    private fun row(vararg v: View): LinearLayout {
        val r = LinearLayout(this)
        for (x in v) r.addView(x, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        return r
    }

    private fun field(hint: String, pass: Boolean) = EditText(this).apply {
        this.hint = hint; setHintTextColor(MUTED); setTextColor(Color.WHITE); textSize = 14f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or (if (pass) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
        background = bg(BG, 10, LINE)
        setPadding(dp(12), dp(13), dp(12), dp(13))
    }

    private fun stat(label: String, value: String): TextView {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        box.addView(TextView(this).apply { text = label; setTextColor(MUTED); textSize = 11f; gravity = Gravity.CENTER })
        val v = TextView(this).apply { text = value; setTextColor(GREEN); textSize = 13f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER }
        box.addView(v)
        return v
    }

    private fun styleChip(c: TextView, sel: Boolean) {
        c.background = if (sel) bg(ACC, 10) else bg(Color.parseColor("#1A2233"), 10, LINE)
        c.setTextColor(if (sel) Color.WHITE else MUTED)
    }

    private fun selectFmt(i: Int, load: Boolean = false) {
        fmtIdx = i
        prefs.edit().putInt("fmt", i).apply()
        for (k in fmtChips.indices) styleChip(fmtChips[k], k == i)
        val f = fmts[i]
        if (!load && f.url.isNotEmpty()) urlEdit.setText(f.url)
        previewTitle.text = "Preview (${f.w}:${f.h})"
        val sw = resources.displayMetrics.widthPixels - dp(28 + 28)
        val h = minOf(sw * f.h / f.w, dp(460))
        previewBox.layoutParams.height = h
        previewBox.requestLayout()
        if (videoUri != null) previewBox.post { fitVideo() }
    }

    private fun selectQual(i: Int) {
        qualIdx = i
        prefs.edit().putInt("qual", i).apply()
        for (k in qualChips.indices) styleChip(qualChips[k], k == i)
    }

    private fun showPreview(uri: Uri) {
        placeholder.visibility = View.GONE
        videoView.visibility = View.VISIBLE
        selectBtn.text = "Change Video"
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            vw = mp.videoWidth; vh = mp.videoHeight
            fitVideo()
            videoView.start()
        }
    }

    private var vw = 0
    private var vh = 0
    private fun fitVideo() {
        if (vw == 0 || vh == 0) return
        val f = fmts[fmtIdx]
        val bw = resources.displayMetrics.widthPixels - dp(28 + 28)
        val bh = minOf(bw * f.h / f.w, dp(460))
        val s = minOf(bw.toFloat() / vw, bh.toFloat() / vh)
        videoView.layoutParams = FrameLayout.LayoutParams((vw * s).toInt(), (vh * s).toInt(), Gravity.CENTER)
    }

    // ---------- start / stop ----------
    private fun onStartTap() {
        if (videoUri == null) { toast("আগে ভিডিও সিলেক্ট করুন"); return }
        if (urlEdit.text.isBlank()) { toast("RTMP URL দিন"); return }
        if (keyEdit.text.isBlank()) { toast("Stream Key দিন"); return }
        prefs.edit().putString("url", urlEdit.text.toString().trim())
            .putString("key", keyEdit.text.toString().trim())
            .putBoolean("loop", loopOn).apply()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else doStart()
    }

    private fun doStart() {
        val i = Intent(this, StreamService::class.java).apply {
            putExtra("uri", videoUri.toString())
            putExtra("url", urlEdit.text.toString().trim())
            putExtra("key", keyEdit.text.toString().trim())
            putExtra("bitrate", quals[qualIdx].second)
            putExtra("loop", loopOn)
        }
        ContextCompat.startForegroundService(this, i)
        applyState(true)
        statusTv.text = "Starting"
    }

    private fun stopLive() {
        startService(Intent(this, StreamService::class.java).apply { action = StreamService.ACTION_STOP })
    }

    private fun applyState(on: Boolean) {
        streaming = on
        val en = !on
        for (c in fmtChips + qualChips) c.alpha = if (en) 1f else 0.4f
        urlEdit.isEnabled = en; keyEdit.isEnabled = en; loopSwitch.isEnabled = en
        selectBtn.alpha = if (en) 1f else 0.4f
        statsBox.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            startBtn.background = bg(Color.parseColor("#DC2F1F"), 12)
            ui.removeCallbacks(tick); ui.post(tick)
        } else {
            startBtn.text = "●  START CONTINUOUS LIVE STREAM"
            startBtn.background = bg(Color.parseColor("#2A3446"), 12)
            ui.removeCallbacks(tick)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!streaming) return
            val sec = (SystemClock.elapsedRealtime() - StreamService.startMs) / 1000
            startBtn.text = "●  STOP LIVE STREAM (%02d:%02d | Loop #%d)".format(sec / 60, sec % 60, StreamService.loops)
            ui.postDelayed(this, 1000)
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- service callbacks ----------
    override fun onState(msg: String, streaming: Boolean) {
        runOnUiThread {
            if (this.streaming != streaming) applyState(streaming)
            statusTv.text = msg
            if (!streaming) toast(msg)
        }
    }

    override fun onBitrate(kbps: Long) { runOnUiThread { speedTv.text = "$kbps kbps" } }
    override fun onLoop(n: Int) { runOnUiThread { loopTv.text = "$n" } }

    override fun onStart() { super.onStart(); StreamService.listener = this; if (StreamService.active != streaming) applyState(StreamService.active) }
    override fun onDestroy() { if (StreamService.listener === this) StreamService.listener = null; super.onDestroy() }
}
