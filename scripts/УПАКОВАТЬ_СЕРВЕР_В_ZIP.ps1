# ============================================================
#  POLITICHELL — собрать pack.tar.gz для облака
#  (server.jar + плагины + конфиги + миры; логи/бэкапы/мусор — мимо)
#
#  Обычный запуск:            powershell -File УПАКОВАТЬ_СЕРВЕР_В_ZIP.ps1
#  Облако с ЧИСТЫМ миром:     powershell -File УПАКОВАТЬ_СЕРВЕР_В_ZIP.ps1 -БезМиров
# ============================================================
param([switch]$БезМиров)

$sv    = 'D:\Programms\Minecraft SERVER ALL\POLITIC HELL SERVER'
$work  = 'D:\Programms\Minecraft SERVER ALL\_work'
$stage = "$work\_pack_stage"
$dst   = "$work\pack.tar.gz"

if (-not (Test-Path $sv)) { Write-Host "[!] Папка сервера не найдена: $sv"; exit 1 }

if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

function rs($src, $dstn) {
  robocopy $src "$stage\$dstn" /E /NFL /NDL /NJH /NJS /XF *.log /XD _PHASE0_disabled backup backups | Out-Null
}
function cpf($list) {
  foreach ($f in $list) { if (Test-Path "$sv\$f") { Copy-Item "$sv\$f" $stage -Force } }
}

Write-Host '[1/4] Ядро и корневые конфиги'
cpf @('server.jar','server.properties','eula.txt','bukkit.yml','spigot.yml','permissions.yml','commands.yml','config/paper-global.yml')
if (Test-Path "$sv\config") { rs "$sv\config" 'config' }

Write-Host '[2/4] Плагины (без бэкапов и логов)'
rs "$sv\plugins" 'plugins'

if (-not $БезМиров) {
  Write-Host '[3/4] Миры (main world ~2.3 ГБ + world2 ~1.5 ГБ)'
  foreach ($w in @('world','world2','world_nether','world_the_end','world2_nether','world2_the_end','spawn')) {
    if (Test-Path "$sv\$w") { rs "$sv\$w" $w }
  }
} else {
  Write-Host '[3/4] Миры ПРОПУЩЕНЫ (-БезМиров) — облако сгенерирует чистый мир'
}

Write-Host '[4/4] Архивация tar.gz (это занимает пару минут)...'
if (Test-Path $dst) { Remove-Item $dst -Force }
& 'C:\Windows\System32\tar.exe' -C $stage -czf $dst .
Remove-Item $stage -Recurse -Force

$gb = [Math]::Round((Get-Item $dst).Length / 1GB, 2)
Write-Host ""
Write-Host "ГОТОВО: $dst ($gb ГБ)"
Write-Host "Залить на облако:"
Write-Host "  scp -i `"$env:USERPROFILE\.ssh\politichell_gcp`" `"$dst`" politichell@ВНЕШНИЙ_IP:/tmp/pack.tar.gz"
