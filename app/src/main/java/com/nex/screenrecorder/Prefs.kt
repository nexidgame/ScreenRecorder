package com.nex.screenrecorder

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

const val SRC_MIC = 0
const val SRC_RAW = 1
const val SRC_PERF = 2
const val SRC_INTERNAL = 3
const val SRC_INTERNAL_MIC = 4

// Mode trigger (seperti Button Savior)
const val TRIG_CLICK = 0 // Klik Ikon: hanya tombol rekam melayang
const val TRIG_SIDE = 1  // Geser dari Sisi
const val TRIG_HALF = 2  // Geser Setengah Atas
const val TRIG_ADJ = 3   // Geser Dapat Diatur

const val SIDE_RIGHT = 0
const val SIDE_LEFT = 1
const val SIDE_TOP = 2
const val SIDE_BOTTOM = 3
const val SIDE_BOTH = 4

/** shortSide = sisi pendek video dalam piksel (0 = resolusi asli layar). */
data class RecSettings(
    val shortSide: Int = 720,
    val fps: Int = 30,
    val bitrateMbps: Int = 8,
    val codec: Int = 264,        // 264 = H.264, 265 = H.265
    val audioOn: Boolean = false,
    val audioSource: Int = SRC_MIC,
    val sampleRate: Int = 44100, // 44100 / 48000
    val channels: Int = 1,       // 1 = mono, 2 = stereo
    val audioCodec: Int = 0,     // 0 = AAC, 1 = AAC_ELD, 2 = HE-AAC
    val audioBitrate: Int = 128, // Kbps: 64 / 128 / 256 / 320
    val gameVolume: Int = 100,   // persen, khusus Internal + MIC
    val micVolume: Int = 100,    // persen, khusus Internal + MIC
    val triggerMode: Int = TRIG_CLICK,
    val triggerSide: Int = SIDE_RIGHT,
    val triggerSens: Int = 2,    // 0 paling tidak sensitif, 1 kurang, 2 bawaan, 3 lebih sensitif
    val covStart: Int = 0,       // area cakupan (persen) untuk Geser Dapat Diatur
    val covEnd: Int = 100,
)

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(c: Context): RecSettings {
        val s = sp(c)
        return RecSettings(
            shortSide = s.getInt("side", 720),
            fps = s.getInt("fps", 30),
            bitrateMbps = s.getInt("mbps", 8),
            codec = s.getInt("codec", 264),
            audioOn = s.getBoolean("audio_on", false),
            audioSource = s.getInt("audio_src", SRC_MIC),
            sampleRate = s.getInt("audio_rate", 44100),
            channels = s.getInt("audio_ch", 1),
            audioCodec = s.getInt("audio_codec", 0),
            audioBitrate = s.getInt("audio_kbps", 128),
            gameVolume = s.getInt("vol_game", 100),
            micVolume = s.getInt("vol_mic", 100),
            triggerMode = s.getInt("trig_mode", TRIG_CLICK),
            triggerSide = s.getInt("trig_side", SIDE_RIGHT),
            triggerSens = s.getInt("trig_sens", 2),
            covStart = s.getInt("cov_start", 0),
            covEnd = s.getInt("cov_end", 100),
        )
    }

    fun floating(c: Context): Boolean = sp(c).getBoolean("floating", false)

    fun setFloating(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("floating", on).apply()
    }

    fun save(c: Context, r: RecSettings) {
        sp(c).edit()
            .putInt("side", r.shortSide)
            .putInt("fps", r.fps)
            .putInt("mbps", r.bitrateMbps)
            .putInt("codec", r.codec)
            .putBoolean("audio_on", r.audioOn)
            .putInt("audio_src", r.audioSource)
            .putInt("audio_rate", r.sampleRate)
            .putInt("audio_ch", r.channels)
            .putInt("audio_codec", r.audioCodec)
            .putInt("audio_kbps", r.audioBitrate)
            .putInt("vol_game", r.gameVolume)
            .putInt("vol_mic", r.micVolume)
            .putInt("trig_mode", r.triggerMode)
            .putInt("trig_side", r.triggerSide)
            .putInt("trig_sens", r.triggerSens)
            .putInt("cov_start", r.covStart)
            .putInt("cov_end", r.covEnd)
            .apply()
    }
}

/** Status perekaman yang dibaca UI. 0 = tidak merekam, selain itu = waktu mulai (elapsedRealtime). */
object RecorderState {
    val startedAt = MutableStateFlow(0L)
}

/** Dinaikkan setiap MainActivity kembali aktif, supaya layar memuat ulang pengaturan dari penyimpanan. */
object UiSignals {
    val resumeTick = MutableStateFlow(0)
}
