@echo off
setlocal EnableDelayedExpansion

REM ================================================================
REM  SOS POS — Build Installer
REM
REM  Genera SOS POS-1.0.0.exe con Java embebido.
REM  El usuario final NO necesita instalar Java ni nada mas.
REM
REM  Descarga automatica (solo la primera vez):
REM    - JavaFX SDK 21.0.2  (~70 MB)
REM    - WiX 3.11           (~4 MB, requerido por jpackage --type exe)
REM ================================================================

set "APP_DIR=%~dp0"
set "TARGET=%APP_DIR%target"
set "INSTALLER_OUT=%TARGET%\installer"
set "JAVAFX_DIR=C:\javafx-sdk-21.0.2"
set "WIX_DIR=C:\wix311"

set "JAVAFX_URL=https://download2.gluonhq.com/openjfx/21.0.2/openjfx-21.0.2_windows-x64_bin-sdk.zip"
set "WIX_URL=https://github.com/wixtoolset/wix3/releases/download/wix3112rtm/wix311-binaries.zip"

echo.
echo  ___  ___  ___    ___  ___  ___
echo  SOS  POS  ^^^  Build  Installer
echo.

REM --- [1] Java / jpackage -------------------------------------------------
echo [1/6] Verificando Java 21...
where jpackage >nul 2>&1
if errorlevel 1 (
    if defined JAVA_HOME (
        set "PATH=!JAVA_HOME!\bin;!PATH!"
    ) else if exist "C:\Program Files\Java\jdk-21\bin\jpackage.exe" (
        set "PATH=C:\Program Files\Java\jdk-21\bin;!PATH!"
    ) else (
        echo.
        echo  ERROR: jpackage no encontrado.
        echo  Instala JDK 21: https://adoptium.net
        pause & exit /b 1
    )
)
for /f "tokens=*" %%v in ('jpackage --version 2^>^&1') do set "JPACKAGE_VER=%%v"
echo  jpackage !JPACKAGE_VER! OK

REM --- [2] Maven -----------------------------------------------------------
echo [2/6] Verificando Maven...
where mvn >nul 2>&1
if errorlevel 1 (
    if exist "C:\tools\apache-maven-3.9.6\bin\mvn.cmd" (
        set "PATH=C:\tools\apache-maven-3.9.6\bin;!PATH!"
    ) else (
        echo  ERROR: mvn no encontrado en PATH.
        pause & exit /b 1
    )
)
echo  Maven OK

REM --- [3] JavaFX SDK ------------------------------------------------------
echo [3/6] Verificando JavaFX SDK...
if not exist "%JAVAFX_DIR%\lib\javafx.controls.jar" (
    echo  Descargando JavaFX SDK 21.0.2 ~70 MB...
    powershell -NoProfile -Command ^
        "Invoke-WebRequest -Uri '%JAVAFX_URL%' -OutFile '$env:TEMP\javafx-sdk.zip' -UseBasicParsing"
    if errorlevel 1 ( echo  ERROR al descargar JavaFX SDK & pause & exit /b 1 )
    echo  Extrayendo en C:\...
    powershell -NoProfile -Command ^
        "Expand-Archive -Path '$env:TEMP\javafx-sdk.zip' -DestinationPath 'C:\' -Force"
    del /q "%TEMP%\javafx-sdk.zip" 2>nul
)
if not exist "%JAVAFX_DIR%\lib" ( echo  ERROR: JavaFX SDK no disponible & pause & exit /b 1 )
echo  JavaFX SDK OK

REM --- [4] WiX (requerido por jpackage --type exe) -------------------------
echo [4/6] Verificando WiX...
set "WIX_FOUND=0"
where candle >nul 2>&1 && set "WIX_FOUND=1"
if "!WIX_FOUND!"=="0" if exist "%WIX_DIR%\candle.exe" (
    set "PATH=%WIX_DIR%;!PATH!"
    set "WIX_FOUND=1"
)
if "!WIX_FOUND!"=="0" (
    echo  Descargando WiX 3.11 ~4 MB...
    powershell -NoProfile -Command ^
        "Invoke-WebRequest -Uri '%WIX_URL%' -OutFile '$env:TEMP\wix311.zip' -UseBasicParsing"
    if errorlevel 1 ( echo  ERROR al descargar WiX & pause & exit /b 1 )
    powershell -NoProfile -Command ^
        "Expand-Archive -Path '$env:TEMP\wix311.zip' -DestinationPath '%WIX_DIR%' -Force"
    del /q "%TEMP%\wix311.zip" 2>nul
    set "PATH=%WIX_DIR%;!PATH!"
)
echo  WiX OK

REM --- [5] Icono -----------------------------------------------------------
echo [5/6] Generando icono...
powershell -NoProfile -ExecutionPolicy Bypass ^
    -File "%APP_DIR%package\windows\create-icon.ps1" 2>nul
if not exist "%APP_DIR%package\windows\icon.ico" (
    echo  AVISO: icono no generado, se usara el icono por defecto.
)

REM --- [6] Compilar + empaquetar ------------------------------------------
echo [6/6] Compilando y empaquetando...
call mvn clean package -q -f "%APP_DIR%pom.xml"
if errorlevel 1 ( echo  ERROR en compilacion Maven & pause & exit /b 1 )

mkdir "%INSTALLER_OUT%" 2>nul

set "ICON_OPT="
if exist "%APP_DIR%package\windows\icon.ico" (
    set "ICON_OPT=--icon "%APP_DIR%package\windows\icon.ico""
)

jpackage ^
  --type exe ^
  --name "SOS POS" ^
  --app-version "1.0.0" ^
  --vendor "PUDU Tecnologia" ^
  --description "Sistema Offline de Punto de Venta - PUDU" ^
  --input "%TARGET%\libs" ^
  --main-jar "sos-pos-1.0-SNAPSHOT.jar" ^
  --main-class com.sospos.Launcher ^
  --module-path "%JAVAFX_DIR%\lib" ^
  --add-modules javafx.controls,javafx.fxml,java.net.http,java.sql,java.desktop ^
  %ICON_OPT% ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut ^
  --dest "%INSTALLER_OUT%"

if errorlevel 1 ( echo  ERROR en jpackage & pause & exit /b 1 )

echo.
echo  ============================================================
echo   Instalador listo:
echo   %INSTALLER_OUT%\SOS POS-1.0.0.exe
echo.
echo   El instalador incluye Java - el cliente no necesita
echo   instalar nada adicional.
echo  ============================================================
echo.
pause
