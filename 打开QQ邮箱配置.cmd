@echo off
setlocal
powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0scripts\configure_mail.ps1"
if errorlevel 1 (
  echo Configuration window failed to open. Please share the error above.
  pause
)
endlocal
