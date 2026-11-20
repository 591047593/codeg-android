const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { existsSync } = require('node:fs');
const fs = require('node:fs/promises');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { before, after, test } = require('node:test');
const { setTimeout: delay } = require('node:timers/promises');
const scriptPath = path.resolve(__dirname,
  '../app/src/main/java/app/codeg/android/shell/display/MobileTurnStats.kt');
let server;
let profile;
let browserSocketUrl;
let browserProcess;
let results;

// Mirrors the actual v0.46.0/v0.47.0 LiveTurnStats markup and container rules.
// The regression is the computed display, not the spelling of our stylesheet.
const fixtureCss = `
  :root { font-size: 16px; }
  body { margin: 0; font-family: sans-serif; }
  * { box-sizing: border-box; }
  [class~="@container/turnstats"] { container-type: inline-size; container-name: turnstats; }
  .stats-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: center;
    gap: .25rem .75rem; min-height: 2rem; padding: .25rem 1rem; font-size: .75rem; }
  .hidden { display: none; }
  @container turnstats (min-width: 24rem) {
    [class~="@[24rem]/turnstats:inline"] { display: inline; }
    [class~="@[24rem]/turnstats:inline-flex"] { display: inline-flex; }
  }
  @container turnstats (min-width: 30rem) {
    [class~="@[30rem]/turnstats:inline"] { display: inline; }
    [class~="@[30rem]/turnstats:inline-flex"] { display: inline-flex; }
  }
`;

function browserFixture(injection) {
  const encoded = JSON.stringify(injection).replaceAll('<', '\\u003c');
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
  <style>${fixtureCss}</style></head><body><script>
  const injection = ${encoded};
  const checks = [];
  const check = (name, actual, expected) => checks.push({name, actual, expected});
  const install = (origin = location.origin) => {
    const source = injection.replaceAll('$quotedServerOrigin', JSON.stringify(origin));
    new Function(source)();
  };
  function row(width, output = true) {
    const container = document.createElement('div');
    container.className = '@container/turnstats shrink-0';
    container.style.width = width + 'px';
    container.innerHTML = '<div class="stats-row">' +
      '<span data-test="elapsed">1m 12s</span>' +
      '<span class="hidden @[24rem]/turnstats:inline">|</span>' +
      '<span data-test="edits" class="hidden @[24rem]/turnstats:inline-flex">3F +12/-2</span>' +
      (output ? '<span data-test="separator" class="hidden @[30rem]/turnstats:inline">|</span>' +
      '<span data-test="rate" class="hidden tabular-nums @[30rem]/turnstats:inline-flex">42.5 tok/s</span>' : '') +
      '</div>';
    document.body.appendChild(container);
    return container;
  }
  const visible = (container, name) => getComputedStyle(container.querySelector('[data-test="' + name + '"]')).display !== 'none';
  const report = result => fetch('/results', { method: 'POST', body: JSON.stringify(result) });
  (async () => {
    const narrow = row(360);
    const wide = row(768);
    check('fixture hides rate below 30rem before adaptation', visible(narrow, 'rate'), false);
    check('fixture shows rate on wide containers before adaptation', visible(wide, 'rate'), true);
    install('https://not-the-selected-server.invalid');
    check('cross-origin injection does not change the page', document.querySelectorAll('style').length, 1);
    const frame = document.createElement('iframe');
    const loaded = new Promise(resolve => { frame.onload = resolve; });
    frame.src = '/frame';
    document.body.appendChild(frame);
    await loaded;
    frame.contentWindow.Function(injection.replaceAll('$quotedServerOrigin', JSON.stringify(location.origin)))();
    check('same-origin child frame is not modified', frame.contentDocument.querySelectorAll('style').length, 0);
    frame.remove();
    install();
    for (const width of [320, 360, 390, 430, 768]) {
      const container = row(width);
      check('rate visible at ' + width + 'px', visible(container, 'rate'), true);
      check('separator visible at ' + width + 'px', visible(container, 'separator'), true);
      const content = container.firstElementChild;
      check('stats do not overflow at ' + width + 'px', content.scrollWidth <= content.clientWidth, true);
    }
    check('narrow file figures keep their original visibility', visible(narrow, 'edits'), false);
    check('wide file figures keep their original visibility', visible(wide, 'edits'), true);
    check('elapsed label stays visible', visible(narrow, 'elapsed'), true);
    check('web-provided rate is not rewritten', narrow.querySelector('[data-test="rate"]').textContent, '42.5 tok/s');
    const unrelated = document.createElement('span');
    unrelated.className = 'hidden @[30rem]/turnstats:inline-flex';
    document.body.appendChild(unrelated);
    check('unrelated hidden elements remain hidden', getComputedStyle(unrelated).display, 'none');
    install(); install();
    check('repeated installation adds only one adaptation stylesheet', document.querySelectorAll('style').length, 2);
    document.documentElement.style.fontSize = '24px';
    const zoomed = row(360);
    check('rate remains visible with 150 percent root font size', visible(zoomed, 'rate'), true);
    check('zoomed stats can wrap without horizontal overflow', zoomed.firstElementChild.scrollWidth <= zoomed.firstElementChild.clientWidth, true);
    document.documentElement.style.fontSize = '16px';
    const late = row(390);
    check('stats mounted after installation are visible', visible(late, 'rate'), true);
    late.firstElementChild.innerHTML = '<span data-test="rate" class="hidden @[30rem]/turnstats:inline-flex">73.1 tok/s</span>';
    check('SPA rerender keeps the rate visible', visible(late, 'rate'), true);
    const waiting = row(390, false);
    check('no fabricated speed when web has no rate', waiting.querySelector('[data-test="rate"]') === null, true);
    await report({ checks });
  })().catch(error => report({ error: error.stack }));
  </script></body></html>`;
}

before(async () => {
  const candidates = [process.env.CHROME_BIN,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium'];
  const browser = candidates.find(value => value && existsSync(value));
  assert.ok(browser, 'Set CHROME_BIN to an installed Chromium browser; this test never downloads one.');
  let injection = '';
  if (existsSync(scriptPath)) {
    const source = await fs.readFile(scriptPath, 'utf8');
    const raw = source.match(/=\s*"""([\s\S]*?)"""\.trimIndent\(\)/);
    assert.ok(raw, 'Expected the actual mobileTurnStatsScript Kotlin raw string.');
    injection = raw[1];
  }
  // Before the fix there is no adaptation script; the real narrow-screen
  // fixture still runs and must fail the visibility assertions.
  let fixtureResult;
  server = http.createServer(async (req, res) => {
    if (req.method === 'POST' && req.url === '/results') {
      try {
        let body = '';
        for await (const chunk of req) {
          body += chunk;
          if (body.length > 64 * 1024) throw new Error('Fixture report is too large');
        }
        fixtureResult = JSON.parse(body);
        res.writeHead(204).end();
      } catch (error) {
        fixtureResult = { error: error.message };
        res.writeHead(400).end();
      }
      return;
    }
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.end(req.url === '/frame' ? '<!doctype html><html><head></head><body></body></html>' : browserFixture(injection));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  profile = await fs.mkdtemp(path.join(os.tmpdir(), 'codeg-token-rate-'));
  const args = ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--disable-background-networking', '--disable-component-update', '--disable-sync',
    '--disable-dev-shm-usage', '--window-size=390,844', '--remote-debugging-port=0',
    '--user-data-dir=' + profile, 'http://127.0.0.1:' + server.address().port];
  if (process.platform === 'linux' && process.getuid?.() === 0) args.unshift('--no-sandbox');
  browserProcess = spawn(browser, args, {stdio: 'ignore'});
  let launchError;
  browserProcess.on('error', error => { launchError = error; });
  // Chrome on Windows starts its own browser process, so CLI stdout/exit is
  // not a portable result channel. The local fixture reports over loopback.
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    if (launchError) throw launchError;
    if (!browserSocketUrl) {
      try {
        const [port, browserPath] = (await fs.readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).trim().split(/\r?\n/);
        browserSocketUrl = 'ws://127.0.0.1:' + port + browserPath;
      } catch {}
    }
    if (fixtureResult && browserSocketUrl) break;
    await delay(100);
  }
  assert.ok(fixtureResult, 'Browser fixture did not report within 30 seconds.');
  assert.ifError(fixtureResult.error);
  results = fixtureResult.checks;
});

after(async () => {
  if (browserSocketUrl) {
    await new Promise((resolve, reject) => {
      const socket = new WebSocket(browserSocketUrl);
      const timer = setTimeout(() => { socket.close(); reject(new Error('Could not close the owned browser')); }, 5000);
      socket.addEventListener('open', () => socket.send(JSON.stringify({id: 1, method: 'Browser.close'})));
      socket.addEventListener('close', () => { clearTimeout(timer); resolve(); });
      socket.addEventListener('error', error => { clearTimeout(timer); reject(error); });
    });
  } else if (browserProcess && browserProcess.exitCode === null) {
    browserProcess.kill();
  }
  if (server) await new Promise(resolve => server.close(resolve));
  if (profile) await fs.rm(profile, {recursive: true, force: true, maxRetries: 20, retryDelay: 200});
});

const groups = [
  ['upstream fixture reproduces narrow-screen hiding', name => name.startsWith('fixture ')],
  ['adaptation is limited to selected-server top-level documents', name => name.includes('origin') || name.includes('child frame')],
  ['token rate remains visible across phone and tablet widths', name => name.startsWith('rate visible') || name.startsWith('separator visible') || name.startsWith('stats do not overflow')],
  ['other stats and upstream values are preserved', name => name.includes('figures') || name.includes('elapsed') || name.includes('not rewritten') || name.includes('unrelated')],
  ['installation is idempotent and survives zoom and SPA rendering', name => name.includes('repeated') || name.includes('root font') || name.includes('zoomed') || name.includes('mounted') || name.includes('rerender') || name.includes('fabricated')],
];
for (const [name, select] of groups) {
  test(name, () => {
    const selected = results.filter(result => select(result.name));
    assert.ok(selected.length, 'No browser checks matched this group.');
    for (const check of selected) assert.deepEqual(check.actual, check.expected, check.name);
  });
}
