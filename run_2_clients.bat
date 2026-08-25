@echo off
title BleedOut - Run 2 Clients

echo ========================================================
echo        Starting 2 Minecraft Clients (BleedOut)
echo ========================================================
echo.
echo [1/2] Launching Client 1 (Dev1, directory: run)...
start "Client 1 - Dev1" cmd /k "gradlew.bat runClient"

echo.
echo Waiting 5 seconds before launching Client 2...
ping -n 6 127.0.0.1 >nul

echo.
echo [2/2] Launching Client 2 (Dev2, directory: run2)...
start "Client 2 - Dev2" cmd /k "gradlew.bat runClient2"

echo.
echo ========================================================
echo Both clients launched in separate windows!
echo Client 1 (Dev1) can Open to LAN.
echo Client 2 (Dev2) can join via Multiplayer (LAN/localhost).
echo ========================================================
echo.
pause
