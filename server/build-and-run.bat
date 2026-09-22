@echo off
setlocal
rem ===========================================================================
rem  DeskStream server - build and launch helper
rem
rem   Double-click, or from a terminal:
rem     build-and-run.bat                 build + start with default options
rem     build-and-run.bat --headless      pass any server flags straight through
rem     build-and-run.bat --web-lan --max-bitrate-kbps 25000
rem
rem   The build is incremental, so rerunning after a source change only takes
rem   a couple of seconds. Stops with Ctrl+C.
rem
rem   Encoder selection is automatic (NVENC first, Media Foundation fallback).
rem   To pin it, set DESKSTREAM_ENCODER=nvenc or DESKSTREAM_ENCODER=mf before
rem   running this script.
rem ===========================================================================
cd /d "%~dp0"
title DeskStream Server

echo ============================================================
echo  DeskStream server - build and run (Release)
if defined DESKSTREAM_ENCODER (
    echo  encoder: DESKSTREAM_ENCODER=%DESKSTREAM_ENCODER%
) else (
    echo  encoder: automatic - NVENC first, Media Foundation fallback
)
echo ============================================================
echo.

rem ---- Build -----------------------------------------------------------------
dotnet build -c Release --nologo -v minimal
set BUILD_RC=%ERRORLEVEL%
if not "%BUILD_RC%"=="0" (
    echo.
    echo [BUILD FAILED] Fix the errors above, then rerun this script.
    pause
    exit /b %BUILD_RC%
)

rem ---- Launch ----------------------------------------------------------------
echo.
echo [build OK] starting server...  ^(stop with Ctrl+C^)
if "%~1"=="" (
    dotnet run -c Release --no-build
) else (
    dotnet run -c Release --no-build -- %*
)
set RUN_RC=%ERRORLEVEL%

rem Keep the output readable when the server exits on its own (crash, port
rem already in use, ...). Ctrl+C also lands here so the reason stays visible.
if not "%RUN_RC%"=="0" (
    echo.
    echo [server exited with code %RUN_RC%]
)
pause
exit /b %RUN_RC%
