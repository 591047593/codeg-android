'use strict';

// Run with Node 22: node --test scripts/test_window_navigation.cjs
// Executes the Kotlin-embedded JavaScript, without a browser, dependencies or network.
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const nav = path.join(root, 'app/src/main/java/app/codeg/android/shell/nav');
const secret = 'document-secret';
const workspace = 'https://codeg.test/workspace?project=%2Frepo&session=abc#turn-7';
const marker = '[data-codeg-shell-back]';
const scripts = Object.fromEntries(['BackButtonScript', 'WindowBridgeScript'].map(name => {
  const source = readFileSync(path.join(nav, name + '.kt'), 'utf8');
  const raw = source.match(/"""([\s\S]*?)"""\.trimIndent\(\)/);
  assert.ok(raw, 'Expected an injected Kotlin raw string in ' + name);
  return [name, raw[1]];
}));

function injection(name, { origin, visible = true, label = '返回 "工作区"\nBack to workspace' }) {
  return scripts[name]
    .replace(/\$\{quote\(origin\)\}|\$originJson/g, () => JSON.stringify(origin))
    .replace(/\$\{visible\}|\$visible\b/g, () => String(visible))
    .replace(/\$labelJson/g, () => JSON.stringify(label))
    .replace(/\$tokenJson/g, () => JSON.stringify(secret));
}

// Small DOM boundary fixture: only DOM/events/history are simulated. The exit,
// route checks, observer lifecycle and button listeners all run from actual scripts.
function page({ url = 'https://codeg.test/settings/models', origin = 'https://codeg.test',
  entries = [workspace, 'https://codeg.test/settings', url], index = entries.length - 1,
  historyLength = entries.length, forbidHistoryRead = false, native = true,
  topFrame = true, header = true } = {}) {
  let current = new URL(url);
  const calls = { home: [], back: 0, historyReads: 0, originalClose: 0, navigations: [] };
  const observers = new Set();
  function changed() {
    for (const observer of observers) {
      if (observer.pending) continue;
      observer.pending = true;
      queueMicrotask(() => {
        observer.pending = false;
        if (observers.has(observer)) observer.callback([]);
      });
    }
  }
  class Events {
    constructor() { this.listeners = new Map(); }
    addEventListener(type, listener) {
      if (!this.listeners.has(type)) this.listeners.set(type, new Set());
      this.listeners.get(type).add(listener);
    }
    removeEventListener(type, listener) { this.listeners.get(type)?.delete(listener); }
    dispatchEvent(event) {
      event.preventDefault ||= () => { event.defaultPrevented = true; };
      event.stopPropagation ||= () => {};
      for (const listener of [...(this.listeners.get(event.type) || [])]) listener.call(this, event);
      return !event.defaultPrevented;
    }
  }
  class Element extends Events {
    constructor(tag) {
      super();
      this.tagName = tag.toUpperCase();
      this.children = [];
      this.parentNode = null;
      this.attributes = new Map();
      this.style = { set cssText(value) {
        for (const part of value.split(';')) {
          const separator = part.indexOf(':');
          if (separator < 0) continue;
          const key = part.slice(0, separator).trim().replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
          this[key] = part.slice(separator + 1).trim();
        }
      } };
    }
    setAttribute(key, value) { this.attributes.set(key, String(value)); changed(); }
    getAttribute(key) { return this.attributes.get(key) ?? null; }
    hasAttribute(key) { return this.attributes.has(key); }
    get className() { return this.getAttribute('class') || ''; }
    set className(value) { this.setAttribute('class', value); }
    get firstElementChild() { return this.children[0] || null; }
    get firstChild() { return this.firstElementChild; }
    get isConnected() {
      return this === document.documentElement || Boolean(this.parentNode?.isConnected);
    }
    appendChild(child) { return this.insertBefore(child, null); }
    insertBefore(child, before) {
      if (child === before) return child;
      if (child.parentNode) child.parentNode.removeChild(child);
      const position = before ? this.children.indexOf(before) : this.children.length;
      assert.ok(position >= 0, 'insertBefore target belongs to its parent');
      this.children.splice(position, 0, child);
      child.parentNode = this;
      changed();
      return child;
    }
    removeChild(child) {
      const position = this.children.indexOf(child);
      assert.ok(position >= 0, 'removeChild target belongs to its parent');
      this.children.splice(position, 1);
      child.parentNode = null;
      changed();
      return child;
    }
    remove() { this.parentNode?.removeChild(this); }
    querySelectorAll(selector) {
      const attribute = selector.match(/^\[([^=\]]+)(?:=["']([^"']*)["'])?\]$/);
      const matches = element => attribute
        ? element.hasAttribute(attribute[1]) && (attribute[2] === undefined || element.getAttribute(attribute[1]) === attribute[2])
        : element.tagName.toLowerCase() === selector;
      const result = [];
      for (const child of this.children) {
        if (matches(child)) result.push(child);
        result.push(...child.querySelectorAll(selector));
      }
      return result;
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  }
  const document = new Events();
  document.documentElement = new Element('html');
  document.body = new Element('body');
  document.documentElement.appendChild(document.body);
  document.createElement = tag => new Element(tag);
  document.querySelectorAll = selector => document.documentElement.querySelectorAll(selector);
  document.querySelector = selector => document.querySelectorAll(selector)[0] || null;
  const location = {
    get origin() { return current.origin; }, get pathname() { return current.pathname; },
    get href() { return current.href; },
    assign(url) { current = new URL(url, current); calls.navigations.push(current.href); }
  };
  const history = {
    get length() {
      calls.historyReads++;
      if (forbidHistoryRead) throw new Error('Window exits must not read history.length');
      return historyLength;
    },
    back() { calls.back++; if (index > 0) current = new URL(entries[--index]); },
    pushState(state, title, url) { if (url != null) current = new URL(url, current); },
    replaceState(state, title, url) { if (url != null) current = new URL(url, current); }
  };
  const originals = { pushState: history.pushState, replaceState: history.replaceState };
  const window = new Events();
  Object.assign(window, { document, location, history, URL,
    close() { calls.originalClose++; },
    setInterval() { throw new Error('Global polling is forbidden'); },
    setTimeout() { throw new Error('Navigation scripts must not need timers'); },
    MutationObserver: class {
      constructor(callback) { this.callback = callback; }
      observe() { observers.add(this); }
      disconnect() { observers.delete(this); }
    }
  });
  window.window = window;
  window.top = topFrame ? window : {};
  if (native) window.CodegShellWindowNative = { home(token) {
    calls.home.push(token);
    assert.equal(token, secret, 'The actual wrapper must send its captured secret');
    location.assign(workspace);
  } };
  const context = vm.createContext(window);
  function run(name, options = {}) {
    vm.runInContext(injection(name, { origin, ...options }), context, { filename: name + '.kt.js', timeout: 1000 });
  }
  function install(options) { run('WindowBridgeScript', options); run('BackButtonScript', options); }
  function mountHeader() {
    const row = document.createElement('div');
    row.className = 'flex items-center';
    row.setAttribute('data-tauri-drag-region', '');
    const slot = document.createElement('div');
    slot.style.display = 'inline-block';
    slot.style.alignItems = 'start';
    const menu = document.createElement('button');
    menu.setAttribute('data-settings-menu', '');
    slot.appendChild(menu);
    row.appendChild(slot);
    document.body.appendChild(row);
    return { row, slot, menu };
  }
  const titlebar = header ? mountHeader() : null;
  return { window, document, location, history, originals, calls, observers, titlebar, run, install, mountHeader,
    button: () => document.querySelector(marker),
    click() { assert.ok(this.button(), 'Expected a visible window-exit button'); this.button().dispatchEvent({ type: 'click' }); },
    setUrl(url) { current = new URL(url, current); },
    async settle() { for (let n = 0; n < 8; n++) await Promise.resolve(); }
  };
}

test('one click leaves several settings subpages for the remembered workspace, not one history step', () => {
  const fixture = page({ entries: [workspace, 'https://codeg.test/settings',
    'https://codeg.test/settings/general', 'https://codeg.test/settings/models'] });
  fixture.install();
  fixture.click();
  assert.equal(fixture.location.href, workspace);
  assert.deepEqual(fixture.calls.home, [secret]);
  assert.equal(fixture.calls.back, 0);
  assert.equal(fixture.calls.historyReads, 0);
});

for (const exit of ['button', 'close']) {
  for (const [name, entries, index, length] of [
    ['empty', [], 0, 0], ['single entry', ['https://codeg.test/settings'], 0, 1],
    ['back entries', [workspace, 'https://codeg.test/settings', 'https://codeg.test/settings/models'], 2, 3],
    ['only forward entries', ['https://codeg.test/settings/models', workspace], 0, 2]
  ]) {
    test(exit + ' ignores ' + name + ' history', () => {
      const fixture = page({ entries, index, historyLength: length, forbidHistoryRead: true });
      fixture.install();
      if (exit === 'button') fixture.click(); else fixture.window.close();
      assert.equal(fixture.location.href, workspace);
      assert.deepEqual(fixture.calls.home, [secret]);
      assert.equal(fixture.calls.back, 0);
      assert.equal(fixture.calls.historyReads, 0);
    });
  }
}

test('window.close and wrapper.home use the native exit for every known window-style route', () => {
  for (const route of ['/settings/advanced', '/commit', '/merge', '/stash', '/push', '/import-sessions', '/project-boot']) {
    for (const action of ['close', 'home']) {
      const fixture = page({ url: 'https://codeg.test' + route, forbidHistoryRead: true });
      fixture.install();
      if (action === 'close') fixture.window.close(); else fixture.window.CodegShellWindow.home();
      assert.equal(fixture.location.href, workspace, route + ' ' + action);
      assert.deepEqual(fixture.calls.home, [secret]);
    }
  }
});

test('absent native bridge or wrapper falls back only to the selected origin workspace', () => {
  for (const action of ['button', 'close', 'unwrapped button']) {
    const fixture = page({ native: false, forbidHistoryRead: true });
    if (action === 'unwrapped button') fixture.run('BackButtonScript'); else fixture.install();
    if (action === 'close') fixture.window.close(); else fixture.click();
    assert.equal(fixture.location.href, 'https://codeg.test/workspace');
    assert.deepEqual(fixture.calls.home, []);
    assert.equal(fixture.calls.back, 0);
  }
});

test('both scripts normalize selected-server default ports and trailing slash', () => {
  for (const [origin, url, expected] of [
    ['https://codeg.test:443/', 'https://codeg.test/settings', 'https://codeg.test/workspace'],
    ['http://codeg.test:80/', 'http://codeg.test/settings', 'http://codeg.test/workspace']
  ]) {
    for (const action of ['button', 'close']) {
      const fixture = page({ origin, url, native: false, forbidHistoryRead: true });
      fixture.install();
      if (action === 'button') fixture.click(); else fixture.window.close();
      assert.equal(fixture.location.href, expected);
    }
  }
});

test('repeated installation keeps one button and observer and preserves the settings menu', async () => {
  const fixture = page();
  fixture.install(); fixture.install(); fixture.install();
  await fixture.settle();
  assert.equal(fixture.document.querySelectorAll(marker).length, 1);
  assert.equal(fixture.observers.size, 1);
  assert.equal(fixture.titlebar.slot.children[1], fixture.titlebar.menu);
  fixture.click();
  assert.deepEqual(fixture.calls.home, [secret], 'Repeated installation must not multiply click listeners');
});

test('a late or replaced titlebar receives the button without hiding its menu', async () => {
  const fixture = page({ header: false });
  fixture.install();
  const late = fixture.mountHeader();
  await fixture.settle();
  assert.ok(fixture.button());
  assert.equal(late.slot.firstElementChild, fixture.button());
  assert.equal(late.slot.children[1], late.menu);
  late.row.remove();
  const replacement = fixture.mountHeader();
  await fixture.settle();
  assert.equal(fixture.document.querySelectorAll(marker).length, 1);
  assert.equal(replacement.slot.firstElementChild, fixture.button());
  assert.equal(replacement.slot.children[1], replacement.menu);
  assert.equal(late.slot.style.display, 'inline-block');
});

test('SPA route changes retain one observer on subpages and tear down on leaving', async () => {
  for (const action of ['pushState', 'replaceState', 'popstate', 'native sync', 'pagehide']) {
    const fixture = page();
    fixture.install();
    fixture.history.replaceState(null, '', '/settings/advanced');
    await fixture.settle();
    assert.equal(fixture.document.querySelectorAll(marker).length, 1);
    assert.equal(fixture.observers.size, 1);
    if (action === 'pushState' || action === 'replaceState') fixture.history[action](null, '', '/workspace');
    else if (action === 'popstate') { fixture.setUrl('/workspace'); fixture.window.dispatchEvent({ type: 'popstate' }); }
    else if (action === 'native sync') { fixture.setUrl('/workspace'); fixture.run('BackButtonScript', { visible: false }); }
    else fixture.window.dispatchEvent({ type: 'pagehide' });
    await fixture.settle();
    assert.equal(fixture.button(), null, action + ' removes the button');
    assert.equal(fixture.observers.size, 0, action + ' disconnects the observer');
    assert.equal(fixture.history.pushState, fixture.originals.pushState);
    assert.equal(fixture.history.replaceState, fixture.originals.replaceState);
    assert.equal(fixture.titlebar.slot.style.display, 'inline-block');
    assert.equal(fixture.titlebar.slot.style.alignItems, 'start');
    assert.equal(fixture.window.listeners.get('popstate')?.size || 0, 0);
    assert.equal(fixture.window.listeners.get('pagehide')?.size || 0, 0);
  }
});

test('native sync can reinstall after leaving and re-entering a window route', async () => {
  const fixture = page();
  fixture.install();
  fixture.history.pushState(null, '', '/workspace');
  await fixture.settle();
  fixture.history.pushState(null, '', '/settings/security');
  fixture.run('BackButtonScript');
  await fixture.settle();
  assert.equal(fixture.document.querySelectorAll(marker).length, 1);
  assert.equal(fixture.observers.size, 1);
  fixture.click();
  assert.equal(fixture.location.href, workspace);
});

test('both scripts isolate foreign origins and child frames', () => {
  for (const options of [
    { url: 'https://foreign.test/settings' }, { url: 'http://codeg.test/settings' },
    { url: 'https://codeg.test:8443/settings' }, { topFrame: false }
  ]) {
    const fixture = page(options);
    const close = fixture.window.close;
    fixture.install();
    assert.equal(fixture.button(), null);
    assert.equal(fixture.window.CodegShellWindow, undefined);
    assert.equal(fixture.window.close, close);
    assert.equal(fixture.observers.size, 0);
    assert.deepEqual(fixture.calls.home, []);
  }
});

test('workspace and lookalike routes cannot invoke the native window exit', () => {
  for (const route of ['/workspace', '/settings-evil', '/committer', '/unknown']) {
    const fixture = page({ url: 'https://codeg.test' + route });
    fixture.install();
    fixture.window.close();
    assert.equal(fixture.button(), null);
    assert.equal(fixture.observers.size, 0);
    assert.deepEqual(fixture.calls.home, []);
    assert.deepEqual(fixture.calls.navigations, []);
  }
});

test('an installed wrapper ignores caller-supplied destinations and rechecks its current route', () => {
  const fixture = page();
  fixture.install();
  fixture.window.CodegShellWindow.home('https://foreign.test/workspace');
  assert.equal(fixture.location.href, workspace);
  assert.deepEqual(fixture.calls.home, [secret]);
  for (const url of ['https://codeg.test/workspace', 'https://codeg.test/settings-evil', 'https://foreign.test/settings']) {
    fixture.setUrl(url);
    fixture.window.close();
    fixture.window.CodegShellWindow.home();
    assert.equal(fixture.location.href, url);
    assert.deepEqual(fixture.calls.home, [secret]);
  }
});

test('button labels are quoted resources in both locales and expose a practical touch target', () => {
  const labels = ['返回 "工作区"\nBack to workspace'];
  for (const locale of ['values', 'values-zh-rCN']) {
    const resource = readFileSync(path.join(root, 'app/src/main/res', locale, 'strings_shell_nav.xml'), 'utf8');
    const label = resource.match(/<string name="shell_nav_back_to_workspace">([^<]+)<\/string>/);
    assert.ok(label, 'Expected a localized workspace-exit label in ' + locale);
    labels.push(label[1]);
  }
  for (const label of labels) {
    const fixture = page();
    fixture.install({ label });
    assert.equal(fixture.button().getAttribute('aria-label'), label);
    assert.equal(fixture.button().getAttribute('title'), label);
    assert.ok(parseFloat(fixture.button().style.minWidth) >= 48);
    assert.ok(parseFloat(fixture.button().style.minHeight) >= 48);
    assert.notEqual(fixture.button().style.position, 'fixed', 'Do not overlay titlebar controls');
  }
});
