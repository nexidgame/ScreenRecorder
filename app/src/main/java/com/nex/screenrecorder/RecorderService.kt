package com.nex.screenrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val ACTION_START = "com.nex.screenrecorder.START"
const val ACTION_STOP = "com.nex.screenrecorder.STOP"
const val EXTRA_CODE = "code"
const val EXTRA_DATA = "data"
const val EXTRA_W = "w"
const val EXTRA_H = "h"
const val EXTRA_DPI = "dpi"
const val EXTRA_SIDE = "side"
const val EXTRA_FPS = "fps"
const val EXTRA_MBPS = "mbps"
const val EXTRA_CODEC = "codec"
const val EXTRA_AUDIO = "audio"
const val EXTRA_ASRC = "asrc"
const val EXTRA_ARATE = "arate"
const val EXTRA_ACH = "ach"
const val EXTRA_ACODEC = "acodec"
const val EXTRA_ABR = "abr"
const val EXTRA_GVOL = "gvol"
const val EXTRA_MVOL = "mvol"

class RecorderService : Service() {

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var internalAudio: InternalAudioRecorder? = null
    private var videoTmp: File? = null
    private var audioTmp: File? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var saving = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val audioOn = intent.getBooleanExtra(EXTRA_AUDIO, false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val src = intent.getIntExtra(EXTRA_ASRC, SRC_MIC)
        val internal = audioOn && (src == SRC_INTERNAL || src == SRC_INTERNAL_MIC)
        val mixMic = audioOn && src == SRC_INTERNAL_MIC
        val micOk = audioOn && !internal // mikrofon lewat MediaRecorder

        // Android 14+: foreground service bertipe mediaProjection harus aktif
        // sebelum getMediaProjection() dipanggil.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (micOk || mixMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        createChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)

        if (saving) {
            Toast.makeText(this, "Masih menyimpan video sebelumnya", Toast.LENGTH_SHORT).show()
            return
        }
        if (recorder != null) return

        try {
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                ?: error("Tidak ada data izin")

            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data) ?: error("Gagal mendapat izin rekam layar")
            projection = proj
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { stopRecording() }
                }
            }
            projectionCallback = cb
            proj.registerCallback(cb, main) // wajib sebelum createVirtualDisplay (Android 14+)

            val rawW = intent.getIntExtra(EXTRA_W, 720)
            val rawH = intent.getIntExtra(EXTRA_H, 1280)
            val side = intent.getIntExtra(EXTRA_SIDE, 720)
            // Semua resolusi (termasuk asli) dibulatkan ke kelipatan 8. Jika encoder menolak, dicoba kelipatan 16.
            var (vw, vh) = scaled(rawW, rawH, side, 8, 8)
            val fps = intent.getIntExtra(EXTRA_FPS, 30)
            val mbps = intent.getIntExtra(EXTRA_MBPS, 8)
            val dpi = intent.getIntExtra(EXTRA_DPI, 320)
            val sampleRate = intent.getIntExtra(EXTRA_ARATE, 44100)
            val channels = intent.getIntExtra(EXTRA_ACH, 1)
            val acodec = intent.getIntExtra(EXTRA_ACODEC, 0)
            val abr = intent.getIntExtra(EXTRA_ABR, 128)
            val gameGain = intent.getIntExtra(EXTRA_GVOL, 100) / 100f
            val micGain = intent.getIntExtra(EXTRA_MVOL, 100) / 100f

            val name = "Rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Gagal membuat file")
            uri = u
            val fd = contentResolver.openFileDescriptor(u, "rw") ?: error("Gagal membuka file")
            pfd = fd

            val wantHevc = intent.getIntExtra(EXTRA_CODEC, 264) == 265
            val useHevc = wantHevc && hasHardwareHevcEncoder()
            if (wantHevc && !useHevc) {
                Toast.makeText(this, "H.265 tidak didukung HP ini, memakai H.264", Toast.LENGTH_LONG).show()
            }

            // Suara internal direkam terpisah, lalu digabung dengan video saat berhenti.
            var ia: InternalAudioRecorder? = null
            if (internal) {
                val aTmp = File(cacheDir, "audio_$name.m4a")
                val cand = InternalAudioRecorder(
                    proj, sampleRate, channels, acodec, aTmp, mixMic, gameGain, micGain, abr,
                )
                try {
                    cand.prepare()
                    ia = cand
                    audioTmp = aTmp
                    if (cand.micFailed) {
                        Toast.makeText(
                            this, "Mikrofon gagal dibuka, merekam suara internal saja", Toast.LENGTH_LONG,
                        ).show()
                    }
                } catch (e: Exception) {
                    cand.release()
                    aTmp.delete()
                    Toast.makeText(
                        this, "Suara internal gagal disiapkan, merekam tanpa suara", Toast.LENGTH_LONG,
                    ).show()
                }
            }
            internalAudio = ia

            val micSource: Int? = if (micOk) {
                when (src) {
                    SRC_RAW -> MediaRecorder.AudioSource.UNPROCESSED
                    SRC_PERF -> MediaRecorder.AudioSource.VOICE_PERFORMANCE
                    else -> MediaRecorder.AudioSource.MIC
                }
            } else null
            val encoder = when (acodec) {
                1 -> MediaRecorder.AudioEncoder.AAC_ELD
                2 -> MediaRecorder.AudioEncoder.HE_AAC
                else -> MediaRecorder.AudioEncoder.AAC
            }

            var outPath: String? = null
            if (ia != null) {
                val v = File(cacheDir, "video_$name.mp4")
                videoTmp = v
                outPath = v.absolutePath
            }

            fun build(w: Int, h: Int): MediaRecorder = try {
                newRecorder(w, h, fps, mbps, useHevc, micSource, sampleRate, channels, encoder, abr, outPath, fd.fileDescriptor)
            } catch (e: Exception) {
                if (micSource == null) throw e
                Toast.makeText(this, "Pengaturan audio tidak didukung, memakai MIC + AAC", Toast.LENGTH_LONG).show()
                newRecorder(
                    w, h, fps, mbps, useHevc, MediaRecorder.AudioSource.MIC, sampleRate, channels,
                    MediaRecorder.AudioEncoder.AAC, abr, outPath, fd.fileDescriptor,
                )
            }

            val mr = try {
                build(vw, vh)
            } catch (e: Exception) {
                val (aw, ah) = scaled(rawW, rawH, side, 16, 16)
                if (aw == vw && ah == vh) throw e
                vw = aw
                vh = ah
                build(vw, vh)
            }
            recorder = mr

            display = proj.createVirtualDisplay(
                "screen_recorder", vw, vh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mr.surface, null, null,
            )
            mr.start()
            try {
                ia?.start()
            } catch (e: Exception) {
                Toast.makeText(this, "Suara internal gagal dimulai", Toast.LENGTH_LONG).show()
            }
            RecorderState.startedAt.value = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            stopRecording()
        }
    }

    private fun newRecorder(
        vw: Int, vh: Int, fps: Int, mbps: Int, hevc: Boolean,
        micSource: Int?, sampleRate: Int, channels: Int, audioEncoder: Int, audioKbps: Int,
        outPath: String?, outFd: FileDescriptor,
    ): MediaRecorder {
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            if (micSource != null) mr.setAudioSource(micSource)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setVideoEncoder(
                if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264,
            )
            if (micSource != null) {
                mr.setAudioEncoder(audioEncoder)
                mr.setAudioChannels(channels)
                mr.setAudioSamplingRate(sampleRate)
                val codecIdx = when (audioEncoder) {
                    MediaRecorder.AudioEncoder.AAC_ELD -> 1
                    MediaRecorder.AudioEncoder.HE_AAC -> 2
                    else -> 0
                }
                mr.setAudioEncodingBitRate(effectiveAudioBitrate(audioKbps, channels, sampleRate, codecIdx))
            }
            mr.setVideoSize(vw, vh)
            mr.setVideoFrameRate(fps)
            mr.setVideoEncodingBitRate(mbps * 1_000_000)
            if (outPath != null) mr.setOutputFile(outPath) else mr.setOutputFile(outFd)
            mr.prepare()
            return mr
        } catch (e: Exception) {
            mr.release()
            throw e
        }
    }

    private fun stopRecording() {
        if (recorder == null && projection == null && uri == null) {
            if (!saving) {
                OverlayState.busy.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }

        var ok = false
        recorder?.let { mr ->
            try { mr.stop(); ok = true } catch (e: RuntimeException) { }
            try { mr.reset() } catch (e: Exception) { }
            mr.release()
        }
        recorder = null

        display?.release()
        display = null

        projection?.let { p ->
            projectionCallback?.let { p.unregisterCallback(it) }
            p.stop()
        }
        projection = null
        projectionCallback = null

        val ia = internalAudio
        internalAudio = null
        ia?.stop()

        val vTmp = videoTmp
        val aTmp = audioTmp
        videoTmp = null
        audioTmp = null
        val fd = pfd
        val u = uri
        pfd = null
        uri = null

        RecorderState.startedAt.value = 0L

        if (ia != null && ok && vTmp != null && aTmp != null && fd != null && u != null) {
            // Gabungkan video + suara internal di thread terpisah agar tidak menahan UI.
            saving = true
            Thread {
                val success = try {
                    if (ia.hasAudio) muxVideoAudio(vTmp, aTmp, fd.fileDescriptor)
                    else copyVideoOnly(vTmp, fd)
                    true
                } catch (e: Exception) {
                    try { copyVideoOnly(vTmp, fd); true } catch (e2: Exception) { false }
                }
                vTmp.delete()
                aTmp.delete()
                main.post { finishRecording(success, fd, u) }
            }.start()
        } else {
            vTmp?.delete()
            aTmp?.delete()
            finishRecording(ia == null && ok, fd, u)
        }
    }

    private fun copyVideoOnly(video: File, fd: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            out.channel.truncate(0)
            FileInputStream(video).use { it.copyTo(out) }
        }
    }

    private fun finishRecording(ok: Boolean, fd: ParcelFileDescriptor?, u: Uri?) {
        OverlayState.busy.value = false
        try { fd?.close() } catch (e: Exception) { }
        if (u != null) {
            if (ok) {
                val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(u, v, null, null)
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            } else {
                contentResolver.delete(u, null, null)
                Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show()
            }
        }
        saving = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording()
        super.onDestroy()
    }

    private fun hasHardwareHevcEncoder(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
        }

    /**
     * Skala ke sisi pendek [side]. Jika diperkecil, dibulatkan ke kelipatan [scaledStep];
     * jika memakai resolusi asli, dibulatkan ke kelipatan [nativeStep].
     */
    private fun scaled(w: Int, h: Int, side: Int, scaledStep: Int, nativeStep: Int): Pair<Int, Int> {
        val shortSide = min(w, h)
        val shrink = side in 1 until shortSide
        val f = if (shrink) side.toFloat() / shortSide else 1f
        val step = if (shrink) scaledStep else nativeStep
        fun align(v: Int) = max(step, (v * f / step).roundToInt() * step)
        return align(w) to align(h)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.stop), stopPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1
    }
}
