param(
    [string]$TokenFile = '',
    [string]$ServerId  = '0b1db158-1c16-4d50-a11d-4942e1717317',
    [string]$LogFile   = ''
)

$ErrorActionPreference = 'Stop'
# ============================================================
#  POLITICHELL / ACOREZERO - auto-renew + auto-unsuspend (restore)
# ============================================================
#  Endpoints (discovered 2026-10-17, AcoreZero extension dev.sqidgeon.serverplans):
#    RENEW:   POST /api/client/extensions/dev.sqidgeon.serverplans/renew
#             body {"server":"<uuid>"} -> resets 16h timer
#             allowed ONLY when < 8h remain (else 422 "less than 8 hours remain")
#    RESTORE: POST /api/client/extensions/dev.sqidgeon.serverplans/restore
#             body {"server":"<uuid>"} -> unsuspend (async, "restoring":true)
#             may 422 "Original node is unavailable or full" -> retry later
#
#  Exit codes: 0 = renewed / not due yet / restored+renewed  |  1 = hard failure
#  Usage:
#    powershell -NoProfile -ExecutionPolicy Bypass -File "ACORE_AUTORENEW.ps1" ^
#      -TokenFile "D:\...\acore_api_token.txt" [-LogFile "D:\...\autorenew.log"]
# ============================================================

$Base  = 'https://dash.acorehosting.com'
$RenewPath   = '/api/client/extensions/dev.sqidgeon.serverplans/renew'
$RestorePath = '/api/client/extensions/dev.sqidgeon.serverplans/restore'
$MinePath    = '/api/client/extensions/dev.sqidgeon.serverplans/mine'
$Curl = 'C:\Windows\System32\curl.exe'
if (-not (Test-Path -LiteralPath $Curl)) { $Curl = 'curl.exe' }

$token = $env:ACORE_TOKEN
if (-not $token -and $TokenFile -and (Test-Path -LiteralPath $TokenFile)) {
    $token = (Get-Content -LiteralPath $TokenFile -Raw).Trim()
}
if (-not $token) { Write-Host 'NO TOKEN: set ACORE_TOKEN env or pass -TokenFile'; exit 1 }
if ($env:ACORE_SERVER_ID) { $ServerId = $env:ACORE_SERVER_ID }
if (-not $LogFile) { $LogFile = Join-Path $PSScriptRoot 'autorenew.log' }
$body = '{"server":"' + $ServerId + '"}'

# quote-proof body: pass via file (PowerShell strips inner quotes otherwise)
$bodyFile = Join-Path $env:TEMP ('acore_body_' + [guid]::NewGuid().ToString('N') + '.json')
[System.IO.File]::WriteAllText($bodyFile, $body)
$MineUri = $Base + $MinePath + '?server=' + $ServerId

function Log($m) {
    $line = ('{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m)
    try { $line | Out-File -LiteralPath $LogFile -Append -Encoding utf8 } catch {}
    Write-Host $line
}

# returns hashtable @{ code = int; body = string }
function PostJson($path) {
    $out = ( & $Curl -sS -o - -w "|%{http_code}" -X POST ($Base + $path) `
        -H "Authorization: Bearer $token" `
        -H "Accept: application/json" `
        -H "Content-Type: application/json" `
        --data "@$bodyFile" 2>&1 ) -join ''
    $m = [regex]::Match($out, '\|(\d+)\s*$')
    $code = 0; if ($m.Success) { $code = [int]($m.Groups[1].Value.Trim()) }
    $txt = $out
    if ($m.Success) { $txt = $out.Substring(0, $out.Length - $m.Length) }
    return @{ code = $code; body = $txt }
}

function GetMineState() {
    try {
        $out = ( & $Curl -sS -o - $MineUri `
            -H "Authorization: Bearer $token" `
            -H "Accept: application/json" 2>&1 ) -join ''
        if ($out -match '"state":"([a-z_]+)"') { return $Matches[1] }
        return 'unknown'
    } catch { return 'unknown' }
}

Log ('--- run start; server ' + $ServerId + ' ---')
$state = GetMineState
Log ('mine state: ' + $state)

if ($state -eq 'suspended') {
    Log 'SUSPENDED -> RESTORE loop (unsuspend)'
    $ok = $false
    for ($i = 1; $i -le 10; $i++) {
        $res = PostJson $RestorePath
        Log ('restore try ' + $i + ': HTTP ' + $res.code + ' ' + $res.body)
        if ($res.code -ge 200 -and $res.code -lt 300) { $ok = $true; break }
        if ($res.body -notmatch 'unavailable or full') { break }
        Start-Sleep -Seconds 60
    }
    if (-not $ok) { Log 'RESTORE FAILED (node full or route error)'; exit 1 }
    Log 'RESTORE accepted; waiting 90s for node transfer...'
    Start-Sleep -Seconds 90
    Log ('mine state after restore: ' + (GetMineState))
}

$renewed = $false
$noop = $false
for ($i = 1; $i -le 3; $i++) {
    $res = PostJson $RenewPath
    Log ('renew try ' + $i + ': HTTP ' + $res.code + ' ' + $res.body)
    if ($res.code -ge 200 -and $res.code -lt 300) { $renewed = $true; break }
    if ($res.body -match 'less than 8 hours remain') { $noop = $true; break }
    if ($res.body -match 'Unsuspend|suspension') {
        $rz = PostJson $RestorePath
        Log ('restore extra: HTTP ' + $rz.code + ' ' + $rz.body)
        if ($rz.code -ge 200 -and $rz.code -lt 300) { Start-Sleep -Seconds 90; continue }
        exit 1
    }
    Start-Sleep -Seconds 20
}

if ($renewed) { Log 'RESULT: RENEWED (16h timer reset)'; exit 0 }
if ($noop)    { Log 'RESULT: NOT DUE YET (>8h remain) - ok'; exit 0 }
Log 'RESULT: RENEW FAILED'
exit 1
