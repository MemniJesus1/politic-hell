#!/usr/bin/env bash
# ============================================================
#  POLITICHELL — установка сервера на свежей Ubuntu 24.04
#  (виртуальная машина Google Cloud, регион рядом с тобой)
#
#  Запуск в веб-SSH консоли VM:
#     sudo bash setup_server.sh
#
#  Сервер должен лежать в /tmp как pack.tar.gz ИЛИ pack.zip
#  (скрипт scripts/УПАКОВАТЬ_СЕРВЕР_В_ZIP.ps1 на твоём ПК).
# ============================================================
set -euo pipefail

MC_DIR=/opt/politichell
MC_USER=mc
PACK_TGZ=/tmp/pack.tar.gz
PACK_ZIP=/tmp/pack.zip
PACK=$( [ -f "$PACK_TGZ" ] && echo "$PACK_TGZ" || { [ -f "$PACK_ZIP" ] && echo "$PACK_ZIP" || echo ""; } )

echo "[1/6] Java 21 + утилиты"
apt-get update -y
DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jre-headless unzip jq ufw

echo "[2/6] Пользователь и папка $MC_DIR"
id -u "$MC_USER" &>/dev/null || useradd -r -m -s /usr/sbin/nologin "$MC_USER"
mkdir -p "$MC_DIR"

echo "[3/6] Распаковка сервера пакета"
if [ -n "$PACK" ]; then
  echo "    пакет: $PACK"
  if [ "$PACK" = "$PACK_TGZ" ]; then
    tar -xzf "$PACK_TGZ" -C "$MC_DIR"
  else
    unzip -o -q "$PACK_ZIP" -d "$MC_DIR"
  fi
else
  echo "    !! pack-архив не найден в /tmp"
  echo "    Сервер встанет ПУСТЫМ: положи сам server.jar + eula.txt в $MC_DIR"
  echo "    (или запусти этот скрипт заново после загрузки pack.tar.gz)"
fi
chown -R "$MC_USER:$MC_USER" "$MC_DIR"

echo "[4/6] Своп 2G (страховка памяти)"
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "[5/6] Firewall: SSH + Minecraft(25565) + RCON(25575)"
ufw allow OpenSSH
ufw allow 25565/tcp
ufw allow 25575/tcp
yes | ufw enable

echo "[6/6] Автозапуск (systemd)"
cat > /etc/systemd/system/politichell.service <<'UNIT'
[Unit]
Description=PoliticHell Minecraft Server (Leaf)
After=network.target

[Service]
User=mc
WorkingDirectory=/opt/politichell
ExecStart=/usr/bin/java -Xms768M -Xmx4G -jar server.jar nogui
Restart=on-failure
RestartSec=10
TimeoutStopSec=120
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now politichell

echo
echo "================ ГОТОВО ================"
systemctl is-active politichell || true
echo "Логи в реальном времени:  journalctl -u politichell -f"
echo "Рестарт сервера:          systemctl restart politichell"
echo "Остановка:                systemctl stop politichell"
echo "Папка сервера:            /opt/politichell"
echo "Обновление jar-ов:        sudo bash $MC_DIR/deploy_jars.sh ТВОЙ_НИК/politic-hell"
