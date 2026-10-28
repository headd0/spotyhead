@echo off
setlocal enabledelayedexpansion

:: Set Android SDK path
set ANDROID_HOME=C:\Android\sdk
set ANDROID_SDK_ROOT=C:\Android\sdk

echo Building Myspoti Debug APK with offline download bugfixes...
echo.

:: Build the APK
call gradlew.bat app:assembleDebug -x test

echo.
echo Build completed. Checking for APK file...
echo.

:: Check if APK exists
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo ✓ APK found at: app\build\outputs\apk\debug\app-debug.apk
    echo.
    for /f "tokens=*" %%A in ('dir /b app\build\outputs\apk\debug\*.apk') do (
        set apk=%%A
        echo APK Name: !apk!
    )
    echo.
    echo You can now install this APK on your device using:
    echo   adb install app\build\outputs\apk\debug\app-debug.apk
) else (
    echo ✗ APK not found
    echo Check build output above for errors
)

endlocal
