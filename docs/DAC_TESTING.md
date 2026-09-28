# Testing AlphaVault against a real DAC

Reference hardware is a **HiBy M500** (Android 14, arm64, 3.7 GB RAM, 256 MB app
heap) driven from a **Pixel 10**. Nothing in here modifies the DAC — that is the
point. Any USB Audio Class device or DLNA renderer should behave the same way.

---

## 1. Wired: the DAP as a USB DAC

This is the best-fidelity path and it puts no plaintext on any network.

1. On the M500: **Settings → USB → `USB DAC (disable USB charging)`**.
   Pick the *disable charging* variant when the source is a phone, or the DAP
   will draw from the Pixel's battery.
2. Connect Pixel → M500 with USB-C.
3. Open a vaulted audio file. The viewer's third line names the output, e.g.
   `Out: M500 - up to 384 kHz - 32-bit`.

**Expect ADB-over-USB to drop** the moment the mode changes — the USB gadget
re-enumerates as an audio device instead of an ADB device. That is normal, not a
fault.

### What "up to 384 kHz" does and does not mean

The viewer reports what the DAC advertises it can *accept*. Playback still goes
through Android's normal media path, so the framework mixer owns sample-rate
conversion. Bit-perfect output would mean driving the USB interface directly and
bypassing the mixer — a separate exercise. The manifest already declares
`android.hardware.usb.host` (for the FIDO2 key), so nothing blocks that later.

---

## 2. Wireless: DLNA push

1. On the M500, open **HiBy Music** and enable its DLNA renderer
   (`com.hiby.music` ships `DMRService`, `DMSService` and `DMCService`).
2. On the Pixel, turn on **Wi-Fi Sync** — the renderer needs something to fetch.
3. Long-press a vaulted file → **Play on…** → pick the M500.

Both devices must be on the same subnet; SSDP discovery is multicast and will not
cross a router or a guest-network client-isolation boundary.

**Trade-off worth stating plainly:** casting streams the *decrypted* file over
plain HTTP on the LAN, and the URL authenticates itself (a single-file, expiring
256-bit capability, because DLNA renderers do not send HTTP Basic credentials).
The USB path keeps plaintext off the wire entirely. Prefer USB when it matters.

---

## 3. Running the app *on* the DAP (low-spec target)

The M500 is a useful worst case: slow CPU, 256 MB app heap even with
`largeHeap="true"`.

```bash
./gradlew assembleProdDebug
adb install -r app/build/outputs/apk/prod/debug/AlphaVault-Ultra-ZFS-prod-debug.apk
```

With both devices attached, `adb -s <serial>` will not disambiguate — the M500
reports its serial as `?`. Use the transport id instead:

```bash
adb devices -l            # note transport_id
adb -t <id> shell ...
```

### Benchmarks

```bash
./gradlew connectedProdDebugAndroidTest
adb logcat -d -s AlphaVaultPerf
```

`EnginePerfTest` reports cold vs warm key derivation, RAID encode/reconstruct,
LSB embed/extract at real track lengths, and the largest whole-file allocation
the device can manage. That last number matters: `VaultVolume.restore()` returns
one `ByteArray`, so it bounds the size of a file that can be restored in one
piece.

---

## 4. Optional: ADB over Wi-Fi

**Off by default, and deliberately so** — the DAC should behave like a normal,
unmodified device. Enable it only when you need a shell while USB is busy being
an audio link:

```bash
adb tcpip 5555
adb connect <dap-ip>:5555
```

Undo with `adb usb`, or just reboot the DAP. While it is on, an unauthenticated
ADB port is listening on your LAN, so do not leave it enabled on a shared
network.
