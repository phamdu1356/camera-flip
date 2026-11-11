import test from "node:test";
import assert from "node:assert/strict";
import { CameraAccess } from "../camera-access.js";

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

function fakeStream() {
  const stopped = [0, 0];
  return {
    stopped,
    getTracks: () =>
      stopped.map((_, index) => ({
        stop() {
          stopped[index]++;
        },
      })),
  };
}

test("cancel while permission is pending releases both late camera and microphone tracks", async () => {
  const access = new CameraAccess();
  const prompt = deferred();
  const request = access.begin();
  const result = request.open(() => prompt.promise);
  access.cancel();
  const stream = fakeStream();
  prompt.resolve(stream);
  assert.equal(await result, null);
  assert.deepEqual(stream.stopped, [1, 1]);
  assert.equal(request.current(), false);
});

test("a late first request cannot replace the camera from a newer request", async () => {
  const access = new CameraAccess();
  const firstPrompt = deferred();
  const first = access.begin().open(() => firstPrompt.promise);
  const activeStream = fakeStream();
  const second = access.begin();
  assert.equal(await second.open(async () => activeStream), activeStream);
  const obsoleteStream = fakeStream();
  firstPrompt.resolve(obsoleteStream);
  assert.equal(await first, null);
  assert.deepEqual(obsoleteStream.stopped, [1, 1]);
  assert.deepEqual(activeStream.stopped, [0, 0]);
  assert.equal(second.current(), true);
});

test("a canceled prompt's late rejection does not show a stale error", async () => {
  const access = new CameraAccess();
  const prompt = deferred();
  const result = access.begin().open(() => prompt.promise);
  access.cancel();
  prompt.reject(new Error("Permission denied after cancellation"));
  assert.equal(await result, null);
});

test("a current permission denial remains available for actionable error feedback", async () => {
  const access = new CameraAccess();
  const denied = Object.assign(new Error("Permission denied"), {
    name: "NotAllowedError",
  });
  await assert.rejects(
    access.begin().open(async () => {
      throw denied;
    }),
    (error) => error === denied,
  );
});
