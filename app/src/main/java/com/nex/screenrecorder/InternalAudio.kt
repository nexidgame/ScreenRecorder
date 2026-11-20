package com.nex.screenrecorder

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Merekam suara internal HP (suara game/media) lewat AudioPlaybackCapture,
 * opsional dicampur dengan mikrofon, lalu dienkode ke AAC dan disimpan ke berkas .m4a sementara.
 * Berkas ini digabung dengan video saat rekaman berhenti.
 */
class InternalAudioRecorder(
    private val projection: MediaProjection,
    private val sampleRate: Int,
    private val channels: Int,
    private val audioCodec: Int, // 0 = AAC, 1 = AAC_ELD, 2 = HE-AAC
    private val outFile: File,
    private val mixMic: Boolean = false,
    private val gameGain: Float = 1f,
    private val micGain: Float = 1f,
    private val audioKbps: Int = 128,
) {
    private var record: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var thread: Thread? = null
    private var bufSize = 0

    @Volatile private var running = false

    /** true jika ada data audio yang benar-benar tertulis. */
    @Volatile var hasAudio = false
        private set

    /** true jika mikrofon diminta tetapi gagal dibuka (suara internal tetap direkam). */
    @Volatile var micFailed = false
        private set

    @SuppressLint("MissingPermission")
    fun prepare() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(mask)
            .build()
        val min = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        bufSize = max(min, 8192)
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufSize * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record = rec
        if (rec.state != AudioRecord.STATE_INITIALIZED) error("AudioRecord gagal dibuat")

        if (mixMic) {
            try {
                val m = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize * 2)
                    .build()
                if (m.state != AudioRecord.STATE_INITIALIZED) {
                    m.release()
                    error("Mikrofon gagal dibuka")
                }
                micRecord = m
            } catch (e: Exception) {
                micFailed = true
            }
        }

        val wanted = when (audioCodec) {
            1 -> MediaCodecInfo.CodecProfileLevel.AACObjectELD
            2 -> MediaCodecInfo.CodecProfileLevel.AACObjectHE
            else -> MediaCodecInfo.CodecProfileLevel.AACObjectLC
        }
        codec = try {
            createCodec(wanted)
        } catch (e: Exception) {
            if (wanted == MediaCodecInfo.CodecProfileLevel.AACObjectLC) throw e
            createCodec(MediaCodecInfo.CodecProfileLevel.AACObjectLC) // cadangan: AAC biasa
        }
        muxer = MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun createCodec(profile: Int): MediaCodec {
        val codecIdx = when (profile) {
            MediaCodecInfo.CodecProfileLevel.AACObjectELD -> 1
            MediaCodecInfo.CodecProfileLevel.AACObjectHE -> 2
            else -> 0
        }
        val bitrate = effectiveAudioBitrate(audioKbps, channels, sampleRate, codecIdx)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw e
        }
        return c
    }

    fun start() {
        running = true
        record?.startRecording()
        micRecord?.startRecording()
        thread = Thread { loop() }.also { it.start() }
    }

    fun stop() {
        running = false
        try { thread?.join(5000) } catch (e: InterruptedException) { }
        release()
    }

    fun release() {
        running = false
        try { record?.stop() } catch (e: Exception) { }
        record?.release()
        record = null
        try { micRecord?.stop() } catch (e: Exception) { }
        micRecord?.release()
        micRecord = null
        try { codec?.stop() } catch (e: Exception) { }
        codec?.release()
        codec = null
        try { muxer?.release() } catch (e: Exception) { }
        muxer = null
    }

    /** Membaca suara game dan mikrofon dengan jumlah yang sama, lalu mencampurnya (dengan pembatas). */
    private fun readMixed(
        game: AudioRecord, mic: AudioRecord, out: ByteBuffer, want: Int,
        gb: ByteArray, mb: ByteArray,
    ): Int {
        val n = game.read(gb, 0, want)
        if (n <= 0) return n
        var m = mic.read(mb, 0, n)
        if (m < 0) m = 0
        if (m < n) java.util.Arrays.fill(mb, m, n, 0.toByte())
        var i = 0
        while (i + 1 < n) {
            val g = (gb[i].toInt() and 0xFF) or (gb[i + 1].toInt() shl 8)
            val mv = (mb[i].toInt() and 0xFF) or (mb[i + 1].toInt() shl 8)
            val v = (g * gameGain + mv * micGain).roundToInt().coerceIn(-32768, 32767)
            out.put(i, (v and 0xFF).toByte())
            out.put(i + 1, ((v shr 8) and 0xFF).toByte())
            i += 2
        }
        return n - (n % 2)
    }

    private fun loop() {
        val rec = record ?: return
        val mic = micRecord
        val c = codec ?: return
        val mux = muxer ?: return
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * channels
        val chunkMax = (bufSize / bytesPerFrame) * bytesPerFrame
        val gameBytes = ByteArray(chunkMax)
        val micBytes = ByteArray(chunkMax)
        var totalFrames = 0L
        var inputDone = false
        var outputDone = false
        var track = -1
        var started = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val idx = c.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = c.getInputBuffer(idx)
                        if (ib != null) {
                            ib.clear()
                            val pts = totalFrames * 1_000_000L / sampleRate
                            if (!running) {
                                c.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val want = (minOf(ib.capacity(), chunkMax) / bytesPerFrame) * bytesPerFrame
                                val n = if (mic != null) {
                                    readMixed(rec, mic, ib, want, gameBytes, micBytes)
                                } else {
                                    rec.read(ib, want)
                                }
                                if (n > 0) {
                                    c.queueInputBuffer(idx, 0, n, pts, 0)
                                    totalFrames += n / bytesPerFrame
                                } else {
                                    if (n < 0) running = false
                                    c.queueInputBuffer(idx, 0, 0, pts, 0)
                                }
                            }
                        }
                    }
                }
                while (true) {
                    val out = c.dequeueOutputBuffer(info, 0)
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(c.outputFormat)
                        mux.start()
                        started = true
                    } else if (out >= 0) {
                        val ob = c.getOutputBuffer(out)
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (ob != null && info.size > 0 && started && !isConfig) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            mux.writeSampleData(track, ob, info)
                            hasAudio = true
                        }
                        c.releaseOutputBuffer(out, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // data yang sudah tertulis tetap dipakai
        } finally {
            if (started) {
                try { mux.stop() } catch (e: Exception) { hasAudio = false }
            } else {
                hasAudio = false
            }
        }
    }
}

/** Menggabungkan video (tanpa suara) dan audio .m4a menjadi satu MP4 ke [out]. */
fun muxVideoAudio(video: File, audio: File, out: FileDescriptor) {
    val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val vEx = MediaExtractor()
    val aEx = MediaExtractor()
    try {
        vEx.setDataSource(video.path)
        aEx.setDataSource(audio.path)
        val vi = findTrack(vEx, "video/")
        val ai = findTrack(aEx, "audio/")
        if (vi < 0 || ai < 0) error("Track tidak ditemukan")
        vEx.selectTrack(vi)
        aEx.selectTrack(ai)
        val vt = muxer.addTrack(vEx.getTrackFormat(vi))
        val at = muxer.addTrack(aEx.getTrackFormat(ai))
        muxer.start()

        val buf = ByteBuffer.allocate(4 shl 20)
        val info = MediaCodec.BufferInfo()
        var vDone = false
        var aDone = false
        while (!vDone || !aDone) {
            val useV = !vDone && (aDone || vEx.sampleTime <= aEx.sampleTime)
            val ex = if (useV) vEx else aEx
            val tr = if (useV) vt else at
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) {
                if (useV) vDone = true else aDone = true
                continue
            }
            val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, ex.sampleTime, flags)
            muxer.writeSampleData(tr, buf, info)
            ex.advance()
        }
        muxer.stop()
    } finally {
        try { vEx.release() } catch (e: Exception) { }
        try { aEx.release() } catch (e: Exception) { }
        try { muxer.release() } catch (e: Exception) { }
    }
}

private fun findTrack(ex: MediaExtractor, prefix: String): Int {
    for (i in 0 until ex.trackCount) {
        val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
        if (mime != null && mime.startsWith(prefix)) return i
    }
    return -1
}

/**
 * Bitrate audio (bps) yang dipakai: pilihan pengguna, dibatasi ke batas encoder AAC
 * (sekitar 6144 bit per saluran per frame) agar encoder tidak menolak. Contoh: mono 320 Kbps
 * melebihi batas AAC-LC sehingga diturunkan otomatis.
 * [audioCodec]: 0 = AAC-LC, 1 = AAC_ELD, 2 = HE-AAC.
 */
fun effectiveAudioBitrate(kbps: Int, channels: Int, sampleRate: Int, audioCodec: Int): Int {
    val perChannelMax = when (audioCodec) {
        2 -> 6144.0 * (sampleRate / 2.0) / 1024.0 // HE-AAC: inti memakai separuh sample rate
        1 -> 6144.0 * sampleRate / 512.0          // AAC_ELD
        else -> 6144.0 * sampleRate / 1024.0      // AAC-LC
    }
    val max = (perChannelMax * channels * 0.98).toInt()
    return minOf(kbps * 1000, max)
}
