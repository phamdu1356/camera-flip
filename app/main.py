import os
import time
import asyncio
from typing import Dict, List, Any
from pathlib import Path

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.responses import StreamingResponse, FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from app.config import (
    STATIC_DIR,
    RECORDINGS_DIR,
    SNAPSHOTS_DIR,
    HOST,
    PORT,
    MAX_STORAGE_GB,
)
from app.camera_streamer import CameraStreamer

app = FastAPI(title="24/7 Smart CCTV Camera Hub", version="2.0")

# Enable CORS for cross-device mobile access
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Active camera instances
cameras: Dict[str, CameraStreamer] = {}

# Active WebSocket subscribers
active_websockets: List[WebSocket] = []

class PTZRequest(BaseModel):
    zoom: float
    pan_x: float = 0.0
    pan_y: float = 0.0

@app.on_event("startup")
def startup_event():
    # Only use Camera 1 (Samsung Z Flip 3) - Do NOT access laptop webcam
    cam1 = CameraStreamer(camera_id="cam_1", name="Samsung Z Flip 3 24/7", source_mode="phone")
    cam1.start()
    cameras["cam_1"] = cam1

@app.on_event("shutdown")
def shutdown_event():
    for cam in cameras.values():
        cam.stop()

# -------------------------------------------------------------
# Streaming Endpoints
# -------------------------------------------------------------

def generate_mjpeg(cam: CameraStreamer):
    """Generator yielding multipart JPEG frames for live browser view."""
    while cam.running:
        frame_bytes = cam.get_jpeg()
        if frame_bytes is not None:
            yield (
                b"--frame\r\n"
                b"Content-Type: image/jpeg\r\n\r\n" + frame_bytes + b"\r\n"
            )
        time.sleep(0.033)  # ~30 FPS

@app.get("/api/stream/{camera_id}")
def video_stream(camera_id: str):
    if camera_id not in cameras:
        raise HTTPException(status_code=404, detail="Camera not found")
    cam = cameras[camera_id]
    return StreamingResponse(
        generate_mjpeg(cam),
        media_type="multipart/x-mixed-replace; boundary=frame",
    )

# -------------------------------------------------------------
# Camera Management & Control APIs
# -------------------------------------------------------------

@app.get("/api/cameras")
def list_cameras():
    result = []
    for cid, cam in cameras.items():
        result.append({
            "id": cid,
            "name": cam.name,
            "source_type": cam.source_type,
            "running": cam.running,
            "fps": round(cam.fps, 1),
            "bitrate_kbps": round(cam.bitrate_kbps, 0),
            "zoom": cam.zoom,
            "night_mode": cam.night_mode,
            "torch_on": cam.torch_on,
            "stream_url": f"/api/stream/{cid}",
        })
    return result

@app.post("/api/cameras/{camera_id}/ptz")
def control_ptz(camera_id: str, ptz: PTZRequest):
    if camera_id not in cameras:
        raise HTTPException(status_code=404, detail="Camera not found")
    cam = cameras[camera_id]
    cam.set_ptz(ptz.zoom, ptz.pan_x, ptz.pan_y)
    return {"success": True, "zoom": cam.zoom, "pan_x": cam.pan_x, "pan_y": cam.pan_y}

@app.post("/api/cameras/{camera_id}/night_mode")
def toggle_night(camera_id: str):
    if camera_id not in cameras:
        raise HTTPException(status_code=404, detail="Camera not found")
    cam = cameras[camera_id]
    state = cam.toggle_night_mode()
    return {"success": True, "night_mode": state}

@app.post("/api/cameras/{camera_id}/torch")
def toggle_torch(camera_id: str):
    if camera_id not in cameras:
        raise HTTPException(status_code=404, detail="Camera not found")
    cam = cameras[camera_id]
    state = cam.toggle_torch()
    return {"success": True, "torch_on": state}

@app.post("/api/cameras/{camera_id}/snapshot")
def take_snapshot(camera_id: str):
    if camera_id not in cameras:
        raise HTTPException(status_code=404, detail="Camera not found")
    cam = cameras[camera_id]
    res = cam.take_snapshot()
    return res

# -------------------------------------------------------------
# 24/7 Playback Timeline & Recordings
# -------------------------------------------------------------

@app.get("/api/recordings/timeline")
def get_timeline(date: str = ""):
    """Get all 5-min chunks recorded on a date (format: YYYY-MM-DD)."""
    if not date:
        date = time.strftime("%Y-%m-%d")
    from app.recorder import ContinuousRecorder
    chunks = ContinuousRecorder.get_timeline_data(date)
    return {"date": date, "chunks": chunks}

@app.get("/api/recordings/{date}/{filename}")
def stream_recording_file(date: str, filename: str):
    target = RECORDINGS_DIR / date / filename
    if not target.exists():
        raise HTTPException(status_code=404, detail="Recording file not found")
    media_type = "image/jpeg" if filename.endswith(".jpg") else "video/mp4"
    return FileResponse(target, media_type=media_type)

# -------------------------------------------------------------
# Snapshots & Motion Alerts
# -------------------------------------------------------------

@app.get("/api/snapshots")
def list_snapshots(limit: int = 50):
    events = []
    for cam in cameras.values():
        events.extend(cam.motion_detector.get_recent_events(limit))
    events.sort(key=lambda x: x["timestamp"], reverse=True)
    return events[:limit]

@app.get("/api/snapshots/{filename}")
def get_snapshot_file(filename: str):
    target = SNAPSHOTS_DIR / filename
    if not target.exists():
        raise HTTPException(status_code=404, detail="Snapshot not found")
    return FileResponse(target, media_type="image/jpeg")

# -------------------------------------------------------------
# System Status & Storage
# -------------------------------------------------------------

@app.get("/api/system/status")
def system_status():
    # Calculate storage
    total_bytes = sum(f.stat().st_size for f in RECORDINGS_DIR.rglob("*") if f.is_file())
    used_gb = round(total_bytes / (1024**3), 2)

    # Check ADB status
    import subprocess
    adb_status = "Disconnected"
    try:
        out = subprocess.check_output(["adb", "devices", "-l"], timeout=1).decode()
        lines = [line.strip() for line in out.splitlines() if line.strip() and not line.startswith("List of")]
        if any("device" in l and "unauthorized" not in l for l in lines):
            adb_status = "Connected (Z Flip 3)"
        elif any("unauthorized" in l for l in lines):
            adb_status = "Awaiting Phone Authorization"
        else:
            adb_status = "No USB Device"
    except Exception:
        pass

    return {
        "status": "Online 24/7",
        "adb_status": adb_status,
        "active_cameras": len(cameras),
        "storage_used_gb": used_gb,
        "storage_max_gb": MAX_STORAGE_GB,
        "storage_percent": round((used_gb / MAX_STORAGE_GB) * 100, 1),
        "server_time": time.strftime("%Y-%m-%d %H:%M:%S"),
    }

# -------------------------------------------------------------
# WebSocket for Realtime Alerts & Status
# -------------------------------------------------------------

@app.websocket("/api/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    active_websockets.append(websocket)
    try:
        while True:
            # Send status update every second
            status_payload = {
                "type": "telemetry",
                "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                "cameras": [
                    {
                        "id": cid,
                        "fps": round(c.fps, 1),
                        "bitrate": round(c.bitrate_kbps, 0),
                        "night_mode": c.night_mode,
                        "torch_on": c.torch_on,
                    }
                    for cid, c in cameras.items()
                ],
            }
            await websocket.send_json(status_payload)
            await asyncio.sleep(1.0)
    except WebSocketDisconnect:
        if websocket in active_websockets:
            active_websockets.remove(websocket)
    except Exception:
        if websocket in active_websockets:
            active_websockets.remove(websocket)

# -------------------------------------------------------------
# Serve Static Frontend (PWA & Web App)
# -------------------------------------------------------------

app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

@app.get("/")
def serve_index():
    index_file = STATIC_DIR / "index.html"
    return FileResponse(index_file)

@app.get("/manifest.json")
def serve_manifest():
    manifest_file = STATIC_DIR / "manifest.json"
    return FileResponse(manifest_file, media_type="application/manifest+json")

@app.get("/sw.js")
def serve_service_worker():
    sw_file = STATIC_DIR / "sw.js"
    return FileResponse(sw_file, media_type="application/javascript")
