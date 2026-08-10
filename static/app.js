const DEFAULT_PLACEHOLDER = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='64' height='64' viewBox='0 0 64 64'><rect width='64' height='64' fill='%230f1624'/><path d='M20 40 L28 24 L36 36 L44 28 L48 40' fill='none' stroke='%2300f2fe' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'/></svg>";

// State variables
let currentUrl = null;
let currentMode = "primary";
let streamStartTime = 0;
let trackDuration = 0;
let isPlaying = false;
let audioContext = null;
let analyser = null;
let visualizerAnimationId = null;
let currentVisMode = "spectrum"; // spectrum | waveform | lissajous
let payloadMode = "file"; // file | text

// DOM Elements
const audioPlayer = document.getElementById("audio-player");
const tabPlayer = document.getElementById("tab-player");
const tabEncoder = document.getElementById("tab-encoder");
const viewPlayer = document.getElementById("view-player");
const viewEncoder = document.getElementById("view-encoder");

// Player Elements
const inputUrl = document.getElementById("stream-url");
const btnLoadUrl = document.getElementById("btn-load-url");
const trackCard = document.getElementById("track-card");
const trackThumbnail = document.getElementById("track-thumbnail");
const trackTitle = document.getElementById("track-title");
const trackArtist = document.getElementById("track-artist");
const trackSourceBadge = document.getElementById("track-source-badge");

const dropPlayerFile = document.getElementById("drop-player-file");
const inputPlayerFile = document.getElementById("input-player-file");
const playerFileName = document.getElementById("player-file-name");

const toggleButtons = document.querySelectorAll(".toggle-btn");
const toggleSlider = document.getElementById("toggle-slider");
const decryptPasswordCheck = document.getElementById("decrypt-password-check");
const playerPasswordInputGroup = document.getElementById("player-password-input-group");
const playerPassword = document.getElementById("player-password");
const playerStegoMethod = document.getElementById("player-stego-method");
const btnScanStego = document.getElementById("btn-scan-stego");
const scanResultBadge = document.getElementById("scan-result-badge");
const scanResultIcon = document.getElementById("scan-result-icon");
const scanResultTitle = document.getElementById("scan-result-title");
const scanResultDesc = document.getElementById("scan-result-desc");

const digitalFilePanel = document.getElementById("digital-file-panel");
const btnDecodeFile = document.getElementById("btn-decode-file");
const decoderProcessing = document.getElementById("decoder-processing");
const playerMfskPresetGroup = document.getElementById("player-mfsk-preset-group");
const playerMfskPreset = document.getElementById("player-mfsk-preset");

const timeCurrent = document.getElementById("time-current");
const timeTotal = document.getElementById("time-total");
const seekSlider = document.getElementById("seek-slider");
const btnPlayPause = document.getElementById("btn-play-pause");
const playIcon = document.getElementById("play-icon");
const pauseIcon = document.getElementById("pause-icon");
const volumeSlider = document.getElementById("volume-slider");
const btnMute = document.getElementById("btn-mute");

const canvas = document.getElementById("visualizer-canvas");
const canvasCtx = canvas ? canvas.getContext("2d") : null;
const visualizerPlaceholder = document.getElementById("visualizer-placeholder");
const visModeBtns = document.querySelectorAll(".vis-mode-btn");

// Encoder Elements
const dropCarrier = document.getElementById("drop-carrier");
const dropPayload = document.getElementById("drop-payload");
const inputCarrier = document.getElementById("input-carrier");
const inputPayload = document.getElementById("input-payload");
const carrierFileName = document.getElementById("carrier-file-name");
const payloadFileName = document.getElementById("payload-file-name");

const digitalPayloadSection = document.getElementById("digital-payload-section");
const btnPayloadFile = document.getElementById("btn-payload-file");
const btnPayloadText = document.getElementById("btn-payload-text");
const dropFilePayload = document.getElementById("drop-file-payload");
const inputFilePayload = document.getElementById("input-file-payload");
const filePayloadName = document.getElementById("file-payload-name");
const containerTextPayload = document.getElementById("container-text-payload");
const inputTextPayload = document.getElementById("input-text-payload");
const textByteCounter = document.getElementById("text-byte-counter");

const stegoStrength = document.getElementById("stego-strength");
const stegoStrengthVal = document.getElementById("stego-strength-val");
const treatmentLoop = document.getElementById("treatment-loop");
const encryptPayloadCheck = document.getElementById("encrypt-payload-check");
const encoderPasswordInputGroup = document.getElementById("encoder-password-input-group");
const encoderPassword = document.getElementById("encoder-password");
const btnEncode = document.getElementById("btn-encode");

const encoderProcessing = document.getElementById("encoder-processing");
const processingStatus = document.getElementById("processing-status");
const encoderResult = document.getElementById("encoder-result");
const btnDownloadResult = document.getElementById("btn-download-result");
const btnSendToPlayer = document.getElementById("btn-send-to-player");
const encoderStegoMethod = document.getElementById("encoder-stego-method");
const encoderMethodWarning = document.getElementById("encoder-method-warning");
const encoderMethodMfskInfo = document.getElementById("encoder-method-mfsk-info");
const encoderMfskPresetGroup = document.getElementById("encoder-mfsk-preset-group");
const encoderMfskPreset = document.getElementById("encoder-mfsk-preset");
const encoderPasswordContainer = document.getElementById("encoder-password-container");

// Modal Elements
const modalTextReader = document.getElementById("modal-text-reader");
const modalTextContent = document.getElementById("modal-text-content");
const btnCloseModal = document.getElementById("btn-close-modal");
const btnCopyModalText = document.getElementById("btn-copy-modal-text");
const btnDownloadModalFile = document.getElementById("btn-download-modal-file");

// --- NAVIGATION ---
tabPlayer.addEventListener("click", () => {
    tabPlayer.classList.add("active");
    tabEncoder.classList.remove("active");
    viewPlayer.classList.add("active");
    viewEncoder.classList.remove("active");
});

tabEncoder.addEventListener("click", () => {
    tabEncoder.classList.add("active");
    tabPlayer.classList.remove("active");
    viewEncoder.classList.add("active");
    viewPlayer.classList.remove("active");
});

// Visualizer Mode Selector
visModeBtns.forEach(btn => {
    btn.addEventListener("click", () => {
        visModeBtns.forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        currentVisMode = btn.getAttribute("data-vis");
    });
});

// --- AUDIO PLAYER METADATA RESOLUTION ---
btnLoadUrl.addEventListener("click", resolveAndLoad);
inputUrl.addEventListener("keydown", (e) => {
    if (e.key === "Enter") resolveAndLoad();
});

async function resolveAndLoad() {
    const url = inputUrl.value.trim();
    if (!url) return;

    btnLoadUrl.disabled = true;
    btnLoadUrl.innerText = "Loading...";

    try {
        const response = await fetch(`/api/resolve?url=${encodeURIComponent(url)}`);
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.detail || "Failed to resolve stream metadata");
        }

        const data = await response.json();
        
        // Update Track Details Card
        trackTitle.innerText = data.title;
        trackArtist.innerText = data.artist;
        trackThumbnail.src = data.thumbnail || DEFAULT_PLACEHOLDER;
        
        // Update Source Badge
        trackSourceBadge.innerText = data.source;
        trackSourceBadge.style.display = "inline-block";
        trackCard.classList.remove("hidden");

        // Set State
        currentUrl = data.stream_url;
        trackDuration = data.duration || 0;
        timeTotal.innerText = formatTime(trackDuration);
        timeCurrent.innerText = "00:00";
        seekSlider.value = 0;
        streamStartTime = 0;

        // Reset player UI
        isPlaying = false;
        btnPlayPause.disabled = false;
        playIcon.classList.remove("hidden");
        pauseIcon.classList.add("hidden");

        // Load stream source
        loadAudioStream(0);

    } catch (err) {
        alert(err.message);
        trackCard.classList.add("hidden");
        btnPlayPause.disabled = true;
    } finally {
        btnLoadUrl.disabled = false;
        btnLoadUrl.innerText = "Load";
    }
}

// --- LOCAL FILE UPLOAD FOR PLAYER ---
setupPlayerUploadBox();

function setupPlayerUploadBox() {
    dropPlayerFile.addEventListener("click", () => inputPlayerFile.click());
    
    inputPlayerFile.addEventListener("change", () => {
        if (inputPlayerFile.files.length > 0) {
            uploadPlayerFile(inputPlayerFile.files[0]);
        }
    });

    dropPlayerFile.addEventListener("dragover", (e) => {
        e.preventDefault();
        dropPlayerFile.classList.add("dragover");
    });

    dropPlayerFile.addEventListener("dragleave", () => {
        dropPlayerFile.classList.remove("dragover");
    });

    dropPlayerFile.addEventListener("drop", (e) => {
        e.preventDefault();
        dropPlayerFile.classList.remove("dragover");
        if (e.dataTransfer.files.length > 0) {
            inputPlayerFile.files = e.dataTransfer.files;
            uploadPlayerFile(inputPlayerFile.files[0]);
        }
    });
}

async function uploadPlayerFile(file) {
    playerFileName.innerText = `Uploading & processing ${file.name}...`;
    btnLoadUrl.disabled = true;
    
    const formData = new FormData();
    formData.append("file", file);
    
    try {
        const response = await fetch("/api/upload_temp", {
            method: "POST",
            body: formData
        });
        
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.detail || "Failed to process uploaded file");
        }
        
        const data = await response.json();
        
        // Load into Player
        trackTitle.innerText = data.title;
        trackArtist.innerText = data.artist;
        trackThumbnail.src = DEFAULT_PLACEHOLDER;
        trackSourceBadge.innerText = "Local Upload";
        trackSourceBadge.style.display = "inline-block";
        trackCard.classList.remove("hidden");
        
        // Set State
        currentUrl = data.stream_url;
        trackDuration = data.duration;
        timeTotal.innerText = formatTime(trackDuration);
        timeCurrent.innerText = "00:00";
        seekSlider.value = 0;
        streamStartTime = 0;
        
        // Reset player UI
        isPlaying = false;
        btnPlayPause.disabled = false;
        playIcon.classList.remove("hidden");
        pauseIcon.classList.add("hidden");
        
        loadAudioStream(0);
        playerFileName.innerText = `Loaded: ${file.name}`;
        
    } catch (err) {
        alert(err.message);
        playerFileName.innerText = "Drag & drop local audio file here to test";
    } finally {
        btnLoadUrl.disabled = false;
    }
}

// Format seconds into MM:SS
function formatTime(secs) {
    if (isNaN(secs) || secs === Infinity) return "00:00";
    const minutes = Math.floor(secs / 60);
    const seconds = Math.floor(secs % 60);
    return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
}

// Load audio stream from server proxy
function loadAudioStream(startTime) {
    if (!currentUrl) return;

    streamStartTime = startTime;
    let mode = currentMode;
    let password = null;

    if (mode === "hidden" && decryptPasswordCheck.checked) {
        password = playerPassword.value.trim();
    }

    let method = playerStegoMethod.value;
    let srcUrl = `/api/stream?url=${encodeURIComponent(currentUrl)}&mode=${mode}&method=${method}&ss=${startTime}`;
    if (password) {
        srcUrl += `&password=${encodeURIComponent(password)}`;
    }

    // Set element source
    audioPlayer.src = srcUrl;
    audioPlayer.load();

    if (isPlaying) {
        audioPlayer.play().catch(() => {
            isPlaying = false;
            playIcon.classList.remove("hidden");
            pauseIcon.classList.add("hidden");
        });
    }

    updatePlayerPanels();
    initAudioContext();
}

playerStegoMethod.addEventListener("change", () => {
    updatePlayerPanels();
    if (currentUrl) {
        const time = streamStartTime + audioPlayer.currentTime;
        loadAudioStream(time);
    }
});

// Initialize Web Audio API for visualizer
function initAudioContext() {
    if (audioContext) return;

    audioContext = new (window.AudioContext || window.webkitAudioContext)();
    analyser = audioContext.createAnalyser();
    analyser.fftSize = 512;

    const source = audioContext.createMediaElementSource(audioPlayer);
    source.connect(analyser);
    analyser.connect(audioContext.destination);

    if (visualizerPlaceholder) visualizerPlaceholder.classList.add("hidden");
    startVisualizer();
}

// Stream mode toggle switch
toggleButtons.forEach((btn, idx) => {
    btn.addEventListener("click", () => {
        toggleButtons.forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        
        toggleSlider.style.transform = `translateX(${idx * 100}%)`;

        const mode = btn.getAttribute("data-mode");
        currentMode = mode;

        if (currentUrl) {
            const time = streamStartTime + audioPlayer.currentTime;
            loadAudioStream(time);
        }
    });
});

decryptPasswordCheck.addEventListener("change", () => {
    if (decryptPasswordCheck.checked) {
        playerPasswordInputGroup.classList.remove("hidden");
    } else {
        playerPasswordInputGroup.classList.add("hidden");
    }
});

// Play / Pause Toggle
btnPlayPause.addEventListener("click", () => {
    if (audioContext && audioContext.state === "suspended") {
        audioContext.resume();
    }

    if (isPlaying) {
        audioPlayer.pause();
        isPlaying = false;
        playIcon.classList.remove("hidden");
        pauseIcon.classList.add("hidden");
    } else {
        audioPlayer.play().then(() => {
            isPlaying = true;
            playIcon.classList.add("hidden");
            pauseIcon.classList.remove("hidden");
        }).catch(err => {
            console.error("Playback error:", err);
        });
    }
});

// Seek Bar Update
audioPlayer.addEventListener("timeupdate", () => {
    if (!trackDuration) return;
    const absoluteTime = streamStartTime + audioPlayer.currentTime;
    timeCurrent.innerText = formatTime(absoluteTime);
    
    if (!document.activeElement || document.activeElement !== seekSlider) {
        seekSlider.value = (absoluteTime / trackDuration) * 100;
    }
});

seekSlider.addEventListener("change", () => {
    if (!currentUrl) return;
    let targetTime = (seekSlider.value / 100) * trackDuration;
    if (isNaN(targetTime) || !isFinite(targetTime) || targetTime < 0) {
        targetTime = 0;
    }
    loadAudioStream(targetTime);
});

function seekRelative(offsetSecs) {
    if (!currentUrl || !trackDuration) return;
    let currentAbsolute = streamStartTime + audioPlayer.currentTime;
    let target = Math.max(0, Math.min(trackDuration, currentAbsolute + offsetSecs));
    loadAudioStream(target);
}

// Keyboard Shortcuts
window.addEventListener("keydown", (e) => {
    if (["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement.tagName)) return;
    if (e.code === "Space") {
        e.preventDefault();
        btnPlayPause.click();
    } else if (e.code === "KeyM") {
        btnMute.click();
    } else if (e.code === "KeyS") {
        btnScanStego.click();
    } else if (e.code === "ArrowLeft") {
        seekRelative(-5);
    } else if (e.code === "ArrowRight") {
        seekRelative(5);
    }
});

volumeSlider.addEventListener("input", () => {
    audioPlayer.volume = volumeSlider.value;
});

btnMute.addEventListener("click", () => {
    audioPlayer.muted = !audioPlayer.muted;
    btnMute.style.opacity = audioPlayer.muted ? "0.5" : "1";
});

audioPlayer.addEventListener("ended", () => {
    isPlaying = false;
    playIcon.classList.remove("hidden");
    pauseIcon.classList.add("hidden");
    seekSlider.value = 0;
    streamStartTime = 0;
    timeCurrent.innerText = "00:00";
});

// --- CANVAS AUDIO VISUALIZER (3 MODES) ---
function startVisualizer() {
    if (visualizerAnimationId) {
        cancelAnimationFrame(visualizerAnimationId);
    }

    if (!canvas || !canvasCtx) return;

    canvas.width = canvas.offsetWidth;
    canvas.height = canvas.offsetHeight;

    const bufferLength = analyser.frequencyBinCount;
    const freqData = new Uint8Array(bufferLength);
    const timeData = new Uint8Array(bufferLength);

    function draw() {
        visualizerAnimationId = requestAnimationFrame(draw);

        canvasCtx.fillStyle = '#090d16';
        canvasCtx.fillRect(0, 0, canvas.width, canvas.height);

        if (currentVisMode === "spectrum") {
            analyser.getByteFrequencyData(freqData);
            const barWidth = (canvas.width / (bufferLength * 0.7)) * 1.8;
            let x = 0;

            for (let i = 0; i < bufferLength * 0.7; i++) {
                const barHeight = (freqData[i] / 255) * canvas.height * 0.85;

                const grad = canvasCtx.createLinearGradient(0, canvas.height, 0, 0);
                grad.addColorStop(0, '#00f2fe');
                grad.addColorStop(1, '#7f00ff');

                canvasCtx.fillStyle = grad;
                canvasCtx.fillRect(x, canvas.height - barHeight, barWidth - 1, barHeight);

                x += barWidth;
            }
        } else if (currentVisMode === "waveform") {
            analyser.getByteTimeDomainData(timeData);
            canvasCtx.lineWidth = 2.5;
            canvasCtx.strokeStyle = '#00f2fe';
            canvasCtx.shadowBlur = 8;
            canvasCtx.shadowColor = '#00f2fe';

            canvasCtx.beginPath();
            const sliceWidth = canvas.width / bufferLength;
            let x = 0;

            for (let i = 0; i < bufferLength; i++) {
                const v = timeData[i] / 128.0;
                const y = (v * canvas.height) / 2;

                if (i === 0) canvasCtx.moveTo(x, y);
                else canvasCtx.lineTo(x, y);

                x += sliceWidth;
            }

            canvasCtx.lineTo(canvas.width, canvas.height / 2);
            canvasCtx.stroke();
            canvasCtx.shadowBlur = 0;

        } else if (currentVisMode === "lissajous") {
            analyser.getByteTimeDomainData(timeData);
            canvasCtx.lineWidth = 2;
            canvasCtx.strokeStyle = '#00e676';
            canvasCtx.shadowBlur = 10;
            canvasCtx.shadowColor = '#00e676';

            const centerX = canvas.width / 2;
            const centerY = canvas.height / 2;
            const radius = Math.min(centerX, centerY) * 0.8;

            canvasCtx.beginPath();
            const quarter = Math.floor(bufferLength / 4);

            for (let i = 0; i < bufferLength; i++) {
                const xVal = (timeData[i] - 128) / 128.0;
                const yVal = (timeData[(i + quarter) % bufferLength] - 128) / 128.0;

                const px = centerX + xVal * radius;
                const py = centerY + yVal * radius;

                if (i === 0) canvasCtx.moveTo(px, py);
                else canvasCtx.lineTo(px, py);
            }
            canvasCtx.closePath();
            canvasCtx.stroke();
            canvasCtx.shadowBlur = 0;
        }
    }

    draw();
}

window.addEventListener("resize", () => {
    if (canvas) {
        canvas.width = canvas.offsetWidth;
        canvas.height = canvas.offsetHeight;
    }
});

// --- AUDIO STEGO ENCODER ---

stegoStrength.addEventListener("input", () => {
    stegoStrengthVal.innerText = `${stegoStrength.value}%`;
});

encryptPayloadCheck.addEventListener("change", () => {
    if (encryptPayloadCheck.checked) {
        encoderPasswordInputGroup.classList.remove("hidden");
    } else {
        encoderPasswordInputGroup.classList.add("hidden");
        encoderPassword.value = "";
    }
});

// Payload tab mode switcher (File vs Secret Text)
if (btnPayloadFile && btnPayloadText) {
    btnPayloadFile.addEventListener("click", () => {
        btnPayloadFile.classList.add("active");
        btnPayloadText.classList.remove("active");
        dropFilePayload.classList.remove("hidden");
        containerTextPayload.classList.add("hidden");
        payloadMode = "file";
        checkEncodeInputs();
    });

    btnPayloadText.addEventListener("click", () => {
        btnPayloadText.classList.add("active");
        btnPayloadFile.classList.remove("active");
        containerTextPayload.classList.remove("hidden");
        dropFilePayload.classList.add("hidden");
        payloadMode = "text";
        checkEncodeInputs();
    });
}

if (inputTextPayload) {
    inputTextPayload.addEventListener("input", () => {
        const text = inputTextPayload.value;
        const bytes = new TextEncoder().encode(text).length;
        textByteCounter.innerText = `${bytes} bytes`;
        checkEncodeInputs();
    });
}

setupUploadBox(dropCarrier, inputCarrier, carrierFileName);
setupUploadBox(dropPayload, inputPayload, payloadFileName);

function setupUploadBox(boxEl, inputEl, labelEl) {
    boxEl.addEventListener("click", () => inputEl.click());
    
    inputEl.addEventListener("change", () => {
        if (inputEl.files.length > 0) {
            labelEl.innerText = inputEl.files[0].name;
            checkEncodeInputs();
        }
    });

    boxEl.addEventListener("dragover", (e) => {
        e.preventDefault();
        boxEl.classList.add("dragover");
    });

    boxEl.addEventListener("dragleave", () => {
        boxEl.classList.remove("dragover");
    });

    boxEl.addEventListener("drop", (e) => {
        e.preventDefault();
        boxEl.classList.remove("dragover");
        if (e.dataTransfer.files.length > 0) {
            inputEl.files = e.dataTransfer.files;
            labelEl.innerText = inputEl.files[0].name;
            checkEncodeInputs();
        }
    });
}

function checkEncodeInputs() {
    const method = encoderStegoMethod.value;
    const hasCarrier = inputCarrier.files.length > 0;
    
    if (method === "lsb" || method === "mfsk") {
        let hasDigitalPayload = false;
        if (payloadMode === "file") {
            hasDigitalPayload = inputFilePayload.files.length > 0;
        } else {
            hasDigitalPayload = inputTextPayload.value.trim().length > 0;
        }
        btnEncode.disabled = !(hasCarrier && hasDigitalPayload);
    } else {
        const hasAudioPayload = inputPayload.files.length > 0;
        btnEncode.disabled = !(hasCarrier && hasAudioPayload);
    }
}

btnEncode.addEventListener("click", async () => {
    const method = encoderStegoMethod.value;
    const hasCarrier = inputCarrier.files.length > 0;
    
    if (!hasCarrier) return;

    btnEncode.disabled = true;
    encoderProcessing.classList.remove("hidden");
    encoderResult.classList.add("hidden");

    const formData = new FormData();
    formData.append("carrier", inputCarrier.files[0]);
    formData.append("method", method);
    
    const alphaVal = parseFloat(stegoStrength.value) / 100.0;
    formData.append("alpha", alphaVal);
    formData.append("loop", treatmentLoop.checked);

    if (method === "lsb" || method === "mfsk") {
        if (payloadMode === "file" && inputFilePayload.files.length > 0) {
            formData.append("file_payload", inputFilePayload.files[0]);
        } else if (payloadMode === "text" && inputTextPayload.value.trim()) {
            formData.append("text_payload", inputTextPayload.value.trim());
        }
        if (method === "mfsk") {
            formData.append("preset", encoderMfskPreset.value);
        }
    } else {
        formData.append("payload", inputPayload.files[0]);
    }

    if (encryptPayloadCheck.checked && encoderPassword.value.trim()) {
        formData.append("password", encoderPassword.value.trim());
    }

    try {
        const response = await fetch("/api/encode", {
            method: "POST",
            body: formData
        });

        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.detail || "Encoding failed on the server");
        }

        const data = await response.json();

        btnDownloadResult.href = data.download_url;
        btnSendToPlayer.setAttribute("data-stream-url", data.stream_url);
        btnSendToPlayer.setAttribute("data-duration", data.duration);

        encoderResult.classList.remove("hidden");
        processingStatus.innerText = "Encoding completed successfully!";
        
    } catch (err) {
        alert(err.message);
    } finally {
        encoderProcessing.classList.add("hidden");
        btnEncode.disabled = false;
    }
});

btnSendToPlayer.addEventListener("click", () => {
    const streamUrl = btnSendToPlayer.getAttribute("data-stream-url");
    if (!streamUrl) return;

    playerStegoMethod.value = encoderStegoMethod.value;
    if (encoderStegoMethod.value === "mfsk") {
        playerMfskPreset.value = encoderMfskPreset.value;
    }
    updatePlayerPanels();

    trackTitle.innerText = "Encoded Stego Track (Local)";
    trackArtist.innerText = "AlphaSteg Encoder Output";
    trackThumbnail.src = DEFAULT_PLACEHOLDER;
    trackSourceBadge.innerText = "Local Session";
    trackSourceBadge.style.display = "inline-block";
    trackCard.classList.remove("hidden");

    currentUrl = streamUrl;
    const durationAttr = btnSendToPlayer.getAttribute("data-duration");
    const parsedDuration = parseFloat(durationAttr);
    if (!isNaN(parsedDuration) && isFinite(parsedDuration) && parsedDuration > 0) {
        trackDuration = parsedDuration;
        timeTotal.innerText = formatTime(trackDuration);
    } else {
        trackDuration = 0;
        timeTotal.innerText = "00:00";
    }
    timeCurrent.innerText = "00:00";
    seekSlider.value = 0;
    streamStartTime = 0;

    isPlaying = false;
    btnPlayPause.disabled = false;
    playIcon.classList.remove("hidden");
    pauseIcon.classList.add("hidden");

    loadAudioStream(0);
    tabPlayer.click();
});

function updatePlayerPanels() {
    const method = playerStegoMethod.value;
    const streamToggleContainer = document.querySelector(".stream-toggle-container");
    const streamToggleHeading = document.querySelector(".mode-selector-card h3");
    
    if (method === "lsb" || method === "mfsk") {
        digitalFilePanel.classList.remove("hidden");
        if (streamToggleContainer) streamToggleContainer.classList.add("hidden");
        if (streamToggleHeading) streamToggleHeading.classList.add("hidden");
        
        if (currentMode !== "primary") {
            currentMode = "primary";
            const primaryBtn = document.querySelector('.toggle-btn[data-mode="primary"]');
            const hiddenBtn = document.querySelector('.toggle-btn[data-mode="hidden"]');
            if (primaryBtn) primaryBtn.classList.add("active");
            if (hiddenBtn) hiddenBtn.classList.remove("active");
            if (toggleSlider) toggleSlider.style.transform = `translateX(0%)`;
        }
    } else {
        digitalFilePanel.classList.add("hidden");
        if (streamToggleContainer) streamToggleContainer.classList.remove("hidden");
        if (streamToggleHeading) streamToggleHeading.classList.remove("hidden");
    }
    
    if (method === "mfsk") {
        playerMfskPresetGroup.classList.remove("hidden");
    } else {
        playerMfskPresetGroup.classList.add("hidden");
    }
}

// Auto-Detect Stego Codec Handler
btnScanStego.addEventListener("click", async () => {
    if (!currentUrl) {
        alert("Please load a track first before scanning.");
        return;
    }
    
    btnScanStego.disabled = true;
    const oldHtml = btnScanStego.innerHTML;
    btnScanStego.innerText = "Scanning...";
    
    try {
        const response = await fetch(`/api/scan_stego?url=${encodeURIComponent(currentUrl)}`);
        if (!response.ok) {
            throw new Error("Scanner failed to analyze the track.");
        }
        
        const data = await response.json();
        const detected = data.method;
        
        if (detected && detected !== "none") {
            playerStegoMethod.value = detected;
            if (detected === "mfsk" && data.preset) {
                playerMfskPreset.value = data.preset;
            }
            updatePlayerPanels();

            // Display Auto-Detect Diagnostic Badge
            scanResultBadge.classList.remove("hidden");
            scanResultIcon.innerText = detected === "lsb" ? "💾" : (detected === "mfsk" ? "📡" : "🎵");
            const conf = data.confidence || 95;
            scanResultTitle.innerText = `${data.description || detected.toUpperCase()} (${conf}% Confidence)`;
            scanResultDesc.innerText = `Auto-Selected Codec: ${playerStegoMethod.options[playerStegoMethod.selectedIndex].text}`;

            // Reload stream
            const time = streamStartTime + audioPlayer.currentTime;
            loadAudioStream(time);
        } else {
            scanResultBadge.classList.remove("hidden");
            scanResultIcon.innerText = "🔍";
            scanResultTitle.innerText = "No Steganography Detected";
            scanResultDesc.innerText = "No known steganography signature found in the first 3 seconds.";
        }
    } catch (err) {
        alert(err.message);
    } finally {
        btnScanStego.disabled = false;
        btnScanStego.innerHTML = oldHtml;
    }
});

// Extract & Reveal Digital File / Text Payload
btnDecodeFile.addEventListener("click", async () => {
    if (!currentUrl) return;
    
    btnDecodeFile.disabled = true;
    decoderProcessing.classList.remove("hidden");
    
    let method = playerStegoMethod.value;
    let password = "";
    if (decryptPasswordCheck.checked) {
        password = playerPassword.value.trim();
    }
    
    let decodeUrl = `/api/decode_file?url=${encodeURIComponent(currentUrl)}&method=${method}&as_json=true`;
    if (method === "mfsk") {
        decodeUrl += `&preset=${playerMfskPreset.value}`;
    }
    if (password) {
        decodeUrl += `&password=${encodeURIComponent(password)}`;
    }
    
    try {
        const response = await fetch(decodeUrl);
        if (!response.ok) {
            const err = await response.json();
            throw new Error(err.detail || "Failed to decode file payload");
        }
        
        const data = await response.json();
        
        if (data.is_text && data.text) {
            // Open Secret Text Reader Modal
            modalTextContent.value = data.text;
            btnDownloadModalFile.href = data.download_url;
            btnDownloadModalFile.download = data.filename || "secret_note.txt";
            modalTextReader.classList.remove("hidden");
        } else {
            // Binary payload download
            const fileResp = await fetch(data.download_url);
            const blob = await fileResp.blob();
            const downloadUrl = window.URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = downloadUrl;
            a.download = data.filename || "AlphaSteg_decoded_file.bin";
            document.body.appendChild(a);
            a.click();
            a.remove();
            window.URL.revokeObjectURL(downloadUrl);
        }
        
    } catch (err) {
        alert(err.message);
    } finally {
        btnDecodeFile.disabled = false;
        decoderProcessing.classList.add("hidden");
    }
});

// Modal Actions
if (btnCloseModal) {
    btnCloseModal.addEventListener("click", () => {
        modalTextReader.classList.add("hidden");
    });
}

if (btnCopyModalText) {
    btnCopyModalText.addEventListener("click", () => {
        modalTextContent.select();
        navigator.clipboard.writeText(modalTextContent.value);
        const originalText = btnCopyModalText.innerText;
        btnCopyModalText.innerText = "Copied!";
        setTimeout(() => {
            btnCopyModalText.innerText = originalText;
        }, 2000);
    });
}

encoderStegoMethod.addEventListener("change", () => {
    const method = encoderStegoMethod.value;
    if (method === "lsb" || method === "mfsk") {
        dropPayload.classList.add("hidden");
        digitalPayloadSection.classList.remove("hidden");
        encoderPasswordContainer.classList.remove("hidden");
    } else {
        dropPayload.classList.remove("hidden");
        digitalPayloadSection.classList.add("hidden");
        encoderPasswordContainer.classList.add("hidden");
        encryptPayloadCheck.checked = false;
        encoderPasswordInputGroup.classList.add("hidden");
        encoderPassword.value = "";
    }
    
    if (method === "lsb") {
        encoderMethodWarning.classList.remove("hidden");
        encoderMethodMfskInfo.classList.add("hidden");
        encoderMfskPresetGroup.classList.add("hidden");
    } else if (method === "mfsk") {
        encoderMethodWarning.classList.add("hidden");
        encoderMethodMfskInfo.classList.remove("hidden");
        encoderMfskPresetGroup.classList.remove("hidden");
    } else {
        encoderMethodWarning.classList.add("hidden");
        encoderMethodMfskInfo.classList.add("hidden");
        encoderMfskPresetGroup.classList.add("hidden");
    }
    checkEncodeInputs();
});

setupFilePayloadUploadBox();

function setupFilePayloadUploadBox() {
    dropFilePayload.addEventListener("click", () => inputFilePayload.click());
    inputFilePayload.addEventListener("change", () => {
        if (inputFilePayload.files.length > 0) {
            filePayloadName.innerText = inputFilePayload.files[0].name;
            checkEncodeInputs();
        }
    });
    
    dropFilePayload.addEventListener("dragover", (e) => {
        e.preventDefault();
        dropFilePayload.classList.add("dragover");
    });
    dropFilePayload.addEventListener("dragleave", () => {
        dropFilePayload.classList.remove("dragover");
    });
    dropFilePayload.addEventListener("drop", (e) => {
        e.preventDefault();
        dropFilePayload.classList.remove("dragover");
        if (e.dataTransfer.files.length > 0) {
            inputFilePayload.files = e.dataTransfer.files;
            filePayloadName.innerText = inputFilePayload.files[0].name;
            checkEncodeInputs();
        }
    });
}
