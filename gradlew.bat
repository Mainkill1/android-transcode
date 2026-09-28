@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\gradle.ps1" %*
exit /b %ERRORLEVEL%
