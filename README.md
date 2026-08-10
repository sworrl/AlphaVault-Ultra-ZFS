# AlphaVault Ultra ZFS

An Android app that hides encrypted files inside a FLAC music library. A file is
encrypted, split into RAID-6 chunks, and each chunk is stored in the metadata of
a real FLAC file, spread across albums. The audio is left unchanged so the tracks
still play, and the hidden data survives losing whole albums through parity and
mirroring.

Started from [bennjordan/AlphaSteg](https://github.com/bennjordan/AlphaSteg): the
Python desktop server (`main.py`, `static/`) is that project, kept as-is. The
Android app under `android/` is new and does not use the server.

## Status

Verified by 21 unit tests and by hand on a Pixel 10 Pro XL (Android 14). Items
under "not verified" have code but have not been run end to end.

Working and tested:

- Build, install, launch through the lock screen.
- Vault tab (hidden files) and Disks tab (the FLAC carriers), with a storage bar
  showing device, music-pool, and vault usage.
- FLAC library sync: finds the `.flac` files that form the pool (tested with 24
  tracks in two albums).
- Vault a file: encrypt, RAID-6 chunk, embed in FLAC, with a replicated encrypted
  index. Tests cover round-trip vault/restore, audio bytes unchanged after
  embedding, a whole album deleted and the file still restores, a corrupted chunk
  caught by its checksum and rebuilt, index generation, and a wrong code showing
  an empty vault.
- RAID-6 recovery: `RaidReedSolomonTest` drops every pair of data chunks with no
  mirror and still restores.
- Multiple codes open different compartments in one library (tested).
- In-app viewers for images, text, audio, video, and PDF, rendered inside a
  screenshot-blocked window; no bytes leave the app.
- Multi-select batch vaulting; "Move to Vault" / "Copy to Vault" from the share
  sheet; rename and sort vaulted files.
- Lock screen: hex-plus-symbol codes (8+ chars), master and duress codes each set
  twice at onboarding; the duress code wipes credentials and clears the carriers.
- Calculator disguise: the launcher becomes a working scientific calculator, and
  entering the code in it unlocks the app.

Not verified end to end:

- The picker-to-vault-to-view flow on hardware (the pieces are tested; the whole
  path has not been walked with the file picker).
- The Wi-Fi Sync switch starts a service; there is no network drive behind it yet.
- The spectrum visualizer is decorative.

## Encryption format

`CryptoEngine` builds one envelope per file from the user's code:

1. `PBKDF2WithHmacSHA512`, 500,000 iterations, 32-byte salt, produces 768 bits
   split into three 32-byte keys (AES, ChaCha20, HMAC).
2. AES-256-GCM, 96-bit nonce, 128-bit tag.
3. ChaCha20-Poly1305 over the AES output, its own 96-bit nonce.
4. HMAC-SHA512 over `magic || salt || aesNonce || chachaNonce || ciphertext`,
   magic `AVMAX768`.

Decryption checks the HMAC first, so a wrong code or a changed byte is rejected
before either cipher runs.

## Storage format (RAID-6)

Parity is Reed-Solomon over GF(2⁸) with primitive polynomial `0x11d` and
generator `g = 2` (the field ZFS and Linux md-raid6 use; H. P. Anvin, "The
mathematics of RAID-6"), in `GaloisField` and `RaidVaultEngine`:

- `P = XOR` of the data chunks.
- `Q = Σ gⁱ·Dᵢ`.
- Recovery takes each data chunk from its primary or its hot-spare mirror, then
  rebuilds any two chunks missing from both by solving the 2×2 system over the
  field. A single chunk can also be rebuilt from Q when P is the one lost.

This is an implementation of the algorithm, not OpenZFS's C code, which cannot be
compiled into an Android app.

## Vault format

The vault lives entirely in the FLAC files; there is no app-private database.

- Data chunks are FLAC `APPLICATION` metadata blocks (`AVC1`), each with a CRC32.
  Players ignore unknown APPLICATION blocks and the audio is untouched.
- The index (`AVIX`) is an encrypted, CRC-checksummed table of contents with a
  generation counter, replicated across several carriers; the highest-generation
  replica that checksums and decrypts wins.
- Chunks are placed so a chunk and its mirror never share an album.
- A chunk that fails its checksum or is missing is rebuilt from parity or mirror
  on restore; `scrub` re-embeds anything it rebuilt.
- Different codes decrypt different indexes, so one library holds several
  compartments; saving one only replaces the replicas that decrypt with its code.
- Carrier reads and writes are streaming and metadata-only, so audio frames are
  never loaded into memory.

## Security notes

- Confidentiality rests only on the user's code. Files and the index are
  encrypted with keys derived from the code; there is no embedded or default key,
  and a blank code leaves the vault locked. A build with no code cannot decrypt.
- The stored master/duress check is salted PBKDF2-HMAC-SHA512 with a random
  per-install salt, not a fast hash, so the prefs file is not an offline oracle.
  It does not protect the data either way.
- Everything protecting the data is symmetric (AES-256, ChaCha20) or hash-based
  (PBKDF2-HMAC-SHA512), which face only Grover (AES-256 stays about 128-bit).
  There is no RSA or elliptic curve for Shor to break.
- Chunks sit in FLAC metadata blocks. The contents are encrypted, but anyone who
  parses the metadata can see the blocks exist; this is not deep concealment.
- `FLAG_SECURE` blocks screenshots, screen recording, and recents thumbnails;
  backups and cleartext traffic are disabled.
- Not audited. Do not stake anything you cannot afford to lose.

## Build

Needs JDK 17 and the Android SDK (compileSdk 34). Set `sdk.dir` in
`android/local.properties`.

```
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/AlphaVault-Ultra-ZFS.apk
./gradlew testDebugUnitTest
```

## Desktop server (upstream)

`main.py` is the AlphaSteg server, unchanged: FastAPI on `127.0.0.1:8000`, hiding
payloads in audio by LSB or MFSK with AES-256-GCM. It is independent of the
Android app. The app's own `LsbStegoEngine` is unused legacy; the vault embeds in
FLAC metadata, not audio LSB.

```
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python main.py
```

## Roadmap

- Private drive mode without root: expose the decrypted volume as a read-write
  drive over WebDAV or SMB (USB-tethering or Wi-Fi), behind a lock overlay that
  kills the share when focus is lost. Real USB mass storage needs root and is out.
- Server companion for backup and offload, where real ZFS can run.
- Graphical disk and vault views; tags, color labels, and folders for vaulted
  files; a faster vault list on unlock (cache which carriers hold the index).
- Biometric was removed because Android does not report which fingerprint
  matched, so a finger cannot trigger duress. Duress is code-based.

## Layout

```
main.py, static/, requirements.txt   AlphaSteg desktop server
android/app/src/main/java/com/alphasteg/pro/
  LockScreenActivity.kt        hex+symbol keypad, master/duress onboarding
  CalculatorActivity.kt        calculator disguise that unlocks on the code
  MainActivity.kt              Vault / Disks / Spectrum / Wi-Fi tabs
  VaultViewerActivity.kt       in-memory image/text/audio/video/PDF viewer
  ShareReceiverActivity.kt     "Move to Vault" share target
  VaultService.kt              foreground service for long jobs
  calc/Calculator.kt           expression evaluator
  data/VaultVolume.kt          vault filesystem: index, vault, restore, scrub
  data/VaultCodec.kt           CRC-checksummed chunk/index framing
  data/VaultLibrary.kt         FLAC-carrier sync
  data/AppSettings.kt          options
  engine/CryptoEngine.kt       cascade cipher
  engine/RaidVaultEngine.kt    RAID-6 encode + Reed-Solomon recovery
  engine/GaloisField.kt        GF(2⁸) arithmetic
  engine/FlacCarrierEngine.kt  streaming FLAC metadata embed/extract
  security/SecurityManager.kt  PBKDF2 verifier, duress, code matching
android/app/src/test/          21 unit tests
```

## Attribution and license

Based on [bennjordan/AlphaSteg](https://github.com/bennjordan/AlphaSteg); the
desktop server and the LSB/MFSK code are from that project. The Android app, the
cascade cipher, the Reed-Solomon RAID-6 engine, the FLAC-metadata vault format,
the compartments, the lock screen, the calculator disguise, and the viewers are
new here.

Author: sworrl (agent.jearl@gmail.com). AlphaSteg has no license file; for terms
on the upstream code see the original repository.
