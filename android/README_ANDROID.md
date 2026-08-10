# AlphaSteg Pro & AlphaVault - Android 14 App (HiBy M500 / Hi-Res Audio DAP)

This directory contains the native **Android 14 Application (APK)** project for AlphaSteg Pro and AlphaVault. It is specifically designed to run natively on Android-based Hi-Res Digital Audio Players (such as the **HiBy M500** or HiBy M300) and Android 14 smartphones.

---

## ⚡ AlphaVault Pro & Security Architecture

### 1. ZFS / RAID-5 Distributed Audio Steganography
- **Distributed Chunking**: Large sensitive files (confidential PDFs, high-res photos, MP4 videos, zip archives) are split into $N$ encrypted data blocks + 1 parity block across multiple FLAC tracks in your `/sdcard/Music/` library.
- **RAID Fault Tolerance**: If 1 or 2 FLAC tracks are accidentally deleted or corrupted, AlphaVault's XOR parity engine reconstructs the original document/video 100% perfectly from remaining tracks!
- **Massive Invisible Storage**: Store gigabytes of sensitive files distributed across 200+ FLAC tracks without making any single audio file suspiciously large.

### 2. Enterprise-Grade Android Security
- **Master PIN / Pattern / Password Lock Screen**: Hardware-backed key derivation via Android KeyStore (PBKDF2HMAC 100,000 iterations).
- **Panic / Decoy Vault Mode**: Enter a secondary decoy PIN under duress to unlock a clean decoy vault or wipe transient decryption keys instantly.
- **AES-256-GCM Chunk Encryption**: Every single chunk stored inside FLAC audio samples is independently encrypted with a unique salt, nonce, and GCM authentication tag.

---

## 🛠️ How to Build the APK (.apk)

### Option 1: Android Studio (Recommended)
1. Open **Android Studio** (Hedgehog 2023.1.1 or newer).
2. Select **Open an Existing Project** and browse to the [`android/`](file:///home/reaver/Documents/GitHub/AlphaSteg/android) folder inside AlphaSteg.
3. Wait for Gradle sync to complete (Gradle will download Chaquopy & Python packages automatically).
4. Connect your **HiBy M500 DAP** via USB with **USB Debugging** enabled in Developer Options.
5. Click **Run 'app'** or select **Build > Build APK(s)** to generate the standalone `app-debug.apk` file inside `android/app/build/outputs/apk/debug/`.

### Option 2: Command Line (Gradle Wrapper)
```bash
cd android

# Linux / macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```
The compiled APK will be located at:
`android/app/build/outputs/apk/debug/app-debug.apk`

---

## 📱 How to Install on HiBy M500 DAP
1. Transfer `app-debug.apk` to your HiBy M500 internal storage or microSD card via USB / Wi-Fi File Transfer.
2. On your HiBy M500, open the **Files / File Manager** app.
3. Tap `app-debug.apk` and confirm installation (**Allow install from unknown sources** if prompted).
4. Launch **AlphaSteg Pro** from your DAP app launcher!

---

## 🎵 How to Use AlphaVault on your HiBy M500
1. Launch **AlphaSteg Pro** and enter your **Master Vault PIN / Pattern**.
2. **Add Files to Vault (ZFS / RAID Encoding)**:
   - Select sensitive files (documents, images, or videos).
   - Select carrier FLAC tracks from your `/sdcard/Music/` library.
   - AlphaVault encrypts, splits, and embeds chunks across the FLAC library with parity protection.
3. **Retrieve Files from Vault**:
   - Unlock the vault.
   - Select any vaulted file to decrypt and view, or export back to storage.

