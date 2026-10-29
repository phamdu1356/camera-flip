#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_ROOT"

if [ ! -d "venv" ]; then
    echo "Creating Python virtual environment..."
    python3 -m venv venv
    ./venv/bin/pip install --upgrade pip
    ./venv/bin/pip install -r requirements.txt
fi

echo "=========================================================="
echo "      SECURECAM 24/7 - CAMERA SURVEILLANCE HUB"
echo "=========================================================="
echo "Local Dashboard (PC): http://127.0.0.1:8000"
echo "Mobile Dashboard (LAN): http://172.17.162.27:8000"
echo "=========================================================="
echo "Starting server on port 8000..."

exec ./venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
