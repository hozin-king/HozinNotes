# BUILD_NOTES.md — Hozin Notes v1.0

> Project: aplikasi catatan Android, **Java 100%** (tanpa Kotlin/WebView).
> Package: `com.hozinking.notes` · minSdk 26 · targetSdk 34 · nama: "Hozin Notes"
> Status: kode lengkap ditulis 2026-10-05, **belum pernah di-compile** (butuh CI/device).

## 1. Struktur file penting

```
~/workspace/hozin-notes/
├── RANCANGAN.md            # spesifikasi (JANGAN dihapus)
├── FIREBASE_SETUP.md       # panduan Firebase + Drive OAuth (untuk user)
├── BUILD_NOTES.md          # file ini
├── settings.gradle / build.gradle / gradle.properties
├── gradlew + gradle/wrapper/   # wrapper Gradle 8.7 (jar sudah diunduh)
├── .github/workflows/android.yml  # CI: JDK 17 → assembleDebug → upload APK
└── app/
    ├── build.gradle        # semua dependensi; plugin google-services
    │                       # HANYA aktif bila app/google-services.json ada
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml   # 12 activity + FileProvider terdaftar
        ├── java/com/hozinking/notes/
        │   ├── HozinNotesApp.java        # init SQLCipher + DB + repo + tema
        │   ├── data/db/                  # 15 file: 7 entitas, 5 DAO,
        │   │                             #   AppDatabase (WAL), VaultDatabase (SQLCipher),
        │   │                             #   NoteFts (FTS4)
        │   ├── data/repo/NoteRepository.java  # satu pintu, executor 4 thread
        │   ├── security/                 # CryptoManager (AES-256-GCM,
        │   │                             #   kunci di Android Keystore), HashUtils (SHA-256)
        │   ├── backup/                   # LocalBackupManager (ZIP+manifest),
        │   │                             #   DriveBackupManager (appDataFolder),
        │   │                             #   BackupScheduler (WorkManager)
        │   ├── worker/AutoBackupWorker.java
        │   ├── ui/                       # MainActivity (5 tab), NoteList,
        │   │                             #   Editor, Search, Folder, Archive,
        │   │                             #   Trash, Vault, VaultPin, Settings,
        │   │                             #   dialog/ (IosDialog, IosActionSheet),
        │   │                             #   mindmap/ (MindmapView custom)
        │   └── chat/                     # FirebaseGate, BadWordFilter,
        │                                 #   Login, RandomMatch, Chat
        └── res/  # 23 layout, values (colors/themes/strings×3),
                  #  8 drawable, menu/bottom_nav.xml, xml/file_paths.xml
```

Total: **44 file Java**, 23 layout XML.

## 2. Fitur yang terimplementasi (sesuai RANCANGAN.md §9a–9k)

- Catatan teks panjang + checklist (`- [ ]`/`- [x]`, tombol +Item & Centang)
- Gambar terenkripsi AES-256-GCM (kunci di Keystore), thumbnail 256px terenkripsi
- SHA-256 tiap file; mismatch saat baca = file dianggap rusak, tidak dipakai
- Folder + tags + warna + pin + arsip + sampah 30 hari + undo hapus (swipe)
- Pencarian FTS4 + debounce 300ms
- Vault privat: database SQLCipher terpisah, FLAG_SECURE anti-screenshot,
  PIN 6 digit + biometrik, ikut format backup (tetap terenkripsi)
- Mindmap: node Folder + Tag, pan/zoom, tap node = info dialog
- URL di catatan tappable (Linkify → intent ke aplikasi pendukung)
- Popup iOS: IosDialog (tengah) + IosActionSheet (bawah) untuk semua konfirmasi
- Backup lokal: ZIP (notes.json + images/ + manifest.json berisi sha256),
  ekspor/impor via Storage Access Framework, mode gabung/timpa, validasi hash
- Backup Drive: appDataFolder tersembunyi, tombol "Cadangkan sekarang",
  jadwal otomatis 1/7/custom hari + opsi hanya-WiFi, simpan 3 versi terakhir
- Chat random: Firebase Auth (email/Google), matchmaking queue,
  BadWordFilter Indonesia (leet-normalize + sensor `*`), laporkan & blokir.
  **Runtime-guarded**: tanpa `google-services.json`, menu chat menampilkan
  penjelasan ramah, tidak crash.
- Tema terang/gelap/sistem, kunci aplikasi (PIN vault), Bahasa Indonesia.

## 3. Anti-lag (diimplementasikan, bukan komentar)

- Paging 3 page 60 + RecyclerView + DiffUtil (daftar)
- Item list hanya preview `substr(content,1,180)` + maxLines 3
- Thumbnail dibuat sekali saat insert (dari bytes terdekripsi di memori)
- FTS4 untuk pencarian, WAL mode untuk DB, semua query di background thread
- Auto-save editor debounce 800ms

## 4. Cara setup Firebase & Drive (ringkas)

Lihat [FIREBASE_SETUP.md](sandbox://workspace/hozin-notes/FIREBASE_SETUP.md) —
buat project Firebase → aktifkan Auth (Email/Google) + Realtime Database
(pakai rules di dokumen) → download `google-services.json` ke `app/` →
aktifkan Drive API + scope `drive.appdata` di OAuth consent → tambah email
sebagai test user.

## 5. Batasan / belum terverifikasi (jujur)

1. **Belum pernah di-compile.** Verifikasi yang dilakukan: `javac` untuk 44 file —
   0 error sintaks murni (error yang ada hanya "cannot find symbol" /
   "package does not exist" karena Android SDK tidak ada di VM, sesuai
   aturan filter). Kompilasi penuh + uji jalan **wajib lewat CI GitHub**
   (workflow sudah siap) atau Android Studio.
2. **Belum diuji di HP nyata**: alur kamera/galeri, biometrik, SQLCipher di
   ARM, WorkManager, dan performa 20.000 catatan belum terbukti di device.
3. **Drive backup belum diuji end-to-end** (butuh OAuth + akun test user).
4. **Chat random belum diuji** (butuh 2 akun + Firebase aktif); logika
   matchmaking sederhana (queue 2 orang), bukan skala produksi.
5. **Mindmap** menampilkan hierarki Folder + Tag saja (tanpa node catatan per
   folder) karena repository tidak mengekspos query top-N per folder —
   keputusan sadar agar tidak mengarang API. Bisa ditambah nanti.
6. **Backup vault (ditambahkan 2026-10-05):** ZIP kini memuat `vault/vault.db`
   (file SQLCipher APA ADANYA — tetap terenkripsi, tidak pernah didekripsi) +
   sha256 di manifest, plus file gambar vault bila ada. Restore memakai
   semantik **REPLACE**: vault.db aktif ditimpa (koneksi ditutup dulu, hash
   diverifikasi sebelum dipakai; tidak cocok = ditolak). **Batasan jujur:**
   backup vault hanya bisa dipulihkan di **perangkat yang sama** karena kunci
   SQLCipher tersimpan di Android Keystore yang terikat perangkat — pindah HP
   (atau reset pabrik) membuat backup vault tidak bisa dibuka. Ekspor manual
   terpisah untuk pindah perangkat **belum diimplementasikan**. Passphrase
   tidak pernah ditulis ke backup/log.
7. Filter bad word client-side tidak 100% (bisa diakali typo) — moderasi
   laporan sebagai pelengkap.
8. `google-services.json` TIDAK disertakan (milik user, sensitif) — CI build
   tetap jalan tanpa itu (plugin conditional).
9. Belum ada repo GitHub / push — menunggu parent agent.

## 6. Perintah build lokal (butuh Android SDK)

```bash
cd ~/workspace/hozin-notes
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
