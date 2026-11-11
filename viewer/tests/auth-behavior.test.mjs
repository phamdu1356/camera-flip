import test from 'node:test';
import assert from 'node:assert/strict';
import { createViewer, deferred, response, settle } from './support/viewer-harness.mjs';

const PASSWORD = '  fixture mật khẩu  ';
function authenticated(path, options) {
  if (path === '/api/logout') return response({}, 204);
  if (path === '/api/login') {
    assert.equal(options.body.get('password'), PASSWORD, 'preserve the exact password');
    return response({ status: 'approved', readOnly: true });
  }
  if (path === '/api/status') return response({ readOnly: true });
  if (path.startsWith('/api/events')) return response({ events: [] });
  return response({ segments: [], gaps: [], nextCursor: null });
}
async function login(viewer) {
  viewer.element('pair-code').value = PASSWORD;
  viewer.element('viewer-name').value = 'Fixture viewer';
  await viewer.element('pair-form').fire('submit');
  await settle();
}
function assertViewer(viewer) {
  assert.equal(viewer.element('pair-screen').hidden, true);
  assert.equal(viewer.element('viewer-screen').hidden, false);
}

for (const origin of ['image probe', 'session monitor', 'manual session check']) {
  test(`late 401 from ${origin} cannot expire a logout/relogin session`, async () => {
    const viewer = await createViewer();
    const old = deferred();
    viewer.respondWith((path, options) => path === '/api/status' ? old.promise : authenticated(path, options));
    let pending;
    if (origin === 'image probe') viewer.element('live-image').onerror();
    else if (origin === 'session monitor') pending = viewer.tickIntervals();
    else pending = viewer.element('retry-status').fire('click');
    const request = viewer.requests.filter(item => item.path === '/api/status').at(-1);
    await viewer.element('logout').fire('click');
    assert.equal(request.options.signal.aborted, true);
    viewer.respondWith(authenticated);
    await login(viewer);
    viewer.element('live-image').onload();
    old.resolve(response({ error: 'unauthorized' }, 401)); // Ignore abort deliberately.
    await pending;
    await settle();
    assertViewer(viewer);
    assert.equal(viewer.element('live-frame').dataset.state, 'live');
    assert.equal(viewer.element('connection-label').textContent, 'Phiên đã xác thực');
  });
}

test('startup 401 arriving after password login cannot replace the authenticated viewer', async () => {
  const old = deferred();
  const viewer = await createViewer({ respond: (path, options) => path === '/api/status' ? old.promise : authenticated(path, options) });
  const request = viewer.requests[0];
  viewer.respondWith(authenticated);
  await login(viewer);
  assert.equal(request.options.signal.aborted, true);
  old.resolve(response({}, 401));
  await settle();
  assertViewer(viewer);
});

for (const outcome of ['401', '200', 'network rejection']) {
  test(`old monitor ${outcome} cannot alter a newer live attempt`, async () => {
    const viewer = await createViewer();
    const old = deferred();
    viewer.respondWith((path, options) => path === '/api/status' ? old.promise : authenticated(path, options));
    const pending = viewer.tickIntervals();
    const request = viewer.requests.filter(item => item.path === '/api/status').at(-1);
    const oldLoad = viewer.element('live-image').onload;
    const oldError = viewer.element('live-image').onerror;
    await viewer.element('retry-live').fire('click');
    assert.equal(request.options.signal.aborted, true);
    viewer.element('live-image').onload();
    viewer.respondWith((path, options) => path === '/api/status' ? response({}, 503) : authenticated(path, options));
    viewer.element('live-image').onerror();
    await settle();
    const label = viewer.element('connection-label').textContent;
    assert.equal(label, 'Chưa kiểm tra được phiên');
    oldError(); // Queued image callbacks from the replaced attempt must also be inert.
    oldLoad();
    if (outcome === 'network rejection') old.reject(new TypeError('Fixture offline'));
    else old.resolve(response({}, Number(outcome)));
    await pending;
    await settle();
    assertViewer(viewer);
    assert.equal(viewer.element('live-frame').dataset.state, 'reconnecting');
    assert.equal(viewer.element('connection-label').textContent, label);
  });
}

test('late JSON body from an old probe cannot expire a newly authenticated session', async () => {
  const viewer = await createViewer();
  const body = deferred();
  viewer.respondWith((path, options) => path === '/api/status'
    ? { status: 401, ok: false, json: () => body.promise } : authenticated(path, options));
  viewer.element('live-image').onerror();
  await settle();
  await viewer.element('logout').fire('click');
  viewer.respondWith(authenticated);
  await login(viewer);
  body.resolve({ error: 'unauthorized' });
  await settle();
  assertViewer(viewer);
});

test('status success confirms the session while generic image error stays cause-neutral', async () => {
  const viewer = await createViewer();
  viewer.respondWith(authenticated);
  viewer.element('live-image').onerror();
  await settle();
  assert.equal(viewer.element('connection-label').textContent, 'Phiên đã xác thực');
  assert.equal(viewer.element('live-frame').dataset.state, 'reconnecting');
  assert.equal(viewer.element('live-image').hidden, true);
  assert.match(viewer.element('stage-copy').textContent, /Chưa xác định được nguyên nhân/);
  await viewer.tickIntervals();
  assert.equal(viewer.element('live-frame').dataset.state, 'reconnecting', 'status 200 is not frame evidence');
  viewer.element('live-image').onload();
  assert.equal(viewer.element('live-frame').dataset.state, 'live');
  assert.equal(viewer.element('live-image').hidden, false);
});

test('current session 401 still expires the viewer and stops media', async () => {
  const viewer = await createViewer();
  viewer.respondWith(() => response({ error: 'unauthorized' }, 401));
  await viewer.tickIntervals();
  assert.equal(viewer.element('pair-screen').hidden, false);
  assert.equal(viewer.element('viewer-screen').hidden, true);
  assert.equal(viewer.element('live-image').src, '');
});

test('cancel pairing then password login ignores the old verification 401 JSON body', async () => {
  const body = deferred();
  let verifying = false;
  const viewer = await createViewer({ respond: () => response({}, 401) });
  viewer.respondWith((path, options) => {
    if (path === '/api/pair/request') return response({ status: 'pending', requestId: 'fixture-request' });
    if (path === '/api/pair/status') return response({ status: 'approved' });
    if (path === '/api/status') {
      verifying = true;
      return { status: 401, ok: false, json: () => body.promise };
    }
    return authenticated(path, options);
  });
  await viewer.element('toggle-auth').fire('click');
  viewer.element('pair-code').value = '12345678';
  viewer.element('viewer-name').value = 'Pair fixture';
  const pairing = viewer.element('pair-form').fire('submit');
  await settle();
  assert.equal(verifying, true);
  await viewer.element('cancel-pending').fire('click');
  await viewer.element('toggle-auth').fire('click');
  viewer.respondWith(authenticated);
  await login(viewer);
  viewer.element('live-image').onload();
  assertViewer(viewer);
  body.resolve({ error: 'unauthorized' });
  await pairing;
  await settle();
  assertViewer(viewer);
  assert.equal(viewer.element('live-frame').dataset.state, 'live');
});

for (const pendingStep of ['pair request body', 'approval body', 'verification success body', 'verification network error']) {
  test(`cancelled ${pendingStep} cannot alter a later password session`, async () => {
    const late = deferred();
    let reached = false;
    const viewer = await createViewer({ respond: () => response({}, 401) });
    viewer.respondWith((path, options) => {
      if (path === '/api/pair/request') {
        if (pendingStep === 'pair request body') {
          reached = true;
          return { status: 200, ok: true, json: () => late.promise };
        }
        return response({ status: 'pending', requestId: 'fixture-request' });
      }
      if (path === '/api/pair/status') {
        if (pendingStep === 'approval body') {
          reached = true;
          return { status: 200, ok: true, json: () => late.promise };
        }
        return response({ status: 'approved' });
      }
      if (path === '/api/status') {
        reached = true;
        return pendingStep === 'verification network error' ? late.promise
          : { status: 200, ok: true, json: () => late.promise };
      }
      return authenticated(path, options);
    });
    await viewer.element('toggle-auth').fire('click');
    viewer.element('pair-code').value = '12345678';
    viewer.element('viewer-name').value = 'Pair fixture';
    const pairing = viewer.element('pair-form').fire('submit');
    await settle();
    assert.equal(reached, true);
    await viewer.element('cancel-pending').fire('click');
    await viewer.element('toggle-auth').fire('click');
    viewer.respondWith(authenticated);
    await login(viewer);
    viewer.element('live-image').onload();
    if (pendingStep === 'verification network error') late.reject(new TypeError('Old fixture offline'));
    else late.resolve({ status: pendingStep === 'pair request body' ? 'pending' : 'approved', requestId: 'old-request', readOnly: true });
    await pairing;
    await settle();
    assertViewer(viewer);
    assert.equal(viewer.element('live-frame').dataset.state, 'live', 'old verification must not restart the new stream');
    assert.equal(viewer.element('connection-label').textContent, 'Phiên đã xác thực');
    assert.equal(viewer.element('pair-code').value, '');
  });
}

test('current approved pairing verifies read-only status and opens the viewer', async () => {
  const viewer = await createViewer({ respond: () => response({}, 401) });
  viewer.respondWith((path, options) => {
    if (path === '/api/pair/request') return response({ status: 'pending', requestId: 'fixture-request' });
    if (path === '/api/pair/status') return response({ status: 'approved' });
    return authenticated(path, options);
  });
  await viewer.element('toggle-auth').fire('click');
  viewer.element('pair-code').value = '12345678';
  viewer.element('viewer-name').value = 'Pair fixture';
  await viewer.element('pair-form').fire('submit');
  assertViewer(viewer);
});
