import os
import time
import threading
import subprocess
from pathlib import Path
from typing import Optional, Tuple, Dict, Any
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

LENS_DEFINITIONS: Dict[int, Dict[str, str]] = {
    0: {"name": "Góc Rộng 1x (Wide Primary)", "tag": "1.0x WIDE"},
    2: {"name": "Góc Siêu Rộng 0.5x (Ultra-wide 123°)", "tag": "0.5x ULTRA"},
    1: {"name": "Camera Trước 10MP (Front)", "tag": "FRONT"},
}

class CameraStreamer:
    """
    24/7 Smart CCTV Camera Streamer with Dynamic Hardware Ingestion.
    - Monitors ADB status in background
    - Hot-swaps dynamically between simulated surveillance view and real Samsung Z Flip 3 hardware stream
    - Supports dynamic lens switching (camera-id 0: Wide, 2: Ultra-wide, 1: Front)
    - Full PTZ, IR Night Vision filter, Motion Detection, and 24/7 H.264 MP4 recording
    """

    def __init__(
        self,
        camera_id: str = "cam_1",
        name: str = "Samsung Z Flip 3 24/7",
        source_mode: str = "auto",
        device_index: int = 0,
    ):
        self.camera_id = camera_id
        self.name = name
        self.source_mode = source_mode  # "phone", "auto", "simulated", "webcam"
        self.source_type = "simulated"
        self.device_index = device_index
        self.rtsp_url = ""

        # Capture status
        self.running = False
        self.capture_thread: Optional[threading.Thread] = None
        self.lock = threading.Lock()
        self.current_frame: Optional[np.ndarray] = None
        self.current_jpeg: Optional[bytes] = None

        # Statistics
        self.fps = 0.0
        self.frame_count = 0
        self.start_time = time.time()
        self.bitrate_kbps = 0.0

        # Controls & Settings
        self.zoom = 1.0        # 1.0 to 4.0
        self.pan_x = 0.0       # -1.0 to 1.0
        self.pan_y = 0.0       # -1.0 to 1.0
        self.night_mode = False
        self.torch_on = False
        self.resolution_mode = "HD"

        # Hardware Ingestion & ADB State
        self.phone_camera_id = 0  # 0: Wide, 2: Ultra-wide, 1: Front
        self.adb_state = "disconnected"  # "device", "unauthorized", "disconnected"
        self.active_serial = ""
        self.hardware_active = False
        self.hardware_frame: Optional[np.ndarray] = None
        self.last_hardware_frame_time = 0.0

        self.scrcpy_proc: Optional[subprocess.Popen] = None
        self.fifo_path = Path(f"/tmp/scrcpy_{self.camera_id}.mkv")
        self.adb_thread: Optional[threading.Thread] = None
        self.reader_thread: Optional[threading.Thread] = None

        # Attached Engines
        self.motion_detector = MotionDetector()
        self.recorder = ContinuousRecorder(camera_id=self.camera_id, fps=DEFAULT_FPS)

    def start(self):
        if self.running:
            return
        self.running = True

        # Start background ADB device monitor
        self.adb_thread = threading.Thread(target=self._adb_watcher_loop, daemon=True)
        self.adb_thread.start()

        # Start main frame capture & processing loop
        self.capture_thread = threading.Thread(target=self._capture_loop, daemon=True)
        self.capture_thread.start()
        print(f"[Streamer] Camera '{self.name}' ({self.camera_id}) started.")

    def stop(self):
        self.running = False
        self._stop_hardware_stream()

        if self.capture_thread and self.capture_thread.is_alive():
            self.capture_thread.join(timeout=1.5)
        if self.adb_thread and self.adb_thread.is_alive():
            self.adb_thread.join(timeout=1.0)

        self.recorder.stop()
        print(f"[Streamer] Camera '{self.name}' stopped.")

    # ------------------------------------------------------------------
    # ADB Watcher & Hardware Ingestion
    # ------------------------------------------------------------------

    def _check_adb(self) -> Tuple[str, str]:
        """Query ADB devices and return (state, target_serial)."""
        try:
            res = subprocess.run(
                ["adb", "devices", "-l"],
                capture_output=True,
                text=True,
                timeout=2.0,
            )
            lines = [
                line.strip()
                for line in res.stdout.splitlines()
                if line.strip() and not line.startswith("List of")
            ]

            # Priority 1: Check for authorized device ('device')
            # Prefer USB over wireless if both exist
            usb_device = None
            tcp_device = None
            for line in lines:
                parts = line.split()
                if len(parts) >= 2 and parts[1] == "device":
                    if ":" in parts[0]:
                        tcp_device = parts[0]
                    else:
                        usb_device = parts[0]
            if usb_device:
                return "device", usb_device
            if tcp_device:
                return "device", tcp_device

            # Priority 2: Check for unauthorized device
            for line in lines:
                parts = line.split()
                if len(parts) >= 2 and parts[1] == "unauthorized":
                    return "unauthorized", parts[0]

            return "disconnected", ""
        except Exception as e:
            return "disconnected", ""

    def _adb_watcher_loop(self):
        """Continuously check ADB status; trigger hardware stream on device authorization."""
        while self.running:
            state, serial = self._check_adb()
            self.adb_state = state
            self.active_serial = serial

            if state == "device":
                if not self.hardware_active and (self.scrcpy_proc is None or self.scrcpy_proc.poll() is not None):
                    print(f"[ADB Watcher] Samsung Z Flip 3 ({serial}) authorized. Launching hardware stream...")
                    self._start_hardware_stream(serial)
            else:
                if self.hardware_active:
                    print(f"[ADB Watcher] Phone state changed to '{state}'. Stopping hardware stream...")
                    self._stop_hardware_stream()

            time.sleep(2.5)

    def _start_hardware_stream(self, serial: str = ""):
        """Launch scrcpy camera mode outputting Matroska stream into FIFO."""
        if self.hardware_active:
            self._stop_hardware_stream()

        target_serial = serial or self.active_serial

        # Prepare FIFO
        try:
            if self.fifo_path.exists():
                self.fifo_path.unlink()
            os.mkfifo(str(self.fifo_path))
        except Exception as e:
            print(f"[Streamer] Error preparing FIFO {self.fifo_path}: {e}")
            return

        cmd = [
            "scrcpy",
            "--video-source=camera",
            f"--camera-id={self.phone_camera_id}",
            "--camera-fps=25",
            "--turn-screen-off",
            "--no-audio",
            "--no-playback",
            f"--record={self.fifo_path}",
            "--record-format=mkv",
        ]
        if target_serial:
            cmd.extend(["-s", target_serial])

        try:
            print(f"[Streamer] Executing: {' '.join(cmd)}")
            self.scrcpy_proc = subprocess.Popen(
                cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
            )
            self.hardware_active = True

            # Start reader thread
            self.reader_thread = threading.Thread(target=self._read_hardware_stream, daemon=True)
            self.reader_thread.start()
        except Exception as e:
            print(f"[Streamer] Failed to spawn scrcpy: {e}")
            self.hardware_active = False

    def _stop_hardware_stream(self):
        """Terminate scrcpy and clean up reader thread and FIFO."""
        self.hardware_active = False

        if self.scrcpy_proc is not None:
            try:
                self.scrcpy_proc.terminate()
                self.scrcpy_proc.wait(timeout=1.0)
            except Exception:
                try:
                    self.scrcpy_proc.kill()
                except Exception:
                    pass
            self.scrcpy_proc = None

        # Unblock FIFO if reader is blocked waiting
        try:
            if self.fifo_path.exists():
                # Open write side briefly to release reader
                open(str(self.fifo_path), "wb").close()
        except Exception:
            pass

        if self.reader_thread and self.reader_thread.is_alive():
            self.reader_thread.join(timeout=1.0)
        self.reader_thread = None

        try:
            if self.fifo_path.exists():
                self.fifo_path.unlink()
        except Exception:
            pass

        with self.lock:
            if self.source_type == "samsung_zflip3":
                self.source_type = "simulated"

    def _read_hardware_stream(self):
        """Read decoded video frames from scrcpy Matroska FIFO via PyAV."""
        import av

        # Wait briefly for scrcpy to initialize
        time.sleep(0.5)
        if self.scrcpy_proc is not None and self.scrcpy_proc.poll() is not None:
            err = self.scrcpy_proc.stderr.read().decode(errors="ignore") if self.scrcpy_proc.stderr else ""
            print(f"[Streamer] scrcpy exited early: {err}")
            self.hardware_active = False
            return

        try:
            container = av.open(str(self.fifo_path), format="matroska")
            for frame in container.decode(video=0):
                if not self.running or not self.hardware_active:
                    break

                bgr = frame.to_ndarray(format="bgr24")
                with self.lock:
                    self.hardware_frame = bgr
                    self.last_hardware_frame_time = time.time()
                    self.source_type = "samsung_zflip3"

            container.close()
        except Exception as e:
            if self.hardware_active:
                print(f"[Streamer] Hardware ingestion ended: {e}")
        finally:
            self.hardware_active = False
            with self.lock:
                if self.source_type == "samsung_zflip3":
                    self.source_type = "simulated"

    # ------------------------------------------------------------------
    # Lens Switching
    # ------------------------------------------------------------------

    def get_lens_name(self, lens_id: int) -> str:
        return LENS_DEFINITIONS.get(lens_id, {}).get("name", f"Lens #{lens_id}")

    def get_lens_tag(self, lens_id: int) -> str:
        return LENS_DEFINITIONS.get(lens_id, {}).get("tag", f"CAM {lens_id}")

    def switch_lens(self, lens_id: int) -> Dict[str, Any]:
        """
        Switch camera lens:
        0: Back Wide 1x (12MP)
        2: Back Ultra-wide 0.5x (12MP 123° FOV)
        1: Front Camera (10MP)
        """
        if lens_id not in [0, 1, 2]:
            return {"success": False, "error": f"Invalid lens_id '{lens_id}'. Supported: 0 (1x), 2 (0.5x), 1 (Front)."}

        old_lens = self.phone_camera_id
        self.phone_camera_id = lens_id
        lens_name = self.get_lens_name(lens_id)
        tag = self.get_lens_tag(lens_id)
        print(f"[Streamer] Lens switched: {old_lens} -> {lens_id} ({lens_name})")

        # If hardware is running, restart scrcpy with new camera-id in worker thread
        if self.adb_state == "device":
            threading.Thread(target=self._restart_hardware_worker, daemon=True).start()

        return {
            "success": True,
            "lens_id": lens_id,
            "lens_name": lens_name,
            "lens_tag": tag,
        }

    def _restart_hardware_worker(self):
        self._stop_hardware_stream()
        time.sleep(0.3)
        if self.adb_state == "device" and self.active_serial:
            self._start_hardware_stream(self.active_serial)

    # ------------------------------------------------------------------
    # Frame Generation & Processing
    # ------------------------------------------------------------------

    def _generate_simulated_frame(self, t: float) -> np.ndarray:
        """
        Generate realistic simulated CCTV security room view.
        Adapts dynamically to the selected lens (Wide 1x, Ultra-wide 0.5x, Front).
        """
        w, h = DEFAULT_WIDTH, DEFAULT_HEIGHT
        frame = np.zeros((h, w, 3), dtype=np.uint8)

        # Lens-dependent background and perspective
        if self.phone_camera_id == 2:
            # Ultra-wide 0.5x (expanded 123° perspective)
            for y in range(h):
                val = int(25 + (y / h) * 45)
                frame[y, :] = [val, val + 4, val + 8]
            # Wide room walls
            cv2.line(frame, (0, int(h * 0.65)), (w, int(h * 0.65)), (65, 70, 75), 2)
            cv2.line(frame, (int(w * 0.08), int(h * 0.65)), (0, h), (55, 60, 65), 2)
            cv2.line(frame, (int(w * 0.92), int(h * 0.65)), (w, h), (55, 60, 65), 2)
            # Two doors / corridor view
            cv2.rectangle(frame, (int(w * 0.2), int(h * 0.22)), (int(w * 0.35), int(h * 0.65)), (75, 80, 90), 2)
            cv2.rectangle(frame, (int(w * 0.65), int(h * 0.22)), (int(w * 0.8), int(h * 0.65)), (75, 80, 90), 2)
        elif self.phone_camera_id == 1:
            # Front camera (indoor portrait perspective)
            for y in range(h):
                val = int(40 + (y / h) * 35)
                frame[y, :] = [val + 8, val + 4, val]
            # Desk / indoor background
            cv2.line(frame, (0, int(h * 0.75)), (w, int(h * 0.75)), (80, 75, 70), 2)
            cv2.rectangle(frame, (int(w * 0.3), int(h * 0.15)), (int(w * 0.7), int(h * 0.55)), (90, 85, 80), 2)
        else:
            # Back Wide 1x standard view
            for y in range(h):
                val = int(35 + (y / h) * 45)
                frame[y, :] = [val, val + 5, val + 10]
            cv2.line(frame, (0, int(h * 0.7)), (w, int(h * 0.7)), (70, 75, 80), 2)
            cv2.line(frame, (int(w * 0.2), int(h * 0.7)), (0, h), (60, 65, 70), 2)
            cv2.line(frame, (int(w * 0.8), int(h * 0.7)), (w, h), (60, 65, 70), 2)
            cv2.rectangle(frame, (int(w * 0.42), int(h * 0.25)), (int(w * 0.58), int(h * 0.7)), (80, 85, 95), 2)

        # Dynamic motion simulation for continuous motion detector testing
        cycle = t % 10.0
        if cycle < 5.0:
            pos_x = int(w * 0.15 + (cycle / 5.0) * (w * 0.7))
            pos_y = int(h * 0.55 + np.sin(cycle * 3) * 12)
            cv2.circle(frame, (pos_x, pos_y), 26, (130, 150, 180), -1)
            cv2.ellipse(frame, (pos_x, pos_y + 40), (18, 30), 0, 0, 360, (110, 130, 160), -1)

        # Sensor noise
        noise = np.random.randint(0, 8, (h, w, 3), dtype=np.uint8)
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

        # Top Bar
        overlay = annotated.copy()
        cv2.rectangle(overlay, (0, 0), (w, 42), (15, 15, 15), -1)
        cv2.addWeighted(overlay, 0.65, annotated, 0.35, 0, annotated)

        # Camera Name & Lens Tag
        lens_tag = self.get_lens_tag(self.phone_camera_id)
        if self.source_type == "samsung_zflip3":
            status_text = f"● REC 24/7 | {self.name.upper()} | {lens_tag} [LIVE HW]"
            tag_color = (0, 255, 0)
        else:
            adb_desc = "UNAUTHORIZED" if self.adb_state == "unauthorized" else ("CONNECTED" if self.adb_state == "device" else "SEARCHING")
            status_text = f"● REC 24/7 | {self.name.upper()} | {lens_tag} [SIMULATED - ADB {adb_desc}]"
            tag_color = (0, 215, 255)

        cv2.putText(
            annotated,
            status_text,
            (16, 28),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.6,
            tag_color,
            2,
            cv2.LINE_AA,
        )

        # Real-time Timestamp
        time_str = time.strftime("%Y-%m-%d  %H:%M:%S")
        cv2.putText(
            annotated,
            time_str,
            (w - 270, 28),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.6,
            (255, 255, 255),
            2,
            cv2.LINE_AA,
        )

        # Bottom Bar
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
        """Continuous frame acquisition, processing, and H.264 recording loop."""
        fps_frame_count = 0
        fps_start_time = time.time()

        while self.running:
            loop_start = time.time()

            # Hot-swap: Check for fresh hardware frame
            use_hardware = False
            with self.lock:
                if (
                    self.hardware_frame is not None
                    and (loop_start - self.last_hardware_frame_time) < 2.0
                ):
                    frame = self.hardware_frame.copy()
                    self.source_type = "samsung_zflip3"
                    use_hardware = True

            if not use_hardware:
                self.source_type = "simulated"
                frame = self._generate_simulated_frame(loop_start)

            # Ensure frame matches standard dimension
            if frame.shape[1] != DEFAULT_WIDTH or frame.shape[0] != DEFAULT_HEIGHT:
                frame = cv2.resize(frame, (DEFAULT_WIDTH, DEFAULT_HEIGHT))

            # Apply Digital PTZ
            processed = self._apply_ptz(frame)

            # Apply Night Vision Filter
            processed = self._apply_night_mode(processed)

            # Feed to Motion Detector
            motion_info = self.motion_detector.process_frame(processed, camera_id=self.camera_id)

            # Feed to Continuous 24/7 H.264 Recorder
            self.recorder.add_frame(processed)

            # Apply OSD watermark
            display_frame = self._apply_osd(processed, motion_info)

            # Compress to JPEG for Web MJPEG Streaming
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
        cmd = "on" if self.torch_on else "off"
        try:
            adb_cmd = ["adb"]
            if self.active_serial:
                adb_cmd.extend(["-s", self.active_serial])
            adb_cmd.extend(["shell", "cmd", "camera", "set-torch-mode", "0", cmd])
            subprocess.run(adb_cmd, capture_output=True, timeout=1.0)
        except Exception:
            pass
        return self.torch_on
