<h1 align="center">
  <br>
  AlphaVault Ultra ZFS
  <br>
  <sub><em>a derivative of <a href="https://github.com/bennjordan/AlphaSteg">AlphaSteg</a> that turns a FLAC library into an encrypted, fault-tolerant vault</em></sub>
  <br>
</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Android_app-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin Android app">
  <img src="https://img.shields.io/badge/minSdk-26_(Android_8.0)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="minSdk 26">
  <img src="https://img.shields.io/badge/targetSdk-34_(Android_14)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="targetSdk 34">
  <img src="https://img.shields.io/badge/APK-6.4_MB-e67e22?style=flat-square" alt="6.4 MB APK">
  <img src="https://img.shields.io/badge/tests-21_passing-2ecc71?style=flat-square" alt="21 tests passing">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Cascade-AES--256--GCM_%2B_ChaCha20--Poly1305-e74c3c?style=flat-square&labelColor=1a1a2e" alt="Cascade cipher">
  <img src="https://img.shields.io/badge/KDF-PBKDF2--HMAC--SHA512_%C3%97_500k-9b59b6?style=flat-square&labelColor=1a1a2e" alt="PBKDF2-HMAC-SHA512 500k">
  <img src="https://img.shields.io/badge/RAID--6-Reed--Solomon_GF(2%E2%81%B8)-f39c12?style=flat-square&labelColor=1a1a2e" alt="Reed-Solomon RAID-6">
  <img src="https://img.shields.io/badge/multi--passcode-deniable_vaults-3498db?style=flat-square&labelColor=1a1a2e" alt="Deniable compartments">
</p>

---

A large FLAC library is a lot of bytes nobody inspects closely. AlphaVault turns one into a hidden, self-contained encrypted volume: it cascade-encrypts a file, splits the ciphertext into RAID-6 chunks, and tucks each chunk inside a real FLAC file's metadata, spread across different albums. The audio frames are left byte-for-byte unchanged, so every track still plays normally, and the hidden volume survives losing whole albums to parity and mirroring. There is no separate database; the vault's own encrypted table of contents lives inside the FLAC files too.

This project derives from [bennjordan/AlphaSteg](https://github.com/bennjordan/AlphaSteg). Upstream is a desktop steganography server; this repository keeps that server and adds a native Kotlin Android app with a much heavier crypto and storage layer. Most of this README is about the Android app.

## Contents

- [Status: what works and what does not](#status-what-works-and-what-does-not)
- [The Android app](#the-android-app)
- [The vault as a filesystem](#the-vault-as-a-filesystem)
- [Cascade encryption, layer by layer](#cascade-encryption-layer-by-layer)
- [RAID-6 dual parity](#raid-6-dual-parity)
- [Security model, stated plainly](#security-model-stated-plainly)
- [Build the APK](#build-the-apk)
- [The upstream desktop server](#the-upstream-desktop-server)
- [Roadmap](#roadmap)
- [Project layout](#project-layout)
- [Attribution and license](#attribution-and-license)

## Status: what works and what does not

Verified by 21 unit tests (all passing) and by hand on a Pixel 10 Pro XL running Android 14. The carrier engine is also verified against a real device FLAC. On-device runs that need the file picker or the physical DAC are called out as not yet fully exercised.

**Working and tested**

- Build, install, and launch through the lock screen; full-bleed adaptive launcher icon.
- Vault-first navigation: a **Vault** tab that shows only the hidden files, and a separate **Disks** tab that presents the FLAC carriers like a RAID manager, with a three-tier storage bar (whole device, music pool, vault usage).
- FLAC library sync. On startup, on resume, and on a manual **Sync Library** tap, the app finds the `.flac` files that form the pool. Verified with 24 tracks across two albums.
- The vault filesystem: cascade-encrypt to RAID-6 chunk to embed-in-FLAC, with a replicated encrypted index. The tests prove round-trip vault and restore, that audio bytes are unchanged after embedding, that a whole album can be deleted and the file still restores, that a corrupted chunk is caught by its checksum and rebuilt, the index generation counter, and that a wrong code reads as an empty vault.
- Real RAID-6 parity. `RaidReedSolomonTest` drops every pair of data chunks with no mirror present and still restores the payload exactly.
- Multiple passcodes, multiple deniable compartments in one FLAC volume; saving one compartment leaves the others untouched (tested).
- Secure in-app viewer: images, text, and audio decrypt into memory and play or display without writing plaintext to disk.
- Hex lock screen (0-9, a-f, and symbols; 8+ characters), keypad shuffled once per screen with an option to shuffle every keypress, mandatory master and duress codes each entered twice at onboarding. The duress code wipes credentials and strips every vault block from the carriers. Biometric was removed on purpose (see [Roadmap](#roadmap)).
- Calculator disguise: a working scientific calculator becomes the launcher face; entering the code, bare or inside an equation, unlocks the real app.
- Hardening: `FLAG_SECURE` on every screen (no screenshots, screen recording, or recents thumbnails), backups and cleartext traffic disabled, no hardcoded keys, slow salted PBKDF2 credential verifier, memory-safe streaming so large files vault without exhausting the heap, and a progress modal that can run in the background.

**Not yet exercised end to end**

- The full on-device flow from the system file picker through vaulting a real file and viewing it back. The pipeline is unit-tested and the carrier engine is verified on a real FLAC, but the picker-driven path has not been walked on hardware.
- The **Wi-Fi Sync** switch starts a foreground service with a notification; the network drive behind it is not built yet.
- The spectrum visualizer draws but is decorative.

## The Android app

Two activities gate the app, then the main screen has four tabs behind a floating dock.

**Lock screen** (`LockScreenActivity`). Codes are hexadecimal plus symbols (the 24-key pad is `0-9`, `a-f`, `! @ # $ % & * ?`), at least 8 characters. Onboarding is mandatory two-step and double-confirmed: a master code entered twice, then a distinct duress code entered twice. The keypad reshuffles once each time it appears; an option makes it reshuffle after every keypress. There is no biometric unlock. Entering the duress code wipes the stored credentials and strips every AlphaVault block from the carriers, then shows an empty vault.

**Vault tab.** Only the hidden files. Pick a file and it is cascade-encrypted, RAID-6 chunked, and embedded across the FLAC carriers; it then appears in the list. Tapping a vaulted file offers view in app, restore to Downloads, or delete from the vault.

**Disks tab.** The RAID-manager view: the storage bar (device used/free, music-pool size, vault usage), pool mode, a sync button, and the FLAC carriers as pool disks.

**Spectrum and Wi-Fi Sync tabs.** A decorative 60 fps visualizer, and a switch that currently starts a foreground service; the network drive behind it is planned.

**Calculator disguise.** Turned on in Options, the launcher icon and name become a calculator, and tapping it opens `CalculatorActivity`, a real scientific calculator. It watches the keys pressed; when your code appears in the stream (typed alone or as part of an equation), the real app unlocks. This uses Android activity-aliases toggled at runtime, so the launcher face swaps without reinstalling.

Under the hood: `minSdk 26` (Android 8.0), `targetSdk 34` (Android 14), `versionName 1.0.0-Ultra-ZFS`, 18 Kotlin files, 6.4 MB APK.

## The vault as a filesystem

The vault has no app-private database. Everything needed to find and rebuild a file lives inside the FLAC library:

- **Data chunks (`AVC1` blocks).** Each vaulted file is cascade-encrypted, split into RAID-6 chunks (data, P and Q parity, plus a hot-spare mirror of every chunk), and each chunk is embedded as a FLAC `APPLICATION` metadata block. Players ignore unknown `APPLICATION` blocks, and the audio frames are untouched, so tracks still decode and play exactly as before. Every chunk carries a CRC32.
- **The index (`AVIX` block).** An encrypted, CRC-checksummed table of contents for the whole vault, carrying a generation counter and replicated into several carriers across different albums, the way a GPT header or a ZFS uberblock is replicated. The highest-generation replica that checksums and decrypts wins.
- **Placement.** Chunks are spread so a chunk and its mirror never share an album; deleting or swapping one whole album then cannot remove both copies of the same data.
- **Self-healing.** On restore, a chunk that fails its CRC or is missing is treated as absent and rebuilt from parity or its mirror. A `scrub` pass re-embeds anything it had to rebuild.
- **Deniable compartments.** Because the index is encrypted with your code, different codes reveal different vaults in the same library. Saving one compartment's index only replaces the replicas that decrypt with that code, so the others stay intact and invisible.
- **Memory safety.** Carrier reads and writes are streaming and metadata-only: the engine parses just the small metadata region and copies the multi-megabyte audio frames straight from the old file to the new one, never loading audio into RAM. This is what lets it run on memory-constrained DACs.

`FlacCarrierEngine` handles the FLAC container, `VaultCodec` the checksummed framing, and `VaultVolume` the whole filesystem.

## Cascade encryption, layer by layer

`CryptoEngine.encryptPayload` builds one envelope from a single code:

1. **Derive keys.** `PBKDF2WithHmacSHA512` runs 500,000 iterations over the code and a fresh 32-byte salt, producing 768 bits (96 bytes) split into three 32-byte keys: AES, ChaCha20, and HMAC.
2. **Layer 1: AES-256-GCM**, 96-bit nonce, 128-bit tag.
3. **Layer 2: ChaCha20-Poly1305** over the AES output, its own 96-bit nonce.
4. **Outer tag: HMAC-SHA512** over `magic || salt || aesNonce || chachaNonce || ciphertext`, where the magic is `AVMAX768`.

Decryption verifies the HMAC first, so a wrong code or a tampered byte is rejected before either cipher runs. On the desktop JVM the ChaCha cipher name differs from Android's, so the engine tries both and therefore runs under host-JVM unit tests unchanged.

## RAID-6 dual parity

Parity is the real RAID-6 / RAID-Z2 algorithm, the same field and generator that ZFS and Linux md-raid6 use: GF(2⁸) with primitive polynomial `0x11d` and generator `g = 2` (H. P. Anvin, "The mathematics of RAID-6"), implemented in `GaloisField` and `RaidVaultEngine`.

- `P = XOR` of the data chunks.
- `Q = Σ gⁱ·Dᵢ` over GF(2⁸).
- Recovery resolves each data chunk from its primary or hot-spare mirror, then rebuilds **any two** data chunks missing from both by solving the 2×2 linear system over the field. A single chunk can also be rebuilt from Q alone when P is the one that is gone.

So the guarantee is real: lose any two carriers and the file still restores from parity, with the mirror tolerating whole-album loss on top of that. This is a from-scratch implementation of the algorithm; it is not OpenZFS's C code, which cannot be compiled into an Android app. If you want literal ZFS in the loop, its home is the server side (see [Roadmap](#roadmap)).

## Security model, stated plainly

The design goal is that only your code protects your data, and nothing about knowing the source or holding the device files shortcuts that.

- **All confidentiality rests on the code.** Files and the vault index are encrypted with keys derived from your code via PBKDF2-HMAC-SHA512 (500,000 iterations). There is no app-embedded key and no default key; a blank code leaves the vault locked. A modified build with no code cannot decrypt anything.
- **The credential verifier is not an oracle.** The stored check for master versus duress is a slow, salted PBKDF2-HMAC-SHA512 value with a random per-install salt, not a fast hash. It never protects the data (the data is separately encrypted with the code), and it does not give an attacker a fast offline brute-force path.
- **Quantum outlook.** Everything protecting the data is symmetric (AES-256, ChaCha20) or hash-based (PBKDF2-HMAC-SHA512). Those face only Grover's algorithm, which leaves AES-256 at about 128-bit post-quantum strength. There is no RSA or elliptic-curve anywhere for Shor's algorithm to break, so the data at rest is already quantum-resistant for this threat model.
- **Steganography, not deep hiding.** Chunks live in FLAC `APPLICATION` metadata blocks. That keeps playback perfect and is reversible, but anyone who parses the metadata can see that blocks exist. The contents stay encrypted; the presence is not deeply concealed.
- **Hardening.** `FLAG_SECURE` blocks screenshots, screen recording, and recents thumbnails on every screen; backups and cleartext traffic are disabled.
- **Not formally reviewed.** The primitives are standard and used correctly at the envelope level, but this has not had a third-party audit. Do not stake anything you cannot afford to lose.

## Build the APK

Needs JDK 17 and the Android SDK (compileSdk 34). Point `android/local.properties` at your SDK with `sdk.dir=/path/to/Android/Sdk`.

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/AlphaVault-Ultra-ZFS.apk
./gradlew testDebugUnitTest      # run the 21 unit tests
```

Both debug and release variants emit `AlphaVault-Ultra-ZFS.apk`.

## The upstream desktop server

`main.py` and `static/` are the upstream AlphaSteg server, kept as-is. FastAPI on `127.0.0.1:8000`, started with `python main.py`. It hides payloads in audio by LSB or multi-frequency shift keying (MFSK), encrypting with AES-256-GCM (PBKDF2-HMAC-SHA256, 50,000 iterations, `AESA` magic prefix). This is separate from the Android app, which does its own thing and does not call the server. Note that the Android app's own `LsbStegoEngine` is legacy: the current vault path embeds in FLAC metadata blocks, not audio LSB.

```bash
python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python main.py                    # serves http://127.0.0.1:8000
```

## Roadmap

- **Private drive mode (no root).** Expose the decrypted volume as a read-write drive a computer can mount, over USB-tethering or Wi-Fi, using a WebDAV or SMB server in the app, guarded by a fullscreen lock overlay that keeps the screen awake and kills the share the instant the app loses focus. A literal USB mass-storage device is not possible without root (the USB gadget is system-owned and SELinux-fenced), and rooting is off the table, so the network drive is the path.
- **Server-linked libraries.** A desktop or home-server companion so a phone or DAC can back up and offload carriers to and from a server. This is the natural home for literal ZFS, since real ZFS can run on the server even though it cannot on the phone.
- **Compartment management UI.** The multi-passcode engine is done and tested; it needs a screen to create and switch compartments.
- **Post-quantum documentation.** The data is already symmetric-only and quantum-safe; a lattice KEM would only matter if a key-exchange or asymmetric server-sync component is added.
- **A note on biometric duress.** Biometric unlock was removed because Android's `BiometricPrompt` reports only that some enrolled fingerprint matched, never which finger, so a specific finger cannot be bound to a duress action. Duress is code-based instead.

## Project layout

```
AlphaVault-Ultra-ZFS/
├── main.py, static/, requirements.txt   upstream desktop server (LSB/MFSK)
├── android/app/src/main/java/com/alphasteg/pro/
│   ├── LockScreenActivity.kt      hex + symbol keypad, mandatory duress onboarding
│   ├── CalculatorActivity.kt      scientific-calculator disguise that unlocks on the code
│   ├── MainActivity.kt            Vault / Disks / Spectrum / Wi-Fi tabs, settings
│   ├── VaultViewerActivity.kt     in-memory image/text/audio viewer
│   ├── VaultService.kt            foreground service that keeps long jobs alive
│   ├── calc/Calculator.kt         scientific expression evaluator
│   ├── data/VaultVolume.kt        the vault-as-filesystem (index, vault, restore, scrub)
│   ├── data/VaultCodec.kt         CRC-checksummed chunk and index framing
│   ├── data/VaultLibrary.kt       persistent FLAC-carrier sync
│   ├── data/AppSettings.kt        options (scramble-per-press)
│   ├── data/FlacTrack.kt          a carrier track
│   ├── engine/CryptoEngine.kt     768-bit cascade cipher
│   ├── engine/RaidVaultEngine.kt  RAID-6 encode + Reed-Solomon recovery
│   ├── engine/GaloisField.kt      GF(2⁸) arithmetic
│   ├── engine/FlacCarrierEngine.kt  streaming FLAC metadata embed/extract
│   ├── engine/LsbStegoEngine.kt   legacy audio-LSB (not used by the vault path)
│   ├── security/SecurityManager.kt  PBKDF2 credential verifier, duress, code matching
│   └── ui/StegoVisualizerView.kt  decorative spectrum view
└── android/app/src/test/          21 unit tests across 4 suites
```

## Attribution and license

AlphaVault Ultra ZFS derives from [bennjordan/AlphaSteg](https://github.com/bennjordan/AlphaSteg); the desktop server and the LSB/MFSK core come from that project. This work adds the Kotlin Android app, the two-layer cascade cipher, the PBKDF2-HMAC-SHA512 key schedule, the real Reed-Solomon RAID-6 engine, the FLAC-carrier vault filesystem with a replicated encrypted index, deniable compartments, the hex/duress lock screen, the calculator disguise, and the secure viewers.

Author: sworrl (agent.jearl@gmail.com). Upstream AlphaSteg has no license file; for terms on the upstream code, see the original repository.
