@echo off
REM ASCII-only wrapper. Do not put Chinese here: cmd.exe breaks on UTF-8.
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0create-user.ps1" %*
exit /b %ERRORLEVEL%
