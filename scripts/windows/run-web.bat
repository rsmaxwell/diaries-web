@echo off
setlocal

rem Build and run Diaries web using the Gradle Application distribution.
rem
rem Usage:
rem     run-web.bat [configuration-file]
rem
rem The default configuration is %USERPROFILE%\.diaries\diaries-web.json.
rem MQTT credentials must already be present in DIARIES_WEB_MQTT_USERNAME and
rem DIARIES_WEB_MQTT_PASSWORD; this script never reads or stores them.

set "SCRIPT_DIR=%~dp0"
set "EXIT_CODE=0"
set "CONFIG_FILE=%~1"

if not "%~2"=="" (
    echo ERROR: Usage: run-web.bat [configuration-file] >&2
    endlocal & exit /b 2
)

if not defined CONFIG_FILE set "CONFIG_FILE=%USERPROFILE%\.diaries\diaries-web.json"

for %%I in ("%CONFIG_FILE%") do set "CONFIG_FILE=%%~fI"

pushd "%SCRIPT_DIR%..\..\.." >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not locate the Diaries project root. >&2
    echo Script directory: "%SCRIPT_DIR%" >&2
    endlocal & exit /b 1
)

set "PROJECT_DIR=%CD%"
set "WEB_DIR=%PROJECT_DIR%\diaries-web"
set "GRADLE_WRAPPER=%PROJECT_DIR%\gradlew.bat"
set "LAUNCHER=%WEB_DIR%\build\install\diaries-web\bin\diaries-web.bat"

if not exist "%WEB_DIR%" (
    echo ERROR: Diaries web directory not found: "%WEB_DIR%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

if not exist "%GRADLE_WRAPPER%" (
    echo ERROR: Gradle wrapper not found: "%GRADLE_WRAPPER%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

if not exist "%CONFIG_FILE%" (
    echo ERROR: Diaries web configuration file not found: "%CONFIG_FILE%" >&2
    echo Copy diaries-web\config\diaries-web.example.json to a developer-owned file and adjust it. >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

if not defined DIARIES_WEB_MQTT_USERNAME (
    echo ERROR: DIARIES_WEB_MQTT_USERNAME is not set. >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

if not defined DIARIES_WEB_MQTT_PASSWORD (
    echo ERROR: DIARIES_WEB_MQTT_PASSWORD is not set. >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

echo Preparing the Diaries web runtime distribution...

call "%GRADLE_WRAPPER%" :diaries-web:installDist
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo ERROR: Could not prepare the Diaries web runtime distribution. >&2
    goto :cleanup
)

if not exist "%LAUNCHER%" (
    echo ERROR: Diaries web launcher was not created: "%LAUNCHER%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

echo Starting Diaries web...
echo Configuration: "%CONFIG_FILE%"
echo.

call "%LAUNCHER%" --config "%CONFIG_FILE%"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo ERROR: Diaries web exited with code %EXIT_CODE%. >&2
)

:cleanup
popd
endlocal & exit /b %EXIT_CODE%
