import test from 'node:test';
import assert from 'node:assert/strict';
import { createViewer, settle } from './support/viewer-harness.mjs';

test('toggle-talkback button initializes in idle state with aria-pressed false', async () => {
  const viewer = await createViewer();
  const button = viewer.element('toggle-talkback');
  assert.equal(button.attributes.get('aria-pressed'), 'false');
  assert.equal(viewer.element('toggle-talkback-label').textContent, 'Nói qua loa');
});

test('clicking toggle-talkback when mediaDevices is unsupported sets button to Không hỗ trợ', async () => {
  const viewer = await createViewer();
  const button = viewer.element('toggle-talkback');
  await button.fire('click');
  await settle();

  assert.equal(button.attributes.get('aria-pressed'), 'false');
  assert.equal(viewer.element('toggle-talkback-label').textContent, 'Không hỗ trợ');
});

test('switching tab away from live automatically resets talkback button', async () => {
  const viewer = await createViewer();
  const button = viewer.element('toggle-talkback');
  button.attributes.set('aria-pressed', 'true');
  viewer.element('toggle-talkback-label').textContent = 'Đang phát loa';

  const historyTab = viewer.element('tab-history');
  await historyTab.fire('click');
  await settle();

  assert.equal(button.attributes.get('aria-pressed'), 'false');
  assert.equal(viewer.element('toggle-talkback-label').textContent, 'Nói qua loa');
});
