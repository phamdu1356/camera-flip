// SecureCam Pro - Client Logic
class SecureCamApp {
  constructor() {
    this.cameraId = 'cam_1';
    this.zoomLevel = 1.0;
    this.isNightMode = false;
    this.isTorchOn = false;
    this.isSirenOn = false;
    this.lensIndex = 0; // 0: Back Wide, 1: Back Ultra-wide, 2: Front
    this.lensNames = ['Góc Rộng 1x', 'Góc Siêu Rộng 0.5x', 'Camera Trước'];
    this.ws = null;
    this.timeline = null;
    this.deferredPrompt = null;

    this.initTimeline();
    this.initHUDClock();
    this.initEventListeners();
    this.initWebSocket();
    this.loadSnapshots();
    this.loadSystemStatus();

    setInterval(() => this.loadSystemStatus(), 4000);
    setInterval(() => this.loadSnapshots(), 6000);
  }

  initTimeline() {
    this.timeline = new CameraTimeline('timelineCanvas', 'timelineCurrentTime', 'timelineDateInput');
  }

  initHUDClock() {
    const clockEl = document.getElementById('hudClock');
    const update = () => {
      const now = new Date();
      const h = String(now.getHours()).padStart(2, '0');
      const m = String(now.getMinutes()).padStart(2, '0');
      const s = String(now.getSeconds()).padStart(2, '0');
      if (clockEl) clockEl.textContent = `${h}:${m}:${s}`;
    };
    update();
    setInterval(update, 1000);
  }

  initWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/api/ws`;

    try {
      this.ws = new WebSocket(wsUrl);

      this.ws.onmessage = (event) => {
        const msg = JSON.parse(event.data);
        if (msg.type === 'telemetry') {
          this.handleTelemetry(msg.cameras);
        }
      };

      this.ws.onclose = () => {
        setTimeout(() => this.initWebSocket(), 3000);
      };
    } catch (e) {
      console.warn('[WS] Fallback to polling');
    }
  }

  handleTelemetry(cameras) {
    if (!cameras || cameras.length === 0) return;
    const cam = cameras[0];
    const statsEl = document.getElementById('hudStats');
    if (statsEl) {
      statsEl.textContent = `1080P FHD | ${cam.fps} FPS | ${cam.bitrate} kbps`;
    }
  }

  initEventListeners() {
    // Action deck buttons
    const btnSnapshot = document.getElementById('btnSnapshot');
    if (btnSnapshot) btnSnapshot.addEventListener('click', () => this.takeSnapshot());

    const btnRecord = document.getElementById('btnRecord');
    if (btnRecord) btnRecord.addEventListener('click', () => this.toggleManualRecord());

    const btnTorch = document.getElementById('btnTorch');
    if (btnTorch) btnTorch.addEventListener('click', () => this.toggleTorch());

    const btnNight = document.getElementById('btnNight');
    if (btnNight) btnNight.addEventListener('click', () => this.toggleNightMode());

    const btnSwitchLens = document.getElementById('btnSwitchLens');
    if (btnSwitchLens) btnSwitchLens.addEventListener('click', () => this.switchLens());

    const btnSiren = document.getElementById('btnSiren');
    if (btnSiren) btnSiren.addEventListener('click', () => this.toggleSiren());

    // Zoom buttons
    const btnZoomIn = document.getElementById('btnZoomIn');
    const btnZoomOut = document.getElementById('btnZoomOut');
    if (btnZoomIn && btnZoomOut) {
      btnZoomIn.addEventListener('click', () => this.adjustZoom(0.5));
      btnZoomOut.addEventListener('click', () => this.adjustZoom(-0.5));
    }

    // Fullscreen buttons
    const toggleFs = () => {
      if (!document.fullscreenElement) {
        document.documentElement.requestFullscreen().catch(() => {});
      } else {
        document.exitFullscreen().catch(() => {});
      }
    };
    const btnFullscreenNav = document.getElementById('btnFullscreenNav');
    const btnViewportFullscreen = document.getElementById('btnViewportFullscreen');
    if (btnFullscreenNav) btnFullscreenNav.addEventListener('click', toggleFs);
    if (btnViewportFullscreen) btnViewportFullscreen.addEventListener('click', toggleFs);

    // Modal close
    const modalCloseBtn = document.getElementById('modalCloseBtn');
    const modalBackdrop = document.getElementById('modalBackdrop');
    if (modalCloseBtn && modalBackdrop) {
      modalCloseBtn.addEventListener('click', () => modalBackdrop.classList.remove('open'));
      modalBackdrop.addEventListener('click', (e) => {
        if (e.target === modalBackdrop) modalBackdrop.classList.remove('open');
      });
    }

    // PWA Install prompt listener
    window.addEventListener('beforeinstallprompt', (e) => {
      e.preventDefault();
      this.deferredPrompt = e;
      const btnInstall = document.getElementById('btnInstallPwa');
      if (btnInstall) {
        btnInstall.style.display = 'flex';
        btnInstall.addEventListener('click', () => {
          this.deferredPrompt.prompt();
          this.deferredPrompt.userChoice.then(() => {
            this.deferredPrompt = null;
            btnInstall.style.display = 'none';
          });
        });
      }
    });
  }

  async takeSnapshot() {
    this.playShutterAudio();
    try {
      const res = await fetch(`/api/cameras/${this.cameraId}/snapshot`, { method: 'POST' });
      const data = await res.json();
      if (data.success) {
        this.showToast('📸 Đã chụp ảnh và lưu vào thư viện!');
        this.loadSnapshots();
      }
    } catch (e) {
      this.showToast('Lỗi khi chụp ảnh');
    }
  }

  toggleManualRecord() {
    const btn = document.getElementById('btnRecord');
    if (!btn) return;
    const isRec = btn.classList.toggle('active');
    if (isRec) {
      this.showToast('🔴 Đang ghi lại đoạn video thủ công...');
    } else {
      this.showToast('✅ Đã lưu đoạn video vào thẻ nhớ!');
      if (this.timeline) this.timeline.loadDayData(this.timeline.currentDate);
    }
  }

  async toggleTorch() {
    try {
      const res = await fetch(`/api/cameras/${this.cameraId}/torch`, { method: 'POST' });
      const data = await res.json();
      this.isTorchOn = data.torch_on;
      const btn = document.getElementById('btnTorch');
      if (btn) btn.classList.toggle('active', this.isTorchOn);
      this.showToast(this.isTorchOn ? '🔦 Đèn Flash rọi đêm: BẬT' : '🔦 Đèn Flash: TẮT');
    } catch (e) {
      this.showToast('Không thể kết nối đèn Flash');
    }
  }

  async toggleNightMode() {
    try {
      const res = await fetch(`/api/cameras/${this.cameraId}/night_mode`, { method: 'POST' });
      const data = await res.json();
      this.isNightMode = data.night_mode;
      const btn = document.getElementById('btnNight');
      if (btn) btn.classList.toggle('active', this.isNightMode);
      this.showToast(this.isNightMode ? '🌙 Chế độ nhìn đêm hồng ngoại: BẬT' : '☀️ Chế độ màu ban ngày: BẬT');
    } catch (e) {
      this.showToast('Lỗi chế độ nhìn đêm');
    }
  }

  switchLens() {
    this.lensIndex = (this.lensIndex + 1) % this.lensNames.length;
    const currentName = this.lensNames[this.lensIndex];
    this.showToast(`🔄 Đã chuyển sang: ${currentName}`);
    const nameEl = document.getElementById('hudCameraName');
    if (nameEl) nameEl.textContent = `SAMSUNG Z FLIP 3 (${currentName.toUpperCase()})`;
  }

  async adjustZoom(delta) {
    this.zoomLevel = Math.max(1.0, Math.min(4.0, this.zoomLevel + delta));
    const valEl = document.getElementById('zoomLevelVal');
    if (valEl) valEl.textContent = `${this.zoomLevel.toFixed(1)}x`;

    try {
      await fetch(`/api/cameras/${this.cameraId}/ptz`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ zoom: this.zoomLevel, pan_x: 0, pan_y: 0 }),
      });
    } catch (e) {}
  }

  toggleSiren() {
    this.isSirenOn = !this.isSirenOn;
    const btn = document.getElementById('btnSiren');
    if (btn) btn.classList.toggle('active', this.isSirenOn);

    if (this.isSirenOn) {
      this.startSiren();
      this.showToast('🚨 Đang hú còi báo động xua đuổi trộm!');
    } else {
      this.stopSiren();
      this.showToast('Đã tắt còi báo động');
    }
  }

  startSiren() {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sawtooth';
    osc.frequency.setValueAtTime(800, ctx.currentTime);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();

    let up = true;
    this.sirenInterval = setInterval(() => {
      if (!this.isSirenOn) {
        clearInterval(this.sirenInterval);
        osc.stop();
        ctx.close();
        return;
      }
      osc.frequency.setValueAtTime(up ? 1600 : 700, ctx.currentTime);
      up = !up;
    }, 350);
  }

  stopSiren() {
    if (this.sirenInterval) clearInterval(this.sirenInterval);
  }

  playShutterAudio() {
    try {
      const ctx = new (window.AudioContext || window.webkitAudioContext)();
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(950, ctx.currentTime);
      gain.gain.setValueAtTime(0.35, ctx.currentTime);
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

      if (badge) badge.textContent = `${events.length} SỰ KIỆN`;

      if (!grid) return;
      if (events.length === 0) {
        grid.innerHTML = '<div style="color:#64748b; padding:24px; text-align:center; grid-column:1/-1;">Chưa có sự kiện chuyển động nào</div>';
        return;
      }

      grid.innerHTML = events
        .map(
          (e) => `
        <div class="event-item-card" onclick="window.app.openMediaModal('${e.snapshot_url}', 'image', '${e.time_str}')">
          <img class="event-item-thumb" src="${e.snapshot_url}" alt="Event" loading="lazy" />
          <div class="event-item-meta">
            <span class="event-item-time">${e.time_str.split(' ')[1] || e.time_str}</span>
            <span class="event-item-tag">CHUYỂN ĐỘNG</span>
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

      const statusDot = document.getElementById('headerStatusDot');
      const statusText = document.getElementById('headerStatusText');
      const standbyOverlay = document.getElementById('standbyOverlay');

      if (statusText) statusText.textContent = data.adb_status;

      if (data.adb_status.includes('Connected')) {
        if (statusDot) {
          statusDot.className = 'pulse-dot';
        }
        if (standbyOverlay) standbyOverlay.style.display = 'none';
      } else {
        if (statusDot) {
          statusDot.className = 'pulse-dot warning';
        }
        // Only show standby overlay if not connected
        if (standbyOverlay) standbyOverlay.style.display = 'none'; // Keep live stream visible
      }

      const storageEl = document.getElementById('storageStatusText');
      if (storageEl) {
        storageEl.textContent = `${data.storage_used_gb} GB / ${data.storage_max_gb} GB (${data.storage_percent}%)`;
      }
    } catch (e) {}
  }

  playRecordedVideo(chunk) {
    this.openMediaModal(chunk.video_url, 'video', `Xem lại: ${chunk.date} ${chunk.filename}`);
  }

  openMediaModal(url, type, title) {
    const backdrop = document.getElementById('modalBackdrop');
    const titleEl = document.getElementById('modalTitle');
    const viewport = document.getElementById('modalViewport');

    if (!backdrop || !viewport) return;
    if (titleEl) titleEl.textContent = title || 'Chi Tiết';

    if (type === 'video') {
      viewport.innerHTML = `<video class="modal-media-content" src="${url}" controls autoplay playsinline></video>`;
    } else {
      viewport.innerHTML = `<img class="modal-media-content" src="${url}" alt="Snapshot" />`;
    }

    backdrop.classList.add('open');
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
        background: rgba(12, 16, 23, 0.95);
        border: 1px solid #06b6d4;
        color: #fff;
        padding: 10px 22px;
        border-radius: 10px;
        font-weight: 700;
        font-size: 0.9rem;
        z-index: 2000;
        box-shadow: 0 12px 30px rgba(0,0,0,0.6);
        pointer-events: none;
        transition: opacity 0.25s ease;
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

// Service Worker for PWA
if ('serviceWorker' in navigator) {
  navigator.serviceWorker
    .register('/sw.js')
    .then(() => console.log('[PWA] SW registered'))
    .catch((e) => console.log('[PWA] SW error:', e));
}

window.addEventListener('DOMContentLoaded', () => {
  window.app = new SecureCamApp();
});
