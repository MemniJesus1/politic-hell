# ============================================================
#  POLITICHELL / ACOREZERO — автопродление сервера
#
#  Цель: чтобы сервер AcoreZero («вечная фишка») не умирал по
#  таймеру Renew-every-16h. Скрипт двумя режимами:
#
#   Режим AUTO (если есть API): POST по URL с токеном.
#   Режим SEMI (fallback): открывает страницу сервера в браузере,
#   остаётся нажать ОДНУ кнопку Renew.
#
#  Локальный запуск: powershell -File ACORE_АВТОПРОДЛЕНИЕ.ps1
#  Планировщик Windows ставится один раз (см. ниже в комментах).
# ============================================================

$ErrorActionPreference = 'SilentlyContinue'

# --- конфиг (можно переопределять переменными окружения) ---
$TokenFile = "$env:USERPROFILE\.politichell\acore_token.txt"   # токен из dash.acorehosting.com
$RenewUrl  = $env:ACORE_RENEW_URL                              # URL конкретного endpoointа (даст их API-док)
$ServerId  = $env:ACORE_SERVER_ID                              # id сервера, например 0b1db158
$Dashboard = "https://dash.acorehosting.com/server/$ServerId"

function Log($msg) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $msg" | Out-File "$env:USERPROFILE\.politichell\autorenew.log" -Append; Write-Host $msg }

New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.politichell" | Out-Null

if ($RenewUrl -and (Test-Path $TokenFile)) {
    # ---------- РЕЖИМ AUTO ----------
    $token = (Get-Content $TokenFile -Raw).Trim()
    try {
        $r = Invoke-WebRequest -Uri $RenewUrl -Method POST -Headers @{
            Authorization = "Bearer $token"
            Accept        = 'application/json'
        } -TimeoutSec 30 -UseBasicParsing
        Log "AUTO renew: HTTP $($r.StatusCode) — продление отправлено ($RenewUrl)"
        exit 0
    } catch {
        Log "AUTO renew FAILED: $($_.Exception.Message) — откатываюсь в SEMI-режим"
    }
}

# ---------- РЕЖИМ SEMI (всегда доступен) ----------
Log "SEMI: открываю панель сервера — нажми кнопку Renew ($Dashboard)"
Start-Process $Dashboard
