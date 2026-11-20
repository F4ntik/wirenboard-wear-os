@echo off
setlocal
chcp 65001 >nul
set "apk=%~dp0app-debug.apk"
if not exist "%apk%" set "apk=%~dp0..\app\build\outputs\apk\debug\app-debug.apk"
if not exist "%apk%" (
    echo Не найден app-debug.apk. Положите его рядом с этим файлом.
    pause
    exit /b 1
)
if not exist "%LOCALAPPDATA%\WirenBoardWearSample\prepared.dpapi" (
    python -X utf8 "%~dp0install_wb_cloud.py" --prepare
    if errorlevel 1 (
        pause
        exit /b 1
    )
)
python -X utf8 "%~dp0install_wb_cloud.py" "%apk%" --install-prepared
set "result=%errorlevel%"
pause
exit /b %result%
