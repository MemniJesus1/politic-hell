# ============================================================
#  POLITICHELL — создать SSH-ключ для облачной VM (Google Cloud)
#  Ключ БЕЗ пароля, лежит ТОЛЬКО на D:/диск пользователя (в .ssh)
# ============================================================
$work = 'D:\Programms\Minecraft SERVER ALL\_work'
$key  = "$work\politichell_gcp"

New-Item -ItemType Directory -Force -Path $work | Out-Null

if (-not (Test-Path "$key.pub")) {
  Write-Host 'Создаю новый ключ ed25519...'
  ssh-keygen -t ed25519 -f $key -N '""' -C 'politichell' | Out-Null
}

$homeSshKey = "$env:USERPROFILE\.ssh\politichell_gcp"
if (-not (Test-Path $homeSshKey)) {
  New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.ssh" | Out-Null
  Copy-Item "$key","$key.pub" "$env:USERPROFILE\.ssh\" -Force
  Write-Host "(копия ключа положена в $env:USERPROFILE\.ssh — scp-команды работают из коробки)"
}

Write-Host ""
Write-Host '================= ПУБЛИЧНЫЙ КЛЮЧ (скопируй ЦЕЛИКОМ) ================='
Get-Content "$key.pub"
Write-Host '====================================================================='
Write-Host ''
Write-Host 'Куда вставить:'
Write-Host '  console.cloud.google.com → Compute Engine → Метаданные (Metadata) →'
Write-Host '  вкладка "SSH-ключи" (SSH Keys) → Изменить → Добавить ключ → вставить строку.'
Write-Host ''
Write-Host 'После этого подключение:'
Write-Host "  ssh -i `"$key`" politichell@ВНЕШНИЙ_IP"
