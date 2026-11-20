# Screen Recorder

Perekam layar minimalis untuk Android 11 (API 30) sampai Android 16 (API 36).

## Cara build
1. Buka folder ini di Android Studio (Ladybug atau lebih baru, JDK 17), tunggu Gradle sync.
2. Hubungkan HP, lalu Run. Atau Build > Generate Signed App Bundle / APK untuk rilis.
3. Setelah sync pertama, Android Studio membuat file gradle-wrapper.jar sendiri.

## Kenapa ringan
- MediaRecorder + encoder hardware (H.264 atau H.265): frame dari layar langsung ke encoder, tanpa proses di CPU.
- Tidak ada kamera atau penggambaran sentuhan. Tombol melayang bersifat opsional dan hilang saat merekam.
- UI hanya aktif saat aplikasi dibuka; saat merekam, aplikasi langsung ke latar belakang.

## Fitur
Resolusi (480p/720p/1080p/asli), 30/60/90/120 fps, bitrate 4-16 Mbps,
codec H.264 atau H.265 (otomatis kembali ke H.264 jika HP tidak mendukung H.265),
audio opsional dengan pilihan:
- Sumber suara: MIC, Raw (tanpa terproses), Performa Suara, Suara Internal, atau Internal + MIC
  (suara game dan mikrofon dicampur, dengan pengaturan volume masing-masing 0-200%)
- Tingkat sampel: 44100 Hz atau 48000 Hz
- Saluran: mono atau stereo
- Enkoder: AAC, AAC_ELD, atau HE-AAC
- Bit rata-rata audio: 64, 128, 256, atau 320 Kbps (otomatis diturunkan jika melebihi batas encoder)
Jika kombinasi audio tidak didukung HP, aplikasi otomatis memakai AAC (dan MIC untuk sumber mikrofon).
Video tersimpan di Movies/ScreenRecorder lewat MediaStore (tanpa izin penyimpanan).

## Tampilan
Empat tab di bagian bawah:
- **Rekam**: tombol rekam, timer, ringkasan pengaturan, dan saklar tombol melayang.
- **Video**: resolusi, frame rate, bitrate, dan codec.
- **Audio**: sumber dan volume, serta kualitas audio.
- **Pengaturan**: tentang aplikasi dan pengembalian pengaturan awal.

## Tombol rekam melayang
Aktifkan saklar "Tombol rekam melayang" di aplikasi (butuh izin "Tampil di atas aplikasi lain").
Tombol merah muncul di tengah layar dan bisa digeser. Saat digeser, ikon tempat sampah muncul di
bagian bawah layar; lepaskan tombol di atasnya untuk menghapus (mematikan) tombol melayang, lalu
aktifkan lagi lewat saklar di tab Rekam. Ketuk untuk mulai merekam; tombol langsung
hilang sehingga tidak ikut terekam, dan muncul lagi setelah rekaman selesai disimpan.
Berhenti lewat tombol Berhenti di notifikasi. Android tetap menampilkan dialog izin rekam layar
setiap kali rekaman dimulai.

## Mode trigger
Di tab Pengaturan, pilih cara memunculkan tombol hijau saat merekam (aktif jika tombol rekam melayang
dinyalakan). Geser dari tepi layar, lalu ketuk tombol hijau untuk menyimpan rekaman. Tombol hijau hilang
sendiri setelah beberapa detik jika tidak diketuk, dan tombol rekam muncul lagi setelah rekaman tersimpan.
- Klik Ikon: hanya tombol rekam melayang (berhenti lewat notifikasi).
- Geser dari Sisi: geser dari tepi layar (kanan, kiri, atau kedua sisi).
- Geser Setengah Atas: geser dari tepi layar bagian atas (setengah tinggi layar).
- Geser Dapat Diatur: sisi kanan, kiri, atas, bawah, atau kedua sisi. Area cakupan diatur lewat
  editor layar penuh: seret dua pegangan di tepi layar, lalu Simpan.
- Sensitivitas geser: Paling Tidak Sensitif, Kurang Sensitif, Bawaan, atau Lebih Sensitif.

## Catatan Android 14-16
Foreground service bertipe mediaProjection dimulai sebelum getMediaProjection(),
callback didaftarkan sebelum createVirtualDisplay(), dan UI edge-to-edge.
Suara Internal memakai AudioPlaybackCapture: direkam terpisah lalu digabung dengan video
saat berhenti, jadi ada jeda singkat untuk menyimpan. Aplikasi/game yang melarang
perekaman audio akan menghasilkan suara kosong.
