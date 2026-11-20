package com.nex.screenrecorder

import android.os.Build
import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Red = Color(0xFFE53935)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFFF8A80), background = Color(0xFF121214))
        else -> lightColorScheme(primary = Red)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val label: String) {
    REKAM("Rekam"),
    VIDEO("Video"),
    AUDIO("Audio"),
    PENGATURAN("Pengaturan"),
}

@Composable
fun MainScreen(onStart: () -> Unit, onStop: () -> Unit, onFloating: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(Prefs.load(ctx)) }
    val startedAt by RecorderState.startedAt.collectAsState()
    val floating by OverlayState.enabled.collectAsState()
    val recording = startedAt != 0L
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val resumeTick by UiSignals.resumeTick.collectAsState()
    LaunchedEffect(resumeTick) { s = Prefs.load(ctx) }

    LaunchedEffect(recording) {
        while (recording) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    fun update(n: RecSettings) {
        s = n
        Prefs.save(ctx, n)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { TabIcon(t) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            when (Tab.entries[tab]) {
                Tab.REKAM -> RecordTab(
                    s, recording, startedAt, now, floating, onStart, onStop, onFloating,
                )
                Tab.VIDEO -> VideoTab(s, recording) { update(it) }
                Tab.AUDIO -> AudioTab(s, recording) { update(it) }
                Tab.PENGATURAN -> SettingsTab(s, recording, { update(it) }) { update(RecSettings()) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------- Tab Rekam

@Composable
private fun RecordTab(
    s: RecSettings,
    recording: Boolean,
    startedAt: Long,
    now: Long,
    floating: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFloating: (Boolean) -> Unit,
) {
    Text(
        "Screen Recorder",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(36.dp))
    RecordButton(recording, if (recording) onStop else onStart)
    Spacer(Modifier.height(16.dp))
    Text(
        if (recording) formatTime(now - startedAt) else "Ketuk untuk mulai merekam",
        style = MaterialTheme.typography.titleLarge,
        color = if (recording) Red else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        summary(s),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(28.dp))
    SectionCard(spacing = 8.dp) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Tombol rekam melayang", style = MaterialTheme.typography.bodyLarge)
            Switch(floating, onFloating)
        }
        Text(
            "Tombol merah di tengah layar. Ketuk untuk mulai merekam, lalu tombol hilang. " +
                "Berhenti lewat tombol di notifikasi. Tombol bisa digeser; seret ke ikon tempat sampah " +
                "yang muncul di bawah layar untuk menghapusnya.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun summary(s: RecSettings): String {
    val res = if (s.shortSide == 0) "Resolusi asli" else "${s.shortSide}p"
    val codec = if (s.codec == 265) "H.265" else "H.264"
    val audio = if (!s.audioOn) "tanpa audio" else when (s.audioSource) {
        SRC_RAW -> "audio Raw"
        SRC_PERF -> "audio Performa"
        SRC_INTERNAL -> "audio Internal"
        SRC_INTERNAL_MIC -> "audio Internal + MIC"
        else -> "audio MIC"
    }
    return "$res, ${s.fps} fps, $codec, $audio"
}

// ---------------------------------------------------------------- Tab Video

@Composable
private fun VideoTab(s: RecSettings, recording: Boolean, update: (RecSettings) -> Unit) {
    SectionCard {
        SectionTitle("Video")
        Option(
            "Resolusi", listOf("480p" to 480, "720p" to 720, "1080p" to 1080, "Asli" to 0),
            s.shortSide, !recording,
        ) { update(s.copy(shortSide = it)) }
        Option(
            "Frame rate",
            listOf("30 fps" to 30, "60 fps" to 60, "90 fps" to 90, "120 fps" to 120),
            s.fps, !recording,
        ) { update(s.copy(fps = it)) }
        Option(
            "Bitrate",
            listOf("4 Mbps" to 4, "8 Mbps" to 8, "12 Mbps" to 12, "16 Mbps" to 16),
            s.bitrateMbps, !recording,
        ) { update(s.copy(bitrateMbps = it)) }
        Option(
            "Codec video", listOf("H.264" to 264, "H.265" to 265),
            s.codec, !recording,
        ) { update(s.copy(codec = it)) }
    }
}

// ---------------------------------------------------------------- Tab Audio

@Composable
private fun AudioTab(s: RecSettings, recording: Boolean, update: (RecSettings) -> Unit) {
    var qualityOpen by rememberSaveable { mutableStateOf(false) }

    // Kartu 1: sumber dan volume
    SectionCard {
        SectionTitle("Sumber dan volume")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Rekam audio", style = MaterialTheme.typography.bodyLarge)
            Switch(s.audioOn, { update(s.copy(audioOn = it)) }, enabled = !recording)
        }
        if (s.audioOn) {
            StackedOption(
                "Sumber suara",
                listOf(
                    "MIC" to SRC_MIC,
                    "Raw (tanpa terproses)" to SRC_RAW,
                    "Performa Suara" to SRC_PERF,
                    "Suara Internal" to SRC_INTERNAL,
                    "Internal + MIC" to SRC_INTERNAL_MIC,
                ),
                s.audioSource, !recording,
            ) { update(s.copy(audioSource = it)) }
            Text(
                when (s.audioSource) {
                    SRC_RAW -> "Raw: mikrofon tanpa terproses (tanpa peredam noise dan penguat otomatis)."
                    SRC_PERF -> "Performa Suara: mikrofon dengan latensi rendah."
                    SRC_INTERNAL -> "Suara Internal: merekam suara dari HP (game/media), bukan mikrofon. " +
                        "Sebagian aplikasi memblokirnya, dan video disatukan saat berhenti."
                    SRC_INTERNAL_MIC -> "Internal + MIC: suara game dan mikrofon dicampur. " +
                        "Pakai earphone agar suara game tidak terekam dua kali lewat mikrofon."
                    else -> "MIC: mikrofon standar."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.audioSource == SRC_INTERNAL_MIC) {
                VolumeSlider("Volume suara game", s.gameVolume, !recording) {
                    update(s.copy(gameVolume = it))
                }
                VolumeSlider("Volume mikrofon", s.micVolume, !recording) {
                    update(s.copy(micVolume = it))
                }
            }
        }
    }

    if (!s.audioOn) return

    // Kartu 2: kualitas audio (bisa dilipat)
    Spacer(Modifier.height(16.dp))
    SectionCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { qualityOpen = !qualityOpen },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionTitle("Kualitas audio")
            Text(
                if (qualityOpen) "Sembunyikan" else "Tampilkan",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (qualityOpen) {
            Option(
                "Tingkat sampel audio",
                listOf("44100 Hz" to 44100, "48000 Hz" to 48000),
                s.sampleRate, !recording,
            ) { update(s.copy(sampleRate = it)) }
            Option(
                "Saluran audio", listOf("Mono" to 1, "Stereo" to 2),
                s.channels, !recording,
            ) { update(s.copy(channels = it)) }
            Option(
                "Enkoder audio",
                listOf("AAC" to 0, "AAC_ELD" to 1, "HE-AAC" to 2),
                s.audioCodec, !recording,
            ) { update(s.copy(audioCodec = it)) }
            Option(
                "Bit rata-rata audio",
                listOf("64 Kbps" to 64, "128 Kbps" to 128, "256 Kbps" to 256, "320 Kbps" to 320),
                s.audioBitrate, !recording,
            ) { update(s.copy(audioBitrate = it)) }
            Text(
                "Otomatis diturunkan jika melebihi batas encoder " +
                    "(misalnya mono tidak mencapai 320 Kbps).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- Tab Pengaturan

@Composable
private fun SettingsTab(
    s: RecSettings,
    recording: Boolean,
    update: (RecSettings) -> Unit,
    onReset: () -> Unit,
) {
    val ctx = LocalContext.current
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "-"
        } catch (e: Exception) {
            "-"
        }
    }
    SectionCard {
        SectionTitle("Mode trigger")
        Text(
            "Saat merekam, geser dari tepi layar untuk memunculkan tombol hijau. " +
                "Ketuk tombol hijau untuk menyimpan rekaman. " +
                "Aktif jika Tombol rekam melayang dinyalakan (tab Rekam).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StackedOption(
            "Mode",
            listOf(
                "Klik Ikon" to TRIG_CLICK,
                "Geser dari Sisi" to TRIG_SIDE,
                "Geser Setengah Atas" to TRIG_HALF,
                "Geser Dapat Diatur" to TRIG_ADJ,
            ),
            s.triggerMode, !recording,
        ) { m ->
            val sideOk = m == TRIG_ADJ || (s.triggerSide != SIDE_TOP && s.triggerSide != SIDE_BOTTOM)
            update(s.copy(triggerMode = m, triggerSide = if (sideOk) s.triggerSide else SIDE_RIGHT))
        }
        Text(
            when (s.triggerMode) {
                TRIG_SIDE -> "Geser dari Sisi: geser dari tepi layar (sepanjang sisi)."
                TRIG_HALF -> "Geser Setengah Atas: geser dari tepi layar bagian atas (setengah tinggi layar)."
                TRIG_ADJ -> "Geser Dapat Diatur: sisi dan area cakupan bisa diatur."
                else -> "Klik Ikon: hanya tombol rekam melayang. Berhenti lewat tombol di notifikasi."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.triggerMode != TRIG_CLICK) {
            StackedOption(
                "Sisi trigger",
                if (s.triggerMode == TRIG_ADJ) {
                    listOf(
                        "Kanan" to SIDE_RIGHT, "Kiri" to SIDE_LEFT, "Atas" to SIDE_TOP,
                        "Bawah" to SIDE_BOTTOM, "Kedua Sisi" to SIDE_BOTH,
                    )
                } else {
                    listOf("Kanan" to SIDE_RIGHT, "Kiri" to SIDE_LEFT, "Kedua Sisi" to SIDE_BOTH)
                },
                s.triggerSide, !recording,
            ) { update(s.copy(triggerSide = it)) }
            if (s.triggerMode == TRIG_ADJ) {
                Text(
                    "Area cakupan: ${s.covStart}% sampai ${s.covEnd}% dari panjang sisi layar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(ctx, CoverageActivity::class.java)) },
                    enabled = !recording,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Atur area cakupan")
                }
            }
            StackedOption(
                "Sensitivitas geser",
                listOf(
                    "Paling Tidak Sensitif" to 0, "Kurang Sensitif" to 1,
                    "Bawaan" to 2, "Lebih Sensitif" to 3,
                ),
                s.triggerSens, !recording,
            ) { update(s.copy(triggerSens = it)) }
            Text(
                "Naikkan sensitivitas jika tombol hijau sulit muncul. Turunkan jika area geser " +
                    "mengganggu keyboard atau gerakan lain.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    SectionCard(spacing = 8.dp) {
        SectionTitle("Tentang")
        Text("Screen Recorder", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Versi $version",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Hasil rekaman disimpan di Movies/ScreenRecorder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(16.dp))
    SectionCard(spacing = 12.dp) {
        SectionTitle("Pengaturan awal")
        Text(
            "Kembalikan resolusi, frame rate, bitrate, codec, dan semua pengaturan audio ke nilai awal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onReset, enabled = !recording, modifier = Modifier.fillMaxWidth()) {
            Text("Kembalikan pengaturan awal")
        }
    }
}

// ---------------------------------------------------------------- Komponen bersama

@Composable
private fun SectionCard(spacing: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** Ikon tab sederhana yang digambar sendiri (tanpa library ikon, supaya APK tetap kecil). */
@Composable
private fun TabIcon(tab: Tab) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val sw = 2.dp.toPx()
        when (tab) {
            Tab.REKAM -> {
                drawCircle(color, radius = w * 0.42f, style = Stroke(sw))
                drawCircle(color, radius = w * 0.2f)
            }
            Tab.VIDEO -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.08f, h * 0.2f),
                    size = Size(w * 0.84f, h * 0.6f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(sw),
                )
                val play = Path().apply {
                    moveTo(w * 0.42f, h * 0.36f)
                    lineTo(w * 0.42f, h * 0.64f)
                    lineTo(w * 0.67f, h * 0.5f)
                    close()
                }
                drawPath(play, color)
            }
            Tab.AUDIO -> {
                val xs = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f)
                val hs = floatArrayOf(0.35f, 0.8f, 0.55f, 0.3f)
                for (i in xs.indices) {
                    drawLine(
                        color = color,
                        start = Offset(w * xs[i], h * (0.5f - hs[i] / 2f)),
                        end = Offset(w * xs[i], h * (0.5f + hs[i] / 2f)),
                        strokeWidth = sw * 1.3f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            Tab.PENGATURAN -> {
                val ys = floatArrayOf(0.25f, 0.5f, 0.75f)
                val knob = floatArrayOf(0.65f, 0.35f, 0.6f)
                for (i in ys.indices) {
                    drawLine(
                        color = color,
                        start = Offset(w * 0.1f, h * ys[i]),
                        end = Offset(w * 0.9f, h * ys[i]),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(color, radius = w * 0.11f, center = Offset(w * knob[i], h * ys[i]))
                }
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val size by animateDpAsState(if (recording) 44.dp else 96.dp, label = "size")
    val corner by animateDpAsState(if (recording) 12.dp else 48.dp, label = "corner")
    Box(
        Modifier
            .size(136.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(Red),
        )
    }
}

@Composable
private fun <T> StackedOption(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (label, value) ->
                val isSelected = value == selected
                val shape = RoundedCornerShape(50)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .alpha(if (enabled) 1f else 0.5f)
                        .clip(shape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent,
                        )
                        .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(value) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeSlider(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("$value%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..200f,
            steps = 19,
            enabled = enabled,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Option(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (label, value) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    enabled = enabled,
                    icon = {},
                    label = {
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60)
    else "%02d:%02d".format(t / 60, t % 60)
}
