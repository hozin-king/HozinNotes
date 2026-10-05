# Backup Google Drive — Panduan Setup (5–10 menit)

**Kenapa perlu ini?** Login Google di Hozin Notes gagal terus (error 10) BUKAN
karena bug aplikasi, melainkan aturan Google: setiap aplikasi Android yang
minta akses Google Drive HARUS didaftarkan dulu di Google Cloud Console dengan
nama package + sidik jari SHA-1 yang cocok. Tanpa langkah ini, Google menolak
login. Cukup dilakukan SEKALI.

## Yang kamu butuhkan
- Akun Google (boleh akun mana saja)
- SHA-1 aplikasi — lihat di log build GitHub Actions (langkah 5):
  `https://github.com/hozin-king/HozinNotes/actions` → klik run terbaru →
  buka step "Siapkan keystore rilis" → catat baris `SHA1: XX:XX:...`

## Langkah-langkah

### 1. Buat project
1. Buka https://console.cloud.google.com
2. Klik daftar project di atas → **New Project**
3. Nama mis. `Hozin Notes` → **Create**. Tunggu selesai, pastikan project
   tersebut yang aktif.

### 2. Aktifkan Google Drive API
1. Menu ☰ → **APIs & Services** → **Library**
2. Cari `Google Drive API` → klik → **Enable**

### 3. OAuth consent screen
1. Menu ☰ → **APIs & Services** → **OAuth consent screen**
2. User type: **External** → **Create**
3. Isi: App name `Hozin Notes`, User support email = email kamu,
   Developer contact = email kamu → **Save and Continue**
4. Scopes: lewati (klik **Save and Continue**)
5. Test users: **Add Users** → masukkan email Google yang dipakai di HP
   (akun yang akan login di aplikasi) → **Save and Continue**

### 4. Buat OAuth Client ID (Android)
1. Menu ☰ → **APIs & Services** → **Credentials**
2. **+ Create Credentials** → **OAuth client ID**
3. Application type: **Android**
4. Name: `Hozin Notes Android`
5. Package name: `com.hozinking.notes` (persis, huruf kecil semua)
6. SHA-1 certificate fingerprint: tempel SHA-1 dari log GitHub tadi
   (format `A3:A8:4A:...`, tanpa spasi)
7. **Create**

### 5. Selesai — coba lagi
1. Buka Hozin Notes → Pengaturan → Backup Google Drive → **Login Google**
2. Pilih akun Google yang tadi didaftarkan sebagai Test user
3. Setujui izin akses Drive → status berubah jadi "Masuk sebagai ..."

## Catatan
- Kalau nanti muncul error 10 lagi setelah update aplikasi, kemungkinan SHA-1
  berubah — ulangi langkah 4 dengan SHA-1 baru dari log build terbaru.
- Backup tersimpan di folder tersembunyi `appDataFolder` (tidak mengotori
  Drive, ala backup WhatsApp).
