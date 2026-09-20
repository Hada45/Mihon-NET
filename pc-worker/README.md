# Mihon Download Worker (PC Engine) v2.0

Standalone Windows service to offload and download manga/comic chapters directly to PC storage for **Mihon Net**.

---

## 🇬🇧 English

### Overview
Mihon Download Worker is a lightweight, high-performance background worker for Windows. When paired with **Mihon Net**, chapter download jobs created on your phone are offloaded to this PC engine, which directly downloads and packages chapters into CBZ or folders without using phone bandwidth, storage, or battery.

### Features
- **Customizable Storage Path**: Choose any drive or folder on your PC (e.g. `J:\Mihon\downloads`, `D:\Manga`, etc.) via console shortcut `[D]`, `config.json`, or CLI argument.
- **Direct PC Downloads**: Saves directly to your PC storage.
- **CBZ & Folder Packaging**: Automatically formats downloaded chapters into clean `.cbz` archives or directories.
- **Image Integrity Verification**: Validates magic bytes (JPEG, PNG, GIF, WebP, AVIF) to prevent corrupt pages or HTML error pages.
- **Secure 6-Digit Pairing**: One-touch pairing with rotating pairing codes.
- **Bilingual Interface**: English (default) and Indonesian support (press `[L]` to toggle).
- **Disk Safety**: Monitors free disk space and prevents downloading if storage falls below configured minimum threshold.

### How to Customize Storage Path
You can change the download folder in 4 ways:
1. **Interactive Console**: While the worker is running, press `[D]` on your keyboard and type your desired folder path.
2. **Configuration File**: Open `config.json` and edit `"StorageRoot": "D:\\MyManga"`.
3. **Command-Line Argument**: Run `MihonPcWorker.exe --storage "D:\MyManga"` (or `-s "D:\MyManga"`).
4. **Batch Script**: Edit `START_WORKER.bat` to pass `--storage "D:\MyManga"`.

### Quick Start
1. **Start Worker**: Double-click `START_WORKER.bat` (or run `MihonPcWorker.exe`).
2. **Note IP & Pairing Code**: The console will display your PC's local IP address (e.g. `http://192.168.1.50:2223`) and a 6-digit Pairing Code.
3. **Configure Mihon Net**:
   - Open **Mihon Net** on your phone.
   - Go to **More** -> **Settings** -> **Downloads** -> **Download Worker**.
   - Turn on **Enable Download Worker**.
   - Set the Worker Host to your PC's IP and Port (e.g. `192.168.1.50` and `2223`).
   - Enter the 6-digit Pairing Code and tap **Pair / Verify**.
4. **Download**: Any chapter download tapped in Mihon Net will now be downloaded directly by your PC!

### Keyboard Shortcuts
- `[D]` : Change storage path interactively
- `[C]` : Open current downloads folder in Windows Explorer
- `[P]` : Generate a new 6-digit pairing code
- `[L]` : Switch display language (English / Indonesian)
- `[Q]` : Gracefully stop the worker

### Configuration (`config.json`)
- `StorageRoot` (string): Storage root directory (default `J:\Mihon`). Chapters are saved in `<StorageRoot>\downloads`.
- `Port` (int): HTTP API port (default `2223`).
- `Token` (string): Secret authentication token automatically exchanged during pairing.
- `PairCode` (string): Current 6-digit one-time code for pairing new devices.
- `Language` (string): UI language (`"en"` or `"id"`).
- `Concurrency` (int): Maximum simultaneous chapter downloads (1 to 6, default `3`).
- `DefaultCbz` (bool): If `true`, chapters are saved as `.cbz` files (default `true`).
- `MinFreeGb` (int): Minimum free disk space in GB required to download (default `2`).
- `AutoCleanup` (bool): Automatically remove oldest completed jobs when storage is low (default `false`).
- `HistoryDays` (int): Days to retain completed job logs (default `7`).

---

## 🇮🇩 Bahasa Indonesia

### Ringkasan
Mihon Download Worker adalah layanan background ringan dan cepat untuk Windows. Saat dipasangkan dengan **Mihon Net**, tugas pengunduhan chapter dari ponsel akan dialihkan ke PC ini, yang akan mengunduh dan mengemas chapter langsung ke penyimpanan PC tanpa menguras kuota, memori, atau baterai ponsel.

### Fitur Utama
- **Path Penyimpanan Bebas Diubah**: Anda bisa memilih drive atau folder apa saja di PC (contoh `J:\Mihon\downloads`, `D:\Komik`, dll.) melalui tombol konsol `[D]`, file `config.json`, atau parameter CLI.
- **Unduh Langsung ke PC**: Menyimpan langsung ke harddisk PC Anda.
- **Format CBZ & Folder**: Mengemas chapter otomatis menjadi file `.cbz` rapi atau folder gambar.
- **Verifikasi Integritas Gambar**: Memeriksa header magic bytes (JPEG, PNG, GIF, WebP, AVIF) agar tidak ada file korup atau halaman error HTML.
- **Pairing Aman 6 Digit**: Pairing cepat dengan kode acak sekali pakai.
- **Dua Bahasa (Bilingual)**: Bahasa Inggris (bawaan) dan Bahasa Indonesia (tekan `[L]` untuk beralih).
- **Proteksi Kapasitas Disk**: Memantau sisa ruang harddisk dan menghentikan unduhan jika ruang disk kurang dari batas minimum.

### Cara Mengubah / Custom Path Penyimpanan
Ada 4 cara mudah untuk mengganti lokasi penyimpanan:
1. **Langsung di Layar Worker**: Saat worker berjalan di console, tekan tombol `[D]`, lalu ketik folder yang diinginkan (contoh: `D:\Komik`).
2. **Lewat File Konfigurasi**: Buka `config.json` dan ubah `"StorageRoot": "D:\\Komik"`.
3. **Lewat Parameter Terminal**: Jalankan `MihonPcWorker.exe --storage "D:\Komik"` (atau `-s "D:\Komik"`).
4. **Lewat Script BAT**: Edit file `START_WORKER.bat` dan tambahkan `--storage "D:\Komik"`.

### Cara Menggunakan
1. **Jalankan Worker**: Klik ganda file `START_WORKER.bat` (atau jalankan `MihonPcWorker.exe`).
2. **Lihat IP & Kode Pairing**: Layar konsol akan menampilkan alamat IP lokal PC Anda dan 6 digit Kode Pairing.
3. **Atur di Mihon Net**:
   - Buka **Mihon Net** di ponsel.
   - Buka **Lainnya** -> **Pengaturan** -> **Unduhan** -> **Download Worker**.
   - Aktifkan **Enable Download Worker**.
   - Masukkan IP PC dan Port (bawaan `2223`).
   - Masukkan 6 digit Kode Pairing lalu klik **Pair / Verify**.
4. **Mulai Mengunduh**: Saat Anda klik unduh chapter di Mihon Net, PC akan mengunduhnya secara otomatis!

### Tombol Navigasi Keyboard
- `[D]` : Ubah lokasi simpan unduhan
- `[C]` : Buka folder unduhan di Windows Explorer
- `[P]` : Buat kode pairing baru
- `[L]` : Ganti bahasa tampilan (English / Indonesia)
- `[Q]` : Tutup dan hentikan worker

---

## 📱 Mihon Worker for Android (STB / TV Box / Android Phone)

An all-in-one APK that combines both the **Download Worker** and a **Built-in FTP Server**, so you **do not need any third-party FTP apps** (like X-plore)!

### How it works:
1. Install `MihonWorker-Android-debug.apk` on your STB / Android TV / Secondary Phone.
2. Open the app, choose your storage folder (Internal storage, SD Card, or USB Hard Drive).
3. The app screen displays:
   - **Device IP**: (e.g. `192.168.1.100`)
   - **Pairing Code**: (6-digit code)
   - **Worker API**: `http://192.168.1.100:2223`
   - **Built-in FTP Server**: `ftp://192.168.1.100:2121` (User: `mihon` / Pass: `mihon`)
4. Click **"Save and start worker"**.
5. On **Mihon Net** (your main phone):
   - **Downloads -> Download Worker**: Host `192.168.1.100`, Port `2223`, enter pairing code.
   - **Downloads -> Network Storage -> FTP**: Host `192.168.1.100`, Port `2121`, User `mihon`, Pass `mihon`.
6. Now you can offload downloads to the STB and stream/read chapters directly from the STB without any other apps running!

---

## 📱 Mihon Worker untuk Android (STB / Android TV / HP Android)

Aplikasi APK lengkap yang menggabungkan **Download Worker** dan **Server FTP Bawaan (Built-in FTP)**, sehingga Anda **sama sekali tidak memerlukan aplikasi pihak ketiga lagi** (seperti X-plore File Manager)!

### Cara Penggunaan:
1. Pasang `MihonWorker-Android-debug.apk` di STB / Android TV / HP kedua Anda.
2. Buka aplikasi, pilih folder penyimpanan (Memori internal, Kartu SD, atau Harddisk/Flashdisk USB).
3. Layar aplikasi langsung menampilkan:
   - **Device IP**: (contoh `192.168.1.100`)
   - **Kode Pairing**: (6 digit)
   - **Worker API**: `http://192.168.1.100:2223`
   - **Server FTP Bawaan**: `ftp://192.168.1.100:2121` (User: `mihon` / Pass: `mihon`)
4. Klik **"Simpan dan jalankan worker"** (*Save and start worker*).
5. Di **Mihon Net** (HP utama Anda):
   - **Unduhan -> Download Worker**: Masukkan IP STB, Port `2223`, dan kode pairing.
   - **Unduhan -> Penyimpanan Jaringan -> FTP**: Masukkan IP STB, Port `2121`, User `mihon`, Password `mihon`.
6. Selesai! STB otomatis mengunduh komik sendiri, dan HP Anda bisa langsung membaca komik tersebut dari STB tanpa butuh X-plore!
