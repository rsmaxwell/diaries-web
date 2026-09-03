@echo off
setlocal

rem Test and build Diaries web through the parent Gradle project.
rem
rem Cleaning is deliberately kept in clean.bat. Automatically cleaning here
rem can delete test resources and report files belonging to another local
rem Gradle invocation which is still using the same subproject build directory.

set "SCRIPT_DIR=%~dp0"
set "EXIT_CODE=0"

pushd "%SCRIPT_DIR%..\..\.." >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not locate the Diaries project root. >&2
    echo Script directory: "%SCRIPT_DIR%" >&2
    endlocal & exit /b 1
)

set "PROJECT_DIR=%CD%"
set "GRADLE_WRAPPER=%PROJECT_DIR%\gradlew.bat"

if not exist "%GRADLE_WRAPPER%" (
    echo ERROR: Gradle wrapper not found: "%GRADLE_WRAPPER%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

echo Building Diaries web...

call "%GRADLE_WRAPPER%" :diaries-web:build
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo ERROR: Diaries web build failed with exit code %EXIT_CODE%. >&2
    goto :cleanup
)

echo Diaries web build completed successfully.

:cleanup
popd
endlocal & exit /b %EXIT_CODE%
