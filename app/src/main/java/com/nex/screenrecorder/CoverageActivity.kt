package com.nex.screenrecorder

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Editor area cakupan geser (seperti "Setup Swipe Coverage" di Button Savior).
 * Layar penuh: batang berwarna di tepi layar menunjukkan area yang merespons geseran,
 * dua pegangan di ujung batang diseret untuk mengatur panjangnya.
 */
class CoverageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layar penuh sampai ke tepi (termasuk area poni) agar persen sama dengan layar sebenarnya.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val s = Prefs.load(this)
        setContent {
            AppTheme {
                CoverageScreen(
                    side = s.triggerSide,
                    initStart = s.covStart,
                    initEnd = s.covEnd,
                    onCancel = { finish() },
                    onSave = { a, b ->
                        Prefs.save(this, Prefs.load(this).copy(covStart = a, covEnd = b))
                        finish()
                    },
                )
            }
        }
    }
}

private const val MIN_GAP = 10f // selisih minimum antara pegangan awal dan akhir (persen)

@Composable
private fun CoverageScreen(
    side: Int,
    initStart: Int,
    initEnd: Int,
    onCancel: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    val horizontal = side == SIDE_TOP || side == SIDE_BOTTOM
    var start by rememberSaveable { mutableStateOf(initStart.toFloat()) }
    var end by rememberSaveable { mutableStateOf(initEnd.toFloat()) }
    val barColor = Color(0xFF2196F3)

    Box(Modifier.fillMaxSize().background(Color(0xFF303030))) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(horizontal) {
                    var active = -1 // 0 = pegangan awal, 1 = pegangan akhir, 2 = seluruh batang
                    val slop = 56.dp.toPx()
                    detectDragGestures(
                        onDragStart = { o ->
                            val total = if (horizontal) size.width.toFloat() else size.height.toFloat()
                            val p = if (horizontal) o.x else o.y
                            val a = start / 100f * total
                            val b = end / 100f * total
                            val da = abs(p - a)
                            val db = abs(p - b)
                            active = when {
                                min(da, db) <= slop -> if (da <= db) 0 else 1
                                p in a..b -> 2
                                else -> -1
                            }
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                        onDrag = { change, drag ->
                            change.consume()
                            val total = if (horizontal) size.width.toFloat() else size.height.toFloat()
                            val d = (if (horizontal) drag.x else drag.y) / total * 100f
                            when (active) {
                                0 -> start = (start + d).coerceIn(0f, end - MIN_GAP)
                                1 -> end = (end + d).coerceIn(start + MIN_GAP, 100f)
                                2 -> {
                                    val len = end - start
                                    val ns = (start + d).coerceIn(0f, 100f - len)
                                    start = ns
                                    end = ns + len
                                }
                            }
                        },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            val total = if (horizontal) w else h
            val a = start / 100f * total
            val b = end / 100f * total
            val thick = 14.dp.toPx()
            val hs = 44.dp.toPx()
            val sides = if (side == SIDE_BOTH) listOf(SIDE_LEFT, SIDE_RIGHT) else listOf(side)
            for (sd in sides) {
                // batang area geser
                when (sd) {
                    SIDE_LEFT -> drawRect(barColor.copy(alpha = 0.55f), Offset(0f, a), Size(thick, b - a))
                    SIDE_RIGHT -> drawRect(barColor.copy(alpha = 0.55f), Offset(w - thick, a), Size(thick, b - a))
                    SIDE_TOP -> drawRect(barColor.copy(alpha = 0.55f), Offset(a, 0f), Size(b - a, thick))
                    else -> drawRect(barColor.copy(alpha = 0.55f), Offset(a, h - thick), Size(b - a, thick))
                }
                // pegangan di kedua ujung
                for (p in floatArrayOf(a, b)) {
                    val pos = (p - hs / 2f).coerceIn(0f, total - hs)
                    val topLeft = when (sd) {
                        SIDE_LEFT -> Offset(0f, pos)
                        SIDE_RIGHT -> Offset(w - hs, pos)
                        SIDE_TOP -> Offset(pos, 0f)
                        else -> Offset(pos, h - hs)
                    }
                    drawRoundRect(barColor, topLeft, Size(hs, hs), CornerRadius(8.dp.toPx()))
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Atur area cakupan geser",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Seret pegangan di tepi layar untuk menentukan area yang merespons geseran. " +
                    "Seret batang di antaranya untuk menggesernya.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFBDBDBD),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Area: ${start.roundToInt()}% sampai ${end.roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF616161),
                    contentColor = Color.White,
                )
                Button(onClick = onCancel, colors = colors) { Text("Batal") }
                Button(
                    onClick = { onSave(start.roundToInt(), end.roundToInt()) },
                    colors = colors,
                ) { Text("Simpan") }
            }
        }
    }
}
