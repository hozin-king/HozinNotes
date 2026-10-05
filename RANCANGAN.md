# Hozin Notes — Rancangan Aplikasi

> Usulan nama: **Hozin Notes** · package `com.hozinking.notes` (bisa diganti)
> Platform: Android native (Kotlin) · minSdk 26 (Android 8) · targetSdk 35
> Status: RANCANGAN — belum ada kode, tunggu persetujuan user sebelum dibangun.

---

## 1. Konsep

Aplikasi catatan yang **fiturnya lengkap tapi tetap gampang dipakai**: buka → langsung nulis, satu tombol + yang gede, sisanya rapi di balik menu sederhana. Fokus utama: **tetap ngebut walau catatan sudah puluhan ribu dan isinya panjang-panjang**, plus backup yang beres (lokal + Google Drive ala WhatsApp).

---

## 2. Fitur v1 (dibangun duluan)

| Fitur | Keterangan |
|---|---|
| Catatan teks | Judul + isi bebas panjang, auto-save tiap berhenti ngetik |
| Checklist | Daftar todo yang bisa dicentang, campur dengan teks biasa |
| Gambar | Sisipkan dari galeri/kamera, beberapa gambar per catatan |
| Label + warna | Kelompokkan catatan (mis. Kerja, Pribadi, Ide) + kode warna |
| Pin | Catatan penting nempel di atas |
| Arsip & Sampah | Arsip disembunyikan; sampah terhapus permanen setelah 30 hari |
| Pencarian instan | Cari di judul + isi, hasil muncul saat ngetik |
| Urutan | Terakhir diubah / dibuat / abjad |
| Tema | Terang, gelap, ikut sistem |
| Kunci aplikasi | PIN / sidik jari (opsional, bisa dimatikan) |

### v2 (nanti, tidak dibangun sekarang)
- Pengingat per catatan (reminder + notifikasi)
- Widget home
- Ekspor per catatan ke PDF / Markdown
- Enkripsi backup pakai password

---

## 3. Arsitektur Anti-Lag (inti permintaan user)

Masalahnya bukan "aplikasi catatan itu berat", tapi aplikasi yang **memuat semuanya ke memori sekaligus**. Rancangannya:

1. **Database SQLite via Room, mode WAL** — baca dan tulis bisa jalan bareng tanpa ngunci. Index di kolom `updated_at` dan `label_id` biar query list selalu cepat.
2. **Paging 3** — daftar catatan dimuat 60 item per halaman, nambah otomatis saat scroll (persis cara Discord me-render ribuan chat tanpa lag).
3. **RecyclerView + DiffUtil** — list di-render ulang cuma untuk baris yang berubah, ViewHolder ringan.
4. **Preview, bukan full content** — di daftar cuma tampil 3 baris pertama; isi penuh baru dimuat pas catatan dibuka (query by id, secepat kilat).
5. **Gambar malas (lazy)** — file asli disimpan di penyimpanan aplikasi; saat gambar dimasukkan, langsung dibuat **thumbnail 256px**. Di list yang tampil cuma thumbnail via Coil (cache memori + disk). Gambar full cuma dibuka pas diketuk.
6. **Pencarian FTS4** — tabel virtual full-text-search; 20.000 catatan tetap ketemu dalam milidetik, query jalan di background dengan debounce 300ms.
7. **Semua kerja berat di background** — coroutines `Dispatchers.IO`; Room dipaksa tidak boleh query di main thread.
8. **Auto-save debounce 800ms** — ngetik tidak memicu tulis database tiap huruf.

**Target performa:** 20.000 catatan + 5.000 gambar → scroll tetap 60fps, cold start < 1 detik, RAM stabil.

---

## 4. Sistem Backup (ala WhatsApp)

### 4a. Backup lokal
- Satu file `.zip` berisi `notes.json` (semua catatan + label + metadata) + folder `images/`.
- Ekspor ke folder Downloads (pilih lokasi via system picker).
- Impor: pilih file zip → validasi manifest → pilih **gabung** atau **timpa**.

### 4b. Backup Google Drive
- Login pakai akun Google (OAuth, scope Drive).
- File disimpan di **appDataFolder** (folder tersembunyi khusus aplikasi — tidak ngotorin Drive user, persis cara WhatsApp menyimpan backup).
- **Auto-backup** via WorkManager: pilihan harian / mingguan / hanya saat WiFi; simpan 3 versi terakhir, yang lama dihapus otomatis.
- **Restore:** daftar backup tampil (tanggal + ukuran) → pilih → download → pulihkan.
- Catatan jujur: butuh setup sekali di Google Cloud Console (OAuth client + SHA-1). Nanti dipandu langkahnya, tidak sulit.

---

## 5. Teknologi

- **Bahasa:** Kotlin 100%
- **Database:** Room (SQLite) + Paging 3
- **Gambar:** Coil
- **Background job:** WorkManager + Coroutines/Flow
- **UI:** Material 3, tema Glossy (selera user) / Doff
- **Drive:** Google Sign-In + Drive API v3
- **Build:** Gradle + GitHub Actions CI (pola sama seperti project Android user sebelumnya)

---

## 6. Struktur Project (rencana)

```
app/src/main/java/com/hozinking/notes/
├── data/
│   ├── db/          # NoteEntity, ImageEntity, LabelEntity, DAO, FTS
│   └── repo/        # NoteRepository (satu pintu ke database)
├── backup/
│   ├── LocalBackupManager.kt   # export/import zip lokal
│   └── DriveBackupManager.kt   # upload/download appDataFolder
├── ui/
│   ├── list/        # daftar catatan (Paging + RecyclerView)
│   ├── editor/      # layar tulis (teks, checklist, gambar)
│   ├── search/      # pencarian FTS
│   └── settings/    # tema, kunci, backup
└── worker/
    └── AutoBackupWorker.kt      # jadwal backup otomatis
```

---

## 7. Tahapan Build

1. Scaffold project + CI GitHub Actions
2. Database + daftar + editor (inti aplikasi)
3. Gambar + label + pencarian
4. Backup lokal (export/import zip)
5. Backup Google Drive + auto-backup
6. Poles UI + uji beban (ribuan catatan dummy)

---

## 8. Batasan Jujur

- Backup Drive butuh internet dan setup OAuth sekali (dipandu).
- Enkripsi backup pakai password masuk v2 (v1 backup polos tapi di folder privat aplikasi + appDataFolder).
- Sinkron real-time antar device (seperti Google Keep) **tidak** termasuk — backup/restore manual/otomatis sudah mencakup kebutuhan ganti HP.

---

## 9. REVISI 2026-10-05 — tambahan permintaan user (masuk v1)

> Bahasa: **Java** (bukan Kotlin, bukan HTML/WebView). Semua di bawah ini dibangun di v1 kecuali disebut lain.

### 9a. Organisasi: Folder + Tags
- **Folder/grup catatan**: catatan bisa dimasukkan ke folder (mis. Kerja, Kuliah). Satu catatan bisa di banyak folder? v1: satu catatan satu folder + banyak tags (lebih simpel & predictable).
- **Tags**: tag bebas per catatan (`#ide`, `#penting`), bisa banyak; filter gabungan folder + tag.
- Warna catatan tetap ada.

### 9b. Private Vault (ruang privat)
- Folder/catatan privat terpisah, dibuka pakai PIN/sidik jari.
- **Anti-screenshot**: `FLAG_SECURE` di semua activity vault (tidak bisa SS / screen record).
- **Enkripsi kuat**: database vault pakai SQLCipher (AES-256); file gambar di vault dienkripsi AES-256-GCM (kunci di Android Keystore).
- Isi vault **bisa ikut di-backup** (tetap terenkripsi di file backup).

### 9c. Enkripsi gambar (semua catatan, bukan cuma vault)
- Semua gambar catatan disimpan terenkripsi AES-256-GCM, kunci di Android Keystore.
- Thumbnail dibuat dari gambar terdekripsi di memori, tidak disimpan polos.

### 9d. Integritas file (hash ID)
- Setiap file (gambar, file backup) dihitung **SHA-256**-nya saat disimpan; hash disimpan di database.
- Setiap kali file dibaca, hash diverifikasi ulang — **kalau tidak cocok = file rusak, tidak dipakai**, user diberi tahu dan ditawari hapus.

### 9e. Chat random + akun
- **Daftar/login akun**: Firebase Auth (email + password, Google Sign-In).
- **Chat random**: cari teman mengobrol acak (matchmaking via Firebase), chat 1-on-1.
- **Proteksi bad word**: filter kata kasar Indonesia di sisi client (daftar kata + variasi), pesan kotor disensor otomatis; tombol laporkan & blokir.
- Catatan: fitur chat butuh Firebase project milik user (`google-services.json`) — setup sekali, dipandu. Tanpa itu, catatan tetap jalan penuh, chat nonaktif.

### 9f. Mindmap
- Tampilan mindmap: node folder → node catatan, bisa pan/zoom, ketuk node untuk buka catatan.
- v1: visualisasi baca + navigasi (edit struktur tetap lewat folder/tags).

### 9g. URL bisa diklik
- Link di isi catatan otomatis jadi tappable; diketuk → dibuka aplikasi yang mendukung (YouTube → aplikasi YouTube, dsb.) via intent.

### 9h. Hapus & Arsip
- **Ruang Arsip** terpisah (arsip tidak terhapus, cuma disembunyikan).
- Hapus: swipe dengan **Undo** + masuk Sampah 30 hari, baru permanen.

### 9i. Popup ala iOS
- Dialog gaya iOS: alert di tengah membulat + action sheet dari bawah, background blur/redup.

### 9j. Jadwal backup Drive (fleksibel)
- Pilihan: **tombol "Cadangkan sekarang"** (manual) + otomatis: tiap 1 hari / 7 hari / jumlah hari custom, opsi hanya saat WiFi.
- Tetap di appDataFolder (tersembunyi), simpan 3 versi terakhir.

### 9k. UI
- Sebagus dan serapih mungkin, gaya glassmorphism ala iOS/Telegram (selera user), **dibuat terlihat seperti didesain manusia, bukan template AI**: spacing konsisten, tipografi rapi, micro-interaction halus.
- Bahasa: **Java semua** (tidak ada WebView/HTML untuk UI utama).

### Batasan jujur tambahan
- Filter bad word client-side tidak 100% sempurna (orang bisa mengakali dengan typo); moderasi laporan user sebagai pelengkap.
- Chat random butuh koneksi internet dan Firebase; tanpa setup Firebase, menu chat tampil nonaktif dengan penjelasan.
