# Mihon Net

Mihon Net is a fork of [Mihon](https://github.com/mihonapp/mihon) that adds direct streaming support for local network storage (SMB / Windows Share and FTP).

---

## Features

- **SMB / Windows Share Support**: Stream manga directly from your local network share without downloading whole chapters to your device.
- **Direct Archive Reading**: Read `.cbz` and `.zip` archives directly from SMB shares using archive index streaming.
- **Optimized Connection Management**: Bounded connection pooling designed to keep network usage stable and avoid server timeouts.
- **Local Cache**: Caches accessed pages to disk and memory for seamless forward and backward page navigation.
- **SMB Performance Logs**: View and share SMB network diagnostics directly from `Settings -> Advanced -> SMB Performance Log`.

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

Download the latest APK builds from the [Releases](../../releases) page:
- **ARM64-v8a**: Recommended for modern Android smartphones.
- **Universal**: Compatible with all Android CPU architectures and emulators.

---

## License

This project is licensed under the [Apache 2.0 License](LICENSE), matching upstream Mihon.
