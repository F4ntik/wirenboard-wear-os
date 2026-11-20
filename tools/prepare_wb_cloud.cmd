@echo off
setlocal
chcp 65001 >nul
python -X utf8 "%~dp0install_wb_cloud.py" --prepare %*
set "result=%errorlevel%"
pause
exit /b %result%
