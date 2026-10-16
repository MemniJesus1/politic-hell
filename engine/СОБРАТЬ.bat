@echo off
chcp 65001 >nul
setlocal

REM ─── PoliticHell Engine 1.16.0 ─ build script ───
set JAVA_HOME=D:\Programms\Minecraft SERVER ALL\JAVA 21
set PATH=%JAVA_HOME%\bin;%PATH%

set PROJECT=%~dp0
set MVN=%PROJECT%tools\apache-maven-3.9.11\bin\mvn.cmd
set SETTINGS=%PROJECT%tools\settings-d-drive.xml

if not exist "%MVN%" (
    echo [FAIL] Maven не найден: %MVN%
    echo Сначала запусти: INSTALL_MAVEN.bat  ^(он скачает Maven в tools\^)
    pause
    exit /b 1
)

cd /d "%PROJECT%"
call "%MVN%" -q -B -DskipTests "-s" "%SETTINGS%" package

if exist "%PROJECT%target\PoliticHellEngine-1.16.0.jar" (
    echo.
    echo [OK] Сборка готова: target\PoliticHellEngine-1.16.0.jar
    if not exist "D:\Programms\Minecraft SERVER ALL\_GoogleDrive" mkdir "D:\Programms\Minecraft SERVER ALL\_GoogleDrive"
    copy /Y "%PROJECT%target\PoliticHellEngine-1.16.0.jar" "D:\Programms\Minecraft SERVER ALL\_GoogleDrive\PoliticHellEngine-1.16.0.jar" >nul
    echo [OK] Копия также упала в _GoogleDrive\ ^(синхронизируется после настройки Drive для ПК^)
) else (
    echo [FAIL] Сборка не удалась. Смотри вывод Maven выше.
)
pause
