// 24/7 Professional Timeline Scrubber
class CameraTimeline {
  constructor(canvasId, timeDisplayId, dateInputId) {
    this.canvas = document.getElementById(canvasId);
    this.ctx = this.canvas ? this.canvas.getContext('2d') : null;
    this.timeDisplay = document.getElementById(timeDisplayId);
    this.dateInput = document.getElementById(dateInputId);

    this.currentDate = new Date().toISOString().split('T')[0];
    this.currentSecond = 0;
    this.chunks = [];
    this.isDragging = false;

    if (this.dateInput) {
      this.dateInput.value = this.currentDate;
      this.dateInput.addEventListener('change', (e) => {
        this.currentDate = e.target.value;
        this.loadDayData(this.currentDate);
      });
    }

    const now = new Date();
    this.currentSecond = now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds();

    this.initCanvasEvents();
    this.loadDayData(this.currentDate);
    window.addEventListener('resize', () => this.resizeCanvas());
  }

  resizeCanvas() {
    if (!this.canvas) return;
    const rect = this.canvas.getBoundingClientRect();
    this.canvas.width = rect.width * window.devicePixelRatio;
    this.canvas.height = rect.height * window.devicePixelRatio;
    if (this.ctx) {
      this.ctx.scale(window.devicePixelRatio, window.devicePixelRatio);
    }
    this.draw();
  }

  async loadDayData(dateStr) {
    try {
      const res = await fetch(`/api/recordings/timeline?date=${dateStr}`);
      const data = await res.json();
      this.chunks = data.chunks || [];
      this.draw();
    } catch (err) {
      console.error('[Timeline] Load error:', err);
    }
  }

  initCanvasEvents() {
    if (!this.canvas) return;

    const getSecondFromEvent = (e) => {
      const rect = this.canvas.getBoundingClientRect();
      const x = Math.max(0, Math.min(rect.width, e.clientX - rect.left));
      return Math.floor((x / rect.width) * 86400);
    };

    this.canvas.addEventListener('mousedown', (e) => {
      this.isDragging = true;
      this.currentSecond = getSecondFromEvent(e);
      this.updateTimeDisplay();
      this.draw();
      this.triggerSeek(this.currentSecond);
    });

    window.addEventListener('mousemove', (e) => {
      if (this.isDragging) {
        this.currentSecond = getSecondFromEvent(e);
        this.updateTimeDisplay();
        this.draw();
      }
    });

    window.addEventListener('mouseup', () => {
      if (this.isDragging) {
        this.isDragging = false;
        this.triggerSeek(this.currentSecond);
      }
    });

    // Touch support for Mobile
    this.canvas.addEventListener('touchstart', (e) => {
      if (e.touches.length === 1) {
        this.isDragging = true;
        this.currentSecond = getSecondFromEvent(e.touches[0]);
        this.updateTimeDisplay();
        this.draw();
      }
    }, { passive: true });

    this.canvas.addEventListener('touchmove', (e) => {
      if (this.isDragging && e.touches.length === 1) {
        this.currentSecond = getSecondFromEvent(e.touches[0]);
        this.updateTimeDisplay();
        this.draw();
      }
    }, { passive: true });

    this.canvas.addEventListener('touchend', () => {
      if (this.isDragging) {
        this.isDragging = false;
        this.triggerSeek(this.currentSecond);
      }
    });

    setTimeout(() => this.resizeCanvas(), 100);
  }

  updateTimeDisplay() {
    if (!this.timeDisplay) return;
    const h = String(Math.floor(this.currentSecond / 3600)).padStart(2, '0');
    const m = String(Math.floor((this.currentSecond % 3600) / 60)).padStart(2, '0');
    const s = String(this.currentSecond % 60).padStart(2, '0');
    this.timeDisplay.textContent = `${h}:${m}:${s}`;
  }

  triggerSeek(second) {
    const match = this.chunks.find((c) => {
      return second >= c.start_seconds && second <= (c.start_seconds + c.duration);
    });

    if (match && window.app && window.app.playRecordedVideo) {
      window.app.playRecordedVideo(match);
    }
  }

  draw() {
    if (!this.ctx || !this.canvas) return;
    const rect = this.canvas.getBoundingClientRect();
    const w = rect.width;
    const h = rect.height;

    this.ctx.clearRect(0, 0, w, h);

    // Track Background
    this.ctx.fillStyle = '#0a0f18';
    this.ctx.fillRect(0, 0, w, h);

    // Center Bar Track
    const trackY = 22;
    const trackH = h - 36;
    this.ctx.fillStyle = '#162032';
    this.ctx.fillRect(0, trackY, w, trackH);

    // Continuous 24/7 Recorded Chunks (Emerald Green glow)
    this.ctx.fillStyle = '#10b981';
    for (const chunk of this.chunks) {
      const startX = (chunk.start_seconds / 86400) * w;
      const chunkW = Math.max(3, (chunk.duration / 86400) * w);
      this.ctx.fillRect(startX, trackY, chunkW, trackH);
    }

    // Hour Grid Lines & Labels (00:00 -> 24:00)
    this.ctx.font = '10px ui-monospace, monospace';
    this.ctx.fillStyle = '#64748b';
    this.ctx.textAlign = 'center';

    for (let hour = 0; hour <= 24; hour += 2) {
      const x = (hour / 24) * w;
      this.ctx.strokeStyle = '#27354a';
      this.ctx.lineWidth = 1;
      this.ctx.beginPath();
      this.ctx.moveTo(x, 0);
      this.ctx.lineTo(x, h);
      this.ctx.stroke();

      const label = `${String(hour).padStart(2, '0')}:00`;
      this.ctx.fillText(label, x, 14);
    }

    // Red Scrubber Playhead
    const cursorX = (this.currentSecond / 86400) * w;

    // Glowing Scrubber Line
    this.ctx.strokeStyle = '#f43f5e';
    this.ctx.lineWidth = 2;
    this.ctx.beginPath();
    this.ctx.moveTo(cursorX, 0);
    this.ctx.lineTo(cursorX, h);
    this.ctx.stroke();

    // Scrubber Top Handle
    this.ctx.fillStyle = '#f43f5e';
    this.ctx.beginPath();
    this.ctx.moveTo(cursorX - 6, 0);
    this.ctx.lineTo(cursorX + 6, 0);
    this.ctx.lineTo(cursorX, 10);
    this.ctx.closePath();
    this.ctx.fill();
  }
}
