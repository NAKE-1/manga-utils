@echo off
REM ===========================================================================
REM  manga-utils dev console.
REM    Double-click (or run `start.bat` with no args) for the interactive menu.
REM    Or use it directly:  start.bat build | test | run <args> | mu <args>
REM                         | clean | android-jar | desktop | web
REM ===========================================================================
setlocal enabledelayedexpansion
set "ROOT=%~dp0"
cd /d "%ROOT%"
set "GRADLE=%ROOT%gradlew.bat"
set "ANDROID_JAR=%ROOT%android-compat\lib\android.jar"

REM ANSI colour setup (Windows 10+ cmd supports virtual-terminal sequences).
for /f %%a in ('echo prompt $E ^| cmd') do set "ESC=%%a"
set "C=%ESC%[96m"
set "Y=%ESC%[93m"
set "G=%ESC%[92m"
set "D=%ESC%[90m"
set "R=%ESC%[0m"

REM Auto-generate the Android stub jar if it's missing (fresh checkout).
if not exist "%ANDROID_JAR%" (
    echo [start] android.jar not found - generating it first...
    pwsh -NoProfile -File "%ROOT%android-compat\generate-android-jar.ps1" || goto :fail
)

REM If args were passed, run non-interactively and exit (scripting-friendly).
if not "%~1"=="" goto :direct

REM ---------------------------------------------------------------------------
REM  Interactive menu
REM ---------------------------------------------------------------------------
:menu
cls
echo %C%===============================================================%R%
echo %C%                 manga-utils  -  dev console%R%
echo %C%===============================================================%R%
echo.
echo   Pick what to do, then press Enter.
echo.
echo     %Y%[1]%R%  Build everything
echo          %D%compile + assemble all modules.  Expect: BUILD SUCCESSFUL%R%
echo.
echo     %Y%[2]%R%  Build + show CLI help
echo          %D%builds, then runs `mu --help`.  Expect: command list%R%
echo.
echo     %Y%[3]%R%  Run tests
echo          %D%run all unit tests (safe).      Expect: BUILD SUCCESSFUL%R%
echo.
echo     %Y%[4]%R%  Run mu  (packaged launcher, fast)
echo          %D%you'll type the args, e.g.  version   or   ext list%R%
echo.
echo     %Y%[5]%R%  Run mu via Gradle  (slower, rebuilds first)
echo          %D%you'll type the args, e.g.  version%R%
echo.
echo     %Y%[6]%R%  Clean build outputs            %D%[asks for confirmation]%R%
echo     %Y%[7]%R%  Regenerate android.jar stub    %D%[asks for confirmation]%R%
echo.
echo     %Y%[8]%R%  Launch test GUI                %D%(basic Swing window with all features)%R%
echo     %Y%[9]%R%  Launch desktop app             %D%(modern Compose UI - the real app)%R%
echo     %Y%[w]%R%  Launch web server              %D%(phone/Tailscale UI - http://localhost:8080)%R%
echo.
echo     %Y%[0]%R%  Exit
echo.
set "choice="
set /p "choice=%G%Your choice: %R%"

if "%choice%"=="1" goto :m_build
if "%choice%"=="2" goto :m_help
if "%choice%"=="3" goto :m_test
if "%choice%"=="4" goto :m_mu
if "%choice%"=="5" goto :m_run
if "%choice%"=="6" goto :m_clean
if "%choice%"=="7" goto :m_androidjar
if "%choice%"=="8" goto :m_gui
if "%choice%"=="9" goto :m_desktop
if /I "%choice%"=="w" goto :m_web
if "%choice%"=="0" goto :end
echo.
echo   "%choice%" is not a valid option.
timeout /t 2 >nul
goto :menu

:m_build
echo.
echo --- Building all modules... (first run downloads dependencies) ---
call "%GRADLE%" build
goto :after

:m_help
echo.
echo --- Building, then `mu --help`... ---
call "%GRADLE%" build || goto :after
call "%GRADLE%" -q :cli:installDist || goto :after
echo.
call "%ROOT%cli\build\install\mu\bin\mu.bat" --help
goto :after

:m_test
echo.
echo --- Running unit tests... ---
call "%GRADLE%" test
goto :after

:m_mu
echo.
echo   %Y%[a]%R%  Raw CLI args    %D%(e.g.  search 123 "naruto"   or   download ... --missing)%R%
echo   %Y%[b]%R%  Friendly menu   %D%(guided: pick source/manga/chapters, no ids or urls)%R%
echo   %D%(gum-powered TUI will be a third option once features are done)%R%
set "mode="
set /p "mode=Choose a or b: "
call "%GRADLE%" -q :cli:installDist || goto :after
if /I "!mode!"=="b" (
    call "%ROOT%cli\build\install\mu\bin\mu.bat" menu
    goto :after
)
echo.
echo   Type the arguments for `mu`.  Examples:  version    ext list    search 123 "naruto"
set "args="
set /p "args=mu "
call "%ROOT%cli\build\install\mu\bin\mu.bat" !args!
goto :after

:m_run
echo.
echo   Type the arguments for `mu` (run via Gradle).  Example:  version
set "args="
set /p "args=mu "
echo.
call "%GRADLE%" -q :cli:run --args="!args!"
goto :after

:m_clean
echo.
echo   This deletes all build/ outputs (they get rebuilt next time).
set "ok="
set /p "ok=Are you sure? (Y/N): "
if /I not "!ok!"=="Y" ( echo   Cancelled. & goto :after )
call "%GRADLE%" clean
goto :after

:m_androidjar
echo.
echo   This re-downloads the Android SDK stub and rebuilds android.jar (~20 MB).
set "ok="
set /p "ok=Are you sure? (Y/N): "
if /I not "!ok!"=="Y" ( echo   Cancelled. & goto :after )
pwsh -NoProfile -File "%ROOT%android-compat\generate-android-jar.ps1"
goto :after

:m_gui
echo.
echo --- Building and launching the test GUI (close the window to return)... ---
call "%GRADLE%" -q :gui:installDist || goto :after
call "%ROOT%gui\build\install\mu-gui\bin\mu-gui.bat"
goto :after

:m_desktop
echo.
echo --- Building and launching the desktop app (close the window to return)... ---
call "%GRADLE%" :desktop:run
goto :after

:m_web
echo.
echo --- Building + launching the web server (press Ctrl+C to stop)... ---
echo     On this PC:        %C%http://localhost:8080%R%
echo     On your phone:     %C%http://^<this-PC-tailscale-ip^>:8080%R%   %D%(must be on the same tailnet)%R%
echo     %D%Tip: set MANGA_WEB_PORT to change the port.%R%
echo.
call "%GRADLE%" :server:run
goto :after

:after
echo.
echo ---------------------------------------------------------------
pause
goto :menu

REM ---------------------------------------------------------------------------
REM  Non-interactive dispatch (start.bat <command> [args])
REM ---------------------------------------------------------------------------
:direct
set "CMD=%1"
set "REST="
shift
:d_collect
if "%~1"=="" goto :d_dispatch
if defined REST (set "REST=!REST! %~1") else (set "REST=%~1")
shift
goto :d_collect
:d_dispatch
if /I "%CMD%"=="build"       ( call "%GRADLE%" build & goto :end )
if /I "%CMD%"=="test"        ( call "%GRADLE%" test & goto :end )
if /I "%CMD%"=="check"       ( call "%GRADLE%" build test & goto :end )
if /I "%CMD%"=="clean"       ( call "%GRADLE%" clean & goto :end )
if /I "%CMD%"=="android-jar" ( pwsh -NoProfile -File "%ROOT%android-compat\generate-android-jar.ps1" & goto :end )
if /I "%CMD%"=="gui" (
    call "%GRADLE%" -q :gui:installDist || goto :fail
    call "%ROOT%gui\build\install\mu-gui\bin\mu-gui.bat"
    goto :end
)
if /I "%CMD%"=="desktop" ( call "%GRADLE%" :desktop:run & goto :end )
if /I "%CMD%"=="web" ( call "%GRADLE%" :server:run & goto :end )
if /I "%CMD%"=="run"         ( call "%GRADLE%" -q :cli:run --args="!REST!" & goto :end )
if /I "%CMD%"=="mu" (
    call "%GRADLE%" -q :cli:installDist || goto :fail
    call "%ROOT%cli\build\install\mu\bin\mu.bat" !REST!
    goto :end
)
echo Unknown command: %CMD%
echo Run `start.bat` with no args for the interactive menu.
goto :end

:fail
echo [start] FAILED
endlocal
exit /b 1

:end
endlocal
