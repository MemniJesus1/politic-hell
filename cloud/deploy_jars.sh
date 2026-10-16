#!/usr/bin/env bash
# ============================================================
#  POLITICHELL — обновление jar-ов на облачной VM с GitHub Releases
#
#  sudo bash deploy_jars.sh ТВОЙ_НИК/politic-hell
#
#  Приватный репозиторий? Создай fine-grained PAT (право "Contents: Read")
#  на github.com → Settings → Developer settings и положи его:
#     echo "ghp_..." > /opt/politichell/.gh_token && chmod 600 /opt/politichell/.gh_token
# ============================================================
set -euo pipefail

REPO="${1:-}"
MC_DIR=/opt/politichell
TOKEN_FILE="$MC_DIR/.gh_token"

if [ -z "$REPO" ]; then echo "Использование: sudo bash deploy_jars.sh ВЛАДЕЛЕЦ/РЕПОЗИТОРИЙ"; exit 1; fi

AUTH=()
if [ -f "$TOKEN_FILE" ]; then
  AUTH=(-H "Authorization: Bearer $(cat "$TOKEN_FILE")")
  echo "использую токен из $TOKEN_FILE (приватный репозиторий)"
fi

echo "Смотрю последний Release в $REPO ..."
JSON=$(curl -sL "${AUTH[@]}" "https://api.github.com/repos/$REPO/releases/latest")

if echo "$JSON" | jq -e '.message' >/dev/null 2>&1; then
  echo "ОШИБКА GitHub: $(echo "$JSON" | jq -r '.message')"
  echo "(нет Release? создай тег v... и запушь его:  git tag v1.17.0 && git push origin v1.17.0)"
  exit 1
fi

echo "Останавливаю сервер..."
systemctl stop politichell || true

echo "$JSON" | jq -r '.assets[] | "\(.name) \(.browser_download_url)"' | while read -r NAME URL; do
  echo "Качаю $NAME ..."
  curl -L "${AUTH[@]}" -o "$MC_DIR/plugins/$NAME" "$URL"
  chown mc:mc "$MC_DIR/plugins/$NAME"
done

echo "Запускаю сервер..."
systemctl start politichell
sleep 3
echo "Статус: $(systemctl is-active politichell)   (логи: journalctl -u politichell -f)"
