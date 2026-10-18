#!/usr/bin/env node
/**
 * visual-gateway/gateway.js — CLI → visual bridge for DSH Mobile.
 *
 * Wraps a stdin/stdout CLI agent (Claude Code, Codex, OpenCode) and exposes a
 * touch-friendly chat-and-files UI on a loopback port so the Android app never
 * has to render a terminal.
 *
 *   node gateway.js --backend claude --port 3091 --host 127.0.0.1 -- <cmd> [args]
 *
 * Responsibilities:
 *  1. Spawn the backend with piped stdio (no TTY, no xterm).
 *  2. Parse stdout/stderr into structured events (chat text vs. approval prompt).
 *  3. Serve an HTTP + SSE surface on the loopback port:
 *       GET  /            -> the visual chat UI (HTML)
 *       GET  /health      -> {"ok":true,...} HTTP fallback liveness probe
 *       GET  /events      -> SSE stream of {type:...} events
 *       GET  /files?path= -> workspace file listing for the Files tab
 *       POST /send        -> {"text":"..."} writes one line to the child's stdin
 *  4. Never bind to a non-loopback interface.
 *
 * Design notes for the Android side:
 *  - EngineWatchdog probes liveness by opening a TCP connection to the port
 *    first; only if that fails does it issue this /health request. So /health
 *    is a fallback, not the primary signal — and unlike DSH (whose web server
 *    has NO /health route at all and answers 401 at / behind a browser-trust
 *    fence), the gateway can afford to serve one. Keep it cheap and 2xx while
 *    alive so a restarted agent is noticed quickly.
 *  - Approval prompts are surfaced to the app as {type:"approval"} events;
 *    the native Bottom Sheet answers by POSTing /send with yes/no.
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

/* ------------------------------------------------------------------ */
/* Args                                                                */
/* ------------------------------------------------------------------ */
function parseArgs(argv) {
  const out = {
    backend: 'claude',
    port: 3091,
    host: '127.0.0.1',
    cwd: process.cwd(),
    command: null,
    commandArgs: [],
  };
  let i = 0;
  for (; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--') { out.command = argv[i + 1] || null; out.commandArgs = argv.slice(i + 2); break; }
    if (a === '--backend') out.backend = argv[++i];
    else if (a === '--port') out.port = parseInt(argv[++i], 10);
    else if (a === '--host') out.host = argv[++i];
    else if (a === '--cwd') out.cwd = argv[++i];
    else if (a === '--workspace') out.cwd = argv[++i];
  }
  if (!Number.isInteger(out.port) || out.port <= 0) out.port = 3091;
  // Security: refuse anything that is not loopback.
  out.host = '127.0.0.1';
  return out;
}

const CFG = parseArgs(process.argv.slice(2));

/* ------------------------------------------------------------------ */
/* Event bus (SSE clients)                                              */
/* ------------------------------------------------------------------ */
const clients = new Set();

// The child is spawned at listen() time, but the WebView only opens /events
// after the page loads — early output (notably the FIRST approval prompt)
// would otherwise be emitted to zero clients and lost, hanging the agent on
// a blocked stdin read forever. New clients therefore replay this backlog.
const BACKLOG_MAX = 200;
const backlog = [];

function emit(ev) {
  backlog.push(ev);
  while (backlog.length > BACKLOG_MAX) backlog.shift();
  const payload = `data: ${JSON.stringify(ev)}\n\n`;
  for (const res of clients) {
    try { res.write(payload); } catch (_) { clients.delete(res); }
  }
}

function log(...parts) {
  process.stderr.write('[gateway] ' + parts.join(' ') + '\n');
}

/* ------------------------------------------------------------------ */
/* Approval prompt detection                                           */
/* ------------------------------------------------------------------ */
/* NOTE: keep in sync with VisualGateway.APPROVAL_PATTERNS (Kotlin) and
 * ApprovalClassificationTest — a missed pattern here means the agent blocks
 * forever on stdin with no approval sheet. */
const APPROVAL_PATTERNS = [
  /do you want to run/i,
  /allow .{0,40}command/i,
  /allow .{0,40}to run/i,
  /confirm .{0,40}execut/i,
  /\[y\/n\]/i,
  /proceed\?/i,
];
const FILE_PATTERNS = [
  /allow file modification/i,
  /overwrite .{0,60}\?/i,
  /create file .{0,60}\?/i,
  /apply diff/i,
];

function classify(line) {
  if (APPROVAL_PATTERNS.some((re) => re.test(line))) return 'command';
  if (FILE_PATTERNS.some((re) => re.test(line))) return 'file_write';
  return null;
}

/* ------------------------------------------------------------------ */
/* Child process                                                       */
/* ------------------------------------------------------------------ */
let child = null;
/**
 * Grace period between announcing a child's exit over SSE and exiting
 * ourselves, so an attached UI receives the event before the port closes.
 */
const EXIT_GRACE_MS = 250;
let startedAt = Date.now();
let lastActivityAt = Date.now();
const seenPrompts = new Set();

function startChild() {
  if (!CFG.command) {
    log('no backend command given (use `-- <cmd> [args..]`); serving UI only');
    return;
  }
  const argv = [CFG.command].concat(CFG.commandArgs);
  log('spawn:', argv.join(' '));
  child = spawn(argv[0], argv.slice(1), {
    cwd: CFG.cwd,
    env: Object.assign({}, process.env, {
      TERM: 'dumb',
      NO_COLOR: '1',
      // Keep the CLI non-interactive; we answer prompts over stdin.
      CI: '',
    }),
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  pump(child.stdout, 'stdout');
  pump(child.stderr, 'stderr');

  child.on('exit', (code, signal) => {
    log('child exited', { code, signal });
    emit({ type: 'exit', code, signal });
    // Follow the child down. The service owns restarts, but it waits on THIS
    // process - so staying alive here means the service never learns the
    // backend died, and the app sits on "Running" with a dead backend and no
    // error anywhere (observed on-device with codex exiting immediately).
    // Exiting lets the service's stratified restart, and its give-up path,
    // actually run. Still no self-respawn: that WOULD fight the watchdog and
    // duplicate children.
    setTimeout(() => process.exit(code === 0 ? 0 : (code || 1)), EXIT_GRACE_MS);
  });
  child.on('error', (err) => {
    log('child error', err && err.message);
    emit({ type: 'error', message: String((err && err.message) || err) });
  });
}

function pump(stream, name) {
  let buf = '';
  stream.on('data', (chunk) => {
    buf += chunk;
    lastActivityAt = Date.now();
    let idx;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).replace(/\r$/, '');
      buf = buf.slice(idx + 1);
      handleLine(line, name);
    }
    // Guard against unbounded buffering from a chatty backend.
    if (buf.length > 64 * 1024) buf = buf.slice(-32 * 1024);
  });
  stream.on('end', () => { if (buf.trim()) handleLine(buf.trim(), name); });
}

function handleLine(line, name) {
  if (!line.trim()) return;
  emit({ type: 'raw', stream: name, text: line });

  const kind = classify(line);
  if (kind) {
    const key = name + '::' + line.trim();
    if (!seenPrompts.has(key)) {
      seenPrompts.add(key);
      if (seenPrompts.size > 200) {
        const first = seenPrompts.values().next().value;
        seenPrompts.delete(first);
      }
      emit({
        type: 'approval',
        id: 'appr-' + Date.now() + '-' + line.length,
        kind,
        backend: CFG.backend,
        summary: line.trim().slice(0, 280),
      });
    }
    return; // never render a prompt as chat text
  }

  const text = extractText(line);
  if (text) emit({ type: 'message', role: 'assistant', text });
}

/** Pull human-readable text out of JSONL events or plain lines. */
function extractText(line) {
  const trimmed = line.trim();
  if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
    try {
      const obj = JSON.parse(trimmed);
      for (const k of ['text', 'delta', 'content', 'message', 'result']) {
        const v = obj[k];
        if (typeof v === 'string' && v.trim()) return v;
        if (v && typeof v === 'object' && typeof v.text === 'string') return v.text;
      }
      return null;
    } catch (_) { /* not JSON — fall through */ }
  }
  return trimmed;
}

function sendToChild(text) {
  if (!child || !child.stdin || child.stdin.destroyed) return false;
  try {
    child.stdin.write(text + '\n');
    return true;
  } catch (err) {
    log('stdin write failed:', err && err.message);
    return false;
  }
}

/* ------------------------------------------------------------------ */
/* Files API                                                           */
/* ------------------------------------------------------------------ */
const IGNORED = new Set(['.git', 'node_modules', '.gradle', 'build', '.idea']);

function listFiles(rel) {
  const base = path.resolve(CFG.cwd, rel && rel !== '.' ? rel : '.');
  // Never escape the workspace root.
  if (!base.startsWith(path.resolve(CFG.cwd))) {
    return { error: 'outside workspace' };
  }
  let entries;
  try {
    entries = fs.readdirSync(base, { withFileTypes: true });
  } catch (err) {
    return { error: String(err.message || err), path: rel || '.' };
  }
  const items = [];
  for (const e of entries) {
    if (IGNORED.has(e.name)) continue;
    const abs = path.join(base, e.name);
    let size = 0;
    if (e.isFile()) {
      try { size = fs.statSync(abs).size; } catch (_) { /* ignore */ }
    }
    items.push({
      name: e.name,
      path: path.relative(path.resolve(CFG.cwd), abs) || e.name,
      dir: e.isDirectory(),
      size,
    });
  }
  items.sort((a, b) => (a.dir === b.dir ? a.name.localeCompare(b.name) : a.dir ? -1 : 1));
  return { cwd: path.resolve(CFG.cwd), path: rel || '.', items: items.slice(0, 500) };
}

function readFile(rel) {
  const root = path.resolve(CFG.cwd);
  const abs = path.resolve(root, rel);
  if (!abs.startsWith(root)) return { error: 'outside workspace' };
  try {
    const stat = fs.statSync(abs);
    if (stat.size > 512 * 1024) return { error: 'file too large to preview' };
    return { path: rel, text: fs.readFileSync(abs, 'utf8').slice(0, 400 * 1024) };
  } catch (err) {
    return { error: String(err.message || err) };
  }
}

/* ------------------------------------------------------------------ */
/* UI                                                                  */
/* ------------------------------------------------------------------ */
const UI_HTML = fs.readFileSync(path.join(__dirname, 'ui.html'), 'utf8');

function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => {
      data += c;
      if (data.length > 1024 * 1024) req.destroy();
    });
    req.on('end', () => resolve(data));
    req.on('error', () => resolve(''));
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const p = url.pathname;

  try {
    if (p === '/health') {
      // HTTP fallback for EngineWatchdog: reached only when its TCP connect
      // already failed. Must stay cheap and 2xx while this process is alive.
      return json(res, 200, {
        ok: true,
        backend: CFG.backend,
        pid: child ? child.pid : null,
        alive: child ? !child.killed : false,
        uptimeMs: Date.now() - startedAt,
        idleMs: Date.now() - lastActivityAt,
      });
    }

    if (p === '/' || p === '/index.html') {
      res.writeHead(200, {
        'content-type': 'text/html; charset=utf-8',
        'cache-control': 'no-store',
      });
      return res.end(UI_HTML);
    }

    if (p === '/events') {
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      });
      res.write(': connected\n\n');
      // Replay anything emitted before this client attached.
      for (const ev of backlog) {
        try { res.write(`data: ${JSON.stringify(ev)}\n\n`); } catch (_) { break; }
      }
      clients.add(res);
      const ka = setInterval(() => { try { res.write(': ka\n\n'); } catch (_) {} }, 20000);
      req.on('close', () => { clearInterval(ka); clients.delete(res); });
      return undefined;
    }

    if (p === '/files') {
      return json(res, 200, listFiles(url.searchParams.get('path') || '.'));
    }

    if (p === '/file') {
      return json(res, 200, readFile(url.searchParams.get('path') || ''));
    }

    if (p === '/send' && req.method === 'POST') {
      const raw = await readBody(req);
      let text = raw;
      try { text = JSON.parse(raw).text || ''; } catch (_) { /* plain body */ }
      const ok = sendToChild(String(text).replace(/[\r\n]+/g, ' '));
      if (ok) emit({ type: 'echo', role: 'user', text: String(text) });
      return json(res, ok ? 200 : 503, { ok });
    }

    return json(res, 404, { error: 'not found' });
  } catch (err) {
    return json(res, 500, { error: String((err && err.message) || err) });
  }
});

server.listen(CFG.port, CFG.host, () => {
  startedAt = Date.now();
  lastActivityAt = startedAt;
  log(`listening on http://${CFG.host}:${CFG.port} (backend=${CFG.backend})`);
  startChild();
});

function shutdown() {
  log('shutting down');
  if (child && !child.killed) { try { child.kill('SIGTERM'); } catch (_) {} }
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1500).unref();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
process.on('uncaughtException', (e) => log('uncaught:', e && e.stack));