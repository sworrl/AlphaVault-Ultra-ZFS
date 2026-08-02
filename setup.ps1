# AlphaSteg 0.5 Installer Script
$PSScriptRoot = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition

Write-Host "===================================================" -ForegroundColor Cyan
Write-Host "            AlphaSteg 0.5 Installer" -ForegroundColor Cyan
Write-Host "===================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Check/Install Python
Write-Host "[1/4] Checking Python installation..." -ForegroundColor Yellow
$pythonInstalled = $false
try {
    $versionString = python --version 2>&1
    if ($versionString -match "Python 3\.") {
        $pythonInstalled = $true
        Write-Host "-> Found Python: $versionString" -ForegroundColor Green
    }
} catch {}

if (-not $pythonInstalled) {
    Write-Host "-> Python 3 was not detected. Downloading installer..." -ForegroundColor Yellow
    $pythonUrl = "https://www.python.org/ftp/python/3.11.8/python-3.11.8-amd64.exe"
    $installerPath = "$env:TEMP\python_installer.exe"
    
    Invoke-WebRequest -Uri $pythonUrl -OutFile $installerPath
    Write-Host "-> Launching Python installer. Please complete the installer window." -ForegroundColor Yellow
    Write-Host "-> IMPORTANT: Check the 'Add Python.exe to PATH' checkbox before installing!" -ForegroundColor Magenta
    
    Start-Process -FilePath $installerPath -Wait
    
    # Refresh Path env variable
    $env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")
    
    # Verify again
    try {
        $versionString = python --version 2>&1
        if ($versionString -match "Python 3\.") {
            Write-Host "-> Python installed successfully!" -ForegroundColor Green
        } else {
            Write-Host "-> Python was not detected in PATH. You may need to restart your terminal or install it manually." -ForegroundColor Red
            Exit
        }
    } catch {
        Write-Host "-> Python setup verified." -ForegroundColor Green
    }
}

# 2. Check/Install FFmpeg
Write-Host ""
Write-Host "[2/4] Checking FFmpeg installation..." -ForegroundColor Yellow
$ffmpegInstalled = $false
try {
    $ffmpegVer = ffmpeg -version 2>&1
    if ($ffmpegVer -match "ffmpeg version") {
        $ffmpegInstalled = $true
        Write-Host "-> Found system FFmpeg." -ForegroundColor Green
    }
} catch {}

if (Test-Path "$PSScriptRoot\ffmpeg.exe") {
    $ffmpegInstalled = $true
    Write-Host "-> Found local FFmpeg binaries in app folder." -ForegroundColor Green
}

if (-not $ffmpegInstalled) {
    Write-Host "-> FFmpeg was not detected. Downloading portable version (gyan.dev)..." -ForegroundColor Yellow
    $ffmpegUrl = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
    $ffmpegZip = "$env:TEMP\ffmpeg.zip"
    
    Invoke-WebRequest -Uri $ffmpegUrl -OutFile $ffmpegZip
    
    Write-Host "-> Extracting FFmpeg binaries..." -ForegroundColor Yellow
    if (Test-Path "$env:TEMP\ffmpeg_extracted") {
        Remove-Item -Path "$env:TEMP\ffmpeg_extracted" -Recurse -Force
    }
    Expand-Archive -Path $ffmpegZip -DestinationPath "$env:TEMP\ffmpeg_extracted" -Force
    
    $ffmpegExe = Get-ChildItem -Path "$env:TEMP\ffmpeg_extracted" -Filter "ffmpeg.exe" -Recurse | Select-Object -First 1
    $ffprobeExe = Get-ChildItem -Path "$env:TEMP\ffmpeg_extracted" -Filter "ffprobe.exe" -Recurse | Select-Object -First 1
    
    if ($ffmpegExe -and $ffprobeExe) {
        Copy-Item -Path $ffmpegExe.FullName -Destination "$PSScriptRoot\ffmpeg.exe" -Force
        Copy-Item -Path $ffprobeExe.FullName -Destination "$PSScriptRoot\ffprobe.exe" -Force
        Write-Host "-> Portable FFmpeg successfully installed inside the application folder." -ForegroundColor Green
    } else {
        Write-Host "-> Failed to find FFmpeg binaries in the downloaded archive." -ForegroundColor Red
    }
    
    # Clean up
    Remove-Item -Path "$env:TEMP\ffmpeg_extracted" -Recurse -ErrorAction SilentlyContinue
    Remove-Item -Path $ffmpegZip -ErrorAction SilentlyContinue
}

# 3. Create Python Virtual Environment
Write-Host ""
Write-Host "[3/4] Setting up Python virtual environment (.venv)..." -ForegroundColor Yellow
if (Test-Path "$PSScriptRoot\.venv") {
    Write-Host "-> Virtual environment already exists." -ForegroundColor Green
} else {
    python -m venv "$PSScriptRoot\.venv"
    if ($LASTEXITCODE -eq 0) {
        Write-Host "-> Virtual environment created successfully." -ForegroundColor Green
    } else {
        Write-Host "-> Failed to create virtual environment." -ForegroundColor Red
        Exit
    }
}

# 4. Install Dependencies
Write-Host ""
Write-Host "[4/4] Installing dependencies from requirements.txt..." -ForegroundColor Yellow
& "$PSScriptRoot\.venv\Scripts\python.exe" -m pip install --upgrade pip
& "$PSScriptRoot\.venv\Scripts\pip.exe" install -r "$PSScriptRoot\requirements.txt"

if ($LASTEXITCODE -eq 0) {
    Write-Host "-> Dependencies installed successfully!" -ForegroundColor Green
} else {
    Write-Host "-> Failed to install dependencies." -ForegroundColor Red
    Exit
}

# 5. Create double-clickable runner batch file
$runBatchContent = @"
@echo off
cd /d "%~dp0"
echo ===================================================
echo             AlphaSteg 0.5 Server
echo ===================================================
echo.
call .venv\Scripts\activate.bat
python main.py
pause
"@
$runBatchContent | Out-File -FilePath "$PSScriptRoot\run.bat" -Encoding ASCII

Write-Host ""
Write-Host "===================================================" -ForegroundColor Green
Write-Host "Installation Complete! Double-click 'run.bat' to start AlphaSteg." -ForegroundColor Green
Write-Host "===================================================" -ForegroundColor Green
