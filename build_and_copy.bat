@echo off
echo ========================================
echo Building and Deploying EntityKiller
echo ========================================

echo.
echo Building project...
call gradlew build --no-daemon

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ERROR: Build failed! Check the errors above.
    pause
    exit /b 1
)

echo.
echo Build completed successfully!
echo.

set FORGE_JAR="build\libs\bleedout-1.0.0.jar"
set TARGET1="E:\Games inst\UltimMC\instances\BleedOutTestq\.minecraft\mods"
set TARGET2="E:\Games inst\UltimMC\instances\BleedOutTest2\.minecraft\mods"

echo Copying mod to target directories...

if exist %FORGE_JAR% (

    copy /Y %FORGE_JAR% %TARGET1%
    copy /Y %FORGE_JAR% %TARGET2%
    
    echo Mod copied successfully!
    echo.
) else (
    echo.
    echo ERROR: Mod JAR file not found at: %FORGE_JAR%
    echo Please check the build output and file name.
    echo.
    echo Available JAR files in build\libs\:
    dir "build\libs\*.jar" /b 2>nul
    echo.
)

echo.


pause