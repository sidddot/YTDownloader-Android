@echo off
:: Ensure you have Java and Android SDK installed and added to PATH
:: Step 1: Build the debug APK
call gradlew.bat assembleDebug

:: Step 2: Verify device is connected
adb devices
if errorlevel 1 (
    echo No device detected. Connect your Android device via USB and enable USB debugging.
    pause
    exit /b 1
)

:: Step 3: Install the APK on the device
adb install -r app\build\outputs\apk\debug\app-debug.apk

:: Step 4: Launch the app
adb shell am start -n com.example.localdl/.MainActivity

:: Step 5: Open logcat for live logs (optional)
adb logcat -s LocalDL:V *:S
