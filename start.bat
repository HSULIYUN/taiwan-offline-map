@echo off
rem Windows: run the map service in this window (for testing). Close the window to stop.
rem To install it as an auto-start service, use install.bat instead.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bin\windows\start.ps1"
pause
