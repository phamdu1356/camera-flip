import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

# Storage configuration
RECORDINGS_DIR = BASE_DIR / "recordings"
SNAPSHOTS_DIR = BASE_DIR / "snapshots"
STATIC_DIR = BASE_DIR / "static"

RECORDINGS_DIR.mkdir(parents=True, exist_ok=True)
SNAPSHOTS_DIR.mkdir(parents=True, exist_ok=True)

# Server configuration
HOST = os.getenv("CAMERA_HOST", "0.0.0.0")
PORT = int(os.getenv("CAMERA_PORT", "8000"))

# Camera Stream Defaults
DEFAULT_FPS = 25
DEFAULT_WIDTH = 1280
DEFAULT_HEIGHT = 720
JPEG_QUALITY = 80

# 24/7 Recording Configuration
RECORDING_CHUNK_SECONDS = 300  # 5 minutes per video file chunk
MAX_STORAGE_GB = float(os.getenv("MAX_STORAGE_GB", "50.0"))  # Auto-purge oldest when exceeding
STORAGE_CLEANUP_INTERVAL_SECONDS = 600

# Motion Detection Configuration
MOTION_ENABLED = True
MOTION_THRESHOLD = 25  # Pixel difference sensitivity
MIN_MOTION_AREA = 500  # Minimum contour area to trigger alert
MOTION_COOLDOWN_SECONDS = 5  # Cooldown between snapshots
