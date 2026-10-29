import os
import time
import shutil
import glob
from pathlib import Path
from typing import Optional, List, Dict, Any
import cv2
import numpy as np

from app.config import (
    RECORDINGS_DIR,
    RECORDING_CHUNK_SECONDS,
    MAX_STORAGE_GB,
    DEFAULT_FPS,
)

class H264VideoWriter:
    """
    High-compatibility H.264 MP4 writer using PyAV (libx264)
    with seamless fallback to cv2.VideoWriter (avc1 / mp4v).
    Ensures recordings play directly in all web browsers (Chrome, Safari, iOS, Android).
    """

    def __init__(self, filepath: str, width: int, height: int, fps: int = 25):
        self.filepath = filepath
        self.width = width
        self.height = height
        self.fps = fps
        self.backend = "none"
        self._av_container = None
        self._av_stream = None
        self._cv2_writer = None
        self._closed = False

        # 1. Try PyAV with libx264 + faststart (gold standard for web browser HTML5 playback)
        try:
            import av

            container = av.open(
                filepath,
                mode="w",
                format="mp4",
                options={"movflags": "faststart"},  # Places moov atom up front for instant streaming
            )
            stream = container.add_stream("libx264", rate=fps)
            stream.width = width
            stream.height = height
            stream.pix_fmt = "yuv420p"  # Universal browser compatibility
            stream.options = {
                "preset": "veryfast",  # Efficient CPU usage for 24/7 continuous recording
                "tune": "zerolatency",
                "crf": "23",
            }
            self._av_container = container
            self._av_stream = stream
            self.backend = "pyav_x264"
            return
        except Exception as e:
            print(f"[H264VideoWriter] PyAV initialization failed: {e}. Falling back to OpenCV.")

        # 2. Try OpenCV with 'avc1' (H.264 fourcc in OpenCV)
        try:
            fourcc = cv2.VideoWriter_fourcc(*"avc1")
            writer = cv2.VideoWriter(filepath, fourcc, float(fps), (width, height))
            if writer.isOpened():
                self._cv2_writer = writer
                self.backend = "opencv_avc1"
                return
            writer.release()
        except Exception as e:
            print(f"[H264VideoWriter] OpenCV avc1 fallback failed: {e}.")

        # 3. Last-resort fallback to mp4v
        try:
            fourcc = cv2.VideoWriter_fourcc(*"mp4v")
            writer = cv2.VideoWriter(filepath, fourcc, float(fps), (width, height))
            self._cv2_writer = writer
            self.backend = "opencv_mp4v"
        except Exception as e:
            print(f"[H264VideoWriter] OpenCV mp4v fallback failed: {e}.")

    def write(self, frame_bgr: np.ndarray):
        if self._closed or frame_bgr is None:
            return

        h, w = frame_bgr.shape[:2]
        if w != self.width or h != self.height:
            frame_bgr = cv2.resize(frame_bgr, (self.width, self.height))

        if self.backend == "pyav_x264" and self._av_container and self._av_stream:
            try:
                import av

                frame = av.VideoFrame.from_ndarray(frame_bgr, format="bgr24")
                for packet in self._av_stream.encode(frame):
                    self._av_container.mux(packet)
            except Exception as e:
                print(f"[H264VideoWriter] Frame write error: {e}")
        elif self._cv2_writer is not None:
            self._cv2_writer.write(frame_bgr)

    def release(self):
        if self._closed:
            return
        self._closed = True

        if self.backend == "pyav_x264" and self._av_container:
            try:
                if self._av_stream:
                    # Flush encoder buffers
                    for packet in self._av_stream.encode(None):
                        self._av_container.mux(packet)
                self._av_container.close()
            except Exception as e:
                print(f"[H264VideoWriter] PyAV container close error: {e}")
            finally:
                self._av_container = None
                self._av_stream = None

        if self._cv2_writer is not None:
            try:
                self._cv2_writer.release()
            except Exception as e:
                print(f"[H264VideoWriter] OpenCV writer release error: {e}")
            finally:
                self._cv2_writer = None


class ContinuousRecorder:
    def __init__(self, camera_id: str = "cam_1", fps: int = DEFAULT_FPS):
        self.camera_id = camera_id
        self.fps = fps
        self.is_recording = True
        self.current_writer: Optional[H264VideoWriter] = None
        self.current_chunk_start: float = 0.0
        self.current_chunk_path: Optional[Path] = None
        self.current_thumb_path: Optional[Path] = None
        self.frame_width: int = 1280
        self.frame_height: int = 720
        self.last_cleanup_time: float = 0.0

    def _start_new_chunk(self, frame_bgr: np.ndarray):
        """Finalize previous chunk and start a new timestamped H.264 MP4 chunk."""
        self._close_current_chunk()

        now = time.time()
        self.current_chunk_start = now
        date_folder = time.strftime("%Y-%m-%d", time.localtime(now))
        save_dir = RECORDINGS_DIR / date_folder
        save_dir.mkdir(parents=True, exist_ok=True)

        time_str = time.strftime("%Y%m%d_%H%M%S", time.localtime(now))
        filename = f"{self.camera_id}_{time_str}.mp4"
        thumb_name = f"{self.camera_id}_{time_str}.jpg"

        self.current_chunk_path = save_dir / filename
        self.current_thumb_path = save_dir / thumb_name

        h, w = frame_bgr.shape[:2]
        self.frame_width = w
        self.frame_height = h

        # Save first frame as thumbnail for timeline preview
        cv2.imwrite(str(self.current_thumb_path), frame_bgr, [int(cv2.IMWRITE_JPEG_QUALITY), 75])

        # Use H.264 encoder for browser video compatibility
        self.current_writer = H264VideoWriter(
            str(self.current_chunk_path),
            self.frame_width,
            self.frame_height,
            fps=self.fps,
        )

    def _close_current_chunk(self):
        """Release current H264VideoWriter."""
        if self.current_writer is not None:
            try:
                self.current_writer.release()
            except Exception as e:
                print(f"[Recorder] Error closing writer: {e}")
            self.current_writer = None

    def add_frame(self, frame_bgr: np.ndarray):
        """Add a frame to the 24/7 continuous recorder."""
        if not self.is_recording or frame_bgr is None:
            return

        now = time.time()

        # Check if we need to rotate chunk
        if (
            self.current_writer is None
            or (now - self.current_chunk_start) >= RECORDING_CHUNK_SECONDS
        ):
            self._start_new_chunk(frame_bgr)

        # Write frame
        if self.current_writer is not None:
            self.current_writer.write(frame_bgr)

        # Periodically clean up old storage
        if now - self.last_cleanup_time > 600:
            self.last_cleanup_time = now
            self.cleanup_storage()

    def cleanup_storage(self):
        """Enforce FIFO storage retention when exceeding MAX_STORAGE_GB."""
        try:
            total_bytes = 0
            all_files = []
            for path in RECORDINGS_DIR.rglob("*.mp4"):
                if path.is_file():
                    stat = path.stat()
                    total_bytes += stat.st_size
                    all_files.append((stat.st_mtime, path))

            total_gb = total_bytes / (1024**3)
            if total_gb > MAX_STORAGE_GB:
                # Sort oldest first
                all_files.sort(key=lambda x: x[0])
                for mtime, fpath in all_files:
                    if total_gb <= MAX_STORAGE_GB * 0.85:  # Purge down to 85% limit
                        break
                    fsize = fpath.stat().st_size
                    thumb_path = fpath.with_suffix(".jpg")
                    try:
                        fpath.unlink()
                        if thumb_path.exists():
                            thumb_path.unlink()
                        total_gb -= fsize / (1024**3)
                        print(f"[Recorder] Purged old recording: {fpath.name}")
                    except Exception as e:
                        print(f"[Recorder] Could not purge {fpath}: {e}")
        except Exception as e:
            print(f"[Recorder] Cleanup error: {e}")

    def stop(self):
        self._close_current_chunk()

    @staticmethod
    def get_timeline_data(date_str: str) -> List[Dict[str, Any]]:
        """
        Query all recorded chunks for a specific date (YYYY-MM-DD)
        for timeline playback scrubbing.
        """
        target_dir = RECORDINGS_DIR / date_str
        if not target_dir.exists():
            return []

        chunks = []
        for mp4_file in sorted(target_dir.glob("*.mp4")):
            try:
                stat = mp4_file.stat()
                filename = mp4_file.name
                thumb_file = mp4_file.with_suffix(".jpg")

                # Parse timestamp from filename: {camera_id}_YYYYMMDD_HHMMSS.mp4
                parts = filename.replace(".mp4", "").split("_")
                if len(parts) >= 3:
                    date_part = parts[-2]
                    time_part = parts[-1]
                    hour = int(time_part[:2])
                    minute = int(time_part[2:4])
                    second = int(time_part[4:6])
                    start_sec = hour * 3600 + minute * 60 + second
                else:
                    lt = time.localtime(stat.st_mtime)
                    start_sec = lt.tm_hour * 3600 + lt.tm_min * 60 + lt.tm_sec

                chunks.append({
                    "filename": filename,
                    "date": date_str,
                    "video_url": f"/api/recordings/{date_str}/{filename}",
                    "thumb_url": f"/api/recordings/{date_str}/{thumb_file.name}" if thumb_file.exists() else None,
                    "start_seconds": start_sec,
                    "duration": RECORDING_CHUNK_SECONDS,
                    "size_mb": round(stat.st_size / (1024 * 1024), 2),
                })
            except Exception as e:
                print(f"[Recorder] Error reading {mp4_file}: {e}")

        return chunks
