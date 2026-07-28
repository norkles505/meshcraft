@echo off
set ANDROID_HOME=C:\Android\android-sdk
echo === Iniciando el emulador MeshCraftPhone ===
echo (la primera vez puede tardar un par de minutos en arrancar)
start "" "%ANDROID_HOME%\emulator\emulator.exe" -avd MeshCraftPhone
