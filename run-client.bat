@echo off
rem Starts the Minecraft dev client with MTGCraft loaded.
rem Uses the Java 17 that ships with CurseForge (your default Java is 22, which Forge 1.20.1 can't use).
set "JAVA_HOME=%USERPROFILE%\curseforge\minecraft\Install\runtime\java-runtime-gamma\windows-x64\java-runtime-gamma"
cd /d "%~dp0"
call gradlew.bat runClient
pause
