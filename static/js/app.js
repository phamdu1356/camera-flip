// Main Application Logic
class SecureCamApp {
  constructor() {
    this.selectedCameraId = 'cam_1';
    this.viewMode = '1x1'; // '1x1' or '2x2'
    this.zoomLevel = 1.0;
    this.isNightMode = false;
    this.isTorchOn = false;
    this.isSirenOn = false;
    this.ws = null;
    this.timeline = null;
    this.sirenAudio = null;

    this.initElements();
    this.initWebSocket();
    this.initEventListeners();
    this.loadSnapshots();
    this.loadSystemStatus();

    // Periodic refresh
    setInterval(() => this.loadSystemStatus(), 5000);
    setInterval(() => this.loadSnapshots(), 8000);
  }

  initElements() {
    this.timeline = new CameraTimeline('timelineCanvas', 'timelineCurrentTime', 'timelineDateInput');
  }

  initWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/api/ws`;

    try {
      this.ws = new WebSocket(wsUrl);

      this.ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'telemetry') {
          this.updateTelemetry(msg.cameras);
        }
      };

      this.ws.onclose = () => {
        setTimeout(() => this.initWebSocket(), 3000);
      };
    } catch (e) {
      console.warn('[WS] Could not connect to WebSocket, fallback to polling:', e);
    }
  }

  updateTelemetry(cameras) {
    if (!cameras) return;
    for (const c of cameras) {
      const statsEl = document.getElementById(`stats-${c.id}`);
      if (statsEl) {
        statsEl.textContent = `${c.fps} FPS | ${c.bitrate} kbps`;
      }
    }
  }

  initEventListeners() {
    // Multi-camera grid buttons
    const btn1x1 = document.getElementById('btnGrid1x1');
    const btn2x2 = document.getElementById('btnGrid2x2');
    const videoStage = document.getElementById('videoStage');

    if (btn1x1 && btn2x2) {
      btn1x1.addEventListener('click', () => {
        this.viewMode = '1x1';
        btn1x1.classList.add('active');
        btn2x2.classList.remove('active');
        videoStage.className = 'video-stage grid-1x1';
        this.renderVideoGrid();
      });

      btn2x2.addEventListener('click', () => {
        this.viewMode = '2x2';
        btn2x2.classList.add('active');
        btn1x1.classList.remove('active');
        videoStage.className = 'video-stage grid-2x2';
        this.renderVideoGrid();
      });
    }

    // Camera tab selector
    document.querySelectorAll('.tab-btn').forEach((btn) => {
      btn.addEventListener('click', (e) => {
        document.querySelectorAll('.tab-btn').forEach((b) => b.classList.remove('active'));
        e.target.classList.add('active');
        this.selectedCameraId = e.target.getAttribute('data-cam');
        this.renderVideoGrid();
      });
    });

    // Action buttons
    const btnSnapshot = document.getElementById('btnSnapshot');
    if (btnSnapshot) {
      btnSnapshot.addEventListener('click', () => this.takeSnapshot());
    }

    const btnTorch = document.getElementById('btnTorch');
    if (btnTorch) {
      btnTorch.addEventListener('click', () => this.toggleTorch());
    }

    const btnNight = document.getElementById('btnNight');
    if (btnNight) {
      btnNight.addEventListener('click', () => this.toggleNightMode());
    }

    const btnSiren = document.getElementById('btnSiren');
    if (btnSiren) {
      btnSiren.addEventListener('click', () => this.toggleSiren());
    }

    // PTZ buttons
    const btnZoomIn = document.getElementById('btnZoomIn');
    const btnZoomOut = document.getElementById('btnZoomOut');
    if (btnZoomIn && btnZoomOut) {
      btnZoomIn.addEventListener('click', () => this.adjustZoom(0.5));
      btnZoomOut.addEventListener('click', () => this.adjustZoom(-0.5));
    }

    // Fullscreen button
    const btnFullscreen = document.getElementById('btnFullscreen');
    if (btnFullscreen) {
      btnFullscreen.addEventListener('click', () => {
        if (!document.fullscreenElement) {
          document.documentElement.requestFullscreen().catch(() => {});
        } else {
          document.exitFullscreen().catch(() => {});
        }
      });
    }

    // Modal close
    const modalClose = document.getElementById('modalClose');
    const modalOverlay = document.getElementById('modalOverlay');
    if (modalClose && modalOverlay) {
      modalClose.addEventListener('click', () => modalOverlay.classList.remove('open'));
      modalOverlay.addEventListener('click', (e) => {
        if (e.target === modalOverlay) modalOverlay.classList.remove('open');
      });
    }
  }

  renderVideoGrid() {
    const stage = document.getElementById('videoStage');
    if (!stage) return;

    if (this.viewMode === '1x1') {
      const name = this.selectedCameraId === 'cam_1' ? 'Samsung Z Flip 3 (Cửa Chính)' : 'Camera 2 (Phòng Khách)';
      stage.innerHTML = `
        <div class="camera-feed-card selected" id="card-${this.selectedCameraId}">
          <div class="feed-header-overlay">
            <div class="cam-badge-tag">
              <span class="rec-dot"></span>
              <span>${name}</span>
            </div>
            <div class="cam-badge-tag" id="stats-${this.selectedCameraId}">
              25 FPS | HD
            </div>
          </div>
          <img class="camera-stream-img" src="/api/stream/${this.selectedCameraId}" alt="${name}" />
        </div>
      `;
    } else {
      // 2x2 View
      stage.innerHTML = `
        <div class="camera-feed-card ${this.selectedCameraId === 'cam_1' ? 'selected' : ''}" onclick="window.app.selectCamera('cam_1')">
          <div class="feed-header-overlay">
            <div class="cam-badge-tag"><span class="rec-dot"></span> CAM 1: Z Flip 3</div>
            <div class="cam-badge-tag" id="stats-cam_1">HD</div>
          </div>
          <img class="camera-stream-img" src="/api/stream/cam_1" alt="Cam 1" />
        </div>
        <div class="camera-feed-card ${this.selectedCameraId === 'cam_2' ? 'selected' : ''}" onclick="window.app.selectCamera('cam_2')">
          <div class="feed-header-overlay">
            <div class="cam-badge-tag"><span class="rec-dot"></span> CAM 2: Phòng Khách</div>
            <div class="cam-badge-tag" id="stats-cam_2">HD</div>
          </div>
          <img class="camera-stream-img" src="/api/stream/cam_2" alt="Cam 2" />
        </div>
      `;
    }
  }

  selectCamera(camId) {
    this.selectedCameraId = camId;
    document.querySelectorAll('.tab-btn').forEach((btn) => {
      if (btn.getAttribute('data-cam') === camId) {
        btn.classList.add('active');
      } else {
        btn.classList.remove('active');
      }
    });
    this.renderVideoGrid();
  }

  async takeSnapshot() {
    try {
      this.playShutterSound();
      const res = await fetch(`/api/cameras/${this.selectedCameraId}/snapshot`, { method: 'POST' });
      const data = await res.json();
      if (data.success) {
        this.showToast('📸 Đã chụp ảnh và lưu vào thư viện!');
        this.loadSnapshots();
      }
    } catch (e) {
      this.showToast('Lỗi khi chụp ảnh');
    }
  }

  async toggleTorch() {
    try {
      const res = await fetch(`/api/cameras/${this.selectedCameraId}/torch`, { method: 'POST' });
      const data = await res.json();
      this.isTorchOn = data.torch_on;
      const btn = document.getElementById('btnTorch');
      if (btn) {
        btn.classList.toggle('active', this.isTorchOn);
      }
      this.showToast(this.isTorchOn ? '🔦 Đã bật đèn Flash' : '🔦 Đã tắt đèn Flash');
    } catch (e) {
      this.showToast('Không thể điều khiển đèn Flash');
    }
  }

  async toggleNightMode() {
    try {
      const res = await fetch(`/api/cameras/${this.selectedCameraId}/night_mode`, { method: 'POST' });
      const data = await res.json();
      this.isNightMode = data.night_mode;
      const btn = document.getElementById('btnNight');
      if (btn) {
        btn.classList.toggle('active', this.isNightMode);
      }
      this.showToast(this.isNightMode ? '🌙 Chế độ hồng ngoại (Night Vision): BẬT' : '☀️ Chế độ ban ngày: BẬT');
    } catch (e) {
      this.showToast('Lỗi chuyển chế độ nhìn đêm');
    }
  }

  async adjustZoom(delta) {
    this.zoomLevel = Math.max(1.0, Math.min(4.0, this.zoomLevel + delta));
    const valEl = document.getElementById('ptzZoomVal');
    if (valEl) valEl.textContent = `${this.zoomLevel.toFixed(1)}x`;

    try {
      await fetch(`/api/cameras/${this.selectedCameraId}/ptz`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ zoom: this.zoomLevel, pan_x: 0, pan_y: 0 }),
      });
    } catch (e) {}
  }

  toggleSiren() {
    this.isSirenOn = !this.isSirenOn;
    const btn = document.getElementById('btnSiren');
    if (btn) {
      btn.classList.toggle('active', this.isSirenOn);
    }

    if (this.isSirenOn) {
      this.startSirenSound();
      this.showToast('🚨 Đang phát còi báo động xua đuổi trộm!');
    } else {
      this.stopSirenSound();
      this.showToast('Đã dừng còi báo động');
    }
  }

  startSirenSound() {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sawtooth';
    osc.frequency.setValueAtTime(800, ctx.currentTime);
    osc.frequency.exponentialRampToValueAtTime(1500, ctx.currentTime + 0.3);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();

    // Modulate pitch like a siren
    let up = true;
    this.sirenInterval = setInterval(() => {
      if (!this.isSirenOn) {
        clearInterval(this.sirenInterval);
        osc.stop();
        ctx.close();
        return;
      }
      osc.frequency.setValueAtTime(up ? 1500 : 800, ctx.currentTime);
      up = !up;
    }, 400);
  }

  stopSirenSound() {
    if (this.sirenInterval) {
      clearInterval(this.sirenInterval);
    }
  }

  playShutterSound() {
    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)();
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(1000, ctx.currentTime);
      gain.gain.setValueAtTime(0.3, ctx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.08);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start();
      osc.stop(ctx.currentTime + 0.09);
    } catch (e) {}
  }

  async loadSnapshots() {
    try {
      const res = await fetch('/api/snapshots?limit=12');
      const events = await res.json();
      const grid = document.getElementById('eventsGrid');
      const badge = document.getElementById('eventBadgeCount');

      if (badge) {
        badge.textContent = events.length;
      }

      if (!grid) return;
      if (events.length === 0) {
        grid.innerHTML = '<div style="color:#64748b; padding:20px; text-align:center;">Chưa có sự kiện chuyển động nào</div>';
        return;
      }

      grid.innerHTML = events
        .map(
          (e) => `
        <div class="event-card">
          <img class="event-thumb" src="${e.snapshot_url}" alt="Event" onclick="window.app.openMediaModal('${e.snapshot_url}', 'image', '${e.time_str}')" />
          <div class="event-info">
            <span class="event-time">${e.time_str.split(' ')[1] || e.time_str}</span>
            <span style="color:#ef4444; font-weight:700;">CHUYỂN ĐỘNG</span>
          </div>
        </div>
      `
        )
        .join('');
    } catch (e) {}
  }

  async loadSystemStatus() {
    try {
      const res = await fetch('/api/system/status');
      const data = await res.json();

      const adbEl = document.getElementById('headerAdbStatus');
      if (adbEl) {
        adbEl.textContent = data.adb_status;
        adbEl.style.color = data.adb_status.includes('Connected') ? '#10b981' : '#f59e0b';
      }

      const storageEl = document.getElementById('storageStatText');
      if (storageEl) {
        storageEl.textContent = `${data.storage_used_gb} GB / ${data.storage_max_gb} GB (${data.storage_percent}%)`;
      }
    } catch (e) {}
  }

  playRecordedVideo(chunk) {
    this.openMediaModal(chunk.video_url, 'video', `${chunk.date} ${chunk.filename}`);
  }

  openMediaModal(url, type, title) {
    const overlay = document.getElementById('modalOverlay');
    const titleEl = document.getElementById('modalTitle');
    const bodyEl = document.getElementById('modalBody');

    if (!overlay || !bodyEl) return;

    if (titleEl) titleEl.textContent = title || 'Xem Lại Video / Ảnh';

    if (type === 'video') {
      bodyEl.innerHTML = `<video class="modal-media" src="${url}" controls autoplay></video>`;
    } else {
      bodyEl.innerHTML = `<img class="modal-media" src="${url}" alt="Snapshot" />`;
    }

    overlay.classList.add('open');
  }

  showToast(msg) {
    let toast = document.getElementById('appToast');
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'appToast';
      toast.style.cssText = `
        position: fixed;
        bottom: 24px;
        left: 50%;
        transform: translateX(-50%);
        background: rgba(15, 23, 42, 0.95);
        border: 1px solid #0ea5e9;
        color: #fff;
        padding: 10px 20px;
        border-radius: 8px;
        font-weight: 600;
        font-size: 0.9rem;
        z-index: 1000;
        box-shadow: 0 10px 25px rgba(0,0,0,0.5);
        pointer-events: none;
        transition: opacity 0.3s;
      `;
      document.body.appendChild(toast);
    }
    toast.textContent = msg;
    toast.style.opacity = '1';
    clearTimeout(this.toastTimeout);
    this.toastTimeout = setTimeout(() => {
      toast.style.opacity = '0';
    }, 2500);
  }
}

// Service Worker Registration for PWA Mobile Install
if ('serviceWorker' in navigator) {
  navigator.serviceWorker
    .register('/sw.js')
    .then(() => console.log('[PWA] Service Worker Registered'))
    .catch((err) => console.log('[PWA] SW Registration Error:', err));
}

// Initialize on page load
window.addEventListener('DOMContentLoaded', () => {
  window.app = new SecureCamApp();
});
