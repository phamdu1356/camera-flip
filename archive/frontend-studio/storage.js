// IndexedDB stores the original Blob, not a base64 copy in localStorage.
let database;
export async function openLibrary() {
  if (database) return database;
  database = await new Promise((resolve, reject) => {
    const request = indexedDB.open("flip-studio", 1);
    request.onupgradeneeded = () =>
      request.result.createObjectStore("media", { keyPath: "id" });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () =>
      reject(new Error("Đóng các tab Flip Studio khác rồi thử lại."));
  });
  return database;
}
export async function listMedia() {
  const db = await openLibrary();
  return new Promise((resolve, reject) => {
    const request = db.transaction("media").objectStore("media").getAll();
    request.onsuccess = () =>
      resolve(request.result.sort((a, b) => b.createdAt - a.createdAt));
    request.onerror = () => reject(request.error);
  });
}
export async function writeMedia(item) {
  const db = await openLibrary();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction("media", "readwrite");
    transaction.objectStore("media").put(item);
    transaction.oncomplete = () => resolve(item);
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () =>
      reject(transaction.error || new Error("Không đủ dung lượng lưu trữ."));
  });
}
export async function deleteMedia(id) {
  const db = await openLibrary();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction("media", "readwrite");
    transaction.objectStore("media").delete(id);
    transaction.oncomplete = resolve;
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}
