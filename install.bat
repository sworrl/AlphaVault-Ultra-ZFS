@echo off
title AlphaSteg 0.5 Installer
echo ===================================================
echo             AlphaSteg 0.5 Installer
echo ===================================================
echo.
echo Launching automated installer script...
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
echo.
echo Installer has finished execution.
pause
