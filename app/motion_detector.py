import time
import os
from pathlib import Path
from typing import Optional, List, Dict, Any
import cv2
import numpy as np
from app.config import (
    SNAPSHOTS_DIR,
    MOTION_THRESHOLD,
    MIN_MOTION_AREA,
    MOTION_COOLDOWN_SECONDS,
)

class MotionDetector:
    def __init__(self):
        self.prev_frame: Optional[np.ndarray] = None
        self.last_alert_time: float = 0.0
        self.events: List[Dict[str, Any]] = []
        self.max_events = 100
        self.enabled = True
        self.sensitivity = MOTION_THRESHOLD
        self.min_area = MIN_MOTION_AREA

    def process_frame(self, frame_bgr: np.ndarray, camera_id: str = "cam_1") -> Dict[str, Any]:
        """
        Process a frame for motion detection.
        Returns dict with motion details and snapshot path if alert triggered.
        """
        if not self.enabled or frame_bgr is None:
            return {"motion_detected": False, "score": 0}

        # Convert to gray & blur to reduce high frequency noise
        gray = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2GRAY)
        gray = cv2.GaussianBlur(gray, (21, 21), 0)

        if self.prev_frame is None:
            self.prev_frame = gray
            return {"motion_detected": False, "score": 0}

        # Compute difference between current frame and previous frame
        frame_diff = cv2.absdiff(self.prev_frame, gray)
        thresh = cv2.threshold(frame_diff, self.sensitivity, 255, cv2.THRESH_BINARY)[1]
        thresh = cv2.dilate(thresh, None, iterations=2)

        contours, _ = cv2.findContours(thresh.copy(), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        motion_detected = False
        motion_area = 0
        boxes = []

        for contour in contours:
            area = cv2.contourArea(contour)
            if area > self.min_area:
                motion_detected = True
                motion_area += area
                (x, y, w, h) = cv2.boundingRect(contour)
                boxes.append({"x": int(x), "y": int(y), "w": int(w), "h": int(h)})

        # Update previous frame
        self.prev_frame = gray

        now = time.time()
        snapshot_url = None

        if motion_detected and (now - self.last_alert_time >= MOTION_COOLDOWN_SECONDS):
            self.last_alert_time = now
            timestamp_str = time.strftime("%Y%m%d_%H%M%S")
            filename = f"motion_{camera_id}_{timestamp_str}.jpg"
            filepath = SNAPSHOTS_DIR / filename

            # Draw green bounding boxes on alert snapshot
            annotated_frame = frame_bgr.copy()
            for b in boxes:
                cv2.rectangle(
                    annotated_frame,
                    (b["x"], b["y"]),
                    (b["x"] + b["w"], b["y"] + b["h"]),
                    (0, 255, 0),
                    2,
                )

            # Add timestamp overlay
            time_label = time.strftime("%Y-%m-%d %H:%M:%S")
            cv2.putText(
                annotated_frame,
                f"MOTION ALERT | {time_label}",
                (20, 40),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.8,
                (0, 0, 255),
                2,
            )

            cv2.imwrite(str(filepath), annotated_frame, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
            snapshot_url = f"/api/snapshots/{filename}"

            event = {
                "id": f"evt_{int(now * 1000)}",
                "camera_id": camera_id,
                "timestamp": now,
                "time_str": time_label,
                "snapshot_url": snapshot_url,
                "area": int(motion_area),
                "box_count": len(boxes),
            }

            self.events.insert(0, event)
            if len(self.events) > self.max_events:
                self.events.pop()

        return {
            "motion_detected": motion_detected,
            "motion_area": int(motion_area),
            "boxes": boxes,
            "snapshot_url": snapshot_url,
        }

    def get_recent_events(self, limit: int = 50) -> List[Dict[str, Any]]:
        return self.events[:limit]
