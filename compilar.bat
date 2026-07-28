@echo off
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
cd /d "%~dp0"

echo === Verificando dispositivo conectado ===
"%ANDROID_HOME%\platform-tools\adb.exe" devices
if "%ANDROID_HOME%"=="" (
    set ANDROID_HOME=C:\Android\android-sdk
    "%ANDROID_HOME%\platform-tools\adb.exe" devices
)

echo.
echo === Compilando e instalando en el celular ===
call gradlew.bat installDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ✅ Listo. La app se instaló en tu celular.
) else (
    echo.
    echo ❌ Algo falló. Revisa el mensaje de error arriba.
)
