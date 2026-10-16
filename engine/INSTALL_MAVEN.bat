@echo off
chcp 65001 >nul
setlocal
REM Скачивает Apache Maven в tools\apache-maven-3.9.11
set PROJECT=%~dp0
if not exist "%PROJECT%tools" mkdir "%PROJECT%tools"
set DEST=%PROJECT%tools\maven.zip

powershell -NoProfile -Command ^
  "$urls = @('https://dlcdn.apache.org/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.zip','https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.zip','https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip');" ^
  "foreach ($u in $urls) { try { Write-Host 'DL' $u; [Net.ServicePointManager]::SecurityProtocol='Tls,Tls11,Tls12'; Invoke-WebRequest -Uri $u -OutFile '%DEST%' -UseBasicParsing -TimeoutSec 300; if ((Get-Item '%DEST%').Length -gt 1000000) { Write-Host 'OK'; break } } catch { Write-Host 'FAIL' $u } }"

if not exist "%DEST%" ( echo [FAIL] скачать не удалось; проверь интернет & pause & exit /b 1 )

powershell -NoProfile -Command "Expand-Archive -Path '%DEST%' -DestinationPath '%PROJECT%tools' -Force"
del "%DEST%"
echo [DONE] Maven установлен в %PROJECT%tools\apache-maven-3.9.11
pause
