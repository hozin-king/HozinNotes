# Chat via Cloudflare Worker — Panduan Setup

Backend chat acak Hozin Notes memakai **Cloudflare Worker + D1**
(menggantikan Firebase). Kode worker ada di `worker/`.

## 1. Install wrangler

```bash
npm install -g wrangler
wrangler login
```

## 2. Buat database D1

```bash
cd worker
wrangler d1 create hozin_chat
```

Catat `database_id` yang dikembalikan, isi ke `wrangler.toml`
(`database_id = "..."`), lalu buat tabel:

```bash
wrangler d1 execute hozin_chat --file=schema.sql
```

## 3. Deploy

```bash
wrangler deploy
```

Catat URL worker, mis. `https://hozin-chat.<subdomain>.workers.dev`.

## 4. Arahkan aplikasi ke worker

URL default ada di
`app/src/main/java/com/hozinking/notes/chat/ChatConfig.java`
(`DEFAULT_BASE_URL`). Ganti dengan URL hasil deploy, lalu build ulang.
(Tanpa build ulang pun bisa dioverride via prefs `chat_worker_url`.)

## Cara kerja (ringkas)

- `POST /api/identify` — daftarkan userId + nama tampilan.
- `POST /api/match` — masuk antrean; bila ada yang menunggu, langsung
  dapat `{roomId, peerName, peerId}`.
- `GET /api/match?userId=` — poll tiap 2 detik untuk pendaftar pertama.
- `POST /api/leave` — keluar antrean (dipanggil saat timeout / back).
- `GET /api/messages?room=&after=&userId=` — pesan baru (poll 2 detik).
- `POST /api/send` — kirim pesan (disensor juga di sisi server).
- `POST /api/report`, `POST /api/block` — lapor & blokir; user yang
  diblokir tidak akan dipasangkan lagi.

Antrean basi (>90 detik) dibersihkan otomatis tiap ada match baru.
Polling 2 detik = pilihan sadar agar tetap jalan di Worker gratis
tanpa Durable Objects/WebSocket.
