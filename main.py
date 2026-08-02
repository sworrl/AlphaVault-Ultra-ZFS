import os
import uuid
import math
import struct
import wave
import hashlib
import subprocess
import shutil
import requests
import yt_dlp
import httpx
import threading
from fastapi import FastAPI, HTTPException, UploadFile, File, Form, Query
from fastapi.responses import StreamingResponse, FileResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

app = FastAPI(title="AlphaSteg 0.5")

# CORS Middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Constants
SUB_BLOCK_SIZE = 1024
TEMP_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "temp")
os.makedirs(TEMP_DIR, exist_ok=True)

# MFSK Frequencies
MFSK_FREQS = [10000.0, 10200.0, 10400.0, 10600.0, 10800.0, 11000.0, 11200.0, 11400.0]

MFSK_PRESETS = {
    "standard": {
        "block_size": 4410,
        "fade_len": 220,
        "repetition": 3,
        "freqs": [10000.0, 10200.0, 10400.0, 10600.0, 10800.0, 11000.0, 11200.0, 11400.0]
    },
    "balanced": {
        "block_size": 2205,
        "fade_len": 110,
        "repetition": 3,
        "freqs": [10000.0, 10200.0, 10400.0, 10600.0, 10800.0, 11000.0, 11200.0, 11400.0]
    },
    "fast": {
        "block_size": 2205,
        "fade_len": 110,
        "repetition": 1,
        "freqs": [8000.0, 8220.0, 8440.0, 8660.0, 8880.0, 9100.0, 9320.0, 9540.0,
                  9760.0, 9980.0, 10200.0, 10420.0, 10640.0, 10860.0, 11080.0, 11300.0]
    }
}

# Helper to clean temp files
def clean_temp_path(path):
    if os.path.exists(path):
        try:
            if os.path.isdir(path):
                shutil.rmtree(path)
            else:
                os.remove(path)
        except Exception:
            pass

def resolve_local_url(url: str) -> str:
    if url.startswith("local:"):
        session_id = url.split("local:")[1]
        for ext in [".wav", ".mp3"]:
            p = os.path.join(TEMP_DIR, session_id, f"AlphaSteg_encoded{ext}")
            if os.path.exists(p):
                return p
        p = os.path.join(TEMP_DIR, session_id, "mixed_stereo.wav")
        if os.path.exists(p):
            return p
        raise HTTPException(status_code=404, detail="Local session file not found or expired")
    return url

# Seeded sign sequence helper
def get_block_sign(block_idx: int, password: str) -> int:
    h = hashlib.sha256(f"{password}:{block_idx}".encode('utf-8')).digest()
    return 1 if (h[0] % 2 == 0) else -1

def process_scramble(samples: list, password: str, start_sample_idx: int = 0) -> list:
    num_samples = len(samples)
    num_blocks = (num_samples + SUB_BLOCK_SIZE - 1) // SUB_BLOCK_SIZE
    start_block = start_sample_idx // SUB_BLOCK_SIZE
    
    signs = [get_block_sign(start_block + b, password) for b in range(num_blocks)]
    
    processed = [0] * num_samples
    for b in range(num_blocks):
        sign = signs[b]
        start = b * SUB_BLOCK_SIZE
        end = min(num_samples, start + SUB_BLOCK_SIZE)
        for i in range(start, end):
            processed[i] = samples[i] * sign
            
    return processed

# --- DSP FILTERS & BIQUAD CLASS ---
class BiquadFilter:
    def __init__(self, b0, b1, b2, a1, a2):
        self.b0 = b0
        self.b1 = b1
        self.b2 = b2
        self.a1 = a1
        self.a2 = a2
        self.x1 = 0.0
        self.x2 = 0.0
        self.y1 = 0.0
        self.y2 = 0.0

    def process(self, x: float) -> float:
        y = self.b0 * x + self.b1 * self.x1 + self.b2 * self.x2 - self.a1 * self.y1 - self.a2 * self.y2
        self.x2 = self.x1
        self.x1 = x
        self.y2 = self.y1
        self.y1 = y
        return y

def make_lpf(fc, fs=44100):
    w0 = math.tan(math.pi * fc / fs)
    Q = 0.7071
    w0_sq = w0 * w0
    denom = 1.0 + w0 / Q + w0_sq
    b0 = w0_sq / denom
    b1 = 2.0 * b0
    b2 = b0
    a1 = 2.0 * (w0_sq - 1.0) / denom
    a2 = (1.0 - w0 / Q + w0_sq) / denom
    return BiquadFilter(b0, b1, b2, a1, a2)

def make_hpf(fc, fs=44100):
    w0 = math.tan(math.pi * fc / fs)
    Q = 0.7071
    w0_sq = w0 * w0
    denom = 1.0 + w0 / Q + w0_sq
    b0 = 1.0 / denom
    b1 = -2.0 * b0
    b2 = b0
    a1 = 2.0 * (w0_sq - 1.0) / denom
    a2 = (1.0 - w0 / Q + w0_sq) / denom
    return BiquadFilter(b0, b1, b2, a1, a2)

# Goertzel Filter for Single Frequency Magnitude Detection
def goertzel(samples: list, target_freq: float, fs: float = 44100.0) -> float:
    N = len(samples)
    if N == 0:
        return 0.0
    k = N * target_freq / fs
    w = 2.0 * math.pi * k / N
    cosine = math.cos(w)
    coeff = 2.0 * cosine
    
    s_prev = 0.0
    s_prev2 = 0.0
    for x in samples:
        s = x + coeff * s_prev - s_prev2
        s_prev2 = s_prev
        s_prev = s
        
    power = s_prev*s_prev + s_prev2*s_prev2 - coeff * s_prev * s_prev2
    return math.sqrt(max(0.0, power)) / N

# Auto-detect file extension and media type from magic bytes
def guess_extension_and_media_type(data: bytes):
    if len(data) >= 4 and data.startswith(b"PK\x03\x04"):
        return ".zip", "application/zip"
    if len(data) >= 4 and data.startswith(b"%PDF"):
        return ".pdf", "application/pdf"
    if len(data) >= 4 and data.startswith(b"\x89PNG"):
        return ".png", "image/png"
    if len(data) >= 3 and data.startswith(b"\xff\xd8\xff"):
        return ".jpg", "image/jpeg"
    try:
        data.decode('utf-8')
        return ".txt", "text/plain"
    except Exception:
        pass
    return ".bin", "application/octet-stream"

# Spotify metadata scraper
def get_spotify_metadata(url: str):
    try:
        r = requests.get(f"https://open.spotify.com/oembed?url={url}", timeout=10)
        r.raise_for_status()
        data = r.json()
        title = data.get("title", "Unknown Track")
        
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        embed_url = data.get("iframe_url", f"https://open.spotify.com/embed/track/{url.split('/track/')[1].split('?')[0]}")
        embed_r = requests.get(embed_url, headers=headers, timeout=10)
        
        import re
        import json
        scripts = re.findall(r'<script[^>]*>(.*?)</script>', embed_r.text, re.DOTALL)
        for s in scripts:
            if '"props"' in s and '"pageProps"' in s:
                try:
                    js_data = json.loads(s)
                    entity = js_data['props']['pageProps']['state']['data']['entity']
                    track_name = entity.get('name', title)
                    artists = ", ".join([a['name'] for a in entity.get('artists', [])])
                    thumbnail = entity.get('visualIdentity', {}).get('image', {}).get('url', data.get("thumbnail_url"))
                    if not thumbnail and 'visuals' in entity:
                        thumbnail = entity['visuals'].get('avatar', {}).get('url')
                    return {
                        "title": track_name,
                        "artist": artists if artists else "Unknown Artist",
                        "thumbnail": thumbnail if thumbnail else data.get("thumbnail_url")
                    }
                except Exception:
                    pass
        return {
            "title": title,
            "artist": "Spotify Artist",
            "thumbnail": data.get("thumbnail_url")
        }
    except Exception as e:
        return {
            "title": "Spotify Track",
            "artist": "Spotify Artist",
            "thumbnail": None
        }

@app.get("/api/resolve")
async def resolve_url(url: str = Query(..., description="The URL to resolve")):
    is_spotify = "spotify.com" in url
    source = "youtube"
    
    spotify_meta = None
    search_query = url
    
    if is_spotify:
        spotify_meta = get_spotify_metadata(url)
        search_query = f"ytsearch1:{spotify_meta['title']} {spotify_meta['artist']}"
        source = "spotify"
    elif "bandcamp.com" in url:
        source = "bandcamp"
        
    ydl_opts = {
        'format': 'bestaudio/best',
        'quiet': True,
        'no_warnings': True,
        'extractor_args': {
            'youtube': {
                'player_client': ['android', 'web_creator', 'web'],
            }
        }
    }
    
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        try:
            info = ydl.extract_info(search_query, download=False)
            if 'entries' in info:
                entry = info['entries'][0]
            else:
                entry = info
                
            stream_url = entry.get("url")
            title = spotify_meta['title'] if is_spotify else entry.get("title", "Unknown Title")
            artist = spotify_meta['artist'] if is_spotify else entry.get("uploader", "Unknown Artist")
            thumbnail = spotify_meta['thumbnail'] if is_spotify else entry.get("thumbnail")
            duration = entry.get("duration")
            
            return {
                "stream_url": stream_url,
                "title": title,
                "artist": artist,
                "thumbnail": thumbnail,
                "duration": duration,
                "source": source
            }
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"Failed to resolve URL: {str(e)}")

# Real-time streaming proxy with channel arithmetic & descrambling
@app.get("/api/stream")
async def stream_audio(
    url: str = Query(...),
    mode: str = Query("primary"),
    method: str = Query("phase"),
    password: str = Query(None),
    ss: float = Query(0.0)
):
    if mode not in ["primary", "hidden"]:
        raise HTTPException(status_code=400, detail="Invalid mode parameter")
    if method not in ["phase", "phase_haas", "spectral", "lsb", "mfsk"]:
        raise HTTPException(status_code=400, detail="Invalid method parameter")
    if math.isnan(ss) or math.isinf(ss) or ss < 0:
        ss = 0.0
        
    print(f"[DEBUG STREAM] ss={ss}, method={method}, mode={mode}, url={url[:60]}")
    url = resolve_local_url(url)

    # For MFSK and LSB, they are files rather than audio streams. However, to let the user play them
    # in the standard browser player, we stream their carrier audio directly (primary) or let the player
    # run them. If method is LSB or MFSK, the primary stream is just the stereo carrier!
    dec_cmd = ["ffmpeg", "-y"]
    if ss > 0:
        dec_cmd.extend(["-ss", str(ss)])
    dec_cmd.extend(["-i", url, "-f", "s16le", "-ac", "2", "-ar", "44100", "-"])
    
    try:
        decoder = subprocess.Popen(
            dec_cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=65536
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to start decoder: {str(e)}")
        
    enc_cmd = [
        "ffmpeg", "-y", "-f", "s16le", "-ac", "2", "-ar", "44100", "-i", "-",
        "-codec:a", "libmp3lame", "-b:a", "192k", "-f", "mp3", "-"
    ]
    try:
        encoder = subprocess.Popen(
            enc_cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=65536
        )
    except Exception as e:
        decoder.terminate()
        raise HTTPException(status_code=500, detail=f"Failed to start encoder: {str(e)}")

    def feed_encoder():
        block_size = 2048
        bytes_per_frame = 4
        read_size = block_size * bytes_per_frame
        
        start_sample_idx = int(ss * 44100)
        boost = 15.0
        
        lpf_L = make_lpf(14000.0)
        lpf_R = make_lpf(14000.0)
        hpf_spectral = make_hpf(15500.0)
        lpf_spectral = make_lpf(3400.0)
        
        haas_len = 882
        haas_buffer = [0.0] * haas_len
        haas_write_idx = 0
        
        try:
            while True:
                pcm_data = decoder.stdout.read(read_size)
                if not pcm_data:
                    break
                num_frames = len(pcm_data) // bytes_per_frame
                if num_frames == 0:
                    break
                    
                samples = struct.unpack(f"<{num_frames * 2}h", pcm_data[:num_frames * bytes_per_frame])
                out_samples = []
                
                # If digital file codecs, streaming 'hidden' is silent or static (MFSK will whistle, LSB is noise)
                # We just stream the exact PCM samples directly for primary, and silence/static for hidden!
                if method in ["lsb", "mfsk"]:
                    if mode == "primary":
                        # Output exactly the mixed carrier samples
                        out_samples.extend(samples)
                    else:
                        # Output quiet static
                        for i in range(0, len(samples), 2):
                            val = int(math.sin(i) * 1000)
                            out_samples.extend([val, val])
                            
                elif method == "phase":
                    if mode == "primary":
                        for i in range(0, len(samples), 2):
                            l = samples[i] / 32768.0
                            r = samples[i+1] / 32768.0
                            mid = (l + r) / 2.0
                            val = int(max(-1.0, min(1.0, mid)) * 32767.0)
                            out_samples.extend([val, val])
                    else:
                        side_fs = []
                        for i in range(0, len(samples), 2):
                            l = samples[i] / 32768.0
                            r = samples[i+1] / 32768.0
                            side_fs.append((l - r) / 2.0)
                        if password:
                            side_ints = [int(s * 32767.0) for s in side_fs]
                            descrambled_ints = process_scramble(side_ints, password, start_sample_idx)
                            side_fs = [s / 32768.0 for s in descrambled_ints]
                        for s in side_fs:
                            s_boosted = max(-1.0, min(1.0, s * boost))
                            val = int(s_boosted * 32767.0)
                            out_samples.extend([val, val])
                            
                elif method == "phase_haas":
                    if mode == "primary":
                        for i in range(0, len(samples), 2):
                            l = samples[i] / 32768.0
                            r = samples[i+1] / 32768.0
                            mono_val = (l + r) / 2.0
                            
                            delayed_val = haas_buffer[haas_write_idx]
                            haas_buffer[haas_write_idx] = mono_val
                            haas_write_idx = (haas_write_idx + 1) % haas_len
                            
                            left_out = int(mono_val * 32767.0)
                            right_out = int(delayed_val * 32767.0)
                            out_samples.extend([left_out, right_out])
                    else:
                        side_fs = []
                        for i in range(0, len(samples), 2):
                            l = samples[i] / 32768.0
                            r = samples[i+1] / 32768.0
                            side_fs.append((l - r) / 2.0)
                        if password:
                            side_ints = [int(s * 32767.0) for s in side_fs]
                            descrambled_ints = process_scramble(side_ints, password, start_sample_idx)
                            side_fs = [s / 32768.0 for s in descrambled_ints]
                        for s in side_fs:
                            s_boosted = max(-1.0, min(1.0, s * boost))
                            val = int(s_boosted * 32767.0)
                            out_samples.extend([val, val])
                            
                elif method == "spectral":
                    if mode == "primary":
                        for i in range(0, len(samples), 2):
                            l = samples[i] / 32768.0
                            r = samples[i+1] / 32768.0
                            l_filt = lpf_L.process(l)
                            r_filt = lpf_R.process(r)
                            l_val = int(max(-1.0, min(1.0, l_filt)) * 32767.0)
                            r_val = int(max(-1.0, min(1.0, r_filt)) * 32767.0)
                            out_samples.extend([l_val, r_val])
                    else:
                        mono_samples = []
                        for i in range(0, len(samples), 2):
                            l = samples[i] / 32768.0
                            r = samples[i+1] / 32768.0
                            mono_samples.append((l + r) / 2.0)
                        hp_samples = [hpf_spectral.process(s) for s in mono_samples]
                        demod_samples = []
                        for idx, s in enumerate(hp_samples):
                            abs_idx = start_sample_idx + idx
                            carrier_wave = math.sin(2.0 * math.pi * 17000.0 * abs_idx / 44100.0)
                            demod_samples.append(s * carrier_wave)
                        lp_samples = [lpf_spectral.process(s) for s in demod_samples]
                        if password:
                            side_ints = [int(s * 32767.0) for s in lp_samples]
                            descrambled_ints = process_scramble(side_ints, password, start_sample_idx)
                            lp_samples = [s / 32768.0 for s in descrambled_ints]
                        for s in lp_samples:
                            s_boosted = max(-1.0, min(1.0, s * boost * 2.0))
                            val = int(s_boosted * 32767.0)
                            out_samples.extend([val, val])

                encoder.stdin.write(struct.pack(f"<{len(out_samples)}h", *out_samples))
                encoder.stdin.flush()
                start_sample_idx += num_frames
                
        except Exception:
            pass
        finally:
            try:
                encoder.stdin.close()
            except Exception:
                pass
            try:
                decoder.terminate()
            except Exception:
                pass

    t = threading.Thread(target=feed_encoder)
    t.daemon = True
    t.start()

    def event_generator():
        nonlocal decoder, encoder, t
        try:
            while True:
                mp3_data = encoder.stdout.read(4096)
                if not mp3_data:
                    break
                yield mp3_data
        except Exception:
            pass
        finally:
            try:
                decoder.terminate()
            except Exception:
                pass
            try:
                encoder.terminate()
            except Exception:
                pass
            t.join(timeout=1.0)
            
    return StreamingResponse(event_generator(), media_type="audio/mpeg")

# Stego Scanner Endpoint
@app.get("/api/scan_stego")
async def scan_stego(url: str = Query(...)):
    url = resolve_local_url(url)

    session_id = str(uuid.uuid4())
    session_dir = os.path.join(TEMP_DIR, session_id)
    os.makedirs(session_dir, exist_ok=True)
    temp_3sec_wav = os.path.join(session_dir, "temp_3sec.wav")
    
    try:
        # Download first 3 seconds of audio to WAV
        cmd = ["ffmpeg", "-y", "-i", url, "-t", "3", "-ac", "2", "-ar", "44100", "-acodec", "pcm_s16le", temp_3sec_wav]
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        
        with wave.open(temp_3sec_wav, 'rb') as w:
            params = w.getparams()
            frames = w.readframes(params.nframes)
            samples = list(struct.unpack(f"<{params.nframes * 2}h", frames))
            
        clean_temp_path(session_dir)
        
        if len(samples) < 1000:
            return {"method": "none"}
            
        # 1. Check LSB Signature [0xAF, 0x55]
        lsb_bytes = bytearray()
        for b_idx in range(2):
            val = 0
            for bit_idx in range(8):
                idx = b_idx * 8 + bit_idx
                if idx < len(samples):
                    val = (val << 1) | (samples[idx] & 1)
            lsb_bytes.append(val)
        if lsb_bytes[0] == 0xAF and lsb_bytes[1] == 0x55:
            return {"method": "lsb"}
            
        # 2. Check MFSK Preamble (Check standard, balanced, and fast presets)
        float_samples = [s / 32768.0 for s in samples]
        mono_samples = []
        for i in range(0, len(float_samples), 2):
            mono_samples.append((float_samples[i] + float_samples[i+1]) / 2.0)
            
        # Check fast preset first (16 frequencies, block size 2205, preamble 4410 samples)
        fast_freqs = MFSK_PRESETS["fast"]["freqs"]
        fast_preamble_found = False
        step = 220
        for idx in range(0, min(len(mono_samples) - 4410, 44100), step):
            win = mono_samples[idx : idx + 4410]
            mags = [goertzel(win, f) for f in fast_freqs]
            if all(m > 0.00035 for m in mags):
                fast_preamble_found = True
                break
        if fast_preamble_found:
            return {"method": "mfsk", "preset": "fast"}
            
        # Check standard/balanced (8 frequencies)
        std_freqs = MFSK_PRESETS["standard"]["freqs"]
        preamble_idx = -1
        for idx in range(0, min(len(mono_samples) - 8820, 88200), step):
            win1 = mono_samples[idx : idx + 4410]
            mags1 = [goertzel(win1, f) for f in std_freqs]
            if all(m > 0.00035 for m in mags1):
                preamble_idx = idx
                break
                
        if preamble_idx != -1:
            # Check the NEXT 4410-sample window
            win2 = mono_samples[preamble_idx + 4410 : preamble_idx + 8820]
            mags2 = [goertzel(win2, f) for f in std_freqs]
            if all(m > 0.00035 for m in mags2):
                return {"method": "mfsk", "preset": "standard"}
            else:
                return {"method": "mfsk", "preset": "balanced"}
            
        # 3. Check Spectral Modulation at 17kHz
        # We calculate the Goertzel magnitude at 17kHz and compare it to 15kHz (which is the control floor)
        # We check over the entire 3 seconds
        spec_mag = goertzel(mono_samples, 17000.0)
        floor_mag = goertzel(mono_samples, 15000.0)
        if spec_mag > 0.0008 and (floor_mag == 0.0 or (spec_mag / floor_mag) > 6.0):
            return {"method": "spectral"}
            
        # 4. Check Phase Inversion (Mono carrier)
        # We measure Sum (L+R) vs Diff (L-R) power
        sum_power = 0.0
        diff_power = 0.0
        for i in range(0, len(float_samples), 2):
            l = float_samples[i]
            r = float_samples[i+1]
            sum_power += (l + r) ** 2
            diff_power += (l - r) ** 2
            
        # If carrier is mono, diff_power is extremely small relative to sum_power
        # Ratio will be high (e.g. sum_power / diff_power > 80.0)
        if sum_power > 0.0 and diff_power > 0.0:
            ratio = sum_power / diff_power
            # Check diff_power is not 0 (silence)
            if ratio > 80.0 and diff_power > 1.0:
                return {"method": "phase"}
                
        return {"method": "none"}
        
    except Exception as e:
        clean_temp_path(session_dir)
        return {"method": "none", "error": str(e)}

# Digital File Decrypt / Decode Endpoint
@app.get("/api/decode_file")
async def decode_file(
    url: str = Query(...),
    method: str = Query(...),
    password: str = Query(None),
    preset: str = Query("standard")
):
    if method not in ["lsb", "mfsk"]:
        raise HTTPException(status_code=400, detail="Decoding file only supports 'lsb' or 'mfsk' methods.")
    if preset not in MFSK_PRESETS:
        raise HTTPException(status_code=400, detail="Invalid MFSK preset parameter.")
        
    url = resolve_local_url(url)

    session_id = str(uuid.uuid4())
    session_dir = os.path.join(TEMP_DIR, session_id)
    os.makedirs(session_dir, exist_ok=True)
    temp_wav = os.path.join(session_dir, "temp_decode.wav")
    
    try:
        # Download full WAV for decoding
        cmd = ["ffmpeg", "-y", "-i", url, "-ac", "2", "-ar", "44100", "-acodec", "pcm_s16le", temp_wav]
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        
        with wave.open(temp_wav, 'rb') as w:
            params = w.getparams()
            frames = w.readframes(params.nframes)
            samples = list(struct.unpack(f"<{params.nframes * 2}h", frames))
            
        clean_temp_path(temp_wav)
        
        if method == "lsb":
            # Extract LSB bits
            # First extract 48 bits to decode magic bytes (16 bits) and length (32 bits)
            if len(samples) < 48:
                raise HTTPException(status_code=400, detail="Audio file is too short.")
                
            header_bits = []
            for i in range(48):
                header_bits.append(samples[i] & 1)
                
            # Parse header
            header_bytes = bytearray()
            for b_idx in range(6):
                val = 0
                for bit_idx in range(8):
                    val = (val << 1) | header_bits[b_idx * 8 + bit_idx]
                header_bytes.append(val)
                
            if header_bytes[0] != 0xAF or header_bytes[1] != 0x55:
                raise HTTPException(status_code=400, detail="No AlphaSteg LSB signature found in this file.")
                
            file_len = struct.unpack("<I", header_bytes[2:6])[0]
            
            # Read file bits
            total_bits_needed = 48 + file_len * 8
            if len(samples) < total_bits_needed:
                raise HTTPException(status_code=400, detail="Corrupted LSB length or carrier file.")
                
            payload_bits = []
            for i in range(48, total_bits_needed):
                payload_bits.append(samples[i] & 1)
                
            # Reconstruct bytes
            file_bytes = bytearray()
            for b_idx in range(file_len):
                val = 0
                for bit_idx in range(8):
                    val = (val << 1) | payload_bits[b_idx * 8 + bit_idx]
                file_bytes.append(val)
                
        elif method == "mfsk":
            p_cfg = MFSK_PRESETS[preset]
            block_size = p_cfg["block_size"]
            repetition = p_cfg["repetition"]
            freqs = p_cfg["freqs"]
            num_freqs = len(freqs)
            
            float_samples = [s / 32768.0 for s in samples]
            mono_samples = []
            for i in range(0, len(float_samples), 2):
                mono_samples.append((float_samples[i] + float_samples[i+1]) / 2.0)
                
            # 1. Synchronization: find preamble max score (2 blocks = 2 * block_size of concurrent tones)
            preamble_len = 2 * block_size
            best_idx = 0
            max_score = 0.0
            step = 220
            for idx in range(0, min(len(mono_samples) - preamble_len, 176400), step):
                win = mono_samples[idx : idx + preamble_len]
                score = sum(goertzel(win, f) for f in freqs)
                if score > max_score:
                    max_score = score
                    best_idx = idx
                    
            if max_score < 0.003:
                raise HTTPException(status_code=400, detail="No robust MFSK preamble detected in this file.")
                
            data_start = best_idx + preamble_len
            
            # Read MFSK blocks (words)
            words_received = []
            while data_start + block_size <= len(mono_samples):
                block_samples = mono_samples[data_start : data_start + block_size]
                w_val = 0
                for j in range(num_freqs):
                    mag = goertzel(block_samples, freqs[j])
                    if mag > 0.00035:
                        w_val |= (1 << (num_freqs - 1 - j))
                words_received.append(w_val)
                data_start += block_size
                
            # Decode repetition code and de-XOR
            words_voted = []
            if repetition == 3:
                for idx in range(0, len(words_received) - 2, 3):
                    w0 = words_received[idx]
                    w1 = words_received[idx+1]
                    w2 = words_received[idx+2]
                    
                    voted_word = 0
                    for bit in range(num_freqs):
                        votes = ((w0 >> bit) & 1) + ((w1 >> bit) & 1) + ((w2 >> bit) & 1)
                        bit_val = 1 if votes >= 2 else 0
                        voted_word |= (bit_val << bit)
                    words_voted.append(voted_word)
            else:
                words_voted = words_received
                
            # De-XOR and rebuild body bytes
            body_bytes = []
            for body_idx, w in enumerate(words_voted):
                if password and len(password.strip()) > 0:
                    packet_pos = 2 + repetition * body_idx
                    if num_freqs == 8:
                        key = hashlib.sha256(f"{password}:{packet_pos}".encode('utf-8')).digest()[0]
                        w = w ^ key
                    else:
                        key_bytes = hashlib.sha256(f"{password}:{packet_pos}".encode('utf-8')).digest()
                        w = w ^ ((key_bytes[0] << 8) | key_bytes[1])
                
                # Unpack word to bytes
                if num_freqs == 8:
                    body_bytes.append(w & 0xFF)
                elif num_freqs == 16:
                    body_bytes.append((w >> 8) & 0xFF)
                    body_bytes.append(w & 0xFF)
                    
            if len(body_bytes) < 5:
                raise HTTPException(status_code=400, detail="Extracted message payload is too short.")
                
            # Parse length (4-byte unsigned int)
            file_len = body_bytes[0] | (body_bytes[1] << 8) | (body_bytes[2] << 16) | (body_bytes[3] << 24)
            if file_len + 5 > len(body_bytes):
                raise HTTPException(status_code=400, detail="MFSK packet checksum failure or incorrect decryption password.")
                
            file_bytes = bytes(body_bytes[4 : 4 + file_len])
            checksum_received = body_bytes[4 + file_len]
            checksum_calculated = sum(file_bytes) & 0xFF
            
            if checksum_received != checksum_calculated:
                raise HTTPException(status_code=400, detail="Checksum mismatch. The file may be corrupted or the password is incorrect.")
                
        # Guess extension & media type
        ext, media_type = guess_extension_and_media_type(file_bytes)
        out_filename = f"AlphaSteg_decoded{ext}"
        out_path = os.path.join(session_dir, out_filename)
        
        with open(out_path, "wb") as f_out:
            f_out.write(file_bytes)
            
        return FileResponse(out_path, media_type=media_type, filename=out_filename)
        
    except Exception as e:
        clean_temp_path(session_dir)
        if isinstance(e, HTTPException):
            raise e
        raise HTTPException(status_code=500, detail=f"Decoding failed: {str(e)}")

# Encode Audio Route
@app.post("/api/encode")
async def encode_audio(
    carrier: UploadFile = File(...),
    payload: UploadFile | None = File(None),
    file_payload: UploadFile | None = File(None),
    alpha: float = Form(0.05),
    loop: bool = Form(True),
    password: str = Form(None),
    method: str = Form("phase"),
    preset: str = Form("standard")
):
    if method not in ["phase", "spectral", "lsb", "mfsk"]:
        raise HTTPException(status_code=400, detail="Invalid method parameter")
    if preset not in MFSK_PRESETS:
        raise HTTPException(status_code=400, detail="Invalid MFSK preset parameter")

    session_id = str(uuid.uuid4())
    session_dir = os.path.join(TEMP_DIR, session_id)
    os.makedirs(session_dir, exist_ok=True)
    
    carrier_ext = os.path.splitext(carrier.filename)[1]
    carrier_path = os.path.join(session_dir, f"carrier_src{carrier_ext}")
    
    with open(carrier_path, "wb") as f:
        shutil.copyfileobj(carrier.file, f)
        
    carrier_decoded_wav = os.path.join(session_dir, "carrier_decoded.wav")
    mixed_stereo_wav = os.path.join(session_dir, "mixed_stereo.wav")
    
    # Check what file output extension to return.
    # LSB MUST be downloaded as WAV to remain lossless! MFSK and audio stego can be MP3!
    is_lossless_out = (method == "lsb")
    output_filename = f"AlphaSteg_encoded{'.wav' if is_lossless_out else '.mp3'}"
    output_file = os.path.join(session_dir, output_filename)
    
    try:
        # Handle digital file stego methods
        if method in ["lsb", "mfsk"]:
            if not file_payload:
                raise HTTPException(status_code=400, detail="You must provide a File Payload for LSB or MFSK steganography.")
                
            file_bytes = await file_payload.read()
            L_file = len(file_bytes)
            
            # Convert carrier to stereo 16-bit 44.1kHz WAV
            cmd_c = ["ffmpeg", "-y", "-i", carrier_path, "-ac", "2", "-ar", "44100", "-acodec", "pcm_s16le", carrier_decoded_wav]
            subprocess.run(cmd_c, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            
            if method == "lsb":
                with wave.open(carrier_decoded_wav, 'rb') as w_c:
                    c_params = w_c.getparams()
                    c_frames = w_c.readframes(c_params.nframes)
                    c_samples = list(struct.unpack(f"<{c_params.nframes * 2}h", c_frames))
                
                # LSB Magic: [0xAF, 0x55] + length (4 bytes) + file bytes
                header = bytearray([0xAF, 0x55])
                header.extend(struct.pack("<I", L_file))
                header.extend(file_bytes)
                
                bits = []
                for b in header:
                    for j in range(8):
                        bits.append((b >> (7 - j)) & 1)
                        
                if len(c_samples) < len(bits):
                    raise HTTPException(status_code=400, detail="Carrier audio is too short to hide this file.")
                    
                for i in range(len(bits)):
                    u_val = c_samples[i] & 0xFFFF
                    u_val = (u_val & 0xFFFE) | bits[i]
                    c_samples[i] = u_val if u_val < 32768 else u_val - 65536
                    
                with wave.open(mixed_stereo_wav, 'wb') as w_out:
                    w_out.setnchannels(2)
                    w_out.setsampwidth(2)
                    w_out.setframerate(44100)
                    w_out.writeframes(struct.pack(f"<{len(c_samples)}h", *c_samples))
                    
            elif method == "mfsk":
                p_cfg = MFSK_PRESETS[preset]
                block_size = p_cfg["block_size"]
                fade_len = p_cfg["fade_len"]
                repetition = p_cfg["repetition"]
                freqs = p_cfg["freqs"]
                num_freqs = len(freqs)
                
                with wave.open(carrier_decoded_wav, 'rb') as w_c:
                    num_frames = w_c.getnframes()
                    
                # Body: length (4 bytes) + file bytes + checksum (1 byte)
                body = bytearray()
                body.extend(struct.pack("<I", L_file))
                body.extend(file_bytes)
                body.append(sum(file_bytes) & 0xFF)
                
                # If fast (16 freqs = 2 bytes per block), pad to even bytes
                if num_freqs == 16 and len(body) % 2 != 0:
                    body.append(0x00)
                    
                # Group bytes into words of size matching num_freqs
                words = []
                if num_freqs == 8:
                    words = list(body)
                elif num_freqs == 16:
                    for idx in range(0, len(body), 2):
                        w = (body[idx] << 8) | body[idx+1]
                        words.append(w)
                        
                # Build packet list of words
                # Preamble: 2 blocks of all active frequencies
                preamble_val = 0xFF if num_freqs == 8 else 0xFFFF
                packet = [preamble_val, preamble_val]
                
                for body_idx, w in enumerate(words):
                    if password and len(password.strip()) > 0:
                        packet_pos = 2 + repetition * body_idx
                        if num_freqs == 8:
                            key = hashlib.sha256(f"{password}:{packet_pos}".encode('utf-8')).digest()[0]
                            w = w ^ key
                        else:
                            key_bytes = hashlib.sha256(f"{password}:{packet_pos}".encode('utf-8')).digest()
                            w = w ^ ((key_bytes[0] << 8) | key_bytes[1])
                            
                    # Apply repetition code
                    packet.extend([w] * repetition)
                    
                # Mix packet centered in carrier
                amp = alpha * 0.05
                needed_frames = len(packet) * block_size
                if num_frames < needed_frames:
                    raise HTTPException(status_code=400, detail=f"Carrier audio is too short. Hiding this file requires at least {needed_frames / 44100.0:.1f} seconds of audio.")
                    
                # Precompute sine tables with trapezoidal window applied
                sine_tables = []
                for f in freqs:
                    table = []
                    for i in range(block_size):
                        win = 1.0
                        if i < fade_len:
                            win = i / float(fade_len)
                        elif i > block_size - fade_len:
                            win = (block_size - i) / float(fade_len)
                        table.append(math.sin(2.0 * math.pi * f * i / 44100.0) * win * amp)
                    sine_tables.append(table)
                    
                # Generate modulated mono samples
                mod_samples = []
                num_blocks = num_frames // block_size
                for b_idx in range(num_blocks):
                    if b_idx < len(packet):
                        w = packet[b_idx]
                    else:
                        w = 0x00
                        
                    active_indices = [j for j in range(num_freqs) if (w >> (num_freqs - 1 - j)) & 1]
                    if active_indices:
                        for i in range(block_size):
                            val = sum(sine_tables[j][i] for j in active_indices)
                            mod_samples.append(int(max(-1.0, min(1.0, val)) * 32767.0))
                    else:
                        mod_samples.extend([0] * block_size)
                        
                modulated_mono_wav = os.path.join(session_dir, "modulated_mono.wav")
                with wave.open(modulated_mono_wav, 'wb') as w_m:
                    w_m.setnchannels(1)
                    w_m.setsampwidth(2)
                    w_m.setframerate(44100)
                    w_m.writeframes(struct.pack(f"<{len(mod_samples)}h", *mod_samples))
                    
                # Mix using FFmpeg pan filter graph
                mix_cmd = [
                    "ffmpeg", "-y",
                    "-i", carrier_decoded_wav,
                    "-i", modulated_mono_wav,
                    "-filter_complex", "[0:a][1:a]amerge=inputs=2[m];[m]pan=stereo|c0=c0+c2|c1=c1+c2[out]",
                    "-map", "[out]",
                    "-acodec", "pcm_s16le",
                    mixed_stereo_wav
                ]
                subprocess.run(mix_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                clean_temp_path(modulated_mono_wav)
                    
            if is_lossless_out:
                shutil.copyfile(mixed_stereo_wav, output_file)
            else:
                # Convert to MP3
                cmd_enc = ["ffmpeg", "-y", "-i", mixed_stereo_wav, "-codec:a", "libmp3lame", "-b:a", "320k", output_file]
                subprocess.run(cmd_enc, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                
        else:
            # Handle audio stego methods (Phase / Spectral)
            if not payload:
                raise HTTPException(status_code=400, detail="You must provide an Audio Payload for Phase or Spectral steganography.")
                
            payload_ext = os.path.splitext(payload.filename)[1]
            payload_path = os.path.join(session_dir, f"payload_src{payload_ext}")
            with open(payload_path, "wb") as f:
                shutil.copyfileobj(payload.file, f)
                
            payload_mono_wav = os.path.join(session_dir, "payload_mono.wav")
            
            # Convert payload to mono 16-bit 44.1kHz WAV
            cmd_p = ["ffmpeg", "-y", "-i", payload_path, "-ac", "1", "-ar", "44100", "-acodec", "pcm_s16le", payload_mono_wav]
            subprocess.run(cmd_p, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            
            with wave.open(payload_mono_wav, 'rb') as w_p:
                p_params = w_p.getparams()
                p_frames = w_p.readframes(p_params.nframes)
                p_samples = list(struct.unpack(f"<{p_params.nframes}h", p_frames))
                
            if password and len(password.strip()) > 0:
                p_samples = process_scramble(p_samples, password.strip(), 0)
                with wave.open(payload_mono_wav, 'wb') as w_p_out:
                    w_p_out.setparams(p_params)
                    w_p_out.writeframes(struct.pack(f"<{len(p_samples)}h", *p_samples))

            if method == "phase":
                cmd_c = ["ffmpeg", "-y", "-i", carrier_path, "-ac", "1", "-ar", "44100", "-acodec", "pcm_s16le", carrier_decoded_wav]
                subprocess.run(cmd_c, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                
                mix_cmd = ["ffmpeg", "-y", "-i", carrier_decoded_wav]
                if loop:
                    mix_cmd.extend(["-stream_loop", "-1"])
                mix_cmd.extend([
                    "-i", payload_mono_wav,
                    "-filter_complex", f"[0:a][1:a]amerge=inputs=2[m];[m]aeval=exprs='val(0)+{alpha}*val(1)|val(0)-{alpha}*val(1)'[out]",
                    "-map", "[out]",
                    "-shortest",
                    "-acodec", "pcm_s16le",
                    mixed_stereo_wav
                ])
                subprocess.run(mix_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                
            elif method == "spectral":
                cmd_c = ["ffmpeg", "-y", "-i", carrier_path, "-ac", "2", "-ar", "44100", "-acodec", "pcm_s16le", carrier_decoded_wav]
                subprocess.run(cmd_c, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                
                mix_cmd = ["ffmpeg", "-y", "-i", carrier_decoded_wav]
                if loop:
                    mix_cmd.extend(["-stream_loop", "-1"])
                    
                filter_graph = f"[1:a]lowpass=f=3000,aeval=exprs='val(0)*sin(2*PI*17000*t)'[mod]; [0:a][mod]amerge=inputs=2[m]; [m]aeval=exprs='val(0)+{alpha}*val(2)|val(1)+{alpha}*val(2)'"
                mix_cmd.extend([
                    "-i", payload_mono_wav,
                    "-filter_complex", filter_graph,
                    "-shortest",
                    "-acodec", "pcm_s16le",
                    mixed_stereo_wav
                ])
                subprocess.run(mix_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
                
            # Convert mixed WAV to MP3
            cmd_enc = ["ffmpeg", "-y", "-i", mixed_stereo_wav, "-codec:a", "libmp3lame", "-b:a", "320k", output_file]
            subprocess.run(cmd_enc, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            
        duration = 0.0
        try:
            with wave.open(carrier_decoded_wav, 'rb') as w:
                duration = w.getnframes() / float(w.getframerate())
        except Exception:
            pass

        return {
            "session_id": session_id,
            "download_url": f"/api/download?session_id={session_id}",
            "stream_url": f"local:{session_id}",
            "duration": duration
        }
        
    except Exception as e:
        clean_temp_path(session_dir)
        raise HTTPException(status_code=500, detail=f"Encoding failed: {str(e)}")

@app.get("/api/download")
async def download_file(session_id: str):
    # Search for wav or mp3
    for ext in [".mp3", ".wav"]:
        file_path = os.path.join(TEMP_DIR, session_id, f"AlphaSteg_encoded{ext}")
        if os.path.exists(file_path):
            media_type = "audio/wav" if ext == ".wav" else "audio/mpeg"
            return FileResponse(file_path, media_type=media_type, filename=f"AlphaSteg_encoded{ext}")
            
    raise HTTPException(status_code=404, detail="File not found or session expired")

@app.post("/api/upload_temp")
async def upload_temp(file: UploadFile = File(...)):
    session_id = str(uuid.uuid4())
    session_dir = os.path.join(TEMP_DIR, session_id)
    os.makedirs(session_dir, exist_ok=True)
    
    ext = os.path.splitext(file.filename)[1]
    temp_upload = os.path.join(session_dir, f"uploaded_file{ext}")
    local_wav = os.path.join(session_dir, "mixed_stereo.wav")
    
    try:
        with open(temp_upload, "wb") as f:
            shutil.copyfileobj(file.file, f)
            
        cmd = ["ffmpeg", "-y", "-i", temp_upload, "-ac", "2", "-ar", "44100", "-acodec", "pcm_s16le", local_wav]
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        clean_temp_path(temp_upload)
        
        with wave.open(local_wav, 'rb') as w:
            frames = w.getnframes()
            rate = w.getframerate()
            duration = frames / float(rate)
            
        return {
            "stream_url": f"local:{session_id}",
            "title": file.filename,
            "artist": "Uploaded Local File",
            "duration": duration
        }
    except Exception as e:
        clean_temp_path(session_dir)
        raise HTTPException(status_code=500, detail=f"Failed to process file: {str(e)}")

# Mount static files
app.mount("/", StaticFiles(directory=os.path.join(os.path.dirname(os.path.abspath(__file__)), "static"), html=True), name="static")

def open_browser():
    import time
    import webbrowser
    time.sleep(1.5)
    webbrowser.open("http://127.0.0.1:8000/")

if __name__ == "__main__":
    import uvicorn
    threading.Thread(target=open_browser, daemon=True).start()
    uvicorn.run("main:app", host="127.0.0.1", port=8000, reload=False)
