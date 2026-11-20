package com.nex.screenrecorder

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

const val ACTION_OVERLAY_OFF = "com.nex.screenrecorder.OVERLAY_OFF"

object OverlayState {
    /** Fitur tombol melayang diaktifkan pengguna. */
    val enabled = MutableStateFlow(false)

    /** true = tombol disembunyikan (sedang meminta izin, merekam, atau menyimpan). */
    val busy = MutableStateFlow(false)

    @Volatile var launched = false

    /** Waktu (elapsedRealtime) rekaman dihentikan lewat tombol hijau; tombol rekam muncul lagi beberapa detik kemudian. */
    @Volatile var triggerStopAt = 0L
}

/** Memulai RecorderService dari activity (dipakai MainActivity dan QuickStartActivity). */
fun Activity.startRecordingService(code: Int, data: Intent) {
    val s = Prefs.load(this)
    val b = windowManager.maximumWindowMetrics.bounds
    val i = Intent(this, RecorderService::class.java)
        .setAction(ACTION_START)
        .putExtra(EXTRA_CODE, code)
        .putExtra(EXTRA_DATA, data)
        .putExtra(EXTRA_W, b.width())
        .putExtra(EXTRA_H, b.height())
        .putExtra(EXTRA_DPI, resources.configuration.densityDpi)
        .putExtra(EXTRA_SIDE, s.shortSide)
        .putExtra(EXTRA_FPS, s.fps)
        .putExtra(EXTRA_MBPS, s.bitrateMbps)
        .putExtra(EXTRA_CODEC, s.codec)
        .putExtra(EXTRA_AUDIO, s.audioOn)
        .putExtra(EXTRA_ASRC, s.audioSource)
        .putExtra(EXTRA_ARATE, s.sampleRate)
        .putExtra(EXTRA_ACH, s.channels)
        .putExtra(EXTRA_ACODEC, s.audioCodec)
        .putExtra(EXTRA_ABR, s.audioBitrate)
        .putExtra(EXTRA_GVOL, s.gameVolume)
        .putExtra(EXTRA_MVOL, s.micVolume)
    ContextCompat.startForegroundService(this, i)
}

/** Tombol rekam melayang di tengah layar. Hilang saat merekam supaya tidak ikut terekam. */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var view: View? = null
    private var trashView: TrashView? = null
    private lateinit var trigger: TriggerController
    private var showJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        trigger = TriggerController(this, wm) { stopFromTrigger() }
        scope.launch {
            OverlayState.busy.collect { busy ->
                showJob?.cancel()
                if (busy) {
                    hide()
                } else {
                    // Setelah berhenti lewat tombol hijau, tombol rekam muncul lagi beberapa detik kemudian.
                    val wait = OverlayState.triggerStopAt + RESHOW_DELAY_MS - SystemClock.elapsedRealtime()
                    if (wait > 0) {
                        showJob = launch {
                            delay(wait)
                            show()
                        }
                    } else {
                        show()
                    }
                }
            }
        }
        scope.launch {
            RecorderState.startedAt.collect { t ->
                if (t != 0L) trigger.start(Prefs.load(this@OverlayService)) else trigger.stop()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Layar diputar saat merekam: pasang ulang strip sesuai ukuran baru.
        if (RecorderState.startedAt.value != 0L) trigger.start(Prefs.load(this))
    }

    /** Tombol hijau diketuk: hentikan dan simpan rekaman. */
    private fun stopFromTrigger() {
        OverlayState.triggerStopAt = SystemClock.elapsedRealtime()
        startService(Intent(this, RecorderService::class.java).setAction(ACTION_STOP))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OVERLAY_OFF) {
            OverlayState.enabled.value = false
            Prefs.setFloating(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        trigger.destroy()
        hide()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun show() {
        if (view != null || !Settings.canDrawOverlays(this)) return
        val size = dp(44)
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 229, 57, 53))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(4).toFloat()
            contentDescription = "Mulai rekam"
        }
        lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        try { wm.updateViewLayout(v, lp) } catch (ex: Exception) { }
                        // Ikon tempat sampah hanya muncul saat tombol digeser.
                        showTrash()
                        val over = overTrash(e.rawX, e.rawY)
                        trashView?.activate(over)
                        v.alpha = if (over) 0.6f else 1f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onTap()
                    } else {
                        val dropped = overTrash(e.rawX, e.rawY)
                        hideTrash()
                        v.alpha = 1f
                        if (dropped) dismissByDrag()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideTrash()
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(v, lp)
            view = v
        } catch (e: Exception) {
            // izin overlay dicabut atau ditolak sistem
        }
    }

    private fun hide() {
        hideTrash()
        view?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        view = null
    }

    private fun showTrash() {
        if (trashView != null) return
        val size = dp(64)
        val t = TrashView(this)
        val tlp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
        try {
            wm.addView(t, tlp)
            trashView = t
        } catch (e: Exception) {
            // izin overlay dicabut atau ditolak sistem
        }
    }

    private fun hideTrash() {
        trashView?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        trashView = null
    }

    /** true jika jari berada di atas ikon tempat sampah (koordinat layar). */
    private fun overTrash(x: Float, y: Float): Boolean {
        val t = trashView ?: return false
        if (t.width == 0) return false
        val loc = IntArray(2)
        t.getLocationOnScreen(loc)
        val cx = loc[0] + t.width / 2f
        val cy = loc[1] + t.height / 2f
        val r = t.width * 0.9f
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= r * r
    }

    /** Tombol dilepas di atas tempat sampah: matikan tombol melayang. */
    private fun dismissByDrag() {
        hide()
        OverlayState.enabled.value = false
        Prefs.setFloating(this, false)
        Toast.makeText(this, "Tombol melayang dimatikan. Aktifkan lagi di tab Rekam.", Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onTap() {
        OverlayState.launched = false
        try {
            startActivity(
                Intent(this, QuickStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            OverlayState.busy.value = true
            // Jika activity ternyata tidak terbuka, munculkan lagi tombolnya.
            handler.postDelayed({
                if (!OverlayState.launched) OverlayState.busy.value = false
            }, 2500)
        } catch (e: Exception) {
            OverlayState.busy.value = false
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Tombol melayang", NotificationManager.IMPORTANCE_MIN),
        )
        val off = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_OVERLAY_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Tombol rekam melayang aktif")
            .setContentText("Ketuk tombol merah di layar untuk mulai merekam")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, "Matikan", off)
            .build()
    }

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 2
        private const val RESHOW_DELAY_MS = 2000L
    }
}

/** Activity transparan: meminta izin rekam layar, memulai rekaman, lalu menutup diri. */
class QuickStartActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                startRecordingService(r.resultCode, data) // tombol tetap tersembunyi sampai rekaman selesai
            } else {
                OverlayState.busy.value = false
            }
            finish()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            askProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayState.launched = true
        val s = Prefs.load(this)
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (s.audioOn && !granted(Manifest.permission.RECORD_AUDIO)) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (need.isEmpty()) askProjection() else permissionLauncher.launch(need.toTypedArray())
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

/** Ikon tempat sampah bulat yang digambar sendiri; membesar dan memerah saat tombol berada di atasnya. */
private class TrashView(context: Context) : View(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private var active = false

    fun activate(on: Boolean) {
        if (on == active) return
        active = on
        if (on) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        animate().scaleX(if (on) 1.25f else 1f).scaleY(if (on) 1.25f else 1f).setDuration(120).start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bg.color = if (active) Color.argb(235, 229, 57, 53) else Color.argb(210, 40, 40, 44)
        canvas.drawCircle(w / 2f, h / 2f, w / 2f, bg)
        fg.strokeWidth = w * 0.06f
        // tutup dan gagang
        canvas.drawLine(w * 0.30f, h * 0.35f, w * 0.70f, h * 0.35f, fg)
        canvas.drawLine(w * 0.42f, h * 0.35f, w * 0.42f, h * 0.29f, fg)
        canvas.drawLine(w * 0.58f, h * 0.35f, w * 0.58f, h * 0.29f, fg)
        canvas.drawLine(w * 0.42f, h * 0.29f, w * 0.58f, h * 0.29f, fg)
        // badan
        val body = Path().apply {
            moveTo(w * 0.35f, h * 0.40f)
            lineTo(w * 0.38f, h * 0.72f)
            lineTo(w * 0.62f, h * 0.72f)
            lineTo(w * 0.65f, h * 0.40f)
        }
        canvas.drawPath(body, fg)
        // garis isi
        canvas.drawLine(w * 0.46f, h * 0.47f, w * 0.46f, h * 0.65f, fg)
        canvas.drawLine(w * 0.54f, h * 0.47f, w * 0.54f, h * 0.65f, fg)
    }
}
