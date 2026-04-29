@echo off
set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot
set M2_HOME=C:\Users\CAVAD\AppData\Local\Maven\apache-maven-3.9.14
set PATH=%JAVA_HOME%\bin;%M2_HOME%\bin;%PATH%

echo Iniciando SOS POS...
cd /d "%~dp0"
mvn javafx:run -q
pause
