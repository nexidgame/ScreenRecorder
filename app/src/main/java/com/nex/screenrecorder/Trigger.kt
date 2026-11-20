package com.nex.screenrecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Trigger geser seperti Button Savior, aktif hanya saat merekam.
 *
 * Strip tipis tak terlihat dipasang di tepi layar. Saat Anda menggeser ke dalam dari strip itu,
 * muncul tombol hijau (tanda rekaman sedang berjalan). Ketuk tombol hijau untuk menyimpan rekaman.
 * Jika tidak diketuk, tombol hijau hilang sendiri setelah beberapa detik.
 */
class TriggerController(
    private val context: Context,
    private val wm: WindowManager,
    private val onStopRequested: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val strips = mutableListOf<View>()
    private var green: GreenButtonView? = null
    private val hideGreenRunnable = Runnable { hideGreen() }

    private var screenW = 0
    private var screenH = 0
    private var insets: Insets = Insets.NONE
    private val density = context.resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).roundToInt()

    /** Memasang strip sesuai pengaturan. Dipanggil saat rekaman dimulai (atau layar diputar). */
    fun start(s: RecSettings) {
        removeStrips()
        hideGreen()
        if (s.triggerMode == TRIG_CLICK || !Settings.canDrawOverlays(context)) return

        val b = wm.maximumWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()
        insets = gestureInsets()

        // Sensitivitas = ketebalan strip dan jarak geser minimum.
        val thickness = dp(
            when (s.triggerSens) {
                0 -> 10
                1 -> 14
                3 -> 30
                else -> 20
            },
        )
        val minSwipe = dp(
            when (s.triggerSens) {
                0 -> 56
                1 -> 40
                3 -> 18
                else -> 28
            },
        )

        var side = s.triggerSide
        if (s.triggerMode != TRIG_ADJ && (side == SIDE_TOP || side == SIDE_BOTTOM)) side = SIDE_RIGHT
        val sides = if (side == SIDE_BOTH) listOf(SIDE_LEFT, SIDE_RIGHT) else listOf(side)

        val (c0, c1) = when (s.triggerMode) {
            TRIG_HALF -> 0f to 0.5f
            TRIG_ADJ -> (s.covStart / 100f) to (s.covEnd / 100f)
            else -> 0f to 1f
        }
        for (sd in sides) addStrip(sd, c0, c1, thickness, minSwipe)
    }

    /** Rekaman selesai: lepas strip. Tombol hijau yang sedang menampilkan centang dibiarkan hilang sendiri. */
    fun stop() {
        removeStrips()
        if (green?.saved != true) hideGreen()
    }

    fun destroy() {
        removeStrips()
        hideGreen()
    }

    // ------------------------------------------------------------------ strip

    private fun addStrip(side: Int, c0: Float, c1: Float, thickness: Int, minSwipe: Int) {
        val horizontalEdge = side == SIDE_TOP || side == SIDE_BOTTOM
        val total = if (horizontalEdge) screenW else screenH
        val length = (total * (c1 - c0)).roundToInt().coerceAtLeast(dp(48))
        val offsetAlong = (total * c0).roundToInt()

        val v = SwipeStrip(context, side, minSwipe) { x, y -> showGreen(side, x, y) }
        val lp = WindowManager.LayoutParams(
            if (horizontalEdge) length else thickness,
            if (horizontalEdge) thickness else length,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        // Digeser ke dalam sebesar area gestur sistem agar tidak bentrok dengan gestur kembali/beranda.
        when (side) {
            SIDE_LEFT -> { lp.x = insets.left; lp.y = offsetAlong }
            SIDE_RIGHT -> { lp.x = screenW - thickness - insets.right; lp.y = offsetAlong }
            SIDE_TOP -> { lp.x = offsetAlong; lp.y = insets.top }
            else -> { lp.x = offsetAlong; lp.y = screenH - thickness - insets.bottom }
        }
        try {
            wm.addView(v, lp)
            strips += v
        } catch (e: Exception) {
            // izin overlay dicabut atau ditolak sistem
        }
    }

    private fun removeStrips() {
        for (v in strips) {
            try { wm.removeView(v) } catch (e: Exception) { }
        }
        strips.clear()
    }

    private fun gestureInsets(): Insets = try {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val wctx = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val m = (wctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics
        m.windowInsets.getInsets(WindowInsets.Type.systemGestures())
    } catch (e: Exception) {
        Insets.NONE
    }

    // ------------------------------------------------------------ tombol hijau

    private fun showGreen(side: Int, x: Float, y: Float) {
        hideGreen()
        val size = dp(52)
        val margin = dp(8)
        val v = GreenButtonView(context)
        v.setOnClickListener { onGreenTap() }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        when (side) {
            SIDE_LEFT -> { lp.x = insets.left + margin; lp.y = y.roundToInt() - size / 2 }
            SIDE_RIGHT -> { lp.x = screenW - size - insets.right - margin; lp.y = y.roundToInt() - size / 2 }
            SIDE_TOP -> { lp.x = x.roundToInt() - size / 2; lp.y = insets.top + margin }
            else -> { lp.x = x.roundToInt() - size / 2; lp.y = screenH - size - insets.bottom - margin }
        }
        lp.x = lp.x.coerceIn(0, (screenW - size).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH - size).coerceAtLeast(0))
        try {
            wm.addView(v, lp)
            green = v
            handler.postDelayed(hideGreenRunnable, GREEN_VISIBLE_MS)
        } catch (e: Exception) {
            // izin overlay dicabut atau ditolak sistem
        }
    }

    private fun onGreenTap() {
        val g = green ?: return
        handler.removeCallbacks(hideGreenRunnable)
        removeStrips()
        g.saved = true // tampilkan centang sebentar
        onStopRequested()
        handler.postDelayed({ hideGreen() }, SAVED_FEEDBACK_MS)
    }

    private fun hideGreen() {
        handler.removeCallbacks(hideGreenRunnable)
        green?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        green = null
    }

    private companion object {
        const val GREEN_VISIBLE_MS = 6000L
        const val SAVED_FEEDBACK_MS = 1500L
    }
}

/** Strip tak terlihat di tepi layar: mendeteksi geseran ke arah dalam layar. */
private class SwipeStrip(
    context: Context,
    private val side: Int,
    private val minDistance: Int,
    private val onSwipe: (Float, Float) -> Unit,
) : View(context) {
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                fired = false
            }
            MotionEvent.ACTION_MOVE -> if (!fired) {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val inward = when (side) {
                    SIDE_LEFT -> dx
                    SIDE_RIGHT -> -dx
                    SIDE_TOP -> dy
                    else -> -dy
                }
                if (inward >= minDistance) {
                    fired = true
                    onSwipe(downX, downY)
                }
            }
        }
        return true
    }
}

/** Tombol hijau bulat dengan ikon berhenti; berubah menjadi centang setelah diketuk. */
private class GreenButtonView(context: Context) : View(context) {
    var saved = false
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 46, 160, 67) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        contentDescription = "Berhenti dan simpan rekaman"
        elevation = 6f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawCircle(w / 2f, h / 2f, w / 2f, fill)
        ring.strokeWidth = w * 0.05f
        canvas.drawCircle(w / 2f, h / 2f, w / 2f - ring.strokeWidth / 2f, ring)
        if (saved) {
            tick.strokeWidth = w * 0.08f
            val p = Path().apply {
                moveTo(w * 0.30f, h * 0.52f)
                lineTo(w * 0.45f, h * 0.67f)
                lineTo(w * 0.72f, h * 0.36f)
            }
            canvas.drawPath(p, tick)
        } else {
            canvas.drawRoundRect(RectF(w * 0.34f, h * 0.34f, w * 0.66f, h * 0.66f), w * 0.06f, w * 0.06f, glyph)
        }
    }
}
