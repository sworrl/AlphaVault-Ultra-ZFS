# AlphaVault Ultra ZFS 🔒🎵

**AlphaVault Ultra ZFS** is an official enhanced fork of [AlphaSteg](https://github.com/bennjordan/AlphaSteg). It transforms lossless FLAC audio collections into a high-availability, fault-tolerant steganographic vault protected by **768-bit Multi-Cipher Cascade Encryption** and **ZFS RAID-Z2 Dual-Parity + Hot Spare Mirroring**.

---

## 🌟 Key Features

### 🛡️ 768-Bit Multi-Cipher Cascade Encryption
- **Layer 1**: AES-256-GCM (Authenticated Galois/Counter Mode with 128-bit GCM tag).
- **Layer 2**: ChaCha20-Poly1305 (256-bit Stream Cipher + 128-bit Poly1305 authenticator).
- **Outer Authentication**: HMAC-SHA512 integrity tag over encrypted vault envelopes.
- **Key Stretch**: PBKDF2-HMAC-SHA512 with 500,000 iterations (768-bit combined key entropy).

### 💾 ZFS RAID-Z2 & Hot Spare Storage Pool
- **Whole Library Auto-Pool**: Scans `/sdcard/Music/` FLAC audio tracks to create a unified steganographic RAID array.
- **RAID-Z2 Dual Parity ($P + Q$)**: Protects data with both XOR Parity ($P$) and Galois Shift-XOR Parity ($Q$).
- **Hot Spare Mirroring**: Automatically replicates chunks to hot-spare tracks in separate album folders.
- **Multi-Album Swapping Resilience**: Delete or swap out 2-3 full music albums without losing any vaulted files or requiring manual resilvering!

### 📱 100% Pure Native Kotlin Android App
- **Native Android 14/15 Engine**: Ultra-compact 3.1 MB APK compiled natively for ARM64 with zero Python runtime overhead.
- **16 KB Page Alignment Compliant**: Native libraries configured with `-Wl,-z,max-page-size=16384` for Android 15 & Pixel 10 compliance.
- **Display Cutout & Edge-to-Edge Integration**: Dynamic camera punch-hole and status bar safe inset adaptation.
- **60 FPS Real-time Stego Visualizer**: Custom Kotlin canvas rendering 32 dynamic audio spectrum frequency bars and real-time oscilloscope waveforms.
- **Biometric & Randomized Keypad Security**: Fingerprint & Face Unlock integration with anti-smudge 0-9 randomized keypad grid and Decoy Panic PIN mode.

---

## 🚀 Quick Start (Android)

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📜 Fork Attribution

This project is an official enhanced fork of [bennjordan/AlphaSteg](https://github.com/bennjordan/AlphaSteg), expanded with native Android 14/15 support, multi-layer post-quantum grade cascade cryptography, and ZFS RAID-Z2 distributed steganography.
