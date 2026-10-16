# PATCH_PHASE0_HUD.ps1 — Фаза 0 «Демонтаж» для PoliticHellHUD (PowerShell-версия, без зависимостей)
#
# Что делает:
#   Ищет в *.java методы старых сундучных меню (installMenus / initMenus /
#   setupMenus / registerMenus) и ставит сразу после "{" ранний return,
#   чтобы старые DeluxeMenus-сундуки перестали регистрироваться при старте HUD.
#   Создает резервные копии *.bak_phase0. Повторный запуск безопасен (идемпотентен).
#
# Использование (в PowerShell):
#   powershell -ExecutionPolicy Bypass -File PATCH_PHASE0_HUD.ps1 -Root "F:\All Minecraft\PoliticHell"
#   powershell -ExecutionPolicy Bypass -File PATCH_PHASE0_HUD.ps1          # скан текущей папки

param(
    [string]$Root = "."
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path $Root).Path
$marker = 'PHASE0'
$guardTitle = 'PHASE0 : legacy chest menus disabled (Style1ScreenHub engine).'
$pattern = '(?m)^([ \t]*)(public|private|protected)[ \w\[\]<>,]*?\bvoid\s+(installMenus|initMenus|setupMenus|registerMenus)\s*\(\s*\)\s*\{'

function Patch-File([string]$path) {
    $text = [System.IO.File]::ReadAllText($path)
    if ($text.Contains($guardTitle)) {
        Write-Host "[SKIP] уже пропатчен: $path" -ForegroundColor DarkGray
        return 'skipped'
    }
    $rx = [System.Text.RegularExpressions.Regex]::new($pattern)
    $matchesList = $rx.Matches($text)
    if ($matchesList.Count -eq 0) { return $false }

    for ($i = $matchesList.Count - 1; $i -ge 0; $i--) {
        $m = $matchesList[$i]
        $indent = $m.Groups[1].Value
        $guard  = "`n$indent    // $guardTitle"
        $guard += "`n$indent    if (true) return; // installMenus -> null"
        $head   = $text.Substring($m.Index, $m.Length)   # заканчивается на "{"
        $text   = $text.Remove($m.Index, $m.Length).Insert($m.Index, ($head + $guard))
    }

    $backup = "$path.bak_phase0"
    if (-not (Test-Path $backup)) {
        [System.IO.File]::Copy($path, $backup, $false)
    }
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($path, $text, $utf8)

    $names = ($matchesList | ForEach-Object { $_.Groups[3].Value }) -join ', '
    Write-Host "[OK] $path : отключены -> $names" -ForegroundColor Green
    return $true
}

$javaFiles = Get-ChildItem -LiteralPath $Root -Recurse -Filter *.java -File -ErrorAction SilentlyContinue
if (-not $javaFiles) {
    Write-Host "[INFO] В '$Root' нет .java файлов. Укажи папку исходников HUD."
    exit 0
}

$touched = 0
$skipped = 0
foreach ($f in $javaFiles) {
    $result = Patch-File $f.FullName
    if ($result -eq $true)  { $touched++ }
    elseif ($result -eq 'skipped') { $skipped++ }
}

if ($touched -eq 0 -and $skipped -gt 0) {
    Write-Host "[INFO] Все файлы уже пропатчены ($skipped шт.). Ничего не изменилось." -ForegroundColor Yellow
}
elseif ($touched -eq 0) {
    Write-Host "[INFO] Методы installMenus/initMenus/setupMenus/registerMenus не найдены — возможно, сундуки уже сняты." -ForegroundColor Yellow
}
Write-Host "[DONE] Файлов пропатчено: $touched. Теперь пересобери свой HUD (mvn package / СОБРАТЬ.bat)." -ForegroundColor Cyan
