const hasDocument = typeof document !== "undefined";
const liveOnly = hasDocument && new URLSearchParams(window.location.search).get("live") === "1";
if (liveOnly) document.documentElement.classList.add("live-only");
const $ = (id) => hasDocument ? document.getElementById(id) : null;

const pairScreen = $("pair-screen");
const viewerScreen = $("viewer-screen");
const pairForm = $("pair-form");
const pairPending = $("pair-pending");
const pairError = $("pair-error");
const sessionError = $("session-error");
const liveFrame = $("live-frame");
const liveImage = $("live-image");
const livePlaceholder = $("live-placeholder");
const timelineList = $("timeline-items");
const timelineStatus = $("timeline-status");
const eventList = $("event-items");
const eventStatus = $("events-status");
const playbackPanel = $("playback-panel");
const segmentPlayer = $("segment-player");
const playbackSpeed = $("playback-speed");
let pairingAttempt = 0;
let activeController = null;
let countdownTimer = null;
let timelineController = null;
let historyPage = null;
let historyData = { segments: [], gaps: [] };
let eventsController = null;
let liveRetryTimer = null;
let liveRetryIndex = 0;
let sessionMonitorTimer = null;
let deviceStatusTimer = null;
let sessionActive = false;
let sessionGeneration = 0;
let liveRevision = 0;
const sessionChecks = new Set();
let selectedPlaybackRate = 1;
let playbackRevision = 0;
let activePlaybackSegment = null;
let nextPlaybackController = null;
let authMode = "password";

export const TIMEZONE_UTC7 = "Asia/Bangkok";
export const UTC7_OFFSET_MS = 7 * 60 * 60 * 1000;
export const PLAYBACK_SPEEDS = Object.freeze([0.5, 0.75, 1, 1.25, 1.5, 2]);
export const TAB_NAMES = Object.freeze(["live", "history", "session"]);

function switchHistorySubtab(tab) {
  const isTimeline = tab === "timeline";
  const btnTimeline = $("subtab-timeline");
  const btnEvents = $("subtab-events");
  const panelTimeline = $("timeline-panel");
  const panelEvents = $("events-panel");
  if (btnTimeline) {
    btnTimeline.classList?.toggle?.("active", isTimeline);
    btnTimeline.setAttribute?.("aria-selected", isTimeline ? "true" : "false");
  }
  if (btnEvents) {
    btnEvents.classList?.toggle?.("active", !isTimeline);
    btnEvents.setAttribute?.("aria-selected", !isTimeline ? "true" : "false");
  }
  if (panelTimeline) panelTimeline.classList?.toggle?.("mobile-active", isTimeline);
  if (panelEvents) panelEvents.classList?.toggle?.("mobile-active", !isTimeline);
}

export function switchTab(name) {
  if (!TAB_NAMES.includes(name)) return;
  if (name !== "live") {
    stopLiveAudio();
    stopTalkback();
  }
  for (const t of TAB_NAMES) {
    const btn = $(`tab-${t}`);
    const page = $(`view-${t}`);
    const isActive = t === name;
    if (btn) {
      btn.classList?.toggle?.("active", isActive);
      btn.setAttribute?.("aria-selected", isActive ? "true" : "false");
    }
    if (page) {
      page.hidden = !isActive;
      page.classList?.toggle?.("active", isActive);
    }
  }
  if (name === "history") {
    const isEvents = $("subtab-events")?.classList?.contains?.("active");
    switchHistorySubtab(isEvents ? "events" : "timeline");
  }
  if (typeof window !== "undefined" && window.location && window.history?.replaceState) {
    try {
      if (window.location.hash !== `#${name}`) {
        window.history.replaceState(null, "", `#${name}`);
      }
    } catch { /* ignore in non-browser contexts */ }
  }
}

export function historyWindow(date, hour) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{1,2}$/.test(String(hour))) return null;
  const h = Number(hour);
  if (!Number.isInteger(h) || h < 0 || h > 23) return null;
  const [year, month, day] = date.split("-").map(Number);
  if (!Number.isInteger(year) || !Number.isInteger(month) || !Number.isInteger(day)) return null;
  const utcBase = Date.UTC(year, month - 1, day, h, 0, 0, 0);
  const check = new Date(utcBase);
  if (
    check.getUTCFullYear() !== year ||
    check.getUTCMonth() !== month - 1 ||
    check.getUTCDate() !== day ||
    check.getUTCHours() !== h
  ) {
    return null;
  }
  const from = utcBase - UTC7_OFFSET_MS;
  return { from, to: from + 60 * 60 * 1000 };
}

export function getUtc7Calendar(nowMs = Date.now()) {
  const timestamp = Number(nowMs);
  const safeMs = Number.isFinite(timestamp) ? timestamp : Date.now();
  const d = new Date(safeMs + UTC7_OFFSET_MS);
  const year = d.getUTCFullYear();
  const month = d.getUTCMonth() + 1;
  const day = d.getUTCDate();
  const hour = d.getUTCHours();
  const minute = d.getUTCMinutes();
  const second = d.getUTCSeconds();
  const dateString = `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`; // taste-skill-ignore: date readout
  return { year, month, day, hour, minute, second, dateString };
}

export function boundaryClassification(item, windowRange) {
  if (!windowRange || !Number.isFinite(windowRange.from) || !Number.isFinite(windowRange.to)) {
    return "inside";
  }
  const start = Number(item?.startUtc);
  const duration = Number(item?.durationMs) || 0;
  const end = Number(item?.endUtc) || (start + duration);
  if (!Number.isFinite(start) || !Number.isFinite(end)) {
    return "inside";
  }
  if (start < windowRange.from && end > windowRange.to) {
    return "spanning";
  }
  if (start < windowRange.from && end > windowRange.from) {
    return "crossing_previous";
  }
  if (start < windowRange.to && end > windowRange.to) {
    return "crossing_next";
  }
  return "inside";
}

export function normalizePlaybackRate(value) {
  const rate = Number(value);
  return PLAYBACK_SPEEDS.includes(rate) ? rate : 1;
}

export function nextChronologicalSegment(current, segments) {
  const currentId = typeof current?.id === "string" ? current.id : current?.segmentId;
  const currentStart = Number(current?.startUtc);
  if (!isCanonicalUuid(currentId) || !Number.isSafeInteger(currentStart) || !Array.isArray(segments)) {
    return null;
  }
  const later = segments.filter((segment) => {
    if (!isCanonicalUuid(segment?.id)) return false;
    const start = Number(segment.startUtc);
    if (!Number.isSafeInteger(start)) return false;
    return start > currentStart ||
      (start === currentStart && segment.id.toLowerCase() > currentId.toLowerCase());
  });
  later.sort((left, right) => {
    const timeOrder = Number(left.startUtc) - Number(right.startUtc);
    if (timeOrder !== 0) return timeOrder;
    const leftId = left.id.toLowerCase();
    const rightId = right.id.toLowerCase();
    return leftId < rightId ? -1 : leftId > rightId ? 1 : 0;
  });
  return later[0] || null;
}

const errorText = {
  invalid_pairing_code: "Mã không đúng hoặc đã hết hạn. Tạo mã mới trên Flip rồi thử lại.",
  rate_limited: "Bạn đã thử quá nhiều lần. Chờ 5 phút rồi thử lại.",
  login_busy: "Flip đang xử lý một yêu cầu đăng nhập khác. Đợi vài giây rồi thử lại.",
  invalid_password: "Mật khẩu chưa đúng. Kiểm tra rồi thử lại.",
  password_not_configured: "Flip chưa đặt mật khẩu. Trong app trên Flip chọn Đặt / đổi mật khẩu xem một lần.",
  viewer_capacity_reached: "Flip đã đủ số thiết bị xem. Hãy thu hồi một phiên rồi thử lại.",
  pairing_rejected: "Yêu cầu bị từ chối trên Flip. Tạo mã mới nếu bạn muốn thử lại.",
  pairing_expired: "Yêu cầu đã hết hạn. Tạo mã ghép đôi mới trên Flip.",
  pairing_complete: "Yêu cầu này đã được dùng. Hãy kiểm tra phiên hoặc tạo mã mới.",
  unauthorized: "Phiên đã kết thúc. Đăng nhập lại để tiếp tục.",
};

function setConnection(state, label) {
  const badge = $("connection-state");
  badge.dataset.state = state;
  $("connection-label").textContent = label;
}

function showPair(message = "") {
  invalidateSessionChecks();
  sessionActive = false;
  stopViewerMedia();
  pairScreen.hidden = false;
  viewerScreen.hidden = true;
  pairForm.hidden = false;
  pairPending.hidden = true;
  pairError.textContent = message;
  pairError.hidden = !message;
  if (message) $("pair-code").focus();
}

function showViewer() {
  invalidateSessionChecks();
  sessionActive = true;
  pairScreen.hidden = true;
  viewerScreen.hidden = false;
  sessionError.hidden = true;
  setConnection("secure", "Phiên đã xác thực");
  const targetTab = typeof window !== "undefined" && window.location?.hash
    ? window.location.hash.replace("#", "")
    : "live";
  switchTab(TAB_NAMES.includes(targetTab) ? targetTab : "live");
  startLivePreview();
  if (!liveOnly) {
    loadTimeline();
    loadEvents();
  }
  if (sessionMonitorTimer === null) {
    sessionMonitorTimer = setInterval(checkViewerSession, 30_000);
  }
  void refreshDeviceStatus();
  if (deviceStatusTimer === null) {
    deviceStatusTimer = setInterval(() => void refreshDeviceStatus(), 2_000);
  }
}

// --- Nhiệt độ pin và đèn flash Flip3 (/api/device-status, POST /api/torch) ---
export function formatBatteryTemp(value) {
  return Number.isFinite(value) && value > 0 ? `${value.toFixed(1)}°C` : "--°C";
}

export function formatLiveFps(value) {
  return Number.isFinite(value) && value >= 0 ? `${value.toFixed(0)} fps` : "-- fps";
}

export function formatLiveRate(kbps) {
  if (!Number.isFinite(kbps) || kbps < 0) return "-- Mbps";
  return kbps >= 1000 ? `${(kbps / 1000).toFixed(1)} Mbps` : `${Math.round(kbps)} kbps`;
}

function applyDeviceStatus(status) {
  const stats = $("live-stats");
  if (stats) {
    stats.hidden = false;
    const fps = status?.liveFps == null ? NaN : Number(status.liveFps);
    const kbps = status?.liveKbps == null ? NaN : Number(status.liveKbps);
    $("live-fps-value").textContent = formatLiveFps(fps);
    $("live-net-value").textContent = formatLiveRate(kbps);
    stats.dataset.slow = Number.isFinite(fps) && fps < 15 ? "1" : "0";
  }
  const temp = $("device-temp");
  if (temp) {
    temp.hidden = false;
    const value = Number(status?.batteryTempC);
    $("device-temp-value").textContent = formatBatteryTemp(value);
    temp.dataset.hot = Number.isFinite(value) && value >= 43 ? "1" : "0";
  }
  const torch = $("toggle-torch");
  if (torch) {
    const available = status?.torchAvailable === true;
    torch.hidden = !available;
    const on = status?.torchOn === true;
    torch.setAttribute("aria-pressed", on ? "true" : "false");
    torch.setAttribute("aria-label", on ? "Tắt đèn flash trên Flip3" : "Bật đèn flash trên Flip3");
    const label = $("toggle-torch-label");
    if (label) label.textContent = on ? "Tắt flash" : "Bật flash";
  }
}

async function refreshDeviceStatus() {
  if (!sessionActive) return;
  try {
    const response = await fetch("/api/device-status", { credentials: "same-origin", cache: "no-store", redirect: "error" });
    if (response.ok) applyDeviceStatus(await response.json());
  } catch (_) { /* giữ giá trị cũ khi mạng chập chờn */ }
}

async function toggleTorch() {
  const button = $("toggle-torch");
  if (!button || button.dataset.busy === "1") return;
  button.dataset.busy = "1";
  const wantOn = button.getAttribute("aria-pressed") !== "true";
  try {
    const response = await fetch(`/api/torch?on=${wantOn ? 1 : 0}`, {
      method: "POST", credentials: "same-origin", cache: "no-store", redirect: "error",
    });
    if (response.ok) applyDeviceStatus(await response.json());
    else {
      const label = $("toggle-torch-label");
      if (label) label.textContent = "Flash lỗi";
      setTimeout(() => void refreshDeviceStatus(), 2_000);
    }
  } catch (_) {
    setTimeout(() => void refreshDeviceStatus(), 2_000);
  } finally {
    button.dataset.busy = "0";
  }
}

function messageFor(error, fallback) {
  return errorText[error] || fallback;
}

async function responseJson(response) {
  return response.json().catch(() => ({}));
}

async function postForm(path, fields, signal) {
  return fetch(path, {
    method: "POST",
    credentials: "same-origin",
    cache: "no-store",
    redirect: "error",
    headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
    body: new URLSearchParams(fields),
    signal,
  });
}

function makeController() {
  invalidateSessionChecks();
  activeController?.abort();
  activeController = new AbortController();
  return activeController;
}

function clearPlayback() {
  playbackRevision += 1;
  nextPlaybackController?.abort();
  nextPlaybackController = null;
  activePlaybackSegment = null;
  segmentPlayer.onerror = null;
  segmentPlayer.onloadedmetadata = null;
  segmentPlayer.onended = null;
  segmentPlayer.pause();
  segmentPlayer.removeAttribute("src");
  segmentPlayer.load();
  playbackPanel.hidden = true;
  $("playback-details").textContent = "";
  $("playback-status").textContent = "";
}

function stopViewerMedia() {
  liveRevision += 1;
  abortSessionChecks();
  if (liveRetryTimer !== null) clearTimeout(liveRetryTimer);
  liveRetryTimer = null;
  liveRetryIndex = 0;
  if (sessionMonitorTimer !== null) clearInterval(sessionMonitorTimer);
  sessionMonitorTimer = null;
  if (deviceStatusTimer !== null) clearInterval(deviceStatusTimer);
  deviceStatusTimer = null;
  timelineController?.abort();
  timelineController = null;
  eventsController?.abort();
  historyPage = null;
  historyData = { segments: [], gaps: [] };
  $("history-more").hidden = true;
  eventsController = null;
  liveImage.onerror = null;
  liveImage.onload = null;
  liveImage.removeAttribute("src");
  liveImage.hidden = true;
  if (livePlaceholder) livePlaceholder.hidden = false;
  clearPlayback();
  timelineList.replaceChildren();
  timelineStatus.textContent = "";
  eventList.replaceChildren();
  eventStatus.textContent = "";
}

function setLiveState(state, label, title, copy) {
  liveFrame.dataset.state = state;
  $("stage-state").textContent = label;
  $("stage-title").textContent = title;
  $("stage-copy").textContent = copy;
  livePlaceholder.hidden = state === "live";
  liveImage.hidden = state !== "live";
}

const liveRetryDelays = [1_000, 2_000, 4_000, 8_000, 12_000, 15_000];

function startLivePreview() {
  if (!sessionActive) return;
  const revision = ++liveRevision;
  abortSessionChecks();
  if (liveRetryTimer !== null) clearTimeout(liveRetryTimer);
  liveRetryTimer = null;
  liveImage.onload = () => {
    if (!sessionActive || revision !== liveRevision) return;
    liveRetryIndex = 0;
    setLiveState("live", "Đang phát trực tiếp", "Camera đang hoạt động", "Khung hình được gửi trực tiếp từ Flip qua kết nối HTTPS.");
    $("frame-metadata").textContent = "Trực tiếp · Chỉ xem · Không điều khiển camera từ trình duyệt";
    setConnection("secure", "Phiên đã xác thực");
  };
  liveImage.onerror = () => {
    if (!sessionActive || revision !== liveRevision) return;
    liveImage.hidden = true;
    const delay = liveRetryDelays[Math.min(liveRetryIndex, liveRetryDelays.length - 1)];
    liveRetryIndex = Math.min(liveRetryIndex + 1, liveRetryDelays.length - 1);
    const seconds = Math.ceil(delay / 1_000);
    setLiveState("reconnecting", "Đang nối lại", "Chưa nhận được hình ảnh", `Chưa xác định được nguyên nhân. Tự thử lại luồng hình sau ${seconds} giây.`);
    setConnection("waiting", "Đang kiểm tra phiên");
    void probeSession();
    liveRetryTimer = setTimeout(() => {
      if (sessionActive && revision === liveRevision) startLivePreview();
    }, delay);
  };
  setLiveState("connecting", "Đang kết nối", "Đang chờ khung hình", "Đang mở luồng hình qua đường kết nối HTTPS tới Flip.");
  $("frame-metadata").textContent = "Đang kết nối với camera Flip…";
  setConnection("secure", "Phiên đã xác thực");
  // The query value only bypasses browser reuse; it contains no credential or session data.
  liveImage.src = `/api/live?retry=${Date.now()}`;
}

function abortSessionChecks() {
  for (const controller of sessionChecks) controller.abort();
  sessionChecks.clear();
}

function invalidateSessionChecks() {
  sessionGeneration += 1;
  abortSessionChecks();
}

// Fetch may finish even after abort (including while response.json() is pending).
// Only the same auth generation and live attempt may consume its result/error.
async function readSessionStatus() {
  const generation = sessionGeneration;
  const revision = liveRevision;
  const controller = new AbortController();
  const current = () => !controller.signal.aborted && generation === sessionGeneration && revision === liveRevision;
  sessionChecks.add(controller);
  try {
    const response = await fetch("/api/status", {
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
      signal: controller.signal,
    });
    if (!current()) return null;
    const body = await responseJson(response);
    return current() ? { response, body, isCurrent: current } : null;
  } catch (error) {
    if (current()) throw error;
    return null;
  } finally {
    sessionChecks.delete(controller);
  }
}

async function probeSession() {
  if (!sessionActive) return;
  const generation = sessionGeneration;
  const revision = liveRevision;
  try {
    const result = await readSessionStatus();
    if (!result?.isCurrent() || !sessionActive) return;
    const { response } = result;
    if (response.status === 401) expireViewerSession();
    else if (response.ok) setConnection("secure", "Phiên đã xác thực");
    else setConnection("error", "Chưa kiểm tra được phiên");
  } catch {
    if (sessionActive && generation === sessionGeneration && revision === liveRevision) {
      setConnection("error", "Chưa kết nối được tới Flip");
    }
  }
}

function expireViewerSession() {
  if (!sessionActive) return;
  pairingAttempt += 1;
  activeController?.abort();
  setAuthMode("password");
  showPair("Phiên xem đã hết hạn hoặc bị thu hồi trên Flip. Đăng nhập lại để tiếp tục.");
  setConnection("idle", "Phiên đã kết thúc");
}

async function checkViewerSession() {
  if (!sessionActive) return;
  const generation = sessionGeneration;
  const revision = liveRevision;
  try {
    const result = await readSessionStatus();
    if (!result?.isCurrent() || !sessionActive) return;
    const { response } = result;
    if (response.status === 401) {
      expireViewerSession();
    } else if (response.ok) setConnection("secure", "Phiên đã xác thực");
    else setConnection("error", "Chưa kiểm tra được phiên");
  } catch {
    if (sessionActive && generation === sessionGeneration && revision === liveRevision) {
      setConnection("error", "Chưa kết nối được tới Flip");
    }
  }
}

export function formatTimestamp(value) {
  const timestamp = Number(value);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return "Thời gian không rõ";
  return new Intl.DateTimeFormat("vi-VN", {
    timeZone: TIMEZONE_UTC7,
    hourCycle: "h23",
    year: "numeric",
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(timestamp));
}

function formatDuration(milliseconds) {
  const seconds = Math.max(0, Math.floor(Number(milliseconds) / 1_000));
  const hours = Math.floor(seconds / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  const remainder = seconds % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}` // taste-skill-ignore: clock readout
    : `${minutes}:${String(remainder).padStart(2, "0")}`; // taste-skill-ignore: clock readout
}

function formatBytes(value) {
  const bytes = Math.max(0, Number(value) || 0);
  if (bytes < 1_000_000) return `${Math.round(bytes / 1_000)} KB`;
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}

function createTimelineEntry(item, kind, windowRange) {
  const row = document.createElement("li");
  row.className = `timeline-entry ${kind === "segment" ? "recording-entry" : "gap-entry"}`;
  const marker = document.createElement("span");
  marker.className = "timeline-marker";
  marker.setAttribute("aria-hidden", "true");

  const boundary = boundaryClassification(item, windowRange);
  let boundaryTag = "";
  if (boundary === "crossing_previous") {
    boundaryTag = " · Giao ranh giới giờ trước";
  } else if (boundary === "crossing_next") {
    boundaryTag = " · Giao ranh giới giờ sau";
  } else if (boundary === "spanning") {
    boundaryTag = " · Bao trùm ranh giới giờ";
  }

  const content = document.createElement(kind === "segment" ? "button" : "div");
  content.className = "timeline-content";
  if (kind === "segment") {
    content.type = "button";
    content.setAttribute("aria-label", `Phát bản ghi lúc ${formatTimestamp(item.startUtc)}, thời lượng ${formatDuration(item.durationMs)}${boundaryTag ? `, ${boundaryTag.slice(3)}` : ""}`);
    content.addEventListener("click", () => {
      const seekToMs = Number.isFinite(windowRange?.from)
        ? Math.max(0, windowRange.from - Number(item.startUtc))
        : 0;
      playSegment({ ...item, seekToMs });
    });
  }

  const heading = document.createElement("span");
  heading.className = "timeline-entry-title";
  heading.textContent = kind === "segment" ? "Bản ghi video" : "Khoảng gián đoạn";
  const details = document.createElement("span");
  details.className = "timeline-entry-details";
  details.textContent = kind === "segment"
    ? `${formatTimestamp(item.startUtc)} · ${formatDuration(item.durationMs)} · ${formatBytes(item.bytes)}${boundaryTag}`
    : `${formatTimestamp(item.startUtc)} · ${formatDuration(item.durationMs)} · ${String(item.reason || "Không có thêm chi tiết")}${boundaryTag}`;
  content.append(heading, details);
  row.append(marker, content);
  return row;
}

function renderTimeline(data, windowRange) {
  const segments = Array.isArray(data.segments) ? data.segments : [];
  const gaps = Array.isArray(data.gaps) ? data.gaps : [];
  const entries = [
    ...segments.map((item) => ({ ...item, kind: "segment" })),
    ...gaps.map((item) => ({ ...item, kind: "gap" })),
  ].filter((item) => Number.isFinite(Number(item.startUtc)))
    .sort((a, b) => Number(b.startUtc) - Number(a.startUtc));

  timelineList.replaceChildren(...entries.map((item) => createTimelineEntry(item, item.kind, windowRange)));
  timelineStatus.textContent = entries.length === 0
    ? "Chưa có bản ghi hoàn tất trong giờ đã chọn. Chỉ có video từ khi bạn bắt đầu ghi."
    : `${segments.length} bản ghi · ${gaps.length} khoảng gián đoạn trong giờ đã chọn (UTC+07:00)${historyPage?.cursor ? " · Còn bản ghi, chọn Xem thêm." : ""}`;
}

function mergeTimelineItems(previous, incoming) {
  // A refreshed/finalized item may reappear across page requests. Keep one row
  // per ID within each kind without changing the server's cursor or timestamps.
  return [...new Map([...previous, ...incoming].map((item) => [item.id, item])).values()];
}

async function loadTimeline({ more = false } = {}) {
  if (!sessionActive) return;
  if (more && !historyPage?.cursor) return;
  timelineController?.abort();
  timelineController = null;
  $("timeline-refresh").disabled = false;
  const range = historyWindow($("history-date").value, $("history-hour").value);
  if (!range) {
    timelineStatus.textContent = "Chọn ngày và giờ hợp lệ.";
    return;
  }
  if (!more) {
    historyPage = null;
    historyData = { segments: [], gaps: [] };
    timelineList.replaceChildren();
    $("history-more").hidden = true;
  }
  if (range.from >= Date.now()) {
    timelineStatus.textContent = "Giờ đã chọn chưa diễn ra. Hãy chọn thời gian trong quá khứ.";
    $("timeline-refresh").disabled = false;
    return;
  }
  const controller = new AbortController();
  timelineController = controller;
  $("timeline-refresh").disabled = true;
  $("history-more").disabled = true;
  timelineStatus.textContent = "Đang tải bản ghi trên Flip3…";
  const from = more ? historyPage.from : range.from;
  const to = more ? historyPage.to : Math.min(range.to, Date.now() + 30000);
  const query = new URLSearchParams({ from, to, limit: 500 });
  if (more) {
    query.set("afterStart", historyPage.cursor.startUtc);
    query.set("afterId", historyPage.cursor.id);
    query.set("afterType", historyPage.cursor.type);
  }
  try {
    const response = await fetch(`/api/timeline?${query}`, {
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
      signal: controller.signal,
    });
    const body = await responseJson(response);
    if (!sessionActive || controller.signal.aborted) return;
    if (response.status === 401) return expireViewerSession();
    if (!response.ok) throw new Error(body.error || "timeline_unavailable");
    historyPage = { from, to, range, cursor: body.nextCursor || null };
    historyData = {
      segments: mergeTimelineItems(historyData.segments, body.segments || []),
      gaps: mergeTimelineItems(historyData.gaps, body.gaps || []),
    };
    renderTimeline(historyData, range);
    $("history-more").hidden = !historyPage.cursor;
  } catch (error) {
    if (error.name !== "AbortError" && sessionActive) {
      timelineStatus.textContent = "Không tải được bản ghi. Kiểm tra kết nối rồi thử lại.";
    }
  } finally {
    if (timelineController === controller) {
      timelineController = null;
      $("timeline-refresh").disabled = false;
      $("history-more").disabled = false;
    }
  }
}

function isCanonicalUuid(value) {
  return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function safeEventThumbnailPath(value) {
  if (typeof value !== "string" || !value.startsWith("/api/events/")) return null;
  try {
    const url = new URL(value, window.location.origin);
    const match = url.pathname.match(/^\/api\/events\/([0-9a-f-]+)\/thumbnail$/i);
    if (url.origin !== window.location.origin || url.search || url.hash || !match || !isCanonicalUuid(match[1])) return null;
    return url.pathname;
  } catch {
    return null;
  }
}

function createEventThumbnailPlaceholder() {
  const placeholder = document.createElement("span");
  placeholder.className = "event-thumbnail-placeholder";
  placeholder.setAttribute("aria-hidden", "true");
  placeholder.textContent = "◉";
  return placeholder;
}

function createEventEntry(item) {
  if (!isCanonicalUuid(item.id) || !Number.isFinite(Number(item.startUtc)) || !Number.isFinite(Number(item.endUtc))) return null;
  const row = document.createElement("li");
  row.className = "event-entry motion-entry";
  const videoPath = safeVideoPath(item.videoUrl);
  const canPlay = videoPath && isCanonicalUuid(item.segmentId) &&
    item.videoUrl === `/api/segments/${item.segmentId}/video` &&
    item.segmentStartUtc !== null && item.segmentStartUtc !== undefined &&
    Number.isSafeInteger(Number(item.segmentStartUtc)) && Number(item.segmentDurationMs) > 0;
  const content = document.createElement(canPlay ? "button" : "div");
  content.className = "event-content";
  if (canPlay) {
    content.type = "button";
    content.setAttribute("aria-label", `Phát bản ghi gần lúc phát hiện chuyển động ${formatTimestamp(item.peakUtc || item.startUtc)}`);
    content.addEventListener("click", () => playEvent(item));
  }

  const thumbnailPath = safeEventThumbnailPath(item.thumbnailUrl);
  if (thumbnailPath) {
    const image = document.createElement("img");
    image.className = "event-thumbnail";
    image.alt = `Ảnh thu nhỏ lúc ${formatTimestamp(item.startUtc)}`;
    image.loading = "lazy";
    image.decoding = "async";
    image.src = thumbnailPath;
    image.addEventListener("error", () => image.replaceWith(createEventThumbnailPlaceholder()), { once: true });
    content.append(image);
  } else {
    content.append(createEventThumbnailPlaceholder());
  }

  const copy = document.createElement("span");
  copy.className = "event-copy";
  const heading = document.createElement("span");
  heading.className = "event-entry-title";
  heading.textContent = "Phát hiện chuyển động";
  const details = document.createElement("span");
  details.className = "event-entry-details";
  const start = Number(item.startUtc);
  const end = Number(item.endUtc);
  const duration = Math.max(0, end - start);
  const peak = Number(item.peakUtc);
  details.textContent = `${formatTimestamp(start)}${Number.isFinite(peak) && peak > 0 ? ` · cao điểm ${formatTimestamp(peak)}` : ""}${duration > 0 ? ` · ${formatDuration(duration)}` : ""}`;
  copy.append(heading, details);
  content.append(copy);
  row.append(content);
  return row;
}

function renderEvents(data) {
  const events = Array.isArray(data.events) ? data.events : [];
  const entries = events
    .filter((item) => item && item.kind === "MOTION")
    .sort((a, b) => Number(b.startUtc) - Number(a.startUtc))
    .map(createEventEntry)
    .filter(Boolean);
  eventList.replaceChildren(...entries);
  eventStatus.textContent = entries.length === 0
    ? "Chưa có sự kiện chuyển động trong 24 giờ gần đây."
    : `${entries.length} sự kiện chuyển động trong 24 giờ gần đây${events.length >= 200 ? " · Đã chạm giới hạn 200 mục; có thể còn sự kiện chưa hiển thị. Chưa hỗ trợ tải thêm sự kiện." : ""}`;
}

async function loadEvents() {
  if (!sessionActive) return;
  eventsController?.abort();
  const controller = new AbortController();
  eventsController = controller;
  $("events-refresh").disabled = true;
  eventStatus.textContent = "Đang tải sự kiện gần đây…";
  const to = Date.now();
  const from = to - 24 * 60 * 60 * 1_000;
  try {
    const response = await fetch(`/api/events?from=${from}&to=${to}&limit=200`, {
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
      signal: controller.signal,
    });
    const body = await responseJson(response);
    if (!sessionActive || controller.signal.aborted) return;
    if (response.status === 401) return expireViewerSession();
    if (!response.ok) throw new Error(body.error || "events_unavailable");
    renderEvents(body);
  } catch (error) {
    if (error.name !== "AbortError" && sessionActive) {
      eventStatus.textContent = "Không tải được sự kiện. Kiểm tra kết nối rồi thử lại.";
    }
  } finally {
    if (eventsController === controller) eventsController = null;
    $("events-refresh").disabled = false;
  }
}

function safeVideoPath(value) {
  if (typeof value !== "string" || !value.startsWith("/api/segments/")) return null;
  try {
    const url = new URL(value, window.location.origin);
    const match = url.pathname.match(/^\/api\/segments\/([0-9a-f-]+)\/video$/i);
    const canonicalId = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
    if (url.origin !== window.location.origin || !match || !canonicalId.test(match[1])) return null;
    return url.pathname;
  } catch {
    return null;
  }
}

function playSegment(segment, { autoPlay = false, title = "Bản ghi đã chọn" } = {}) {
  if (!sessionActive) return;
  stopLiveAudio();
  stopTalkback();
  switchTab("history");
  const revision = ++playbackRevision;
  nextPlaybackController?.abort();
  nextPlaybackController = null;
  activePlaybackSegment = {
    id: segment.id || segment.segmentId,
    startUtc: Number(segment.startUtc),
  };
  segmentPlayer.pause();
  segmentPlayer.onerror = null;
  segmentPlayer.onloadedmetadata = null;
  segmentPlayer.onended = null;
  segmentPlayer.removeAttribute("src");
  segmentPlayer.load();
  const path = safeVideoPath(segment.videoUrl);
  if (!path) {
    $("playback-status").textContent = "Không thể mở đường dẫn bản ghi này.";
    playbackPanel.hidden = false;
    return;
  }
  segmentPlayer.onerror = () => {
    if (!sessionActive || revision !== playbackRevision) return;
    $("playback-status").textContent = "Không phát được video. Kiểm tra kết nối hoặc thử lại sau.";
    void probeSession();
  };
  segmentPlayer.onended = () => {
    if (!sessionActive || revision !== playbackRevision) return;
    void advancePlayback(activePlaybackSegment, revision);
  };
  segmentPlayer.onloadedmetadata = () => {
    if (!sessionActive || revision !== playbackRevision) return;
    segmentPlayer.playbackRate = selectedPlaybackRate;
    const seekToMs = Number(segment.seekToMs);
    if (Number.isFinite(seekToMs) && seekToMs > 0) {
      const lastSeekableSecond = Number.isFinite(segmentPlayer.duration)
        ? Math.max(0, segmentPlayer.duration - 0.1)
        : seekToMs / 1_000;
      segmentPlayer.currentTime = Math.min(seekToMs / 1_000, lastSeekableSecond);
    }
    if (autoPlay) {
      const playResult = segmentPlayer.play();
      playResult?.catch(() => {
        if (revision === playbackRevision && sessionActive) {
          $("playback-status").textContent = "Đã mở bản ghi tiếp theo. Nhấn phát nếu trình duyệt yêu cầu.";
        }
      });
    }
  };
  $("playback-title").textContent = title;
  $("playback-details").textContent = `${formatTimestamp(segment.startUtc)} · ${formatDuration(segment.durationMs)} · ${segment.width || "?"} × ${segment.height || "?"} · ${formatBytes(segment.bytes)}`;
  $("playback-status").textContent = "Video được tải theo từng phần khi phát.";
  segmentPlayer.src = path;
  segmentPlayer.load();
  const downloadLink = $("download-segment");
  if (downloadLink) {
    downloadLink.href = `${path}?download=1`;
    downloadLink.hidden = false;
  }
  playbackPanel.hidden = false;
  const behavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
  playbackPanel.scrollIntoView({ behavior, block: "nearest" });
}

async function advancePlayback(current, revision, { seekToMs = 0 } = {}) {
  if (!sessionActive || revision !== playbackRevision || !current ||
      !isCanonicalUuid(current.id) || !Number.isSafeInteger(current.startUtc)) return;
  const controller = new AbortController();
  nextPlaybackController = controller;
  $("playback-status").textContent = "Đang tìm bản ghi tiếp theo…";
  try {
    const response = await fetch(
      `/api/segments/${current.id}/next`,
      {
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
        signal: controller.signal,
      },
    );
    const body = await responseJson(response);
    if (!sessionActive || revision !== playbackRevision || controller.signal.aborted) return;
    if (response.status === 401) return expireViewerSession();
    if (!response.ok) throw new Error(body.error || "timeline_unavailable");
    const next = nextChronologicalSegment(current, body.segment ? [body.segment] : []);
    if (!next) {
      $("playback-status").textContent = "Đã phát hết các bản ghi đã hoàn tất. Làm mới thư viện để xem đoạn mới.";
      return;
    }
    const seek = Number.isFinite(seekToMs) && seekToMs > 0 ? seekToMs : (next.seekToMs || 0);
    playSegment({ ...next, seekToMs: seek }, { autoPlay: true, title: "Bản ghi tiếp theo" });
  } catch (error) {
    if (error.name !== "AbortError" && sessionActive && revision === playbackRevision) {
      $("playback-status").textContent = "Không tải được bản ghi tiếp theo. Làm mới dòng thời gian rồi thử lại.";
    }
  } finally {
    if (nextPlaybackController === controller) nextPlaybackController = null;
  }
}

function playEvent(event) {
  const segmentStart = Number(event.segmentStartUtc);
  const segmentDuration = Number(event.segmentDurationMs);
  const eventTime = Number(event.peakUtc || event.startUtc);
  if (!sessionActive || !Number.isFinite(segmentStart) || !Number.isFinite(segmentDuration) ||
      segmentDuration <= 0 || !Number.isFinite(eventTime)) return;
  const seekToMs = Math.max(0, Math.min(segmentDuration, eventTime - segmentStart));
  playSegment({
    id: event.segmentId,
    videoUrl: event.videoUrl,
    startUtc: segmentStart,
    durationMs: segmentDuration,
    seekToMs,
  });
  $("playback-title").textContent = "Bản ghi tại sự kiện";
}

function closePlayback() {
  clearPlayback();
}

function wait(milliseconds, signal) {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new DOMException("Aborted", "AbortError"));
    const onAbort = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
      reject(new DOMException("Aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, milliseconds);
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

function updateCountdown(deadline) {
  const remaining = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
  $("pending-time").textContent = `${String(Math.floor(remaining / 60)).padStart(2, "0")}:${String(remaining % 60).padStart(2, "0")}`; // taste-skill-ignore: countdown readout
  return remaining;
}

async function verifySession(attempt, generation, signal) {
  const result = await readSessionStatus();
  if (!result?.isCurrent() || signal.aborted || attempt !== pairingAttempt || generation !== sessionGeneration) return;
  const { response, body } = result;
  if (response.ok && body.readOnly === true) return showViewer();
  if (response.status === 401) return showPair(messageFor(body.error, "Không nhận được phiên. Hãy ghép đôi lại."));
  showPair("Không kiểm tra được phiên. Kiểm tra kết nối tới Flip rồi thử lại.");
}

async function pollApproval(requestId, attempt, generation, deadline, signal) {
  const current = () => attempt === pairingAttempt && generation === sessionGeneration && !signal.aborted;
  while (current() && updateCountdown(deadline) > 0) {
    try {
      const response = await postForm("/api/pair/status", { requestId }, signal);
      const body = await responseJson(response);
      if (!current()) return;
      if (response.status === 202 && body.status === "pending") {
        setConnection("waiting", "Đang chờ duyệt trên Flip");
      } else if (response.ok && body.status === "approved") {
        clearInterval(countdownTimer);
        await verifySession(attempt, generation, signal);
        return;
      } else {
        const message = messageFor(body.error, "Không hoàn tất được ghép đôi. Tạo mã mới trên Flip.");
        clearInterval(countdownTimer);
        showPair(message);
        setConnection("error", "Chưa ghép đôi");
        return;
      }
    } catch (error) {
      if (error.name === "AbortError" || !current()) return;
      $("pending-copy").textContent = "Mất kết nối tạm thời. Đang thử lại; hãy giữ trang mở.";
    }
    try {
      await wait(1_250, signal);
    } catch {
      return;
    }
  }
  if (current()) {
    clearInterval(countdownTimer);
    showPair("Yêu cầu đã hết thời gian. Tạo mã mới trên Flip.");
    setConnection("error", "Yêu cầu hết hạn");
  }
}

function setAuthMode(mode) {
  authMode = mode;
  const field = $("pair-code");
  field.value = "";
  field.type = authMode === "password" ? "password" : "text";
  field.name = authMode === "password" ? "password" : "code";
  field.autocomplete = authMode === "password" ? "current-password" : "one-time-code";
  field.minLength = 8;
  field.maxLength = authMode === "password" ? 128 : 8;
  if (authMode === "code") {
    field.inputMode = "numeric";
    field.pattern = "[0-9]{8}";
  } else {
    field.removeAttribute("inputmode");
    field.removeAttribute("pattern");
  }
  $("credential-label").textContent = authMode === "password" ? "Mật khẩu xem" : "Mã ghép đôi";
  $("pair-submit").textContent = authMode === "password" ? "Đăng nhập" : "Ghép đôi thiết bị";
  $("toggle-auth").textContent = authMode === "password" ? "Dùng mã ghép đôi" : "Dùng mật khẩu";
  $("auth-copy").textContent = authMode === "password"
    ? "Nhập mật khẩu xem đã đặt trên Flip. Không cần lấy mã hoặc duyệt lại trên điện thoại."
    : "Nhập mã trong app Flip Camera, sau đó duyệt yêu cầu trên Flip.";
  $("code-help").textContent = authMode === "password"
    ? "Đặt mật khẩu một lần trong app Flip: Đặt / đổi mật khẩu xem."
    : "Mã gồm 8 chữ số và hết hạn sau 2 phút.";
  pairError.hidden = true;
  field.focus();
}

// --- Âm thanh trực tiếp (PCM s16le mono 16 kHz qua /api/live-audio) ---
let audioController = null;
let audioContext = null;

function setAudioButton(on, text, ariaLabel) {
  const button = $("toggle-audio");
  if (!button) return;
  button.setAttribute("aria-pressed", on ? "true" : "false");
  button.setAttribute("aria-label", ariaLabel || (on ? "Tắt âm thanh trực tiếp" : "Bật âm thanh trực tiếp"));
  const label = $("toggle-audio-label");
  if (label) label.textContent = text;
}

export function stopLiveAudio(text = "Bật âm thanh", ariaLabel) {
  if (audioController) {
    audioController.abort();
    audioController = null;
  }
  if (audioContext) {
    void audioContext.close().catch(() => {});
    audioContext = null;
  }
  setAudioButton(false, text, ariaLabel);
}

export async function startLiveAudio() {
  if (audioController) stopLiveAudio();
  const Ctx = (typeof window !== "undefined") && (window.AudioContext || window.webkitAudioContext);
  if (!Ctx) {
    setAudioButton(false, "Không hỗ trợ", "Trình duyệt không hỗ trợ Web Audio");
    return;
  }
  const controller = new AbortController();
  audioController = controller;
  let ctx;
  try {
    ctx = new Ctx({ sampleRate: 16_000 });
  } catch {
    ctx = new Ctx();
  }
  audioContext = ctx;
  if (ctx.state === "suspended") {
    try { await ctx.resume(); } catch { /* ignore */ }
  }
  setAudioButton(true, "Đang nối…", "Đang kết nối âm thanh micro");
  try {
    const response = await fetch("/api/live-audio", {
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
    });
    if (!response.ok || !response.body) {
      const isUnavailable = response.status === 503;
      stopLiveAudio(
        isUnavailable ? "Âm thanh tắt" : "Không nghe được",
        isUnavailable ? "Micro đang tắt trên máy. Bấm để thử lại." : "Lỗi kết nối âm thanh",
      );
      return;
    }
    setAudioButton(true, "Tắt âm thanh", "Tắt âm thanh trực tiếp");
    const reader = response.body.getReader();
    let leftover = new Uint8Array(0);
    let playAt = 0;
    while (audioController === controller) {
      const { value, done } = await reader.read();
      if (done) break;
      const merged = new Uint8Array(leftover.length + value.length);
      merged.set(leftover);
      merged.set(value, leftover.length);
      const usable = merged.length - (merged.length % 2);
      leftover = merged.slice(usable);
      if (usable === 0) continue;
      const samples = new Int16Array(merged.buffer, 0, usable / 2);
      const buffer = ctx.createBuffer(1, samples.length, 16_000);
      const channel = buffer.getChannelData(0);
      for (let i = 0; i < samples.length; i += 1) channel[i] = samples[i] / 32768;
      const source = ctx.createBufferSource();
      source.buffer = buffer;
      source.connect(ctx.destination);
      source.onended = () => {
        try { source.disconnect(); } catch { /* ignore */ }
      };
      const now = ctx.currentTime;
      // Giữ đệm ~0,3 s; nếu trễ quá 1 s thì bỏ qua để không tích lũy độ trễ.
      if (playAt < now + 0.05 || playAt > now + 1) playAt = now + 0.3;
      source.start(playAt);
      playAt += buffer.duration;
    }
  } catch (error) {
    if (error?.name !== "AbortError") {
      stopLiveAudio("Không nghe được", "Lỗi truyền âm thanh");
    }
  } finally {
    if (audioController === controller) stopLiveAudio();
  }
}

// --- Phát âm thanh tới loa Flip3 (Talkback - POST /api/speaker) ---
let talkbackStream = null;
let talkbackAudioContext = null;
let talkbackProcessor = null;
let talkbackActive = false;

function setTalkbackButton(on, text, ariaLabel) {
  const button = $("toggle-talkback");
  if (!button) return;
  button.setAttribute("aria-pressed", on ? "true" : "false");
  button.setAttribute("aria-label", ariaLabel || (on ? "Dừng nói qua loa" : "Nói qua loa Flip3"));
  const label = $("toggle-talkback-label");
  if (label) label.textContent = text;
}

export function stopTalkback(text = "Nói qua loa", ariaLabel) {
  talkbackActive = false;
  if (talkbackStream) {
    try {
      talkbackStream.getTracks().forEach((t) => t.stop());
    } catch { /* ignore */ }
    talkbackStream = null;
  }
  if (talkbackProcessor) {
    try {
      talkbackProcessor.disconnect();
    } catch { /* ignore */ }
    talkbackProcessor = null;
  }
  if (talkbackAudioContext) {
    void talkbackAudioContext.close().catch(() => {});
    talkbackAudioContext = null;
  }
  setTalkbackButton(false, text, ariaLabel);
}

export async function startTalkback() {
  if (talkbackActive) {
    stopTalkback();
    return;
  }
  if (typeof navigator === "undefined" || !navigator.mediaDevices?.getUserMedia) {
    setTalkbackButton(false, "Không hỗ trợ", "Trình duyệt không hỗ trợ thu âm micro");
    return;
  }
  const Ctx = (typeof window !== "undefined") && (window.AudioContext || window.webkitAudioContext);
  if (!Ctx) {
    setTalkbackButton(false, "Không hỗ trợ", "Trình duyệt không hỗ trợ Web Audio");
    return;
  }

  setTalkbackButton(true, "Đang kết nối…", "Đang mở micro");

  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        sampleRate: 16_000,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    });
    talkbackStream = stream;

    let ctx;
    try {
      ctx = new Ctx({ sampleRate: 16_000 });
    } catch {
      ctx = new Ctx();
    }
    talkbackAudioContext = ctx;
    if (ctx.state === "suspended") {
      try { await ctx.resume(); } catch { /* ignore */ }
    }

    const source = ctx.createMediaStreamSource(stream);
    const processor = ctx.createScriptProcessor(2048, 1, 1);
    talkbackProcessor = processor;
    talkbackActive = true;

    setTalkbackButton(true, "Đang phát loa", "Đang nói qua loa Flip3. Bấm để dừng.");

    const targetSampleRate = 16_000;
    const inputSampleRate = ctx.sampleRate || 16_000;
    let isSending = false;

    processor.onaudioprocess = (e) => {
      if (!talkbackActive) return;
      if (isSending) return;
      const inputData = e.inputBuffer.getChannelData(0);

      const sampleCount = Math.round(inputData.length * (targetSampleRate / inputSampleRate));
      const pcm16 = new Int16Array(sampleCount);
      for (let i = 0; i < sampleCount; i += 1) {
        const srcIdx = Math.min(Math.round(i * (inputSampleRate / targetSampleRate)), inputData.length - 1);
        const s = Math.max(-1, Math.min(1, inputData[srcIdx]));
        pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
      }

      isSending = true;
      fetch("/api/speaker", {
        method: "POST",
        headers: { "Content-Type": "application/octet-stream" },
        body: pcm16.buffer,
        credentials: "same-origin",
      })
        .then((res) => {
          if (!res.ok) {
            if (res.status === 503) {
              stopTalkback("Loa tắt", "Loa Flip3 đang tắt trong cài đặt");
            }
          }
        })
        .catch(() => {})
        .finally(() => {
          isSending = false;
        });
    };

    source.connect(processor);
    const muteGain = ctx.createGain();
    muteGain.gain.value = 0;
    processor.connect(muteGain);
    muteGain.connect(ctx.destination);
  } catch (err) {
    stopTalkback("Không mở được mic", "Không có quyền truy cập micro");
  }
}

if (hasDocument) {
$("toggle-auth").addEventListener("click", () => {
  setAuthMode(authMode === "password" ? "code" : "password");
});

pairForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  if ($("pair-submit").disabled) return;
  pairError.hidden = true;
  const credential = $("pair-code").value;
  const deviceName = $("viewer-name").value.trim();
  if (authMode === "code" ? !/^\d{8}$/.test(credential) : credential.length < 8 || credential.length > 128) {
    pairError.textContent = authMode === "code" ? "Nhập đủ 8 chữ số trên Flip." : "Nhập mật khẩu từ 8 đến 128 ký tự.";
    pairError.hidden = false;
    $("pair-code").focus();
    return;
  }
  if (!deviceName) {
    pairError.textContent = "Nhập tên để nhận ra thiết bị này trên Flip.";
    pairError.hidden = false;
    $("viewer-name").focus();
    return;
  }

  const attempt = ++pairingAttempt;
  const controller = makeController();
  const generation = sessionGeneration;
  const current = () => attempt === pairingAttempt && generation === sessionGeneration && !controller.signal.aborted;
  if (authMode === "password") {
    $("pair-submit").disabled = true;
    $("pair-submit").textContent = "Đang đăng nhập…";
    pairForm.setAttribute("aria-busy", "true");
    $("toggle-auth").disabled = true;
    $("pair-code").readOnly = true;
    const timeout = setTimeout(() => controller.abort(), 15_000);
    setConnection("waiting", "Đang đăng nhập");
    try {
      const response = await postForm("/api/login", { password: credential, deviceName }, controller.signal);
      const body = await responseJson(response);
      if (!current()) return;
      if (response.ok && body.status === "approved" && body.readOnly === true) {
        showViewer();
      } else {
        showPair(messageFor(body.error, "Chưa đăng nhập được. Kiểm tra kết nối rồi thử lại."));
        setConnection("error", "Chưa đăng nhập");
      }
    } catch {
      if (attempt === pairingAttempt && generation === sessionGeneration) {
        showPair("Chưa kết nối được tới Flip. Thử lại khi đường xem hoạt động.");
        setConnection("error", "Không kết nối được");
      }
    } finally {
      clearTimeout(timeout);
      if (controller === activeController) {
        $("pair-code").value = "";
        $("pair-code").readOnly = false;
        $("pair-submit").disabled = false;
        $("pair-submit").textContent = "Đăng nhập";
        pairForm.removeAttribute("aria-busy");
        $("toggle-auth").disabled = false;
      }
    }
    return;
  }
  pairForm.hidden = true;
  pairPending.hidden = false;
  $("pending-copy").textContent = "Mở ứng dụng trên Flip và cho phép yêu cầu này.";
  setConnection("waiting", "Đang gửi yêu cầu");
  const deadline = Date.now() + 120_000;
  countdownTimer = setInterval(() => updateCountdown(deadline), 250);
  updateCountdown(deadline);

  try {
    const response = await postForm("/api/pair/request", { code: credential, deviceName }, controller.signal);
    const body = await responseJson(response);
    if (!current()) return;
    if (!response.ok || body.status !== "pending" || !body.requestId) {
      clearInterval(countdownTimer);
      showPair(messageFor(body.error, "Không gửi được yêu cầu. Kiểm tra mã rồi thử lại."));
      setConnection("error", "Chưa ghép đôi");
      return;
    }
    // Keep request ID only in memory; it is never added to a URL or browser storage.
    await pollApproval(body.requestId, attempt, generation, deadline, controller.signal);
  } catch (error) {
    if (error.name === "AbortError" || !current()) return;
    clearInterval(countdownTimer);
    showPair("Không kết nối được tới Flip. Kiểm tra cùng mạng và thử lại.");
    setConnection("error", "Không kết nối được");
  }
});

$("cancel-pending").addEventListener("click", () => {
  pairingAttempt += 1;
  activeController?.abort();
  clearInterval(countdownTimer);
  $("pair-code").value = "";
  showPair("Đã dừng chờ trên thiết bị này. Yêu cầu trên Flip sẽ tự hết hạn sau tối đa 2 phút.");
  setConnection("idle", "Chưa ghép đôi");
});

async function checkSession() {
  const controller = makeController();
  const generation = sessionGeneration;
  sessionError.hidden = true;
  $("retry-status").disabled = true;
  try {
    const result = await readSessionStatus();
    if (!result?.isCurrent() || controller.signal.aborted) return;
    const { response, body } = result;
    if (response.ok && body.readOnly === true) {
      showViewer();
    } else if (response.status === 401) {
      showPair(messageFor(body.error, "Phiên đã kết thúc. Ghép đôi lại để tiếp tục."));
      setConnection("idle", "Chưa ghép đôi");
    } else {
      sessionError.textContent = "Không kiểm tra được phiên. Kết nối lại với Flip rồi thử lần nữa.";
      sessionError.hidden = false;
      setConnection("error", "Kiểm tra phiên thất bại");
    }
  } catch (error) {
    if (error.name !== "AbortError" && !controller.signal.aborted && generation === sessionGeneration) {
      sessionError.textContent = "Không kết nối được tới Flip. Kiểm tra mạng rồi thử lại.";
      sessionError.hidden = false;
      setConnection("error", "Mất kết nối");
    }
  } finally {
    $("retry-status").disabled = false;
  }
}

playbackSpeed.addEventListener("change", (event) => {
  selectedPlaybackRate = normalizePlaybackRate(event.target.value);
  event.target.value = String(selectedPlaybackRate);
  segmentPlayer.playbackRate = selectedPlaybackRate;
});

$("retry-status").addEventListener("click", checkSession);
$("retry-live").addEventListener("click", () => {
  liveRetryIndex = 0;
  startLivePreview();
});
$("timeline-refresh").addEventListener("click", loadTimeline);
$("history-more").addEventListener("click", () => loadTimeline({ more: true }));
for (const id of ["history-date", "history-hour"]) {
  $(id).addEventListener("change", () => loadTimeline());
}
const initialCalendar = getUtc7Calendar();
$("history-date").value = initialCalendar.dateString;
for (let hour = 0; hour < 24; hour++) {
  const option = document.createElement("option");
  option.value = String(hour);
  option.textContent = `${String(hour).padStart(2, "0")}:00 – ${String(hour).padStart(2, "0")}:59`; // taste-skill-ignore: hour range readout
  option.selected = hour === initialCalendar.hour;
  $("history-hour").append(option);
}
$("history-filter").addEventListener("submit", (event) => {
  event.preventDefault();
  void loadTimeline();
});
$("events-refresh").addEventListener("click", loadEvents);
$("close-playback").addEventListener("click", closePlayback);

// Tải video về: trình duyệt thường tải có tiến độ riêng; WebView của app Flip tự xử lý bằng DownloadListener.
$("download-segment")?.addEventListener("click", async (event) => {
  if (/;\s*wv\)/.test(navigator.userAgent)) return;
  event.preventDefault();
  const link = event.currentTarget;
  if (link.dataset.busy === "1") return;
  const status = $("playback-status");
  const name = (new URL(link.href).searchParams.get("name")) || "flip-video.mp4";
  link.dataset.busy = "1";
  link.setAttribute("aria-disabled", "true");
  status.textContent = "Đang tải video… 0%";
  try {
    const response = await fetch(link.href, { credentials: "same-origin", cache: "no-store" });
    if (!response.ok) throw new Error(String(response.status));
    const total = Number(response.headers.get("Content-Length")) || 0;
    const disposition = response.headers.get("Content-Disposition") || "";
    const fileName = (disposition.match(/filename="([^"]+)"/) || [])[1] || name;
    const reader = response.body.getReader();
    const chunks = [];
    let received = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      received += value.length;
      status.textContent = total
        ? `Đang tải video… ${Math.floor((received * 100) / total)}% (${formatBytes(received)} / ${formatBytes(total)})`
        : `Đang tải video… ${formatBytes(received)}`;
    }
    const url = URL.createObjectURL(new Blob(chunks, { type: "video/mp4" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
    status.textContent = `Đã tải xong: ${fileName} (${formatBytes(received)}). Xem trong thư mục Download của trình duyệt.`;
  } catch (failure) {
    status.textContent = "Tải video thất bại. Kiểm tra kết nối hoặc đăng nhập lại rồi thử lại.";
  } finally {
    link.dataset.busy = "0";
    link.removeAttribute("aria-disabled");
  }
});

$("logout").addEventListener("click", async () => {
  stopLiveAudio();
  stopTalkback();
  const controller = makeController();
  $("logout").disabled = true;
  try {
    const response = await fetch("/api/logout", {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
      signal: controller.signal,
    });
    if (controller.signal.aborted) return;
    if (response.status === 204 || response.ok) {
      setAuthMode("password");
      showPair("Đã đăng xuất khỏi thiết bị này.");
      $("pair-code").value = "";
      setConnection("idle", "Đã đăng xuất");
    } else {
      sessionError.textContent = "Không đăng xuất được. Thử lại khi kết nối với Flip ổn định.";
      sessionError.hidden = false;
    }
  } catch (error) {
    if (error.name !== "AbortError" && !controller.signal.aborted) {
      sessionError.textContent = "Không kết nối được tới Flip. Phiên sẽ hết hạn hoặc được thu hồi trên điện thoại.";
      sessionError.hidden = false;
    }
  } finally {
    $("logout").disabled = false;
  }
});

$("pair-code").addEventListener("input", (event) => {
  if (authMode === "code") event.target.value = event.target.value.replace(/\D/g, "").slice(0, 8);
});

// Live audio initialization and listeners
setAudioButton(false, "Bật âm thanh");

$("toggle-torch")?.addEventListener("click", () => void toggleTorch());

$("toggle-audio")?.addEventListener("click", () => {
  if (audioController) stopLiveAudio(); else void startLiveAudio();
});

// Talkback initialization and listeners
setTalkbackButton(false, "Nói qua loa");

$("toggle-talkback")?.addEventListener("click", () => {
  if (talkbackActive) stopTalkback(); else void startTalkback();
});

if (typeof window !== "undefined" && typeof window.addEventListener === "function") {
  window.addEventListener("pagehide", () => {
    stopLiveAudio();
    stopTalkback();
  });
}

$("toggle-fullscreen")?.addEventListener("click", () => {
  if (typeof document === "undefined") return;
  if (document.fullscreenElement) {
    document.exitFullscreen?.();
  } else {
    const target = $("live-frame") || $("viewer-screen");
    target?.requestFullscreen?.();
  }
});

$("jump-to-history")?.addEventListener("click", () => switchTab("history"));

function seekBackward(seconds = 10) {
  if (!segmentPlayer) return;
  const currentPos = Number(segmentPlayer.currentTime) || 0;
  segmentPlayer.currentTime = Math.max(0, currentPos - seconds);
}

function seekForward(seconds = 10) {
  if (!segmentPlayer) return;
  const currentPos = Number(segmentPlayer.currentTime) || 0;
  const duration = Number(segmentPlayer.duration);
  const targetPos = currentPos + seconds;

  // Nếu vị trí sau khi cộng 10s chạm hoặc vượt qua độ dài video hiện tại,
  // và đang có bản ghi active -> tự động chuyển sang video tiếp theo!
  if (activePlaybackSegment && Number.isFinite(duration) && duration > 0 && targetPos >= duration) {
    const overflowMs = Math.max(0, Math.round((targetPos - duration) * 1000));
    $("playback-status").textContent = "Đang chuyển sang video tiếp theo…";
    void advancePlayback(activePlaybackSegment, playbackRevision, { seekToMs: overflowMs });
  } else {
    segmentPlayer.currentTime = Number.isFinite(duration) && duration > 0
      ? Math.min(duration, targetPos)
      : targetPos;
  }
}

function togglePlay() {
  if (!segmentPlayer) return;
  const lbl = $("play-toggle-label");
  if (segmentPlayer.paused) {
    segmentPlayer.play?.();
    if (lbl) lbl.textContent = "Tạm dừng";
  } else {
    segmentPlayer.pause?.();
    if (lbl) lbl.textContent = "Tiếp tục phát";
  }
}

$("seek-backward")?.addEventListener("click", () => seekBackward(10));
$("seek-forward")?.addEventListener("click", () => seekForward(10));
$("playback-play-toggle")?.addEventListener("click", togglePlay);
$("next-segment-btn")?.addEventListener("click", () => {
  if (activePlaybackSegment) {
    void advancePlayback(activePlaybackSegment, playbackRevision, { seekToMs: 0 });
  }
});

segmentPlayer?.addEventListener?.("play", () => {
  const lbl = $("play-toggle-label");
  if (lbl) lbl.textContent = "Tạm dừng";
});
segmentPlayer?.addEventListener?.("pause", () => {
  const lbl = $("play-toggle-label");
  if (lbl) lbl.textContent = "Tiếp tục phát";
});

if (typeof window !== "undefined" && typeof window.addEventListener === "function") {
  window.addEventListener("keydown", (event) => {
    const target = event.target;
    const tag = target?.tagName?.toLowerCase();
    if (tag === "input" || tag === "select" || tag === "textarea" || target?.isContentEditable) return;
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    if (!playbackPanel || playbackPanel.hidden) return;

    if (event.key === "ArrowRight" || event.key === "l" || event.key === "L") {
      event.preventDefault();
      seekForward(10);
    } else if (event.key === "ArrowLeft" || event.key === "j" || event.key === "J") {
      event.preventDefault();
      seekBackward(10);
    } else if (event.key === " " || event.key === "k" || event.key === "K") {
      event.preventDefault();
      togglePlay();
    } else if (event.key === "n" || event.key === "N") {
      if (activePlaybackSegment) {
        event.preventDefault();
        void advancePlayback(activePlaybackSegment, playbackRevision, { seekToMs: 0 });
      }
    }
  });
}

$("prev-hour")?.addEventListener("click", () => {
  const select = $("history-hour");
  if (!select) return;
  const current = Number(select.value) || 0;
  select.value = String((current - 1 + 24) % 24);
  void loadTimeline();
});

$("next-hour")?.addEventListener("click", () => {
  const select = $("history-hour");
  if (!select) return;
  const current = Number(select.value) || 0;
  select.value = String((current + 1) % 24);
  void loadTimeline();
});

$("subtab-timeline")?.addEventListener("click", () => switchHistorySubtab("timeline"));
$("subtab-events")?.addEventListener("click", () => switchHistorySubtab("events"));
switchHistorySubtab("timeline");

for (const name of TAB_NAMES) {
  $(`tab-${name}`)?.addEventListener("click", () => switchTab(name));
}

if (typeof window !== "undefined" && typeof window.addEventListener === "function") {
  window.addEventListener("hashchange", () => {
    const hash = (window.location.hash || "").replace("#", "");
    if (TAB_NAMES.includes(hash)) switchTab(hash);
  });
}

async function initialize() {
  const generation = sessionGeneration;
  try {
    const result = await readSessionStatus();
    if (!result?.isCurrent()) return;
    const { response, body } = result;
    if (response.ok && body.readOnly === true) {
      showViewer();
    } else if (response.status === 401) {
      showPair();
      setConnection("idle", "Chưa đăng nhập");
      const optionsGeneration = sessionGeneration;
      try {
        const optionsResponse = await fetch("/api/auth/options", { credentials: "same-origin", cache: "no-store", redirect: "error" });
        const options = await responseJson(optionsResponse);
        if (optionsGeneration === sessionGeneration && optionsResponse.ok && options.passwordEnabled && authMode === "password") {
          $("code-help").textContent = "Không cần duyệt lại trên Flip. Quên mật khẩu: đổi trực tiếp trong app trên điện thoại.";
        }
      } catch { /* The login request will report an unavailable connection. */ }
    } else {
      showPair("Flip chưa phản hồi đúng. Bật HTTPS viewer trên điện thoại rồi tải lại trang.");
      setConnection("error", "Không tìm thấy viewer");
    }
  } catch {
    if (generation !== sessionGeneration) return;
    showPair("Không kết nối được tới Flip. Kiểm tra địa chỉ HTTPS và mạng nội bộ.");
    setConnection("error", "Không kết nối được");
  }
}

initialize();
}
