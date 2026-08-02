@echo off
cd /d "%~dp0"
echo ===================================================
echo             AlphaSteg 0.5 Server
echo ===================================================
echo.
call .venv\Scripts\activate.bat
python main.py
pause
