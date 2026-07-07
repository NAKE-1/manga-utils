@echo off
REM ===========================================================================
REM  manga-utils backup inspector — GUI launcher (opens a window, no console)
REM  - Double-click to open the inspector, then use "Open backup..." inside it.
REM  - Or drag a .tachibk file onto this .bat to open it straight away.
REM ===========================================================================
start "" pythonw "%~dp0inspect_backup_gui.py" %*
