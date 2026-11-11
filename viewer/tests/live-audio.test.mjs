import test from 'node:test';
import assert from 'node:assert/strict';
import { createViewer, response, settle } from './support/viewer-harness.mjs';

class MockAudioBufferSource {
  constructor() {
    this.buffer = null;
    this.startedAt = null;
  }
  connect() {}
  disconnect() {}
  start(time) {
    this.startedAt = time;
  }
}

class MockAudioBuffer {
  constructor(channels, length, sampleRate) {
    this.numberOfChannels = channels;
    this.length = length;
    this.sampleRate = sampleRate;
    this.duration = length / sampleRate;
    this._channelData = new Float32Array(length);
  }
  getChannelData() {
    return this._channelData;
  }
}

class MockAudioContext {
  constructor() {
    this.state = 'running';
    this.currentTime = 0;
    this.destination = {};
    this.closed = false;
  }
  resume() {
    this.state = 'running';
    return Promise.resolve();
  }
  close() {
    this.closed = true;
    return Promise.resolve();
  }
  createBuffer(channels, length, sampleRate) {
    return new MockAudioBuffer(channels, length, sampleRate);
  }
  createBufferSource() {
    return new MockAudioBufferSource();
  }
}

test('toggle-audio button initializes in muted state with aria-pressed false', async () => {
  const viewer = await createViewer();
  const button = viewer.element('toggle-audio');
  assert.equal(button.attributes.get('aria-pressed'), 'false');
  assert.equal(viewer.element('toggle-audio-label').textContent, 'Bật âm thanh');
});

test('clicking toggle-audio when Web Audio is unsupported sets button to Không hỗ trợ', async () => {
  const viewer = await createViewer();
  const button = viewer.element('toggle-audio');
  await button.fire('click');
  await settle();

  assert.equal(button.attributes.get('aria-pressed'), 'false');
  assert.equal(viewer.element('toggle-audio-label').textContent, 'Không hỗ trợ');
});

test('clicking toggle-audio with AudioContext requests live audio stream and enters active state', async () => {
  const streamChunks = [new Uint8Array([0, 0, 100, 0])];
  let streamClosed = false;

  const mockResponse = {
    ok: true,
    status: 200,
    body: {
      getReader() {
        let index = 0;
        return {
          async read() {
            if (index < streamChunks.length) {
              return { value: streamChunks[index++], done: false };
            }
            // Block until aborted
            return new Promise((resolve) => {
              if (streamClosed) resolve({ done: true });
            });
          },
        };
      },
    },
  };

  const viewer = await createViewer({
    audioContext: MockAudioContext,
    respond: (path) => {
      if (path === '/api/live-audio') return mockResponse;
      if (path === '/api/status') return response({ readOnly: true });
      return response({});
    },
  });

  const button = viewer.element('toggle-audio');
  await button.fire('click');
  await settle();

  assert.equal(button.attributes.get('aria-pressed'), 'true');
  assert.equal(viewer.element('toggle-audio-label').textContent, 'Tắt âm thanh');

  const audioRequest = viewer.requests.find((r) => r.path === '/api/live-audio');
  assert.ok(audioRequest, 'must request /api/live-audio');

  // Second click stops audio
  await button.fire('click');
  await settle();

  assert.equal(button.attributes.get('aria-pressed'), 'false');
  assert.equal(viewer.element('toggle-audio-label').textContent, 'Bật âm thanh');
  assert.equal(audioRequest.options.signal.aborted, true);
});

test('live audio reports Âm thanh tắt when server returns 503 Service Unavailable', async () => {
  const viewer = await createViewer({
    audioContext: MockAudioContext,
    respond: (path) => {
      if (path === '/api/live-audio') return response({ error: 'audio_unavailable' }, 503);
      if (path === '/api/status') return response({ readOnly: true });
      return response({});
    },
  });

  const button = viewer.element('toggle-audio');
  await button.fire('click');
  await settle();

  assert.equal(button.attributes.get('aria-pressed'), 'false');
  assert.equal(viewer.element('toggle-audio-label').textContent, 'Âm thanh tắt');
});

test('switching tab away from live automatically stops live audio stream', async () => {
  let abortSignal = null;
  const viewer = await createViewer({
    audioContext: MockAudioContext,
    respond: (path, options) => {
      if (path === '/api/live-audio') {
        abortSignal = options.signal;
        return {
          ok: true,
          status: 200,
          body: {
            getReader: () => ({ read: () => new Promise(() => {}) }),
          },
        };
      }
      if (path === '/api/status') return response({ readOnly: true });
      return response({});
    },
  });

  const button = viewer.element('toggle-audio');
  await button.fire('click');
  await settle();

  assert.equal(button.attributes.get('aria-pressed'), 'true');

  // Switch to history tab
  await viewer.element('tab-history').fire('click');
  await settle();

  assert.equal(button.attributes.get('aria-pressed'), 'false');
  assert.equal(abortSignal?.aborted, true, 'signal must be aborted when switching tab');
});

test('live audio reports Không nghe được when fetch encounters network error', async () => {
  const viewer = await createViewer({
    audioContext: MockAudioContext,
    respond: (path) => {
      if (path === '/api/live-audio') throw new Error('Network error');
      if (path === '/api/status') return response({ readOnly: true });
      return response({});
    },
  });

  const button = viewer.element('toggle-audio');
  await button.fire('click');
  await settle();

  assert.equal(button.attributes.get('aria-pressed'), 'false');
  assert.equal(viewer.element('toggle-audio-label').textContent, 'Không nghe được');
});
