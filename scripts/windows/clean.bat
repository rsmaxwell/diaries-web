@echo off
setlocal

rem Remove all Gradle-generated Diaries web output, including distributions.

set "SCRIPT_DIR=%~dp0"
set "EXIT_CODE=0"

pushd "%SCRIPT_DIR%..\..\.." >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not locate the Diaries project root. >&2
    echo Script directory: "%SCRIPT_DIR%" >&2
    endlocal & exit /b 1
)

set "PROJECT_DIR=%CD%"
set "WEB_DIR=%PROJECT_DIR%\diaries-web"
set "GRADLE_WRAPPER=%PROJECT_DIR%\gradlew.bat"

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

echo Cleaning Diaries web...

call "%GRADLE_WRAPPER%" :diaries-web:clean
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo ERROR: Diaries web clean failed with exit code %EXIT_CODE%. >&2
    goto :cleanup
)

echo Diaries web clean completed successfully.

:cleanup
popd
endlocal & exit /b %EXIT_CODE%
