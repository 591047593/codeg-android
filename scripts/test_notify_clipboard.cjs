'use strict';
// Run the actual Kotlin-injected scripts in isolated VMs. No network, browser,
// device, external packages or real clipboard. User activation is simulated.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const root = path.resolve(__dirname, '../app/src/main/java/app/codeg/android/shell');
const notifySource = fs.readFileSync(path.join(root, 'notify/NotificationScript.kt'), 'utf8');
const clipboardSource = fs.readFileSync(path.join(root, 'web/ClipboardScript.kt'), 'utf8');
const SERVER = 'https://example.com';
const TOKEN = 'tok-1';

function script(source, name, values) {
  const start = source.indexOf('internal fun ' + name + '(');
  assert.notEqual(start, -1, 'Missing actual Kotlin script function ' + name);
  const raw = source.slice(start).match(/(?:return\s+|=\s*)"""([\s\S]*?)"""\.trimIndent\(\)/);
  assert.ok(raw, 'Expected Kotlin raw-string script in ' + name);
  return raw[1].replace(/\$(\w+)/g, (_, key) => {
    assert.ok(Object.hasOwn(values, key), 'Unfilled Kotlin placeholder ' + key);
    return JSON.stringify(values[key]);
  });
}

const notification = (origin = SERVER, token = TOKEN, state = 'default') =>
  script(notifySource, 'notificationScript', { originJson: origin, tokenJson: token, stateJson: state });
const click = (id, origin = SERVER, token = TOKEN) =>
  script(notifySource, 'notificationClickScript', { originJson: origin, tokenJson: token, idJson: id });
const permission = (settle, origin = SERVER, token = TOKEN) =>
  script(notifySource, 'notificationPermissionScript', {
    originJson: origin, tokenJson: token,
    methodJson: settle ? '__codegNotifySettle' : '__codegNotifyRefresh',
  });
const clipboard = (origin = SERVER) =>
  script(clipboardSource, 'clipboardScript', { originJson: origin, tokenJson: TOKEN });

function realm({ origin = SERVER, href = origin + '/app', child = false,
  nativeClipboard, activation = { isActive: false }, notifyBridge, clipboardBridge } = {}) {
  let now = 1700000000000;
  let sequence = 0;
  const timers = new Map();
  const listeners = new Map();
  const schedule = (fn, ms, repeat = false) => {
    const id = ++sequence;
    timers.set(id, { fn, at: now + Math.max(1, ms || 0), repeat: repeat ? Math.max(1, ms || 0) : 0 });
    return id;
  };
  class Clock extends Date { static now() { return now; } }
  const sandbox = {
    console, DOMException, Date: Clock,
    location: { origin, href },
    navigator: {},
    document: {
      visibilityState: 'visible',
      addEventListener: (name, fn) => listeners.set(name, fn),
    },
    setTimeout: (fn, ms) => schedule(fn, ms), clearTimeout: id => timers.delete(id),
    setInterval: (fn, ms) => schedule(fn, ms, true), clearInterval: id => timers.delete(id),
  };
  if (activation !== null) sandbox.navigator.userActivation = activation;
  if (nativeClipboard !== undefined) sandbox.navigator.clipboard = nativeClipboard;
  if (notifyBridge) sandbox.CodegShellNotify = notifyBridge;
  if (clipboardBridge) sandbox.CodegShellClipboard = clipboardBridge;
  sandbox.window = sandbox;
  sandbox.top = child ? {} : sandbox;
  vm.createContext(sandbox);
  return {
    sandbox, timers, listeners,
    run: body => new vm.Script(body, { filename: 'actual-injected-script.js' }).runInContext(sandbox),
    advance(ms) {
      const end = now + ms;
      let iterations = 0;
      for (;;) {
        const next = [...timers].filter(([, timer]) => timer.at <= end)
          .sort((a, b) => a[1].at - b[1].at)[0];
        if (!next) break;
        assert.ok(++iterations < 10000, 'Timer loop did not converge');
        const [id, timer] = next;
        now = timer.at;
        if (timer.repeat) timer.at += timer.repeat; else timers.delete(id);
        timer.fn();
      }
      now = end;
    },
    userClick(action) {
      activation.isActive = true;
      try { return action(); } finally { activation.isActive = false; }
    },
  };
}

function notifyEnv({ origin = SERVER, state = 'default', token = TOKEN,
  postResult = true, requestResult = true, ...options } = {}) {
  const calls = { state: [], request: [], post: [], cancel: [] };
  const bridge = {
    permission: state,
    state(secret) { calls.state.push(secret); return this.permission; },
    request(secret) { calls.request.push(secret); return requestResult; },
    post(secret, id, title, body, pageUrl) {
      calls.post.push({ secret, id, title, body, pageUrl });
      return postResult;
    },
    cancel(secret, id) { calls.cancel.push({ secret, id }); },
  };
  const env = realm({ origin, notifyBridge: bridge, ...options });
  env.run(notification(origin, token, state));
  return { ...env, bridge, calls, Notification: env.sandbox.Notification };
}

function clipboardEnv(options = {}) {
  const calls = { read: [], write: [] };
  const bridge = {
    readText(secret) { calls.read.push(secret); return 'native-text'; },
    writeText(secret, text) { calls.write.push({ secret, text }); return true; },
  };
  const env = realm({ clipboardBridge: bridge, ...options });
  env.run(clipboard(options.origin));
  return { ...env, calls, bridge, clipboard: env.sandbox.navigator.clipboard };
}

// Notifications: browser origin strings are exercised here. Kotlin origin
// serialization is covered separately by NotificationOriginTest (CI, not Node).
test('notify: default origins and IPv6 install and answer permission', () => {
  for (const origin of ['http://example.com', SERVER, SERVER + ':8443',
    'http://127.0.0.1:3000', 'http://[::1]', 'https://[2001:db8::1]', 'http://[::1]:3000']) {
    const env = notifyEnv({ origin });
    assert.equal(typeof env.Notification, 'function', origin);
    assert.equal(env.Notification.permission, 'default', origin);
    assert.equal(env.sandbox.__codegNotifyInstalled, true);
    assert.ok(env.calls.state.length > 0);
  }
});

test('notify: wrong origin, child frame and missing bridge install nothing', () => {
  const wrong = realm({ origin: 'https://other.invalid' });
  wrong.run(notification());
  assert.equal(wrong.sandbox.Notification, undefined);
  const child = notifyEnv({ child: true });
  assert.equal(child.Notification, undefined);
  const absent = realm();
  absent.run(notification());
  assert.equal(absent.sandbox.Notification, undefined);
});

test('notify: installation acknowledges the same token, rejects an outgoing document token', () => {
  const env = notifyEnv();
  assert.equal(env.run(notification()), true);
  assert.equal(env.sandbox.Notification, env.Notification, 'same document keeps its instances');
  assert.equal(env.run(notification(SERVER, 'next-document')), false);
  assert.equal(env.sandbox.Notification, env.Notification);
});

test('notify: granted post captures the current SPA page URL', () => {
  const env = notifyEnv({ state: 'granted' });
  env.sandbox.location.href = SERVER + '/app/session/current?tab=chat#latest';
  const n = new env.Notification('hello', { body: 'world' });
  assert.equal(env.calls.post.length, 1);
  assert.equal(env.calls.post[0].secret, TOKEN);
  assert.equal(env.calls.post[0].title, n.title);
  assert.equal(env.calls.post[0].body, n.body);
  assert.equal(env.calls.post[0].pageUrl, env.sandbox.location.href);
});

test('notify: undecided/denied posts never request permission; refused posts throw', () => {
  for (const state of ['default', 'denied']) {
    const env = notifyEnv({ state });
    assert.throws(() => new env.Notification('x'), e => e.name === 'NotAllowedError');
    assert.deepEqual(env.calls.request, []);
    assert.deepEqual(env.calls.post, []);
  }
  const refused = notifyEnv({ state: 'granted', postResult: false });
  assert.throws(() => new refused.Notification('x'), e => e.name === 'NotAllowedError');
  assert.equal(refused.calls.post.length, 1);
});

test('notify: actual native tap script calls a live handler once, old ids fall back', () => {
  const env = notifyEnv({ state: 'granted' });
  const n = new env.Notification('live');
  let clicks = 0;
  n.onclick = () => { clicks++; };
  const id = env.calls.post[0].id;
  assert.equal(env.run(click(id)), true);
  assert.equal(clicks, 1);
  assert.equal(env.run(click(id)), false);
  assert.equal(env.run(click('old-id')), false);
  const restarted = notifyEnv({ state: 'granted', token: 'new-process' });
  assert.equal(restarted.run(click(id, SERVER, 'new-process')), false, 'no prior instances after process death');
});

test('notify: missing handlers, expired and evicted instances answer false for native navigation', () => {
  const env = notifyEnv({ state: 'granted' });
  new env.Notification('no handler');
  assert.equal(env.run(click(env.calls.post[0].id)), false);
  const old = new env.Notification('expired');
  old.onclick = () => assert.fail('expired handler called');
  const oldId = env.calls.post[1].id;
  env.advance(31 * 60 * 1000);
  assert.equal(env.run(click(oldId)), false);
  assert.equal(env.timers.size, 0, 'expired instances release their cleanup timer');
  const first = env.calls.post.length;
  for (let i = 0; i < 65; i++) {
    const n = new env.Notification('bounded ' + i);
    n.onclick = () => {};
  }
  assert.equal(env.run(click(env.calls.post[first].id)), false);
  assert.equal(env.run(click(env.calls.post.at(-1).id)), true);
  assert.equal(env.timers.size, 1, 'only one instance expiry timer is retained');
});

test('notify: close cancels with the secret and removes the live handler', () => {
  const env = notifyEnv({ state: 'granted' });
  const n = new env.Notification('x');
  let closed = 0;
  n.onclose = () => { closed++; };
  n.close();
  assert.equal(closed, 1);
  assert.deepEqual(env.calls.cancel, [{ secret: TOKEN, id: env.calls.post[0].id }]);
  assert.equal(env.run(click(env.calls.post[0].id)), false);
});

test('notify: Android permission result settles both callback and promise', async () => {
  const env = notifyEnv();
  let callback;
  const promise = env.Notification.requestPermission(value => { callback = value; });
  assert.deepEqual(env.calls.request, [TOKEN]);
  env.bridge.permission = 'granted';
  assert.equal(env.run(permission(true)), true);
  assert.equal(await promise, 'granted');
  assert.equal(callback, 'granted');
  assert.equal(env.Notification.permission, 'granted');
  assert.equal(env.timers.size, 0);
});

test('notify: background native settlement and synchronous refusal preserve default', async () => {
  const env = notifyEnv();
  const promise = env.Notification.requestPermission();
  env.run(permission(true));
  assert.equal(await promise, 'default');
  assert.equal(env.Notification.permission, 'default');
  const refused = notifyEnv({ requestResult: false });
  assert.equal(await refused.Notification.requestPermission(), 'default');
  assert.equal(refused.timers.size, 0);
});

test('notify: timeout does not invent denial or poll the bridge', async () => {
  const env = notifyEnv();
  const promise = env.Notification.requestPermission();
  const reads = env.calls.state.length;
  env.advance(119999);
  assert.equal(env.calls.state.length, reads, 'no polling');
  env.advance(1);
  assert.equal(await promise, 'default');
  assert.equal(env.timers.size, 0);
});

test('notify: already-decided permission settles without requesting', async () => {
  for (const state of ['granted', 'denied']) {
    const env = notifyEnv({ state });
    let callback;
    assert.equal(await env.Notification.requestPermission(value => { callback = value; }), state);
    assert.equal(callback, state);
    assert.deepEqual(env.calls.request, []);
  }
});

test('notify: concurrent requests share one native request and have bounded waiters/timers', async () => {
  const env = notifyEnv();
  const promises = Array.from({ length: 100 }, () => env.Notification.requestPermission());
  assert.equal(env.calls.request.length, 1);
  assert.equal(env.timers.size, 1);
  env.bridge.permission = 'granted';
  env.run(permission(true));
  const results = await Promise.all(promises);
  assert.equal(results.filter(value => value === 'granted').length, 32);
  assert.equal(results.filter(value => value === 'default').length, 68);
  assert.equal(env.timers.size, 0);
});

test('notify: bridge exceptions cannot leave permission requests pending', async () => {
  const env = notifyEnv();
  env.bridge.request = () => { throw new Error('bridge unavailable'); };
  assert.equal(await env.Notification.requestPermission(), 'default');
  assert.equal(env.timers.size, 0);
});

test('notify: permission updates on resume/native refresh, reads and visibility change', () => {
  const env = notifyEnv({ state: 'granted' });
  env.bridge.permission = 'denied'; // E.g. channel disabled in Android settings.
  assert.equal(env.run(permission(false)), true);
  assert.equal(env.Notification.permission, 'denied');
  env.bridge.permission = 'granted';
  assert.equal(env.Notification.permission, 'granted', 'getter uses current safe snapshot');
  env.bridge.permission = 'denied';
  env.listeners.get('visibilitychange')();
  assert.equal(env.Notification.permission, 'denied');
});

test('notify: stale native callbacks cannot settle or click a replacement document', async () => {
  const env = notifyEnv({ token: 'new-document' });
  const promise = env.Notification.requestPermission();
  let settled = false;
  promise.then(() => { settled = true; });
  env.bridge.permission = 'granted';
  assert.equal(env.run(permission(true, SERVER, 'old-document')), false);
  assert.equal(env.run(click('any', SERVER, 'old-document')), false);
  await Promise.resolve();
  assert.equal(settled, false);
  assert.equal(env.run(permission(true, SERVER, 'new-document')), true);
  assert.equal(await promise, 'granted');
  env.sandbox.location.origin = 'https://other.invalid';
  assert.equal(env.run(permission(true, SERVER, 'new-document')), false);
  assert.equal(env.run(click('any', SERVER, 'new-document')), false);
});

// Clipboard activation is simulated; all text stays inside this process.
test('clipboard: installs on selected top document only, not same-origin frames', () => {
  const env = clipboardEnv();
  assert.equal(typeof env.clipboard.readText, 'function');
  const child = clipboardEnv({ child: true });
  assert.equal(child.clipboard, undefined);
  const wrong = realm({ origin: 'https://other.invalid' });
  wrong.run(clipboard());
  assert.equal(wrong.sandbox.navigator.clipboard, undefined);
});

test('clipboard: browser permission denials are preserved for read and write', async () => {
  for (const name of ['NotAllowedError', 'SecurityError']) {
    for (const method of ['readText', 'writeText']) {
      const denied = new DOMException('permission denied', name);
      const env = clipboardEnv({
        activation: { isActive: true },
        nativeClipboard: { [method]: () => Promise.reject(denied) },
      });
      await assert.rejects(env.clipboard[method]('text'), error => error === denied);
      assert.deepEqual(env.calls, { read: [], write: [] });
    }
  }
});

test('clipboard: no activation, including missing capability, refuses native reads', async () => {
  for (const activation of [{ isActive: false }, null]) {
    const env = clipboardEnv({ activation });
    await assert.rejects(env.clipboard.readText(), error => error.name === 'NotAllowedError');
    assert.deepEqual(env.calls.read, []);
  }
});

test('clipboard: HTTP copy/paste works only during a user-triggered read fallback', async () => {
  const env = clipboardEnv({ origin: 'http://127.0.0.1:3000' });
  assert.equal(await env.userClick(() => env.clipboard.readText()), 'native-text');
  await env.userClick(() => env.clipboard.writeText('hello'));
  assert.deepEqual(env.calls.read, [TOKEN]);
  assert.deepEqual(env.calls.write, [{ secret: TOKEN, text: 'hello' }]);
  await assert.rejects(env.clipboard.readText(), error => error.name === 'NotAllowedError');
  assert.equal(env.calls.read.length, 1);
});

test('clipboard: unexpected browser failures require activation at fallback time', async () => {
  const active = { isActive: true };
  const broken = { readText: () => Promise.reject(new TypeError('unavailable')) };
  const env = clipboardEnv({ activation: active, nativeClipboard: broken });
  assert.equal(await env.clipboard.readText(), 'native-text');
  active.isActive = false;
  await assert.rejects(env.clipboard.readText(), error => error.name === 'NotAllowedError');
  assert.equal(env.calls.read.length, 1);
});

test('clipboard: working browser clipboard is unchanged; native unavailability stays an error', async () => {
  const env = clipboardEnv({ nativeClipboard: { readText: () => Promise.resolve('browser-text') } });
  assert.equal(await env.clipboard.readText(), 'browser-text');
  assert.deepEqual(env.calls.read, []);
  const unavailable = clipboardEnv({ activation: { isActive: true } });
  unavailable.bridge.readText = () => null;
  await assert.rejects(unavailable.clipboard.readText(), /Clipboard unavailable/);
});

test('clipboard: reinjection in the same document preserves the wrapper functions', () => {
  const env = clipboardEnv();
  const read = env.clipboard.readText;
  const write = env.clipboard.writeText;
  env.run(clipboard());
  assert.equal(env.clipboard.readText, read);
  assert.equal(env.clipboard.writeText, write);
});
