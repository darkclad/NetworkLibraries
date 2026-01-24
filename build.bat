@echo off
setlocal

:: Set JAVA_HOME to Android Studio's bundled JDK
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

:: Default to debug if no argument provided
set BUILD_TYPE=%1
if "%BUILD_TYPE%"=="" set BUILD_TYPE=debug

:: Validate build type
if /i "%BUILD_TYPE%"=="debug" goto :build
if /i "%BUILD_TYPE%"=="release" goto :build

echo Invalid build type: %BUILD_TYPE%
echo Usage: build.bat [debug^|release]
exit /b 1

:build
echo Building %BUILD_TYPE% APK...
echo.

:: Run gradle build
call gradlew.bat assemble%BUILD_TYPE%

if %ERRORLEVEL% neq 0 (
    echo.
    echo Build failed!
    exit /b %ERRORLEVEL%
)

echo.
echo Build successful!
echo APK location: app\build\outputs\apk\%BUILD_TYPE%\NetworkLibraries-%BUILD_TYPE%.apk
