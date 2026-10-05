# FIREBASE_SETUP.md — Panduan Setup Firebase & Google Drive untuk Hozin Notes

> Bahasa Indonesia. Lakukan sekali saja. Tanpa langkah ini, aplikasi catatan tetap
> jalan 100% — yang nonaktif hanya fitur **Chat Random** dan **Backup Google Drive**
> (muncul penjelasan ramah di aplikasi, tidak crash).

## Bagian A — Buat Project Firebase (untuk Chat Random)

1. Buka https://console.firebase.google.com/ → **Add project** → nama mis. `hozin-notes`
   → nonaktifkan Google Analytics (tidak butuh) → Create.
2. Di dashboard project → **Build** → **Authentication** → **Get started** →
   tab **Sign-in method** → aktifkan **Email/Password** dan **Google**.
   - Untuk Google: isi *Project public-facing name* dan *support email*.
3. Masih di **Build** → **Realtime Database** → **Create Database** → pilih lokasi
   `asia-southeast1` (Singapura, paling dekat) → mode **locked mode** dulu.
4. Tab **Rules**, ganti dengan rules berikut lalu **Publish**:

```json
{
  "rules": {
    "match_queue": { ".read": "auth != null", ".write": "auth != null" },
    "matches":     { ".read": "auth != null", ".write": "auth != null" },
    "chats": {
      "$chatId": {
        ".read": "auth != null",
        ".write": "auth != null"
      }
    },
    "reports": { ".read": "auth != null", ".write": "auth != null" },
    "blocks":  { ".read": "auth != null", ".write": "auth != null" }
  }
}
```

5. Kembali ke **Project Overview** (ikon gerigi → **Project settings**) →
   **Your apps** → ikon Android → isi:
   - *Android package name*: `com.hozinking.notes` (HARUS persis)
   - *App nickname*: Hozin Notes
   - *Debug signing certificate SHA-1*: lihat Bagian C.
6. Download **google-services.json** → taruh di folder `app/` project ini
   (sejajar dengan `app/build.gradle`), lalu build ulang. Plugin google-services
   aktif otomatis hanya jika file ini ada.

## Bagian B — Google Drive Backup (OAuth)

Backup Drive memakai akun Google yang login di HP — **tidak perlu project
terpisah**, cukup project Firebase di atas:

1. Di Firebase console → **Project settings** → pastikan **Support email** terisi.
2. Buka https://console.cloud.google.com/ → pilih project yang sama →
   **APIs & Services** → **Library** → cari **Google Drive API** → **Enable**.
3. **APIs & Services** → **OAuth consent screen**:
   - User type: **External**, isi nama aplikasi + email → Save.
   - **Scopes** → Add: `https://www.googleapis.com/auth/drive.appdata`
     (akses folder tersembunyi khusus aplikasi saja, tidak membaca Drive user).
   - **Test users** → tambahkan email Google kamu (wajib selama status Testing).
4. Selesai. Di aplikasi: Pengaturan → Backup Drive → **Login Google** →
   pilih akun → backup otomatis jalan.

> Catatan: selama OAuth masih mode *Testing*, token kedaluwarsa tiap 7 hari dan
> hanya test users yang bisa login. Kalau mau permanen, ajukan *Verification*
> di OAuth consent screen (butuh review Google, biasanya untuk aplikasi publik).

## Bagian C — Mendapatkan SHA-1 (debug)

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1
```

Tempel hasilnya di Firebase **Project settings → Your apps → SHA certificate
fingerprints**. Untuk APK release, pakai SHA-1 dari keystore release kamu.

## Bagian D — Cek Berhasil / Tidak

| Gejala | Artinya |
|---|---|
| Menu Chat membuka layar login | Firebase OK |
| Menu Chat menampilkan "Chat belum aktif" | `google-services.json` belum dipasang / salah package name |
| Backup Drive: "Belum login Google" | Login dulu di Pengaturan → Backup Drive |
| Backup Drive: error 403 | Scope `drive.appdata` belum ditambah / email belum jadi test user |

## Bagian E — Tanpa Firebase sama sekali

Bisa. Jangan taruh `google-services.json`, build seperti biasa. Semua fitur
catatan, vault, mindmap, dan backup lokal tetap berfungsi penuh.
