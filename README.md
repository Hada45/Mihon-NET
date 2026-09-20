# Mihon Net

Mihon Net is a fork of [Mihon](https://github.com/mihonapp/mihon) that adds direct streaming support for local network storage (SMB / Windows Share and FTP).

> [!NOTE]
> **Experimental / Vibe-Coded Project**:
> This project was built through vibe coding. There might still be edge-case bugs or quirks, and it has only been tested on a limited set of devices and local network setups. Please feel free to test it out and report issues!

---

## Features

- **SMB / Windows Share Support**: Stream manga directly from your local network share without downloading whole chapters to your device.
- **Direct Archive Reading**: Read `.cbz` and `.zip` archives directly from SMB shares using archive index streaming.
- **Optimized Connection Management**: Bounded connection pooling designed to keep network usage stable and avoid server timeouts.
- **Local Cache**: Caches accessed pages to disk and memory for seamless forward and backward page navigation.
- **SMB Performance Logs**: View and share SMB network diagnostics directly from `Settings -> Advanced -> SMB Performance Log`.
- **Optional Download Delegation**: Mihon can dispatch download commands to an external worker instead of downloading through your phone.

---

## Optional Companion Workers

Using a companion worker is **entirely optional**. Mihon Net functions fully on its own as a standalone network reader for streaming manga over SMB.

When using a worker, Mihon on your phone does **not** perform any downloads directly. It only sends download commands to the worker. The worker then takes over and downloads chapters directly from the source to its own storage, preserving your phone's battery, bandwidth, and storage.

- **Mihon PC Worker (`MihonPcWorker.exe`)**: A lightweight standalone Windows service. When commanded by Mihon, it downloads chapters directly from the source into your PC's manga folder (in `.cbz` or folder format).
- **Mihon Android / STB Worker (`MihonWorker-Android-debug.apk`)**: Run this worker on an Android TV box, TV stick, or secondary Android device. When commanded by Mihon, it downloads chapters directly from the source to connected external drives or storage.

---

## Getting Started

### Setting up a Windows Share (SMB)
1. **On your PC**:
   - Right-click your manga folder > **Properties** > **Sharing** > **Advanced Sharing** > enable sharing and set permissions.
2. **In Mihon Net**:
   - Go to **Settings** > **Data and storage** > **Network storage (SMB)**.
   - Enter your server IP address, share name, and optional login credentials.
   - Return to your Library to browse and read your manga.

---

## Downloads

Download the latest builds from the [Releases](../../releases) page:
- **Mihon Net (Main App)**:
  - `app-arm64-v8a-debug.apk`: Recommended for modern Android smartphones.
  - `app-universal-debug.apk`: Compatible with all Android CPU architectures.
- **Optional Companion Workers**:
  - `MihonPcWorker.exe`: Windows PC background download worker.
  - `MihonWorker-Android-debug.apk`: Android / STB background download worker.

---

## License

This project is licensed under the [Apache 2.0 License](LICENSE), matching upstream Mihon.
