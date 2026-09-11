@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts/start_backup_server.ps1" -Restart -Background
pause
endlocal
