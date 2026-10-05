# Yang Harus Kamu Lakukan Sendiri — Hozin Notes v1.1
(Disiapkan 5 Okt 2026 — dikirim bareng APK v1.1)

Dua hal ini memang tidak bisa diwakilkan karena memakai akunmu sendiri.

---

## 1. Daftarkan aplikasi ke Google (biar backup Drive jalan) — ±10 menit

Kenapa error terus: Google belum kenal aplikasi `com.hozinking.notes`.
Setiap aplikasi Android yang mau akses Drive harus didaftarkan dulu dengan
sidik jari tanda tangannya (SHA-1). Sekali daftar, berlaku selamanya —
tidak perlu diulang tiap update.

**Langkah A — ambil SHA-1 dari APK yang kamu install:**
1. Di laptop/HP: install aplikasi `keytool` (sudah bawaan kalau ada Java).
2. Jalankan: `keytool -printcert -jarfile HozinNotes-v1.1.apk`
3. Catat baris **SHA1** (format `AA:BB:CC:...`).

**Langkah B — buat OAuth client di Google Cloud Console:**
1. Buka console.cloud.google.com → login akun Google-mu → buat project baru
   (mis. nama "Hozin Notes").
2. Menu **APIs & Services → Library** → cari **Google Drive API** → Enable.
3. Menu **APIs & Services → OAuth consent screen**:
   - User type: **External** → isi nama aplikasi & email → Save.
   - Di tab **Test users** → tambah email Google-mu sendiri.
   - Di tab **Data access/scopes** → tambah scope `drive.appdata`
     (folder khusus aplikasi, tidak mengotori Drive-mu — ala backup WhatsApp).
4. Menu **APIs & Services → Credentials → Create Credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.hozinking.notes`
   - SHA-1: tempel dari Langkah A → Create.

**Langkah C — di HP:**
1. Buka Hozin Notes → Pengaturan → Backup → **Login Google** (pakai akun
   yang tadi didaftarkan sebagai test user).
2. Coba **Backup sekarang** — harusnya sukses, file tersimpan di folder
   tersembunyi khusus aplikasi di Drive-mu.

Catatan: kalau nanti ganti HP/reset dan install ulang APK yang sama,
SHA-1-nya sama → tidak perlu daftar ulang.

---

## 2. Taruh file update manifest di repo `hozin-chat-worker` — ±3 menit

Repo public `hozin-king/hozin-chat-worker` SUDAH ADA. Aplikasi mengecek file
ini tiap dibuka (max 1x sehari, diam kalau gagal):
`https://raw.githubusercontent.com/hozin-king/hozin-chat-worker/main/hozin-notes-update.json`

**Langkah:**
1. Buka github.com/hozin-king/hozin-chat-worker → **Add file → Create new file**
   → nama file: `hozin-notes-update.json` → isi dengan ini:

```json
{
  "versionCode": 2,
  "versionName": "1.1",
  "changelog": "- Pesan error login Google Drive yang jelas\n- Cek pembaruan otomatis + tombol cek manual\n- Pengingat catatan (notifikasi walau aplikasi ditutup)",
  "apkUrl": "https://github.com/hozin-king/HozinNotes/releases/latest",
  "force": false
}
```

3. Commit. Selesai — aplikasi langsung bisa mendeteksi update.

**Ke depannya (tiap rilis baru):** cukup edit file itu — naikkan `versionCode`
ke angka build terbaru, tulis changelog, commit. Aplikasi user akan
menawarkan update otomatis. (Atau minta Muse yang urus — repo-nya tinggal
dipakai.)
