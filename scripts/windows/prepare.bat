@echo off
setlocal

rem Generate the Diaries web build-information resource.

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
set "BUILD_INFO_FILE=%WEB_DIR%\build\generated\resources\buildInfo\build-info.properties"
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

echo Generating Diaries web build information...

call "%GRADLE_WRAPPER%" :diaries-web:generateBuildInfo --rerun-tasks
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo ERROR: Could not generate Diaries web build information; Gradle exited with code %EXIT_CODE%. >&2
    goto :cleanup
)

if not exist "%BUILD_INFO_FILE%" (
    echo ERROR: Build information was not created: "%BUILD_INFO_FILE%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

echo Diaries web build information written to:
echo "%BUILD_INFO_FILE%"

:cleanup
popd
endlocal & exit /b %EXIT_CODE%
