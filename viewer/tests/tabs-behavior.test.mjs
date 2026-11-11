import test from 'node:test';
import assert from 'node:assert/strict';
import { createViewer, response, settle } from './support/viewer-harness.mjs';

const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const segment = (n, startUtc = Date.now(), durationMs = 60_000) => ({
  id: id(n), startUtc, endUtc: startUtc + durationMs, durationMs,
  videoUrl: `/api/segments/${id(n)}/video`, width: 1280, height: 720, bytes: 1000,
});
const page = (segments, gaps = [], nextCursor = null) => response({ segments, gaps, nextCursor });

test('default active tab on viewer login is live tab', async () => {
  const viewer = await createViewer();
  assert.equal(viewer.element('view-live').hidden, false, 'live view must be visible by default');
  assert.equal(viewer.element('view-history').hidden, true, 'history view must be hidden by default');
  assert.equal(viewer.element('view-session').hidden, true, 'session view must be hidden by default');
  assert.equal(viewer.element('tab-live').attributes.get('aria-selected'), 'true');
  assert.equal(viewer.element('tab-history').attributes.get('aria-selected'), 'false');
  assert.equal(viewer.element('tab-session').attributes.get('aria-selected'), 'false');
});

test('clicking history tab shows history panel and hides live and session', async () => {
  const viewer = await createViewer();
  await viewer.element('tab-history').fire('click');
  assert.equal(viewer.element('view-live').hidden, true);
  assert.equal(viewer.element('view-history').hidden, false);
  assert.equal(viewer.element('view-session').hidden, true);
  assert.equal(viewer.element('tab-live').attributes.get('aria-selected'), 'false');
  assert.equal(viewer.element('tab-history').attributes.get('aria-selected'), 'true');
  assert.equal(viewer.element('tab-session').attributes.get('aria-selected'), 'false');
});

test('clicking session tab shows session panel and hides live and history', async () => {
  const viewer = await createViewer();
  await viewer.element('tab-session').fire('click');
  assert.equal(viewer.element('view-live').hidden, true);
  assert.equal(viewer.element('view-history').hidden, true);
  assert.equal(viewer.element('view-session').hidden, false);
  assert.equal(viewer.element('tab-live').attributes.get('aria-selected'), 'false');
  assert.equal(viewer.element('tab-history').attributes.get('aria-selected'), 'false');
  assert.equal(viewer.element('tab-session').attributes.get('aria-selected'), 'true');
});

test('playing a segment automatically switches to history tab', async () => {
  const viewer = await createViewer();
  // First be on live tab
  assert.equal(viewer.element('view-live').hidden, false);
  assert.equal(viewer.element('view-history').hidden, true);

  const item = segment(1);
  viewer.respondWith(() => page([item]));
  await viewer.selectHour();
  const rowButton = viewer.element('timeline-items').children[0].children[1];
  await rowButton.fire('click');

  // Should now automatically be switched to history tab
  assert.equal(viewer.element('view-history').hidden, false, 'history tab must be active when playback starts');
  assert.equal(viewer.element('view-live').hidden, true);
  assert.equal(viewer.element('tab-history').attributes.get('aria-selected'), 'true');
});

test('jump-to-history button switches to history tab', async () => {
  const viewer = await createViewer();
  assert.equal(viewer.element('view-live').hidden, false);
  await viewer.element('jump-to-history').fire('click');
  assert.equal(viewer.element('view-history').hidden, false);
  assert.equal(viewer.element('view-live').hidden, true);
});

test('hour stepper updates selected hour', async () => {
  const viewer = await createViewer();
  viewer.element('history-hour').value = '10';
  await viewer.element('prev-hour').fire('click');
  assert.equal(viewer.element('history-hour').value, '9');
  await viewer.element('next-hour').fire('click');
  assert.equal(viewer.element('history-hour').value, '10');
});

test('subtab switching toggles mobile-active state on history panels', async () => {
  const viewer = await createViewer();
  assert.equal(viewer.element('timeline-panel').classList.contains('mobile-active'), true);
  assert.equal(viewer.element('events-panel').classList.contains('mobile-active'), false);
  await viewer.element('subtab-events').fire('click');
  assert.equal(viewer.element('subtab-events').attributes.get('aria-selected'), 'true');
  assert.equal(viewer.element('subtab-timeline').attributes.get('aria-selected'), 'false');
  assert.equal(viewer.element('events-panel').classList.contains('mobile-active'), true);
  assert.equal(viewer.element('timeline-panel').classList.contains('mobile-active'), false);
  await viewer.element('subtab-timeline').fire('click');
  assert.equal(viewer.element('subtab-timeline').attributes.get('aria-selected'), 'true');
  assert.equal(viewer.element('subtab-events').attributes.get('aria-selected'), 'false');
  assert.equal(viewer.element('timeline-panel').classList.contains('mobile-active'), true);
  assert.equal(viewer.element('events-panel').classList.contains('mobile-active'), false);
});

test('seek controls adjust player currentTime and clamp within duration bounds', async () => {
  const viewer = await createViewer();
  const player = viewer.element('segment-player');
  player.duration = 60;
  player.currentTime = 30;

  await viewer.element('seek-backward').fire('click');
  assert.equal(player.currentTime, 20);

  await viewer.element('seek-forward').fire('click');
  assert.equal(player.currentTime, 30);
});

test('seeking +10s past the end of the segment automatically advances to next video', async () => {
  const viewer = await createViewer();
  const seg1 = segment(1, 1000, 60_000);
  const seg2 = segment(2, 61_000, 60_000);

  viewer.respondWith((path) => {
    if (path.includes('/next')) return response({ segment: seg2 });
    return page([seg1, seg2]);
  });

  await viewer.selectHour();
  // timeline is newest first, so seg1 (older) is children[1]
  const rowButton = viewer.element('timeline-items').children[1].children[1];
  await rowButton.fire('click');

  const player = viewer.element('segment-player');
  assert.equal(player.src, seg1.videoUrl);
  player.duration = 60;
  player.currentTime = 55; // 55s into 60s video

  // Seeking +10s exceeds duration (55 + 10 = 65 >= 60)
  await viewer.element('seek-forward').fire('click');
  await settle();

  // Player must now be loaded with seg2 and seek to remaining 5s!
  assert.equal(player.src, seg2.videoUrl, 'player must advance to seg2 video url');
  player.onloadedmetadata();
  assert.equal(player.currentTime, 5, 'seekToMs should carry over remaining 5s overflow');
});

test('next-segment-btn button immediately advances to next video', async () => {
  const viewer = await createViewer();
  const seg1 = segment(1, 1000, 60_000);
  const seg2 = segment(2, 61_000, 60_000);

  viewer.respondWith((path) => {
    if (path.includes('/next')) return response({ segment: seg2 });
    return page([seg1, seg2]);
  });

  await viewer.selectHour();
  // timeline is newest first, so seg1 (older) is children[1]
  const rowButton = viewer.element('timeline-items').children[1].children[1];
  await rowButton.fire('click');

  const player = viewer.element('segment-player');
  assert.equal(player.src, seg1.videoUrl);

  await viewer.element('next-segment-btn').fire('click');
  await settle();

  assert.equal(player.src, seg2.videoUrl);
});

