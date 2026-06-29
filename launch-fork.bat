@echo off
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d "%~dp0apps\multiplatform"
call gradlew.bat :desktop:run
