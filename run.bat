@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "JDK25=C:\Program Files\Java\jdk-25"
set "JDK21=C:\Program Files\Java\jdk-21"

REM ---------------------------------------------------------------------------
REM  Vista test client launcher.
REM  It temporarily switches Supplementaries to compileOnly (the runtime jar
REM  crashes with Sodium 0.8.13's mixin), launches :neoforge:runClient with the
REM  known-good Iris 1.8.14 + Sodium 0.8.13 combo in neoforge\run\mods, then
REM  restores neoforge\build.gradle.kts.
REM
REM  Usage:
REM    run.bat            launch the client
REM    run.bat --dry-run  only check paths and test patch/restore
REM ---------------------------------------------------------------------------

echo [run.bat] Project dir: %CD%
echo [run.bat] Checking prerequisites...

if not exist "%JDK25%\bin\java.exe" (
    echo [run.bat] ERROR: JDK 25 not found at %JDK25%
    exit /b 1
)
if not exist "%JDK21%\bin\java.exe" (
    echo [run.bat] ERROR: JDK 21 not found at %JDK21%
    exit /b 1
)
if not exist "gradlew.bat" (
    echo [run.bat] ERROR: gradlew.bat not found in %CD%
    exit /b 1
)

REM Restore first, in case a previous launch was interrupted and left the
REM build file patched.
echo [run.bat] Restoring build file if a previous run left it patched...
powershell -NoProfile -NonInteractive -EncodedCommand JABwAD0AJwBuAGUAbwBmAG8AcgBnAGUALwBiAHUAaQBsAGQALgBnAHIAYQBkAGwAZQAuAGsAdABzACcAOwAgACQAcwA9AEcAZQB0AC0AQwBvAG4AdABlAG4AdAAgAC0AUgBhAHcAIAAkAHAAOwAgACQAcwA9ACQAcwAuAFIAZQBwAGwAYQBjAGUAKAAnAG0AbwBkAEMAbwBtAHAAaQBsAGUATwBuAGwAeQAoACIAYwB1AHIAcwBlAC4AbQBhAHYAZQBuADoAcwB1AHAAcABsAGUAbQBlAG4AdABhAHIAaQBlAHMALQA0ADEAMgAwADgAMgA6ADgAMAA1ADEANgAyADgAIgApACcALAAnAG0AbwBkAEkAbQBwAGwAZQBtAGUAbgB0AGEAdABpAG8AbgAoACIAYwB1AHIAcwBlAC4AbQBhAHYAZQBuADoAcwB1AHAAcABsAGUAbQBlAG4AdABhAHIAaQBlAHMALQA0ADEAMgAwADgAMgA6ADgAMAA1ADEANgAyADgAIgApACcAKQA7ACAAUwBlAHQALQBDAG8AbgB0AGUAbgB0ACAALQBOAG8ATgBlAHcAbABpAG4AZQAgAC0AUABhAHQAaAAgACQAcAAgAC0AVgBhAGwAdQBlACAAJABzAA== >nul 2>nul
findstr /c:"supplementaries-412082" "neoforge\build.gradle.kts"

if not exist "neoforge\run\mods\iris-neoforge-1.8.14-beta.1+mc1.21.1.jar" echo [run.bat] WARNING: Iris 1.8.14 jar not found in neoforge\run\mods



dir /b "neoforge\run\mods\sodium-neoforge-0.8.13*.jar" >nul 2>&1

if errorlevel 1 echo [run.bat] WARNING: Sodium 0.8.13 jar not found in neoforge\run\mods

if not exist "neoforge\run\shaderpacks\*.zip" echo [run.bat] WARNING: no shaderpack zip found in neoforge\run\shaderpacks

findstr /r /c:"iris_off_hack[ ]*=[ ]*false" "neoforge\run\config\vista-client.toml" >nul 2>&1
if errorlevel 1 echo [run.bat] WARNING: iris_off_hack=false not found in neoforge\run\config\vista-client.toml

findstr /r /c:"enableShaders[ ]*=[ ]*true" "neoforge\run\config\iris.properties" >nul 2>&1
if errorlevel 1 echo [run.bat] WARNING: enableShaders=true not found in neoforge\run\config\iris.properties

echo [run.bat] Patching Supplementaries to compileOnly...
powershell -NoProfile -NonInteractive -EncodedCommand JABwAD0AJwBuAGUAbwBmAG8AcgBnAGUALwBiAHUAaQBsAGQALgBnAHIAYQBkAGwAZQAuAGsAdABzACcAOwAgACQAcwA9AEcAZQB0AC0AQwBvAG4AdABlAG4AdAAgAC0AUgBhAHcAIAAkAHAAOwAgACQAcwA9ACQAcwAuAFIAZQBwAGwAYQBjAGUAKAAnAG0AbwBkAEkAbQBwAGwAZQBtAGUAbgB0AGEAdABpAG8AbgAoACIAYwB1AHIAcwBlAC4AbQBhAHYAZQBuADoAcwB1AHAAcABsAGUAbQBlAG4AdABhAHIAaQBlAHMALQA0ADEAMgAwADgAMgA6ADgAMAA1ADEANgAyADgAIgApACcALAAnAG0AbwBkAEMAbwBtAHAAaQBsAGUATwBuAGwAeQAoACIAYwB1AHIAcwBlAC4AbQBhAHYAZQBuADoAcwB1AHAAcABsAGUAbQBlAG4AdABhAHIAaQBlAHMALQA0ADEAMgAwADgAMgA6ADgAMAA1ADEANgAyADgAIgApACcAKQA7ACAAUwBlAHQALQBDAG8AbgB0AGUAbgB0ACAALQBOAG8ATgBlAHcAbABpAG4AZQAgAC0AUABhAHQAaAAgACQAcAAgAC0AVgBhAGwAdQBlACAAJABzAA== >nul 2>nul
findstr /c:"supplementaries-412082" "neoforge\build.gradle.kts"

if /i "%~1"=="--dry-run" (
    echo [run.bat] Dry run complete. Restoring build file...
    powershell -NoProfile -NonInteractive -EncodedCommand JABwAD0AJwBuAGUAbwBmAG8AcgBnAGUALwBiAHUAaQBsAGQALgBnAHIAYQBkAGwAZQAuAGsAdABzACcAOwAgACQAcwA9AEcAZQB0AC0AQwBvAG4AdABlAG4AdAAgAC0AUgBhAHcAIAAkAHAAOwAgACQAcwA9ACQAcwAuAFIAZQBwAGwAYQBjAGUAKAAnAG0AbwBkAEMAbwBtAHAAaQBsAGUATwBuAGwAeQAoACIAYwB1AHIAcwBlAC4AbQBhAHYAZQBuADoAcwB1AHAAcABsAGUAbQBlAG4AdABhAHIAaQBlAHMALQA0ADEAMgAwADgAMgA6ADgAMAA1ADEANgAyADgAIgApACcALAAnAG0AbwBkAEkAbQBwAGwAZQBtAGUAbgB0AGEAdABpAG8AbgAoACIAYwB1AHIAcwBlAC4AbQBhAHYAZQBuADoAcwB1AHAAcABsAGUAbQBlAG4AdABhAHIAaQBlAHMALQA0ADEAMgAwADgAMgA6ADgAMAA1ADEANgAyADgAIgApACcAKQA7ACAAUwBlAHQALQBDAG8AbgB0AGUAbgB0ACAALQBOAG8ATgBlAHcAbABpAG4AZQAgAC0AUABhAHQAaAAgACQAcAAgAC0AVgBhAGwAdQBlACAAJABzAA== >nul 2>nul
    findstr /c:"supplementaries-412082" "neoforge\build.gradle.kts"
    exit /b 0
)

set "JAVA_HOME=%JDK25%"
echo [run.bat] JAVA_HOME=%JAVA_HOME%
echo [run.bat] Gradle log: %TEMP%\vista-neoforge-run.log
echo [run.bat] Starting :neoforge:runClient (offline, no-daemon)...
call gradlew.bat :neoforge:runClient --offline --no-daemon "-Porg.gradle.java.installations.paths=%JDK21%" > "%TEMP%\vista-neoforge-run.log" 2>&1
set "RUN_EXIT=%ERRORLEVEL%"

echo [run.bat] Client exited with code %RUN_EXIT%
echo [run.bat] Restoring neoforge\build.gradle.kts...
powershell -NoProfile -NonInteractive -EncodedCommand JABwAD0AJwBuAGUAbwBmAG8AcgBnAGUALwBiAHUAaQBsAGQALgBnAHIAYQBkAGwAZQAuAGsAdABzACcAOwAgACQAcwA9AEcAZQB0AC0AQwBvAG4AdABlAG4AdAAgAC0AUgBhAHcAIAAkAHAAOwAgACQAcwA9ACQAcwAuAFIAZQBwAGwAYQBjAGUAKAAnAG0AbwBkAEMAbwBtAHAAaQBsAGUATwBuAGwAeQAoACIAYwB1AHIAcwBlAC4AbQBhAHYAZQBuADoAcwB1AHAAcABsAGUAbQBlAG4AdABhAHIAaQBlAHMALQA0ADEAMgAwADgAMgA6ADgAMAA1ADEANgAyADgAIgApACcALAAnAG0AbwBkAEkAbQBwAGwAZQBtAGUAbgB0AGEAdABpAG8AbgAoACIAYwB1AHIAcwBlAC4AbQBhAHYAZQBuADoAcwB1AHAAcABsAGUAbQBlAG4AdABhAHIAaQBlAHMALQA0ADEAMgAwADgAMgA6ADgAMAA1ADEANgAyADgAIgApACcAKQA7ACAAUwBlAHQALQBDAG8AbgB0AGUAbgB0ACAALQBOAG8ATgBlAHcAbABpAG4AZQAgAC0AUABhAHQAaAAgACQAcAAgAC0AVgBhAGwAdQBlACAAJABzAA== >nul 2>nul
findstr /c:"supplementaries-412082" "neoforge\build.gradle.kts"
echo [run.bat] Done. Gradle log: %TEMP%\vista-neoforge-run.log
exit /b %RUN_EXIT%
