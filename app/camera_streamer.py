import time
import threading
import subprocess
import os
from typing import Optional, Tuple, Dict, Any
from pathlib import Path
import cv2
import numpy as np

from app.config import (
    DEFAULT_FPS,
    DEFAULT_WIDTH,
    DEFAULT_HEIGHT,
    JPEG_QUALITY,
    SNAPSHOTS_DIR,
)
from app.motion_detector import MotionDetector
from app.recorder import ContinuousRecorder

class CameraStreamer:
    def __init__(self, camera_id: str = "cam_1", name: str = "Samsung Z Flip 3 - Cửa Chính", source_mode: str = "auto", device_index: int = 0):
        self.camera_id = camera_id
        self.name = name
        self.source_mode = source_mode  # "phone", "webcam", "simulated", "auto"
        self.source_type = "simulated"
        self.device_index = device_index
        self.rtsp_url = ""

        # Capture status
        self.running = False
        self.thread: Optional[threading.Thread] = None
        self.lock = threading.Lock()
        self.current_frame: Optional[np.ndarray] = None
        self.current_jpeg: Optional[bytes] = None

        # Statistics
        self.fps = 0.0
        self.frame_count = 0
        self.start_time = time.time()
        self.bitrate_kbps = 0.0

        # Controls
        self.zoom = 1.0        # 1.0 to 4.0
        self.pan_x = 0.0       # -1.0 to 1.0
        self.pan_y = 0.0       # -1.0 to 1.0
        self.night_mode = False
        self.torch_on = False
        self.resolution_mode = "HD"  # "HD", "SD", "SMOOTH"

        # Attached engines
        self.motion_detector = MotionDetector()
        self.recorder = ContinuousRecorder(camera_id=self.camera_id, fps=DEFAULT_FPS)

        # Phone camera state
        self.phone_camera_id = 0  # 0: Back Wide, 1: Front, 2: Back Ultra-wide

    def start(self):
        if self.running:
            return
        self.running = True
        self.thread = threading.Thread(target=self._capture_loop, daemon=True)
        self.thread.start()
        print(f"[Streamer] Camera '{self.name}' ({self.camera_id}) started.")

    def stop(self):
        self.running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.5)
        self.recorder.stop()
        print(f"[Streamer] Camera '{self.name}' stopped.")

    def _open_capture(self) -> Tuple[Optional[cv2.VideoCapture], str]:
        """Open physical webcam if requested, or fallback to simulated."""
        if self.source_mode == "webcam":
            try:
                cap = cv2.VideoCapture(self.device_index)
                if cap.isOpened():
                    cap.set(cv2.CAP_PROP_FRAME_WIDTH, DEFAULT_WIDTH)
                    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, DEFAULT_HEIGHT)
                    cap.set(cv2.CAP_PROP_FPS, DEFAULT_FPS)
                    ret, test_frame = cap.read()
                    if ret and test_frame is not None:
                        print(f"[Streamer] Camera {self.camera_id} connected to physical webcam #{self.device_index}")
                        return cap, "webcam"
                    cap.release()
            except Exception as e:
                print(f"[Streamer] Error opening webcam #{self.device_index}: {e}")

        # For phone or fallback
        return None, "simulated"

    def _generate_simulated_frame(self, t: float) -> np.ndarray:
        """
        Generate realistic security camera surveillance frame
        with simulated outdoor/indoor room lighting, subtle shadows, and motion.
        """
        w, h = DEFAULT_WIDTH, DEFAULT_HEIGHT
        frame = np.zeros((h, w, 3), dtype=np.uint8)

        # Gradient room background (CCTV surveillance view)
        for y in range(h):
            val = int(35 + (y / h) * 45)
            frame[y, :] = [val, val + 5, val + 10]

        # Draw simulated walls / floor perspective lines
        cv2.line(frame, (0, int(h * 0.7)), (w, int(h * 0.7)), (70, 75, 80), 2)
        cv2.line(frame, (int(w * 0.2), int(h * 0.7)), (0, h), (60, 65, 70), 2)
        cv2.line(frame, (int(w * 0.8), int(h * 0.7)), (w, h), (60, 65, 70), 2)

        # Door / Window outline in background
        cv2.rectangle(frame, (int(w * 0.42), int(h * 0.25)), (int(w * 0.58), int(h * 0.7)), (80, 85, 95), 2)

        # Simulate dynamic object moving periodically (for motion detection demo)
        cycle = t % 10.0
        if cycle < 5.0:
            # Moving object from left to right
            pos_x = int(w * 0.15 + (cycle / 5.0) * (w * 0.7))
            pos_y = int(h * 0.55 + np.sin(cycle * 3) * 12)
            # Simulated person / pet shape
            cv2.circle(frame, (pos_x, pos_y), 26, (130, 150, 180), -1)
            cv2.ellipse(frame, (pos_x, pos_y + 40), (18, 30), 0, 0, 360, (110, 130, 160), -1)

        # Subtle camera sensor noise
        noise = np.random.randint(0, 10, (h, w, 3), dtype=np.uint8)
        frame = cv2.add(frame, noise)

        return frame

    def _apply_ptz(self, frame: np.ndarray) -> np.ndarray:
        """Apply Digital Pan-Tilt-Zoom."""
        if self.zoom <= 1.0:
            return frame

        h, w = frame.shape[:2]
        crop_w = int(w / self.zoom)
        crop_h = int(h / self.zoom)

        center_x = int(w / 2 + (self.pan_x * (w - crop_w) / 2))
        center_y = int(h / 2 + (self.pan_y * (h - crop_h) / 2))

        x1 = max(0, min(w - crop_w, center_x - crop_w // 2))
        y1 = max(0, min(h - crop_h, center_y - crop_h // 2))
        x2 = x1 + crop_w
        y2 = y1 + crop_h

        cropped = frame[y1:y2, x1:x2]
        return cv2.resize(cropped, (w, h), interpolation=cv2.INTER_LINEAR)

    def _apply_night_mode(self, frame: np.ndarray) -> np.ndarray:
        """Simulate high-sensitivity Infrared Night Vision."""
        if not self.night_mode:
            return frame

        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
        enhanced = clahe.apply(gray)

        ir_view = cv2.cvtColor(enhanced, cv2.COLOR_GRAY2BGR)
        ir_view[:, :, 0] = np.clip(enhanced * 0.85, 0, 255)
        ir_view[:, :, 1] = np.clip(enhanced * 1.05, 0, 255)
        ir_view[:, :, 2] = np.clip(enhanced * 0.80, 0, 255)
        return ir_view

    def _apply_osd(self, frame: np.ndarray, motion_info: Dict[str, Any]) -> np.ndarray:
        """Add CCTV On-Screen Display (OSD) overlay."""
        h, w = frame.shape[:2]
        annotated = frame.copy()

        # Top Bar Background
        overlay = annotated.copy()
        cv2.rectangle(overlay, (0, 0), (w, 42), (15, 15, 15), -1)
        cv2.addWeighted(overlay, 0.6, annotated, 0.4, 0, annotated)

        # Camera Name & Status
        status_text = f"● REC 24/7 | {self.name.upper()}"
        cv2.putText(
            annotated,
            status_text,
            (16, 28),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.65,
            (0, 255, 0),
            2,
            cv2.LINE_AA,
        )

        # Real-time Timestamp
        time_str = time.strftime("%Y-%m-%d  %H:%M:%S")
        cv2.putText(
            annotated,
            time_str,
            (w - 280, 28),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.65,
            (255, 255, 255),
            2,
            cv2.LINE_AA,
        )

        # Bottom Bar Status
        zoom_str = f"ZOOM: {self.zoom:.1f}x" if self.zoom > 1.0 else "1.0x"
        night_str = " | NIGHT: ON" if self.night_mode else ""
        stats_str = f"{self.fps:.1f} FPS | {self.bitrate_kbps:.0f} kbps | {zoom_str}{night_str}"
        cv2.putText(
            annotated,
            stats_str,
            (16, h - 16),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.5,
            (200, 200, 200),
            1,
            cv2.LINE_AA,
        )

        # Motion Alert Indicator
        if motion_info.get("motion_detected"):
            cv2.circle(annotated, (w - 24, h - 22), 8, (0, 0, 255), -1)
            cv2.putText(
                annotated,
                "MOTION",
                (w - 100, h - 16),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.55,
                (0, 0, 255),
                2,
                cv2.LINE_AA,
            )

        return annotated

    def _capture_loop(self):
        """Continuous frame acquisition and processing thread."""
        cap, actual_source = self._open_capture()
        self.source_type = actual_source

        prev_time = time.time()
        fps_frame_count = 0
        fps_start_time = time.time()

        while self.running:
            loop_start = time.time()

            if cap is not None and cap.isOpened():
                ret, frame = cap.read()
                if not ret or frame is None:
                    cap.release()
                    cap = None
                    self.source_type = "simulated"
                    frame = self._generate_simulated_frame(loop_start)
            else:
                frame = self._generate_simulated_frame(loop_start)

            # Apply Digital PTZ
            processed = self._apply_ptz(frame)

            # Apply Night Vision Filter
            processed = self._apply_night_mode(processed)

            # Feed to Motion Detector
            motion_info = self.motion_detector.process_frame(processed, camera_id=self.camera_id)

            # Feed clean frame to 24/7 Recorder
            self.recorder.add_frame(processed)

            # Apply OSD watermark
            display_frame = self._apply_osd(processed, motion_info)

            # Compress to JPEG
            encode_param = [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY]
            ret, jpeg_buf = cv2.imencode(".jpg", display_frame, encode_param)

            if ret:
                jpeg_bytes = jpeg_buf.tobytes()
                with self.lock:
                    self.current_frame = display_frame
                    self.current_jpeg = jpeg_bytes

                fps_frame_count += 1
                now = time.time()
                elapsed = now - fps_start_time
                if elapsed >= 1.0:
                    self.fps = fps_frame_count / elapsed
                    self.bitrate_kbps = (len(jpeg_bytes) * 8 * self.fps) / 1000.0
                    fps_frame_count = 0
                    fps_start_time = now

            target_delay = 1.0 / DEFAULT_FPS
            actual_elapsed = time.time() - loop_start
            sleep_time = max(0.005, target_delay - actual_elapsed)
            time.sleep(sleep_time)

        if cap is not None:
            cap.release()

    def get_jpeg(self) -> Optional[bytes]:
        with self.lock:
            return self.current_jpeg

    def take_snapshot(self) -> Dict[str, Any]:
        """Capture a high-res snapshot and save to disk."""
        with self.lock:
            if self.current_frame is None:
                return {"success": False, "error": "No frame available"}
            frame_to_save = self.current_frame.copy()

        time_str = time.strftime("%Y%m%d_%H%M%S")
        filename = f"snap_{self.camera_id}_{time_str}.jpg"
        filepath = SNAPSHOTS_DIR / filename
        cv2.imwrite(str(filepath), frame_to_save, [int(cv2.IMWRITE_JPEG_QUALITY), 95])

        return {
            "success": True,
            "filename": filename,
            "url": f"/api/snapshots/{filename}",
            "timestamp": time.time(),
            "time_str": time.strftime("%Y-%m-%d %H:%M:%S"),
        }

    def set_ptz(self, zoom: float, pan_x: float, pan_y: float):
        self.zoom = max(1.0, min(5.0, float(zoom)))
        self.pan_x = max(-1.0, min(1.0, float(pan_x)))
        self.pan_y = max(-1.0, min(1.0, float(pan_y)))

    def toggle_night_mode(self) -> bool:
        self.night_mode = not self.night_mode
        return self.night_mode

    def toggle_torch(self) -> bool:
        self.torch_on = not self.torch_on
        try:
            cmd = "on" if self.torch_on else "off"
            subprocess.run(
                ["adb", "shell", f"cmd camera set-torch-mode 0 {cmd}"],
                capture_output=True,
                timeout=1,
            )
        except Exception:
            pass
        return self.torch_on
