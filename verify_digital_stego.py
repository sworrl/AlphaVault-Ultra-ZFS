import wave
import struct
import math
import sys
import os
import requests
import subprocess
import time

os.chdir(os.path.dirname(os.path.abspath(__file__)))

TEMP_DIR = "temp"
os.makedirs(TEMP_DIR, exist_ok=True)

# Generate test carrier (10 seconds stereo sine wave)
def generate_carrier_wav(path, freq, duration=30.0, sample_rate=44100):
    with wave.open(path, 'wb') as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        num_samples = int(duration * sample_rate)
        samples = []
        for i in range(num_samples):
            # Left channel: sine 440Hz
            # Right channel: sine 440Hz panned slightly
            val = math.sin(2.0 * math.pi * freq * i / sample_rate)
            samples.extend([int(val * 16384.0), int(val * 12000.0)])
        w.writeframes(struct.pack(f"<{len(samples)}h", *samples))

def main():
    print("--- ALPHASTEG 0.5 DIGITAL FILE & SCANNER TEST ---")
    
    carrier_path = "test_carrier.wav"
    secret_text_path = "secret_message.txt"
    secret_content = b"AlphaSteg 0.5 Digital MFSK & LSB Verification Payload String 12345!"
    
    generate_carrier_wav(carrier_path, 440.0)
    with open(secret_text_path, "wb") as f:
        f.write(secret_content)
        
    print("Test carrier and file payload generated.")
    
    # Start server
    print("Starting server...")
    server_process = subprocess.Popen(
        [sys.executable, "main.py"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL
    )
    time.sleep(2.5) # Wait for startup
    
    try:
        # 1. Test Digital LSB Encoding
        print("Testing Digital LSB encoding...")
        with open(carrier_path, 'rb') as c_file, open(secret_text_path, 'rb') as f_file:
            files = {
                'carrier': c_file,
                'file_payload': f_file
            }
            data = {
                'alpha': 0.05,
                'method': 'lsb'
            }
            r = requests.post("http://127.0.0.1:8000/api/encode", files=files, data=data)
        r.raise_for_status()
        lsb_res = r.json()
        print("LSB encode response:", lsb_res)
        
        # 2. Test LSB Scan
        print("Testing LSB scanning...")
        r_scan = requests.get(f"http://127.0.0.1:8000/api/scan_stego?url={lsb_res['stream_url']}")
        r_scan.raise_for_status()
        scan_res = r_scan.json()
        print("LSB scan result:", scan_res)
        assert scan_res["method"] == "lsb", f"Expected lsb, got {scan_res['method']}"
        
        # 3. Test LSB Decoding
        print("Testing LSB file decoding...")
        r_dec = requests.get(f"http://127.0.0.1:8000/api/decode_file?url={lsb_res['stream_url']}&method=lsb")
        r_dec.raise_for_status()
        decoded_content = r_dec.content
        print("LSB decoded payload length:", len(decoded_content))
        assert decoded_content == secret_content, "LSB payload corrupted!"
        print("LSB payload content verification: MATCH")
        
        # 4. Test Digital MFSK Encoding & Decoding for each preset
        for preset in ["standard", "balanced", "fast"]:
            print(f"\n--- Testing MFSK preset: {preset} ---")
            with open(carrier_path, 'rb') as c_file, open(secret_text_path, 'rb') as f_file:
                files = {
                    'carrier': c_file,
                    'file_payload': f_file
                }
                data = {
                    'alpha': 0.05,
                    'method': 'mfsk',
                    'preset': preset,
                    'password': 'testpassword123'
                }
                r = requests.post("http://127.0.0.1:8000/api/encode", files=files, data=data)
            r.raise_for_status()
            mfsk_res = r.json()
            print(f"MFSK ({preset}) encode response:", mfsk_res)
            
            # Test Scan
            print(f"Testing MFSK ({preset}) scanning...")
            r_scan = requests.get(f"http://127.0.0.1:8000/api/scan_stego?url={mfsk_res['stream_url']}")
            r_scan.raise_for_status()
            scan_res = r_scan.json()
            print(f"MFSK ({preset}) scan result:", scan_res)
            assert scan_res["method"] == "mfsk", f"Expected mfsk, got {scan_res['method']}"
            assert scan_res.get("preset") == preset, f"Expected preset {preset}, got {scan_res.get('preset')}"
            
            # Test Decoding
            print(f"Testing MFSK ({preset}) file decoding...")
            r_dec = requests.get(f"http://127.0.0.1:8000/api/decode_file?url={mfsk_res['stream_url']}&method=mfsk&preset={preset}&password=testpassword123")
            r_dec.raise_for_status()
            decoded_content = r_dec.content
            print(f"MFSK ({preset}) decoded payload length:", len(decoded_content))
            assert decoded_content == secret_content, f"MFSK ({preset}) payload corrupted!"
            print(f"MFSK ({preset}) payload content verification: MATCH")
        
        print("\nAll digital file methods, scanning, password encryption, and majority-voting checksums passed successfully!")
        print("PASS")
        
    except Exception as e:
        if hasattr(e, 'response') and e.response is not None:
            print("Server Error Response:", e.response.text)
        print("TEST FAILED:", e)
        sys.exit(1)
    finally:
        print("Terminating server...")
        server_process.terminate()
        server_process.wait()
        
        # Clean up files
        for f in [carrier_path, secret_text_path]:
            if os.path.exists(f):
                os.remove(f)

if __name__ == "__main__":
    main()
