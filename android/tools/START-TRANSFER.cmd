@echo off
chcp 65001 >nul
setlocal
set "PYTHONUTF8=1"
set "PATH=%~dp0platform-tools;%PATH%"
"%~dp0python\python.exe" -X utf8 "%~dp0tools\transfer_windows.py"
set "transfer_result=%ERRORLEVEL%"
pause
exit /b %transfer_result%
