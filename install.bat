@echo off
rem Windows: double-click to install the offline map service (auto start on boot).
rem All messages are shown by bin\windows\install.ps1.
cd /d "%~dp0"
net session >nul 2>&1
if %errorlevel% neq 0 (
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bin\windows\install.ps1"
pause
