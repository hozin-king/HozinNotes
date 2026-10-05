# Update Manifest — Hozin Notes

Aplikasi mengecek pembaruan dari file JSON publik ini:
`https://raw.githubusercontent.com/hozin-king/hozin-chat-worker/main/hozin-notes-update.json`

## Status repo
Repo `hozin-king/hozin-chat-worker` SUDAH ADA (public) — file manifest
`hozin-notes-update.json` ditaruh di root repo tersebut (satu repo gabungan:
Cloudflare Worker + manifest update).

## Format manifest (`hozin-notes-update.json`)
```json
{
  "versionCode": 2,
  "versionName": "1.1",
  "changelog": "- Perbaikan pesan error login Google Drive\n- Cek pembaruan otomatis\n- Pengingat catatan",
  "apkUrl": "https://github.com/hozin-king/HozinNotes/releases/latest",
  "force": false
}
```

| Field | Arti |
|---|---|
| `versionCode` | WAJIB. Dibandingkan dengan `BuildConfig.VERSION_CODE`. Update ditawarkan bila lebih besar. |
| `versionName` | Ditampilkan di dialog (mis. "1.1"). |
| `changelog` | Ditampilkan di dialog. Boleh multiline (`\n`). |
| `apkUrl` | Dibuka di browser saat user tap Download. |
| `force` | Bila `true`, dialog tidak bisa ditutup (wajib update). |

## Prosedur tiap rilis (maintainer)
1. Naikkan `versionCode`/`versionName` di `app/build.gradle`.
2. Setelah CI hijau & APK terbit, update file manifest di repo public:
   naikkan `versionCode` ke angka yang sama, tulis changelog.
3. (Opsional) ganti `apkUrl` ke link download langsung APK bila tersedia.

## Perilaku aplikasi
- Auto-check max 1x/24 jam saat MainActivity dibuka.
- Tombol manual: Pengaturan → Tentang → "Cek pembaruan".
- Gagal (offline/timeout/404/JSON rusak): auto → diam; manual → toast singkat.
