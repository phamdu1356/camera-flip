import { hydrateIcons, icon } from "./icons.js";
import { listMedia, writeMedia, deleteMedia } from "./storage.js";
import { makeArchive } from "./archive.js";
import { CameraAccess } from "./camera-access.js";

const $ = (id) => document.getElementById(id);
hydrateIcons();

let stream = null;
let recording = null;
let connecting = false;
let capturing = false;
let media = [];
let filter = "all";
let page = "live";
let toastTimer;
let desiredFps = 30;
let audioEnabled = false;
let shuttingDown = false;
let libraryReady = false;
let previewMode = "off";
let captureAbort = null;
let audioConnecting = false;
const cameraAccess = new CameraAccess();
const microphoneAccess = new CameraAccess();
const urls = new Map();
const video = $("camera-video");
const sample = $("sample");

function toast(message, error = false, action = null) {
  clearTimeout(toastTimer);
  $("toast-message").textContent = message;
  $("toast").classList.toggle("error", error);
  $("toast").hidden = false;
  $("toast-action").hidden = !action;
  $("toast-action").textContent = action?.label || "";
  $("toast-action").onclick = action
    ? () => {
        $("toast").hidden = true;
        action.run();
      }
    : null;
  toastTimer = setTimeout(
    () => {
      $("toast").hidden = true;
    },
    error ? 12000 : action ? 8000 : 4000,
  );
}
function bytes(size) {
  return size < 1024 * 1024
    ? `${Math.max(1, Math.round(size / 1024))} KB`
    : `${(size / 1024 / 1024).toFixed(1)} MB`;
}
function timestamp(ms) {
  return new Date(ms).toLocaleString("vi-VN", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}
function filename(type, demo, ext) {
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  return `flip-${demo ? "mau-" : ""}${type === "photo" ? "anh" : "video"}-${stamp}.${ext}`;
}
function getURL(item) {
  if (!urls.has(item.id)) urls.set(item.id, URL.createObjectURL(item.blob));
  return urls.get(item.id);
}
function setAudio(enabled) {
  audioEnabled = enabled;
  $("toggle-audio").setAttribute("aria-pressed", String(enabled));
  $("audio-icon").innerHTML = icon(enabled ? "mic" : "mic-off");
  $("audio-label").textContent = `Thu âm: ${enabled ? "bật" : "tắt"}`;
  $("connect-audio").checked = enabled;
}
function updateDisplay() {
  const brightness = Number($("brightness").value);
  $("brightness-value").value = `${brightness}%`;
  $("brightness").style.background =
    `linear-gradient(to right,var(--sage) ${brightness - 50}%,#414a3d ${brightness - 50}%)`;
  $("media-stage").style.filter = `brightness(${brightness / 100})`;
  $("media-stage").style.transform = $("mirror").checked ? "scaleX(-1)" : "";
  $("grid-overlay").hidden = !$("grid").checked;

  try {
    localStorage.setItem(
      "flip-display",
      JSON.stringify({
        brightness,
        mirror: $("mirror").checked,
        grid: $("grid").checked,
        delay: $("delay").value,
      }),
    );
  } catch {
    /* Camera remains usable if browser storage is unavailable. */
  }
}
try {
  const display = JSON.parse(localStorage.getItem("flip-display") || "{}");
  if (Number.isFinite(display.brightness))
    $("brightness").value = Math.max(50, Math.min(150, display.brightness));
  $("mirror").checked = display.mirror === true;
  $("grid").checked = display.grid === true;
  if (["0", "3", "5", "10"].includes(display.delay))
    $("delay").value = display.delay;
} catch {
  /* Use defaults if settings were cleared or malformed. */
}
updateDisplay();

function route() {
  const requested = location.hash.slice(1) || "live";
  page = ["live", "library", "settings", "help"].includes(requested)
    ? requested
    : "live";
  document.querySelectorAll(".page").forEach((element) => {
    element.hidden = element.id !== `page-${page}`;
  });
  document.querySelectorAll(".nav-link").forEach((link) => {
    link.classList.toggle("active", link.dataset.page === page);
    if (link.dataset.page === page) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });
  $("breadcrumb-page").textContent = {
    live: "Camera",
    library: "Thư viện",
    settings: "Cài đặt",
    help: "Hướng dẫn",
  }[page];
  document.title = recording
    ? "● Đang quay · Flip Studio"
    : `${$("breadcrumb-page").textContent} · Flip Studio`;
  if (page === "library") renderLibrary();
}
window.addEventListener("hashchange", route);
route();

function syncCameraUI() {
  const live = !!stream;
  const demo = !live && previewMode === "demo" && !connecting;
  const busy = connecting || shuttingDown || capturing || audioConnecting;
  const saving = !!recording?.stopping;
  const phase = saving
    ? "saving"
    : recording
      ? "recording"
      : connecting
        ? "connecting"
        : live
          ? "live"
          : demo
            ? "demo"
            : "off";
  $("viewfinder").dataset.state = phase;
  sample.hidden = !demo;
  $("demo-notice").hidden = !demo || connecting;
  video.hidden = !live;
  $("sample-caption").hidden = live || demo;
  $("preview-badge").innerHTML =
    `<span></span>${{ off: "Chưa bật", demo: "Ảnh mẫu · Không trực tiếp", connecting: "Đang kết nối", live: "Trực tiếp", recording: "Đang quay", saving: "Đang lưu video" }[phase]}`;
  $("preview-badge").classList.toggle("live", live);
  $("source").value = live ? "local" : demo ? "demo" : "off";
  $("source").disabled = busy || !!recording;
  $("source-state").textContent = live
    ? "Đang bật"
    : demo
      ? "Ảnh mẫu"
      : "Chưa bật";
  $("device-indicator").classList.toggle("connected", live);
  $("sidebar-source-status").textContent = live
    ? "Đang hoạt động"
    : connecting
      ? "Đang kết nối"
      : "Chưa bật";
  $("stop-camera").hidden = !live;
  $("stop-camera").disabled = shuttingDown;
  $("connect").disabled = busy || !!recording;
  $("connect-label").textContent = live ? "Đổi camera" : "Chọn thiết bị";
  $("resolution").disabled = live || connecting;
  document.querySelectorAll("[data-fps]").forEach((button) => {
    button.disabled = live || connecting;
  });
  $("quality-hint").textContent = live
    ? "Tắt camera để thay đổi chất lượng."
    : "Áp dụng khi bật camera. Tùy khả năng thiết bị.";
  $("take-photo").disabled =
    !libraryReady || busy || saving || (!live && !demo);
  $("photo-label").textContent = capturing
    ? "Đang chụp…"
    : demo
      ? "Chụp ảnh mẫu"
      : "Chụp ảnh";
  $("record").disabled =
    !libraryReady ||
    busy ||
    saving ||
    !live ||
    typeof MediaRecorder === "undefined";
  $("record-label").textContent = saving
    ? "Đang lưu…"
    : recording
      ? "Dừng & lưu"
      : "Quay video";
  $("toggle-audio").disabled =
    connecting || shuttingDown || audioConnecting || !!recording;
  $("toggle-audio").title = live
    ? "Bật hoặc tắt thu âm cho video"
    : "Chọn thu âm cho lần bật camera tiếp theo";
  $("try-demo").disabled = connecting;
  $("sample-capture").disabled = busy || !!recording;
  $("sample-capture").hidden = live || demo;
  $("start-in-preview").hidden = connecting;
  $("cancel-connection").hidden = !connecting;
  $("preview-title").textContent = connecting
    ? "Đang chờ quyền camera…"
    : "Camera chưa bật";
  $("preview-description").textContent = connecting
    ? "Chọn Cho phép trong thông báo của trình duyệt. Bạn có thể hủy nếu chưa muốn bật."
    : "Bật camera và cấp quyền truy cập khi trình duyệt yêu cầu.";
  $("camera-status").textContent = {
    off: "Bật camera để chụp ảnh hoặc quay video.",
    demo: "Bạn đang xem ảnh mẫu. Bật camera máy tính để quay video.",
    connecting:
      "Đang kết nối camera. Hãy kiểm tra thông báo cấp quyền của trình duyệt.",
    live: "Camera sẵn sàng. Chọn chụp ảnh hoặc quay video.",
    recording: "Đang quay video. Nhấn Dừng & lưu để hoàn tất.",
    saving: "Đang lưu video vào thư viện. Vui lòng giữ trang mở.",
  }[phase];
  $("capture-hint").textContent = !libraryReady
    ? "Đang mở thư viện…"
    : capturing
      ? "Đang chụp ảnh. Bạn có thể hủy khi đang đếm ngược."
      : saving
        ? "Đang lưu video, vui lòng chờ."
        : recording
          ? "Video chỉ được lưu khi bạn dừng quay. Nút dừng luôn có ở thanh trên."
          : live
            ? "Ảnh và video tự lưu vào thư viện. Tải xuống để giữ bản riêng."
            : demo
              ? "Chụp ảnh mẫu để thử thư viện. Quay video cần camera thật."
              : "Bật camera để sử dụng nút chụp và quay.";
  if (audioConnecting) {
    $("camera-status").textContent =
      "Đang chờ quyền micro. Chọn Cho phép trong thông báo của trình duyệt.";
    $("capture-hint").textContent =
      "Đang kết nối micro. Tắt camera để hủy nếu bạn không muốn thu âm.";
  }
  $("global-recording").hidden = !recording;
  $("global-stop").disabled = saving;
  $("global-stop").textContent = saving ? "Đang lưu…" : "Dừng & lưu";
  $("view-clock").hidden = !live;
  $("fullscreen").disabled = !live && !demo;
  if (live) {
    const track = stream.getVideoTracks()[0];
    const settings = track.getSettings();
    $("view-source").replaceChildren();
    const sourceIcon = document.createElement("span");
    sourceIcon.innerHTML = icon("monitor");
    $("view-source").append(
      sourceIcon,
      document.createTextNode(track.label || "Camera máy tính"),
    );
    $("view-details").textContent =
      `${settings.width || "?"} × ${settings.height || "?"}${settings.frameRate ? ` · ${Math.round(settings.frameRate)} fps` : ""} · ${audioEnabled ? "Có thu âm" : "Không thu âm"}`;
    $("frame-size").textContent =
      `${settings.width || "?"} × ${settings.height || "?"}`;
  } else {
    $("view-source").innerHTML =
      `${icon(demo ? "image" : "monitor")}${demo ? "Ảnh mẫu văn phòng" : "Camera máy tính"}`;
    $("view-details").textContent = demo
      ? "Ảnh tĩnh để thử thao tác, không phải hình trực tiếp"
      : "Camera và micro chưa hoạt động";
    $("frame-size").textContent = demo ? "Ảnh tĩnh" : "";
    $("frame-size").hidden = !demo;
  }
  if (live) $("frame-size").hidden = false;
  document.title = recording
    ? "● Đang quay · Flip Studio"
    : `${$("breadcrumb-page").textContent} · Flip Studio`;
}
async function enumerateCameras() {
  if (!navigator.mediaDevices?.enumerateDevices) return;
  const selected = $("device-list").value;
  const devices = await navigator.mediaDevices.enumerateDevices();
  $("device-list").replaceChildren(new Option("Camera mặc định", ""));
  devices
    .filter((device) => device.kind === "videoinput")
    .forEach((device, index) => {
      $("device-list").add(
        new Option(device.label || `Camera ${index + 1}`, device.deviceId),
      );
    });
  if ([...$("device-list").options].some((option) => option.value === selected))
    $("device-list").value = selected;
}
async function connectDialog() {
  if (recording || shuttingDown || audioConnecting)
    return toast("Dừng quay và chờ lưu video trước khi đổi camera.");
  if (capturing) return toast("Chờ chụp ảnh xong trước khi đổi camera.");
  $("connect-error").hidden = true;
  $("connect-audio").checked = audioEnabled;
  $("connect-dialog").showModal();
  try {
    await enumerateCameras();
  } catch {
    /* Default source is still available. */
  }
}
function cameraError(error) {
  return (
    {
      NotAllowedError:
        "Chưa được cấp quyền camera hoặc micro. Cho phép trong thanh địa chỉ rồi thử lại.",
      NotFoundError:
        "Không tìm thấy camera hoặc micro. Kiểm tra thiết bị, hoặc tắt thu âm rồi thử lại.",
      NotReadableError:
        "Không đọc được camera. Đóng ứng dụng khác đang sử dụng camera rồi thử lại.",
      OverconstrainedError:
        "Camera không hỗ trợ lựa chọn này. Chọn camera mặc định hoặc giảm độ phân giải.",
      AbortError: "Camera bị gián đoạn. Kiểm tra kết nối rồi thử bật lại.",
    }[error.name] ||
    error.message ||
    "Không mở được camera. Kiểm tra thiết bị rồi thử lại."
  );
}
function showCameraError(message) {
  $("camera-error").textContent = message;
  $("camera-notice").hidden = false;
  if ($("connect-dialog").open) {
    $("connect-error").textContent = message;
    $("connect-error").hidden = false;
  }
}
function cancelConnection() {
  cameraAccess.cancel();
  connecting = false;
  if (video.srcObject && video.srcObject !== stream) {
    video.srcObject.getTracks().forEach((track) => track.stop());
    video.srcObject = null;
  }
  $("connect-start").disabled = false;
  $("connect-start").innerHTML = `${icon("power")}Bật camera`;
  $("use-demo").disabled = false;
  syncCameraUI();
}
async function startCamera({ fromDialog = false } = {}) {
  if (connecting || recording || capturing || shuttingDown || audioConnecting)
    return;
  connecting = true;
  const request = cameraAccess.begin();
  $("camera-notice").hidden = true;
  $("connect-start").disabled = true;
  $("connect-start").textContent = "Đang mở camera…";
  $("connect-error").hidden = true;
  $("use-demo").disabled = true;
  syncCameraUI();
  let next = null;
  try {
    if (!navigator.mediaDevices?.getUserMedia)
      throw new Error("Camera cần Firefox/Chromium và địa chỉ localhost.");
    stream?.getTracks().forEach((track) => track.stop());
    stream = null;
    video.srcObject = null;
    syncCameraUI();
    const height = Number($("resolution").value);
    const deviceId = $("device-list").value;
    const wantAudio = fromDialog ? $("connect-audio").checked : audioEnabled;
    next = await request.open(() =>
      navigator.mediaDevices.getUserMedia({
        video: {
          height: { ideal: height },
          width: { ideal: Math.round((height * 16) / 9) },
          frameRate: { ideal: desiredFps },
          ...(deviceId ? { deviceId: { exact: deviceId } } : {}),
        },
        audio: wantAudio,
      }),
    );
    if (!next) return;
    video.srcObject = next;
    await video.play();
    if (!request.current()) {
      next.getTracks().forEach((track) => track.stop());
      if (video.srcObject === next) video.srcObject = null;
      return;
    }
    stream = next;
    previewMode = "off";
    setAudio(stream.getAudioTracks().length > 0);
    stream.getVideoTracks()[0].addEventListener(
      "ended",
      () => {
        stopCamera(false).then(() =>
          showCameraError(
            "Camera đã ngắt kết nối. Kiểm tra dây hoặc thiết bị rồi nhấn Thử lại.",
          ),
        );
      },
      { once: true },
    );
    connecting = false;
    if ($("connect-dialog").open) $("connect-dialog").close();
    toast("Camera đã bật. Bạn có thể chụp và quay ngay.");
  } catch (error) {
    next?.getTracks().forEach((track) => track.stop());
    if (request.current()) {
      stream = null;
      video.srcObject = null;
      showCameraError(cameraError(error));
    }
  } finally {
    if (request.current()) {
      connecting = false;
      $("connect-start").disabled = false;
      $("connect-start").innerHTML = `${icon("power")}Bật camera`;
      $("use-demo").disabled = false;
      syncCameraUI();
    }
  }
}
async function useDemo() {
  if (recording || capturing || shuttingDown || audioConnecting) return;
  cancelConnection();
  await stopCamera(false);
  previewMode = "demo";
  $("camera-notice").hidden = true;
  if ($("connect-dialog").open) $("connect-dialog").close();
  syncCameraUI();
}
async function stopCamera(notify = true) {
  if (shuttingDown) return;
  captureAbort?.abort();
  microphoneAccess.cancel();
  audioConnecting = false;
  shuttingDown = true;
  $("stop-camera").disabled = true;
  try {
    await stopRecording();
    stream?.getTracks().forEach((track) => track.stop());
    stream = null;
    previewMode = "off";
    video.srcObject = null;
    if (notify) toast("Đã tắt camera và micro.");
  } finally {
    shuttingDown = false;
    syncCameraUI();
  }
}

function mediaCard(item) {
  const card = document.createElement("article");
  card.className = "media-card";
  const button = document.createElement("button");
  button.setAttribute("aria-label", `Mở ${item.name}`);
  const thumbnail = document.createElement("div");
  thumbnail.className = "media-thumbnail";
  const preview = document.createElement(
    item.type === "photo" ? "img" : "video",
  );
  preview.src = getURL(item);
  if (item.type === "photo")
    preview.alt = item.demo ? "Ảnh mẫu đã chụp" : "Ảnh chụp camera";
  else {
    preview.preload = "metadata";
    preview.muted = true;
    preview.playsInline = true;
  }
  const tag = document.createElement("span");
  tag.textContent = item.demo
    ? "Ảnh mẫu"
    : item.type === "photo"
      ? "Ảnh"
      : "Video";
  thumbnail.append(preview, tag);
  if (item.type === "video") {
    const play = document.createElement("div");
    play.className = "play-overlay";
    play.innerHTML = icon("play");
    thumbnail.append(play);
  }
  const meta = document.createElement("div");
  meta.className = "media-card-info";
  const title = document.createElement("strong");
  title.textContent = `${item.demo ? "Ảnh mẫu" : item.type === "photo" ? "Ảnh chụp" : "Video"} · ${timestamp(item.createdAt)}`;
  const detail = document.createElement("small");
  detail.textContent = `${bytes(item.blob.size)}${item.sessionOnly ? " · Chưa lưu bền" : " · Đã lưu trong trình duyệt"}`;
  meta.append(title, detail);
  button.append(thumbnail, meta);
  card.append(button);
  button.addEventListener("click", () => showMedia(item));
  return card;
}
function renderLibrary() {
  const query = $("library-search").value.trim().toLocaleLowerCase("vi");
  const matching = media.filter(
    (item) =>
      (filter === "all" || item.type === filter) &&
      item.name.toLocaleLowerCase("vi").includes(query),
  );
  $("library-grid").replaceChildren(...matching.map(mediaCard));
  $("empty-library").hidden = matching.length > 0;
  $("library-summary").textContent = media.length
    ? `${matching.length} / ${media.length} tệp`
    : "";
  $("clear-filters").hidden = media.length === 0;
  $("empty-camera-link").hidden = media.length > 0;
  $("empty-library").querySelector("h2").textContent = media.length
    ? "Không tìm thấy tệp phù hợp"
    : "Chưa có khoảnh khắc nào";
  $("empty-library").querySelector("p").textContent = media.length
    ? "Thử đổi bộ lọc hoặc từ khóa tìm kiếm."
    : "Ảnh và video bạn tạo sẽ xuất hiện ở đây.";
}
function renderMedia() {
  $("library-count").textContent = media.length;
  $("recent-count").textContent = media.length;
  $("recent-grid").replaceChildren(...media.slice(0, 3).map(mediaCard));
  $("empty-recent").hidden = media.length > 0;
  $("stored-files").textContent = media.length;
  $("stored-size").textContent = media.length
    ? bytes(media.reduce((total, item) => total + item.blob.size, 0))
    : "0 MB";
  $("export-all").disabled = media.length === 0;
  renderLibrary();
}
function download(item) {
  const link = document.createElement("a");
  link.href = getURL(item);
  link.download = item.name;
  document.body.append(link);
  link.click();
  link.remove();
}
function showMedia(item) {
  const detail = $("media-detail");
  detail.replaceChildren();
  const preview = document.createElement(
    item.type === "photo" ? "img" : "video",
  );
  preview.src = getURL(item);
  preview.className = "media-detail-preview";
  if (item.type === "video") {
    preview.controls = true;
    preview.playsInline = true;
  } else preview.alt = item.demo ? "Ảnh mẫu đã chụp" : "Ảnh chụp camera";
  const footer = document.createElement("div");
  footer.className = "media-detail-footer";
  const meta = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = item.name;
  const description = document.createElement("p");
  description.textContent = `${timestamp(item.createdAt)} · ${bytes(item.blob.size)}${item.demo ? " · Ảnh mẫu" : ""}${item.sessionOnly ? " · Chưa lưu bền: hãy tải xuống" : ""}`;
  meta.append(title, description);
  const actions = document.createElement("div");
  actions.className = "media-detail-actions";
  const remove = document.createElement("button");
  remove.className = "button danger-button";
  remove.innerHTML = `${icon("trash")}Xóa`;
  let confirmDelete = false;
  remove.onclick = async () => {
    if (!confirmDelete) {
      confirmDelete = true;
      remove.textContent = "Xác nhận xóa?";
      return;
    }
    remove.disabled = true;
    try {
      if (!item.sessionOnly) await deleteMedia(item.id);
      $("media-dialog").close();
      media = media.filter((entry) => entry.id !== item.id);
      if (urls.has(item.id)) {
        URL.revokeObjectURL(urls.get(item.id));
        urls.delete(item.id);
      }
      renderMedia();
      toast("Đã xóa tệp khỏi thư viện.");
    } catch {
      remove.disabled = false;
      toast("Không xóa được tệp. Hãy thử lại.", true);
    }
  };
  const save = document.createElement("button");
  save.className = "button primary";
  save.innerHTML = `${icon("download")}Tải xuống`;
  save.onclick = () => download(item);
  actions.append(remove, save);
  footer.append(meta, actions);
  detail.append(preview, footer);
  $("media-dialog").showModal();
}
$("media-dialog").addEventListener("close", () => {
  $("media-detail").querySelector("video")?.pause();
});
async function saveCapture(blob, type, demo = false, ext = "png") {
  const item = {
    id: crypto.randomUUID(),
    createdAt: Date.now(),
    name: filename(type, demo, ext),
    type,
    demo,
    blob,
  };
  try {
    await writeMedia(item);
  } catch {
    item.sessionOnly = true;
    toast(
      "Bộ nhớ trình duyệt không khả dụng hoặc đã đầy. Hãy mở tệp vừa tạo và tải xuống trước khi đóng trang.",
      true,
      { label: "Tải xuống", run: () => download(item) },
    );
  }
  media.unshift(item);
  renderMedia();
  if (!item.sessionOnly)
    toast(
      `${type === "photo" ? (demo ? "Đã chụp ảnh mẫu" : "Đã chụp ảnh") : "Đã lưu video"} vào thư viện.`,
      false,
      {
        label: type === "photo" ? "Xem ảnh" : "Xem video",
        run: () => showMedia(item),
      },
    );
}
async function takePhoto() {
  if (
    capturing ||
    !libraryReady ||
    connecting ||
    shuttingDown ||
    recording?.stopping ||
    audioConnecting ||
    (!stream && previewMode !== "demo")
  )
    return;
  capturing = true;
  captureAbort = new AbortController();
  const signal = captureAbort.signal;
  syncCameraUI();
  const originalStream = stream;
  try {
    const delay = Number($("delay").value);
    for (let remaining = delay; remaining > 0; remaining--) {
      $("countdown-number").textContent = remaining;
      $("countdown-overlay").hidden = false;
      await new Promise((resolve, reject) => {
        const cancel = () => {
          clearTimeout(timer);
          reject(new DOMException("Đã hủy chụp", "AbortError"));
        };
        const timer = setTimeout(() => {
          signal.removeEventListener("abort", cancel);
          resolve();
        }, 1000);
        signal.addEventListener("abort", cancel, { once: true });
        if (signal.aborted) cancel();
      });
    }
    $("countdown-overlay").hidden = true;
    if (originalStream !== stream)
      throw new Error("Camera đã thay đổi. Vui lòng chụp lại.");
    if (signal.aborted) throw new DOMException("Đã hủy chụp", "AbortError");
    const demo = !stream,
      source = demo ? sample : video;
    if (demo) await sample.decode();
    const canvas = document.createElement("canvas");
    canvas.width = demo ? sample.naturalWidth : video.videoWidth;
    canvas.height = demo ? sample.naturalHeight : video.videoHeight;
    if (!canvas.width || !canvas.height)
      throw new Error("Chưa có hình ảnh. Chờ camera sẵn sàng rồi thử lại.");
    const context = canvas.getContext("2d");
    // Match the preview's sample treatment and display adjustments in stills.
    context.filter = demo
      ? `brightness(${(Number($("brightness").value) / 100) * 0.76}) saturate(.65)`
      : `brightness(${Number($("brightness").value) / 100})`;
    if ($("mirror").checked) {
      context.translate(canvas.width, 0);
      context.scale(-1, 1);
    }
    context.drawImage(source, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise((resolve) =>
      canvas.toBlob(resolve, "image/png"),
    );
    if (signal.aborted) throw new DOMException("Đã hủy chụp", "AbortError");
    if (!blob) throw new Error("Không tạo được ảnh. Thử chụp lại.");
    $("shutter-flash").classList.remove("flash");
    void $("shutter-flash").offsetWidth;
    $("shutter-flash").classList.add("flash");
    await saveCapture(blob, "photo", demo);
  } catch (error) {
    if (error.name === "AbortError") toast("Đã hủy chụp ảnh.");
    else toast(error.message, true);
  } finally {
    capturing = false;
    captureAbort = null;
    $("countdown-overlay").hidden = true;
    syncCameraUI();
  }
}
function updateRecordTime(startedAt) {
  const elapsed = Math.floor((Date.now() - startedAt) / 1000);
  $("record-time").textContent =
    `${String(Math.floor(elapsed / 60)).padStart(2, "0")}:${String(elapsed % 60).padStart(2, "0")}`;
  $("global-record-time").textContent = $("record-time").textContent;
}
function startRecording() {
  if (
    recording ||
    capturing ||
    audioConnecting ||
    !libraryReady ||
    shuttingDown ||
    connecting
  )
    return;
  if (!stream) return toast("Bật camera máy tính trước khi quay video.");
  if (!window.MediaRecorder)
    return toast(
      "Trình duyệt chưa hỗ trợ quay video. Thử Firefox hoặc Chromium mới hơn.",
      true,
    );
  try {
    const mimeType = [
      "video/webm;codecs=vp9,opus",
      "video/webm;codecs=vp8,opus",
      "video/webm",
      "video/mp4",
    ].find((type) => MediaRecorder.isTypeSupported(type));
    const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});
    const chunks = [];
    let resolveDone;
    const done = new Promise((resolve) => {
      resolveDone = resolve;
    });
    const session = { recorder, done, interval: null, stopping: false };
    recorder.ondataavailable = (event) => {
      if (event.data.size) chunks.push(event.data);
    };
    recorder.onerror = () => {
      toast("Ghi video bị gián đoạn. Đang lưu phần đã ghi được.", true);
      stopRecording();
    };
    recorder.onstop = async () => {
      clearInterval(session.interval);
      session.stopping = true;
      syncCameraUI();
      $("recording-indicator").hidden = true;
      $("record-label").textContent = "Đang lưu…";
      $("record").disabled = true;
      try {
        if (chunks.length) {
          const type = recorder.mimeType || chunks[0].type || "video/webm";
          await saveCapture(
            new Blob(chunks, { type }),
            "video",
            false,
            type.includes("mp4") ? "mp4" : "webm",
          );
        } else
          toast(
            "Không có dữ liệu video. Thử quay lâu hơn hoặc kiểm tra camera.",
            true,
          );
      } finally {
        recording = null;
        $("record").classList.remove("is-recording");
        $("record-label").textContent = "Quay video";
        $("record").disabled = false;
        $("source").disabled = false;
        $("toggle-audio").disabled = false;
        syncCameraUI();
        resolveDone();
      }
    };
    recorder.start(1000);
    recording = session;
    const startedAt = Date.now();
    updateRecordTime(startedAt);
    session.interval = setInterval(() => updateRecordTime(startedAt), 1000);
    $("recording-indicator").hidden = false;
    $("record").classList.add("is-recording");
    $("record-label").textContent = "Dừng & lưu";
    $("source").disabled = true;
    $("toggle-audio").disabled = true;
    syncCameraUI();
    toast("Đang quay video. Dừng quay để lưu; nên thử các đoạn ngắn trước.");
  } catch (error) {
    toast(`Không bắt đầu quay được: ${error.message}`, true);
  }
}
async function stopRecording() {
  if (!recording) return;
  const session = recording;
  if (!session.stopping && session.recorder.state !== "inactive") {
    session.stopping = true;
    $("record").disabled = true;
    syncCameraUI();
    session.recorder.stop();
  }
  await session.done;
}

$("connect").onclick = connectDialog;
$("sidebar-device").onclick = () => (stream ? connectDialog() : startCamera());
$("start-in-preview").onclick = () => startCamera();
$("retry-camera").onclick = () => startCamera();
$("connect-start").onclick = () => startCamera({ fromDialog: true });
$("connect-dialog").addEventListener("close", () => {
  if (connecting) cancelConnection();
  syncCameraUI();
});
$("cancel-connection").onclick = cancelConnection;
$("try-demo").onclick = useDemo;
$("start-from-demo").onclick = () => startCamera();
$("sample-capture").onclick = useDemo;
$("use-demo").onclick = useDemo;
$("cancel-countdown").onclick = () => captureAbort?.abort();
$("global-stop").onclick = stopRecording;
$("source").onchange = () => {
  if ($("source").value === "local") startCamera();
  else useDemo();
};
$("stop-camera").onclick = () => stopCamera();
$("take-photo").onclick = takePhoto;
$("record").onclick = () => (recording ? stopRecording() : startRecording());
$("toggle-audio").onclick = async () => {
  if (recording || connecting || audioConnecting || shuttingDown) return;
  if (!stream) {
    setAudio(!audioEnabled);
    return;
  }
  const tracks = stream.getAudioTracks();
  if (tracks.length) {
    setAudio(!audioEnabled);
    tracks.forEach((track) => {
      track.enabled = audioEnabled;
    });
    syncCameraUI();
  } else {
    // Enabling a microphone on an active video-only stream needs permission.
    audioConnecting = true;
    const request = microphoneAccess.begin();
    syncCameraUI();
    const currentStream = stream;
    try {
      const audio = await request.open(() =>
        navigator.mediaDevices.getUserMedia({ audio: true }),
      );
      if (!audio) return;
      if (stream !== currentStream || recording) {
        audio.getTracks().forEach((track) => track.stop());
        return;
      }
      audio.getAudioTracks().forEach((track) => stream.addTrack(track));
      setAudio(true);
      syncCameraUI();
    } catch (error) {
      toast(cameraError(error), true);
    } finally {
      if (request.current()) {
        audioConnecting = false;
        syncCameraUI();
      }
    }
  }
};
for (const id of ["brightness", "mirror", "grid", "delay"])
  $(id).addEventListener("input", updateDisplay);
$("reset-display").onclick = () => {
  $("brightness").value = 100;
  $("mirror").checked = false;
  $("grid").checked = false;
  updateDisplay();
};
document.querySelectorAll("[data-fps]").forEach((button) => {
  button.onclick = () => {
    desiredFps = Number(button.dataset.fps);
    document.querySelectorAll("[data-fps]").forEach((option) => {
      option.classList.toggle("selected", option === button);
      option.setAttribute("aria-pressed", String(option === button));
    });
  };
});
$("fullscreen").onclick = async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else if ($("viewfinder").requestFullscreen)
      await $("viewfinder").requestFullscreen();
    else toast("Trình duyệt này chưa hỗ trợ toàn màn hình.");
  } catch {
    toast("Không mở được toàn màn hình trong trình duyệt này.", true);
  }
};
document.querySelectorAll('[data-action="help"]').forEach((button) => {
  button.onclick = () => {
    location.hash = "help";
  };
});
document.querySelectorAll("[data-filter]").forEach((button) => {
  button.onclick = () => {
    filter = button.dataset.filter;
    document.querySelectorAll("[data-filter]").forEach((option) => {
      option.classList.toggle("selected", option === button);
      option.setAttribute("aria-pressed", String(option === button));
    });
    renderLibrary();
  };
});
$("library-search").oninput = renderLibrary;
$("clear-filters").onclick = () => {
  filter = "all";
  $("library-search").value = "";
  document.querySelectorAll("[data-filter]").forEach((button) => {
    const selected = button.dataset.filter === "all";
    button.classList.toggle("selected", selected);
    button.setAttribute("aria-pressed", String(selected));
  });
  renderLibrary();
  $("library-search").focus();
};
$("export-all").onclick = async () => {
  // A single TAR download avoids browsers silently blocking multiple downloads.
  try {
    const archive = await makeArchive(media);
    const item = {
      id: crypto.randomUUID(),
      blob: archive,
      name: `flip-studio-${new Date().toISOString().slice(0, 10)}.tar`,
    };
    download(item);
    setTimeout(() => {
      URL.revokeObjectURL(urls.get(item.id));
      urls.delete(item.id);
    }, 60000);
    toast("Đã tạo gói thư viện .tar. Có thể giải nén trên Linux.");
  } catch {
    toast("Không tạo được gói thư viện. Hãy tải từng tệp riêng.", true);
  }
};
document.addEventListener("keydown", (event) => {
  if (event.code === "Escape" && captureAbort) {
    captureAbort.abort();
    return;
  }
  if (event.repeat) return;
  if (
    page !== "live" ||
    document.querySelector("dialog[open]") ||
    event.ctrlKey ||
    event.altKey ||
    event.metaKey
  )
    return;
  if (
    event.target.closest(
      "input,select,textarea,button,a,summary,[contenteditable]",
    )
  )
    return;
  if (event.code === "Space") {
    event.preventDefault();
    takePhoto();
  }
  if (event.code === "KeyR") {
    event.preventDefault();
    if (recording) stopRecording();
    else startRecording();
  }
  if (event.code === "KeyG") {
    $("grid").checked = !$("grid").checked;
    updateDisplay();
  }
});
window.addEventListener("beforeunload", (event) => {
  if (recording || capturing || media.some((item) => item.sessionOnly)) {
    event.preventDefault();
    event.returnValue = "";
  }
});
window.addEventListener("pagehide", () => {
  cameraAccess.cancel();
  microphoneAccess.cancel();
  stream?.getTracks().forEach((track) => track.stop());
});
function tick() {
  $("view-clock").textContent = new Date().toLocaleTimeString("vi-VN", {
    hour12: false,
  });
}
tick();
setInterval(tick, 1000);
syncCameraUI();
$("take-photo").disabled = true;
try {
  media = await listMedia();
} catch {
  toast(
    "Không mở được bộ nhớ trình duyệt. Tệp mới chỉ giữ trong phiên này; hãy tải xuống để lưu.",
    true,
  );
} finally {
  libraryReady = true;
  renderMedia();
  syncCameraUI();
}

// Open the authenticated viewer on the phone itself, preserving its HTTPS origin.
$("open-security").addEventListener("click", () => {
  $("security-error").hidden = true;
  $("security-dialog").showModal();
});
$("security-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const error = $("security-error");
  error.hidden = true;
  let address;
  try {
    address = new URL($("security-address").value.trim());
    if (address.protocol !== "https:" || address.username || address.password) throw new Error();
  } catch {
    error.textContent = "Nhập địa chỉ HTTPS của Flip, không kèm tài khoản hoặc mật khẩu.";
    error.hidden = false;
    return;
  }
  address.pathname = "/";
  address.search = "?live=1";
  address.hash = "";
  const popup = window.open("about:blank", "_blank", "popup,width=1100,height=760,resizable=yes,scrollbars=yes");
  if (!popup) {
    error.textContent = "Trình duyệt đã chặn cửa sổ. Cho phép cửa sổ bật lên cho trang này rồi thử lại.";
    error.hidden = false;
    return;
  }
  popup.opener = null;
  popup.location.replace(address.href);
  $("security-dialog").close();
});
