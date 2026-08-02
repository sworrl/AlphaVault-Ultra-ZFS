# AlphaSteg
## Audio Steganography Suite

### AlphaSteg is a tool for embedding and extracting hidden payloads inside audio tracks.

**It supports both analog audio streams (hiding a second song inside a song) and digital files (hiding zips, pdfs, images, or text files inside audio). It features many different encoding and decoding formats, some supporting file encryption.**

 ![App Screenshot](https://github.com/bennjordan/AlphaSteg/blob/main/screen.png?raw=true)

---

## Installation & Setup (Windows)

We have provided a fully automated installer for novice users. Setting up Python, virtual environments, and audio codecs takes just one double-click.

### Quick Start:
1. Download or extract the project folder.
2. Double-click *install.bat* inside the root directory.
   - *Note: If Python is missing, the official installer will pop up. Make sure to check the **"Add Python to PATH"** checkbox before completing Python setup.*
   - *The installer will automatically download portable `ffmpeg` binaries and configure everything locally.*
3. Once the installer finishes, double-click **`run.bat`** to start the app.
4. The server will start, and your web browser will automatically open to `http://localhost:8000/`.

---

## How Does it Work?

AlphaSteg combines modern digital signal processing (DSP), software-defined radio modems, and psychoacoustic masking to hide data in carrier tracks:

- **Stego Scanner**: The player has an **Auto-Detect Codec** (magnifying glass) feature that scans the first 3 seconds of any track. It runs spectral magnitude analysis, phase cancelation ratios, LSB magic header checks, and MFSK tone searches to immediately detect if stego is present and select the matching decoder settings.
- **Dynamic Seeking**: Streams are served dynamically. Sliding the playhead timeline seeks instantly using backend-calculated frame-rate offsets, avoiding browser caching bottlenecks.

---

## 📊 Steganography Methods: What To Expect

AlphaSteg includes 4 different encoding methods divided into two categories (Analog Audio hiding and Digital File hiding). Here is a comparison of their characteristics:

### 1. Phase Inversion (Analog Audio Payload)
* **How it works**: Mixes the payload audio in-phase into the left channel and out-of-phase (inverted) into the right channel. When played in mono, the channels cancel each other out, making the payload silent. In stereo, the human brain resolves it as a wide spatial field, but the secret track can be isolated by subtracting the channels.
* **Pros**:
  * 🔊 **Perfect Fidelity**: The carrier audio sounds clean and natural.
  * ⏱️ **Full Length**: Can hide secondary audio that matches the exact duration of the carrier.
* **Cons**:
  * ❌ **Fragile**: Cannot survive YouTube/Spotify compression (which sums channels to mono or alters stereo phases to save bandwidth).
  * ❌ **Stereo Only**: Requires stereo playback to isolate.

### 2. Spectral Modulation (Analog Audio Payload)
* **How it works**: Downsamples the payload audio to a 3kHz bandwidth, modulates it onto a high-frequency carrier tone 17kHz, and mixes it into the carrier. The decoder demodulates the high-frequency band back to audible range.
* **Pros**:
  * 🔊 **Mono-Compatible**: Works equally well in mono and stereo audio files.
  * ⏱️ **Full Length**: Hides full-length audio tracks.
* **Cons**:
  * ⚠️ **Audible Hiss**: Introduces a high-pitched background whisper/hiss.
  * ❌ **Filtered Out**: Fails on YouTube/Spotify because lossy compression codecs cut off all frequencies above 15-16 kHz (low-pass filtering).

### 3. Least Significant Bit (LSB) (Digital File Payload)
* **How it works**: Replaces the lowest bit of each 16-bit PCM audio sample with the binary bits of the hidden file. Includes a magic byte header (`0xAF55`) for instant scanning.
* **Pros**:
  * 💾 **100% Lossless**: Decodes exact binary duplicates of zip archives, text files, PDFs, or keys.
  * ⚡ **High Capacity**: Up to 11.0KB of data per second of CD-quality stereo audio.
  * 🔇 **Completely Inaudible**: The LSB noise floor is at 96dB (physically impossible for human ears to hear).
* **Cons**:
  * ❌ **Extremely Fragile**: **Cannot survive any compression.** Saving as MP3, converting format, or uploading to YouTube/Spotify destroys the data instantly. Must remain a lossless WAV file.

### 4. MFSK Modem (Digital File Payload)
* **How it works**: A software-defined Multi-Tone Frequency Shift Keying modem that translates files into high-frequency sound tones between 8kHz and 11.4kHz. Employs a synchronization preamble, repetition coding, and checksum validation.
* **Pros**:
  * 🛡️ **Robust**: **Survives Spotify/YouTube compression and low-bitrate MP3 conversion.**
  * 🔧 **Adjustable Speed Presets**:
    * **Standard** (~26 B/s, 3x repetition): Maximum robustness (Spotify/YouTube proof).
    * **Balanced** (~53 B/s, 3x repetition): Moderate speed, survives standard MP3.
    * **Fast** (~160 B/s, no repetition): Maximum capacity for high-quality audio.
* **Cons**:
  * ⏱️ **Low Speed**: Hiding a 1KB file takes between 6.4 and 38 seconds of audio.
  * ⚠️ **Audible Chirp**: High-frequency modem tones are slightly audible as background chirping.
