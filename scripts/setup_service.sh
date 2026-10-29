#!/usr/bin/env bash
set -e

SERVICE_DIR="$HOME/.config/systemd/user"
mkdir -p "$SERVICE_DIR"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cat <<EOF > "$SERVICE_DIR/securecam.service"
[Unit]
Description=SecureCam 24/7 Surveillance Hub
After=network.target

[Service]
Type=simple
WorkingDirectory=$PROJECT_ROOT
ExecStart=$PROJECT_ROOT/venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
Restart=always
RestartSec=3
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable securecam.service
systemctl --user restart securecam.service

echo "=========================================================="
echo " SecureCam 24/7 Systemd Service installed & enabled!"
echo " Status command: systemctl --user status securecam"
echo "=========================================================="
