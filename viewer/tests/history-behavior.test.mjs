import test from 'node:test';
import assert from 'node:assert/strict';
import { createViewer, deferred, response, settle } from './support/viewer-harness.mjs';

const FROM = Date.parse('2026-10-31T01:00:00Z'); // 08:00 UTC+7
const TO = FROM + 3_600_000;
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const segment = (n, startUtc = FROM, durationMs = 60_000) => ({
  id: id(n), startUtc, endUtc: startUtc + durationMs, durationMs,
  videoUrl: `/api/segments/${id(n)}/video`, width: 1280, height: 720, bytes: 1000,
});
const gap = (n, startUtc = FROM) => ({ id: id(n), startUtc, endUtc: startUtc + 1000, durationMs: 1000, reason: 'Gap fixture' });
const page = (segments, gaps = [], nextCursor = null) => response({ segments, gaps, nextCursor });
const rowButton = (viewer, index = 0) => viewer.element('timeline-items').children[index].children[1];

for (const [name, start, duration, seek] of [
  ['crossing the left hour boundary', FROM - 20_000, 60_000, 20],
  ['spanning the selected hour', FROM - 20_000, 3_700_000, 20],
  ['starting inside the selected hour', FROM + 20_000, 60_000, 0],
]) {
  test(`timeline click loads the clip and seeks correctly when ${name}`, async () => {
    const viewer = await createViewer();
    const item = segment(1, start, duration);
    const original = structuredClone(item);
    viewer.respondWith(() => page([item]));
    await viewer.selectHour();
    await rowButton(viewer).fire('click');
    const player = viewer.element('segment-player');
    assert.equal(player.src, item.videoUrl);
    player.duration = duration / 1000;
    player.onloadedmetadata();
    assert.equal(player.currentTime, seek);
    assert.deepEqual(item, original, 'seek must not mutate API timestamps or metadata');
  });
}

test('timeline seek is clamped to the actual playable media duration', async () => {
  const viewer = await createViewer();
  viewer.respondWith(() => page([segment(1, FROM - 20_000)]));
  await viewer.selectHour();
  await rowButton(viewer).fire('click');
  const player = viewer.element('segment-player');
  player.duration = 10;
  player.onloadedmetadata();
  assert.equal(player.currentTime, 9.9);
});

test('all pages preserve the full cursor/window and merge segment/gap identities without repeats', async () => {
  const viewer = await createViewer();
  const sameIdSegment = segment(1);
  const sameIdGap = gap(1);
  const cursor1 = { startUtc: FROM, id: id(1), type: 'gap' };
  const cursor2 = { startUtc: FROM, id: id(2), type: 'segment' };
  const calls = [];
  viewer.respondWith(path => {
    const query = new URL(path, 'https://viewer.test').searchParams;
    calls.push(query);
    if (calls.length === 1) return page([sameIdSegment], [sameIdGap], cursor1);
    if (calls.length === 2) return page([sameIdSegment, segment(2)], [sameIdGap], cursor2);
    return page([segment(3)], [gap(4)]);
  });
  await viewer.selectHour();
  assert.equal(viewer.element('history-more').hidden, false);
  await viewer.element('history-more').fire('click');
  assert.equal(viewer.element('history-more').hidden, false);
  await viewer.element('history-more').fire('click');
  assert.equal(calls.length, 3);
  for (const query of calls) {
    assert.equal(query.get('from'), String(FROM));
    assert.equal(query.get('to'), String(TO));
    assert.equal(query.get('limit'), '500');
  }
  for (const [index, cursor] of [[1, cursor1], [2, cursor2]]) {
    assert.equal(calls[index].get('afterStart'), String(cursor.startUtc));
    assert.equal(calls[index].get('afterId'), cursor.id);
    assert.equal(calls[index].get('afterType'), cursor.type);
  }
  assert.equal(viewer.element('timeline-items').children.length, 5);
  assert.match(viewer.element('timeline-status').textContent, /3 bản ghi · 2 khoảng gián đoạn/);
  assert.equal(viewer.element('history-more').hidden, true);
});

test('failed extra page can be retried without dropping rows or advancing its cursor', async () => {
  const viewer = await createViewer();
  const cursor = { startUtc: FROM, id: id(1), type: 'segment' };
  let calls = 0;
  viewer.respondWith(() => {
    calls++;
    if (calls === 1) return page([segment(1)], [], cursor);
    if (calls === 2) throw new TypeError('Mock offline');
    return page([segment(2)]);
  });
  await viewer.selectHour();
  await viewer.element('history-more').fire('click');
  assert.equal(viewer.element('timeline-items').children.length, 1);
  assert.equal(viewer.element('history-more').disabled, false);
  await viewer.element('history-more').fire('click');
  assert.equal(viewer.element('timeline-items').children.length, 2);
  const requests = viewer.requests.filter(r => r.path.includes('afterStart='));
  assert.equal(requests.length, 2);
  assert.equal(requests[0].path, requests[1].path);
});

test('changing the hour aborts and rejects a late page even when the fetch double ignores abort', async () => {
  const viewer = await createViewer();
  const oldPage = deferred();
  let calls = 0;
  let oldSignal;
  viewer.respondWith((path, options) => {
    calls++;
    if (calls === 1) return page([segment(1)], [], { startUtc: FROM, id: id(1), type: 'segment' });
    if (calls === 2) { oldSignal = options.signal; return oldPage.promise; }
    return page([segment(3, FROM + 3_600_000)]);
  });
  await viewer.selectHour();
  const pending = viewer.element('history-more').fire('click');
  await viewer.selectHour('2026-10-31', '9');
  assert.equal(oldSignal.aborted, true);
  oldPage.resolve(page([segment(2)]));
  await pending;
  assert.equal(viewer.element('timeline-items').children.length, 1);
  await rowButton(viewer).fire('click');
  assert.equal(viewer.element('segment-player').src, segment(3).videoUrl);
});

test('session expiry aborts a pending page and late results cannot restore private history', async () => {
  const viewer = await createViewer();
  const oldPage = deferred();
  let calls = 0;
  let oldSignal;
  viewer.respondWith((path, options) => {
    if (path.startsWith('/api/events')) return response({ error: 'unauthorized' }, 401);
    calls++;
    if (calls === 1) return page([segment(1)], [], { startUtc: FROM, id: id(1), type: 'segment' });
    oldSignal = options.signal;
    return oldPage.promise;
  });
  await viewer.selectHour();
  const pending = viewer.element('history-more').fire('click');
  await viewer.element('events-refresh').fire('click');
  assert.equal(oldSignal.aborted, true);
  oldPage.resolve(page([segment(2)]));
  await pending;
  await settle();
  assert.equal(viewer.element('viewer-screen').hidden, true);
  assert.equal(viewer.element('pair-screen').hidden, false);
  assert.equal(viewer.element('timeline-items').children.length, 0);
  assert.equal(viewer.element('history-more').hidden, true);
});

test('events at the server cap disclose incomplete history instead of claiming all events', async () => {
  const viewer = await createViewer();
  viewer.respondWith(() => response({ events: Array.from({ length: 200 }, (_, n) => ({
    id: id(n + 1), kind: 'MOTION', startUtc: FROM, endUtc: FROM + 1000,
  })) }));
  await viewer.element('events-refresh').fire('click');
  assert.equal(viewer.element('event-items').children.length, 200);
  assert.match(viewer.element('events-status').textContent, /Đã chạm giới hạn 200 mục/);
  assert.match(viewer.element('events-status').textContent, /có thể còn sự kiện chưa hiển thị/);
});
