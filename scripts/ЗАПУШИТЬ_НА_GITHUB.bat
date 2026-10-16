@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem === PoliticHell: первая отправка проекта на GitHub ===
set "GIT=D:\Programms\Minecraft SERVER ALL\_work\PortableGit\cmd"
if not exist "!GIT!\git.exe" (
    echo [!] PortableGit не найден: !GIT!
    echo     Сначала запусти установку: см. README.md
    pause
    exit /b 1
)
set "PATH=!GIT!;%PATH%"

cd /d "%~dp0.."

echo === PoliticHell - отправка на GitHub ===
echo.

git remote get-url origin >nul 2>&1
if errorlevel 1 (
    set /p URL="Вставь HTTPS-ссылку на созданного тобой repo и нажми Enter: "
    if not defined URL (
        echo [!] Ссылка не введена.
        pause
        exit /b 1
    )
    git remote add origin "!URL!"
)

echo Отправляю main...
git push -u origin main
if errorlevel 1 (
    echo.
    echo [!] Не отправилось. Чаще всего:
    echo     1) repo ещё не создан - открой https://github.com/new и создай ПУСТОЙ repository
    echo     2) ссылка неверна - пример правильной: https://github.com/имя/politic-hell.git
    echo     3) если открылось окно браузера - войди в GitHub и разреши доступ, потом снова запусти этот bat
) else (
    echo.
    echo ГОТОВО! Проект на GitHub.
    echo См. вкладку Actions - сборка jar-ов началась автоматически.
)
echo.
pause
