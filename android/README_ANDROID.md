# AlphaSteg Pro & AlphaVault - Android 14 App (HiBy M500 / Hi-Res Audio DAP)

This directory contains the native **Android 14 Application (APK)** project for AlphaSteg Pro and AlphaVault. It is specifically designed to run natively on Android-based Hi-Res Digital Audio Players (such as the **HiBy M500** or HiBy M300) and Android 14 smartphones.

---

## ⚡ AlphaVault Pro & Security Architecture

### 1. RAID-Z2 Distributed Audio Steganography
- **Distributed Chunking**: Files are split into $N$ data chunks plus **two** parity chunks (genuine Reed-Solomon over GF(2⁸), the same math ZFS RAID-Z2 uses), and every chunk is mirrored to a hot spare.
- **The array is sized from your library**: `RaidVaultEngine.dataChunksFor()` scales $N$ with the number of carriers so a file lands on **at least half** the library rather than always the same handful of tracks. 100 carriers → 23 data + 2 parity, mirrored across 50 tracks; 200 carriers → 48 + 2 across 100.
- **RAID Fault Tolerance**: Any two lost chunks are recovered by solving the 2×2 system over the field; hot-spare mirrors tolerate losing whole albums beyond that.

### 2. Android Security
- **Master code lock screen**, with a **duress code** that turns itself into the master code, opens an ordinary empty vault, and wipes the carriers in a foreground service that resumes after a kill or reboot. See `SECURITY.md`.
- **Two-stage key derivation**: PBKDF2-HMAC-SHA512 at 500,000 iterations produces a master key **once per session**, cached in memory; each frame then takes its own AES and ChaCha subkeys from it via HKDF-SHA512. Guess-resistance is unchanged — an attacker still pays the full stretch per password candidate — while browsing a vault no longer re-runs it per carrier. The cache is zeroed on lock.
- **Cascade encryption**: AES-256-GCM, then ChaCha20-Poly1305, under an outer HMAC-SHA512 that is verified *before* either cipher touches the data.
- **Framed payloads (1 MiB)**: an AEAD cipher cannot emit plaintext until it has verified its tag, so Java's GCM and Poly1305 buffer the *whole* message — sealing one big blob forced several full-size copies to coexist and capped restores at ~64 MB on a 256 MB heap. Each frame is now sealed independently, so `restoreTo(OutputStream)` streams a file to disk or a socket with a one-frame working set. Frames cannot be reordered, duplicated, dropped or truncated: subkeys are bound to the frame index, the payload length is authenticated in the header, and the outer HMAC covers every byte.

### 3. Opening a library you do not own (guest sessions)
A fresh install does **not** have to be onboarded. From the first screen you can enter a code, point the app at any folder of FLAC tracks — a card pulled from a DAP, a folder copied off a NAS — and read whatever is hidden inside it.

- No credentials are written, no track database is saved, nothing records that the library was opened.
- The session is **read-only**, so someone else's tracks are never rewritten.
- Existing installs can do the same via **Options → Advanced → Open another library**.

### 4. Playing the vault on a real DAC
Both paths work with a **completely unmodified** DAC — nothing is installed on it.

- **Wired (best fidelity, nothing on the network)**: put the DAP in USB-DAC mode and connect it. `AudioOutput` finds the USB audio device, names it in the viewer, and pins playback to it with `setPreferredDevice`. This is Android's normal media path, so the framework mixer still owns sample-rate conversion — the viewer reports what the DAC *accepts*, not a claim of bit-perfect output.
- **Wireless (convenience)**: **Play on…** discovers DLNA renderers by SSDP and pushes the file to one. Because a renderer will not send HTTP Basic credentials, the URL carries a single-file, expiring, 256-bit capability token (`CastGrants`). This streams the **decrypted** file over plain HTTP on your LAN; the USB path keeps plaintext off the wire entirely.

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

