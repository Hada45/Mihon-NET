# Mihon Net 🚀
### Next-Gen Manga Reader with High-Performance SMB & Network Streaming

**Mihon Net** adalah fork pengembangan khusus dari [Mihon](https://github.com/mihonapp/mihon) yang menghadirkan kemampuan **SMB (Windows Share / Samba) & Network Streaming langsung**, memungkinkan Anda membaca koleksi manga/komik yang tersimpan di PC atau NAS tanpa perlu mengunduh seluruh bab ke penyimpanan HP terlebih dahulu.

---

## ✨ Fitur Unggulan (Key Features)

### ⚡ SMB Direct Archive Streaming (Fast-Path CBZ/ZIP)
- **Instan Buka Bab (<150ms)**: Menggunakan pembaca *Central Directory* cerdas yang hanya membaca metadata ujung file `.cbz` / `.zip` di server. Tidak perlu mengunduh seluruh arsip untuk mulai membaca.
- **Dedicated Metadata Channel**: Jalur pembacaan struktur folder dan arsip (`metaShare`) dipisahkan total dari jalur streaming gambar, memastikan pembukaan bab selalu mulus dan tidak pernah terhambat oleh unduhan gambar yang sedang berlangsung.

### 🛡️ Windows SMB Server Friendly (Anti Rate-Limiter)
- **Singleton SMB Engine**: Mencegah kebocoran soket TCP, thread pool, dan event loop internal.
- **Bounded Connection Pool (`Semaphore(2)`)**: Membatasi koneksi konkuren agar tidak memicu fitur keamanan *Auth Rate Limiter* pada Windows Server / Windows 11 SMB (`EnableAuthRateLimiter: True`).

### 📊 Built-in Realtime SMB Performance Profiler
- Dilengkapi dengan sistem pencatatan performa terintegrasi (`SmbPerfLogger`) yang melacak durasi buka bab, waktu latensi pemuatan halaman, dan kecepatan transfer jaringan secara riil (MB/s).
- Akses log kapan saja langsung dari HP melalui menu:
  **Pengaturan ➔ Lanjutan ➔ Log Performa SMB** untuk menyalin atau membagikan riwayat metrik.

### 💾 Dual-Tier Caching System
- **Memory + Disk Cache**: Halaman yang telah dibaca disimpan secara lokal sehingga navigasi bolak-balik antar halaman berjalan instan (**0 ms**).

---

## 📥 Unduh / Download APK

Dapatkan file APK siap pasang pada menu [Releases](../../releases):
- **ARM64-v8a (Direkomendasikan)**: Untuk ponsel modern (Snapdragon, Dimensity, Exynos 64-bit).
- **Universal**: Untuk kompatibilitas perangkat lama atau emulator.

---

## 🛠️ Cara Penggunaan (Setup Windows Share)

1. **Di PC Windows**:
   - Bagikan folder koleksi komik/manga Anda (*Klik kanan folder ➔ Properties ➔ Sharing ➔ Advanced Sharing ➔ Berikan izin akses*).
2. **Di Aplikasi Mihon Net**:
   - Buka **Pengaturan ➔ Data dan Penyimpanan ➔ Penyimpanan Jaringan (SMB)**.
   - Masukkan IP PC Anda (misal: `192.168.1.x`), Port `445`, nama Share, serta username & password PC jika ada.
   - Selesai! Buka tab Library Anda dan manga di server PC Anda akan langsung dapat dibaca seketika.

---

## 📜 Lisensi & Atribusi

Proyek ini dibangun di atas basis kode [Mihon](https://github.com/mihonapp/mihon) di bawah lisensi [Apache 2.0](LICENSE).
