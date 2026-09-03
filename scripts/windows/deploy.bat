@echo off
setlocal

rem Build the local deployable Diaries web container image.
rem
rem Usage:
rem     deploy.bat [image-reference]
rem
rem The default image reference is diaries-web:local. This development script
rem never pushes an image; registry publication belongs to the Jenkins pipeline.

set "SCRIPT_DIR=%~dp0"
set "EXIT_CODE=0"
set "IMAGE_REFERENCE=%~1"

if not "%~2"=="" (
    echo ERROR: Usage: deploy.bat [image-reference] >&2
    endlocal & exit /b 2
)

if not defined IMAGE_REFERENCE set "IMAGE_REFERENCE=diaries-web:local"

pushd "%SCRIPT_DIR%..\..\.." >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not locate the Diaries project root. >&2
    echo Script directory: "%SCRIPT_DIR%" >&2
    endlocal & exit /b 1
)

set "PROJECT_DIR=%CD%"
set "DOCKERFILE=%PROJECT_DIR%\diaries-web\Dockerfile"

if not exist "%DOCKERFILE%" (
    echo ERROR: Diaries web Dockerfile not found: "%DOCKERFILE%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

where docker >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not available on PATH. >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

echo Building Diaries web image "%IMAGE_REFERENCE%"...

docker build --file "%DOCKERFILE%" --tag "%IMAGE_REFERENCE%" "%PROJECT_DIR%"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo ERROR: Diaries web image build failed with exit code %EXIT_CODE%. >&2
    goto :cleanup
)

echo Diaries web image built successfully: %IMAGE_REFERENCE%
echo The image was not pushed.

:cleanup
popd
endlocal & exit /b %EXIT_CODE%
