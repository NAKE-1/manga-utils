@echo off
REM ===========================================================================
REM  manga-utils backup inspector launcher
REM  - Drag a .tachibk file onto this .bat, OR double-click and paste the path.
REM ===========================================================================
setlocal EnableDelayedExpansion

set "FILE=%~1"
if "%FILE%"=="" (
  echo.
  set /p "FILE=Drag a .tachibk backup here (or paste its path), then press Enter: "
)
REM strip any surrounding quotes the shell adds on drag-drop
set FILE=!FILE:"=!

if "!FILE!"=="" (
  echo No file given.
  echo.
  pause
  exit /b 1
)

echo.
python "%~dp0inspect_backup.py" "!FILE!" --full
echo.
pause
