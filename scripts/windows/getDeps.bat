@echo off
setlocal

rem Create the Diaries web runtime distribution and its dependency directory.

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
set "DISTRIBUTION_DIR=%WEB_DIR%\build\install\diaries-web"
set "RUNTIME_DIR=%DISTRIBUTION_DIR%\lib"
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

echo Creating the Diaries web runtime distribution...

call "%GRADLE_WRAPPER%" :diaries-web:installDist
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo ERROR: Could not create the Diaries web runtime distribution; Gradle exited with code %EXIT_CODE%. >&2
    goto :cleanup
)

if not exist "%RUNTIME_DIR%" (
    echo ERROR: Runtime dependency directory was not created: "%RUNTIME_DIR%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

echo Diaries web runtime distribution created successfully:
echo "%DISTRIBUTION_DIR%"

:cleanup
popd
endlocal & exit /b %EXIT_CODE%
