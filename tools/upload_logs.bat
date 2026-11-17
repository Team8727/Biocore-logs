@echo off
REM Double-click launcher for Windows. Edit config\uploader.json once before use.
cd /d "%~dp0.."
py -3 tools\upload_logs.py %*
if errorlevel 1 (
  echo.
  echo Upload failed. Logs remain queued; run again later.
  pause
)
