// Unit-level DOM/media doubles, not a browser or phone acceptance harness.
// Runs the actual viewer event handlers and fetch orchestration without exporting
// product internals or adding a new runtime asset to the APK route allowlist.
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const NOW = Date.parse('2026-11-01T12:00:00Z');
class FixtureDate extends Date {
  constructor(...args) { super(...(args.length ? args : [NOW])); }
  static now() { return NOW; }
}

class Element {
  constructor(tag = 'div') {
    this.tagName = tag;
    this.children = [];
    this.listeners = new Map();
    this.dataset = {};
    this.attributes = new Map();
    const classes = new Set();
    this.classList = {
      add: (...tokens) => tokens.forEach(t => classes.add(t)),
      remove: (...tokens) => tokens.forEach(t => classes.delete(t)),
      toggle: (token, force) => {
        const next = force !== undefined ? Boolean(force) : !classes.has(token);
        if (next) classes.add(token); else classes.delete(token);
        return next;
      },
      contains: (token) => classes.has(token),
    };
    this.hidden = false;
    this.disabled = false;
    this.value = '';
    this.duration = 120;
    this.currentTime = 0;
    this._text = '';
  }
  set textContent(value) { this._text = value; this.children = []; }
  get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
  addEventListener(name, handler) {
    this.listeners.set(name, [...(this.listeners.get(name) || []), handler]);
  }
  async fire(name) {
    if (this.disabled) throw new Error('Cannot activate a disabled test control');
    for (const handler of this.listeners.get(name) || []) {
      await handler({ target: this, preventDefault() {} });
    }
  }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); if (name === 'src') this.src = ''; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = [...children]; this._text = ''; }
  focus() {}
  scrollIntoView() {}
  pause() {}
  load() { this.currentTime = 0; }
  play() { return Promise.resolve(); }
}

export const response = (body, status = 200) => ({
  ok: status >= 200 && status < 300, status, json: async () => body,
});
export function deferred() {
  let resolve, reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}
export async function settle() {
  // Drain promise continuations only; there are no real application timers.
  for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve));
}
export async function createViewer({ respond, search = '', audioContext = null } = {}) {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
  };
  const requests = [];
  let responder = respond || (path => {
    if (path === '/api/status') return response({ readOnly: true });
    if (path.startsWith('/api/events')) return response({ events: [] });
    return response({ segments: [], gaps: [], nextCursor: null });
  });
  const document = { getElementById: element, createElement: tag => new Element(tag), documentElement: new Element() };
  const window = {
    location: { search, origin: 'https://viewer.test' },
    matchMedia: () => ({ matches: true }),
    ...(audioContext ? { AudioContext: audioContext } : {}),
  };
  const intervals = new Map();
  let timerId = 0;
  const context = {
    document, window, URL, URLSearchParams, AbortController, Intl, Date: FixtureDate, DOMException,
    Uint8Array, Int16Array, ArrayBuffer,
    ...(audioContext ? { AudioContext: audioContext } : {}),
    setTimeout: () => ++timerId, clearTimeout() {},
    setInterval(fn) { const id = ++timerId; intervals.set(id, fn); return id; },
    clearInterval(id) { intervals.delete(id); },
    fetch: async (path, options = {}) => {
      requests.push({ path, options });
      return responder(path, options);
    },
  };
  const source = readFileSync(new URL('../../viewer.js', import.meta.url), 'utf8');
  vm.runInNewContext(source.replace(/^export /gm, ''), context, { filename: 'viewer.js' });
  await settle();
  return {
    element, requests,
    tickIntervals() { return Promise.all([...intervals.values()].map(fn => fn())); },
    respondWith(fn) { responder = fn; },
    async selectHour(date = '2026-10-31', hour = '8') {
      element('history-date').value = date;
      element('history-hour').value = hour;
      await element('history-hour').fire('change');
    },
  };
}
