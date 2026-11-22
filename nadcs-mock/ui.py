"""The submissions page of the NADCS test server: one self-contained HTML document (no external files, fonts or scripts).

It contains no data and no key. It asks for the API key, keeps it for this browser tab only (sessionStorage) and calls the
JSON API of this same server. Everything that comes from a submission (ids, error messages, the received body) is
inserted as text, never as HTML, because a submitted body is untrusted input; the Content-Security-Policy sent with the
page (see main.py) backs that up by allowing only this page's own script and style.
"""

_PAGE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>NADCS test server: submissions</title>
<style nonce="__NONCE__">
  :root { --bg:#f4f6f9; --card:#fff; --ink:#1b2430; --mute:#667085; --line:#e3e8ef; --blue:#2563eb;
          --ok:#067647; --okbg:#ecfdf3; --okline:#abefc6; --bad:#b42318; --badbg:#fef3f2; --badline:#fecdca;
          --err:#b54708; --errbg:#fffaeb; --errline:#fedf89; --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--ink); font: 14px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  header { background:#fff; border-bottom:1px solid var(--line); padding:12px 20px; display:flex; flex-wrap:wrap; gap:10px 16px; align-items:center; justify-content:space-between; }
  .brand { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
  .brand h1 { margin:0; font-size:17px; }
  .tag { font-size:11px; font-weight:700; letter-spacing:.04em; padding:2px 8px; border-radius:999px; background:#eff6ff; color:#1d4ed8; border:1px solid #bfdbfe; }
  .hright { display:flex; align-items:center; gap:10px; flex-wrap:wrap; font-size:12px; color:var(--mute); }
  main { max-width:1240px; margin:0 auto; padding:18px 20px 60px; }
  button, input { font: inherit; }
  button { cursor:pointer; border:1px solid #cfd6e0; background:#fff; border-radius:7px; padding:6px 11px; color:var(--ink); }
  button:hover { background:#f8fafc; }
  button.primary { background:var(--blue); border-color:var(--blue); color:#fff; font-weight:600; }
  button.primary:hover { background:#1d4ed8; }
  button:focus-visible, input:focus-visible, tr:focus-visible, pre:focus-visible { outline:2px solid var(--blue); outline-offset:1px; }
  input[type=text], input[type=password], input[type=search] { border:1px solid #cfd6e0; border-radius:7px; padding:7px 10px; background:#fff; }
  .hidden { display:none !important; }
  #login { max-width:460px; margin:70px auto; background:var(--card); border:1px solid var(--line); border-radius:12px; padding:24px; }
  #login h2 { margin:0 0 6px; font-size:16px; }
  #login p { color:var(--mute); margin:6px 0 14px; font-size:13px; }
  #login form { display:flex; gap:8px; }
  #login input { flex:1; }
  .msg { margin-top:10px; font-size:13px; color:var(--bad); min-height:1em; }
  .tiles { display:grid; grid-template-columns:repeat(auto-fit, minmax(150px, 1fr)); gap:12px; margin-bottom:14px; }
  .tile { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:12px 14px; }
  .tile b { display:block; font-size:24px; line-height:1.1; }
  .tile span { font-size:12px; color:var(--mute); }
  .tile.ok b { color:var(--ok); } .tile.bad b { color:var(--bad); } .tile.err b { color:var(--err); }
  .toolbar { display:flex; flex-wrap:wrap; gap:8px 12px; align-items:center; margin-bottom:10px; }
  .seg { display:inline-flex; border:1px solid #cfd6e0; border-radius:8px; overflow:hidden; background:#fff; }
  .seg button { border:0; border-radius:0; border-right:1px solid #cfd6e0; padding:6px 12px; }
  .seg button:last-child { border-right:0; }
  .seg button[aria-pressed=true] { background:#eaf1ff; color:#1d4ed8; font-weight:600; }
  .grow { flex:1; min-width:180px; }
  .note { font-size:12px; color:var(--mute); }
  .mb { margin-bottom:8px; }
  .banner { border-radius:8px; padding:8px 12px; margin-bottom:12px; font-size:13px; border:1px solid var(--errline); background:var(--errbg); color:#7a2e0e; }
  .tablewrap { background:var(--card); border:1px solid var(--line); border-radius:10px; overflow-x:auto; }
  table { width:100%; border-collapse:collapse; font-size:13px; }
  th { text-align:left; font-size:11px; text-transform:uppercase; letter-spacing:.05em; color:var(--mute); background:#fafbfc; padding:9px 12px; border-bottom:1px solid var(--line); white-space:nowrap; }
  td { padding:9px 12px; border-bottom:1px solid #eef1f5; vertical-align:top; }
  tbody tr { cursor:pointer; }
  tbody tr:hover { background:#f6f9ff; }
  tbody tr.sel { background:#eaf1ff; }
  tr:last-child td { border-bottom:0; }
  .mono { font-family:var(--mono); font-size:12px; word-break:break-all; }
  .nw { white-space:nowrap; } .nw.mono { word-break:normal; }
  td.res { min-width:240px; max-width:380px; }
  .small { font-size:12px; color:var(--mute); }
  .pill { display:inline-block; font-size:11px; font-weight:700; padding:2px 8px; border-radius:999px; border:1px solid; }
  .pill.ACCEPTED { color:var(--ok); background:var(--okbg); border-color:var(--okline); }
  .pill.REJECTED { color:var(--bad); background:var(--badbg); border-color:var(--badline); }
  .pill.ERROR { color:var(--err); background:var(--errbg); border-color:var(--errline); }
  .empty { padding:40px 16px; text-align:center; color:var(--mute); }
  /* detail drawer */
  #shade { position:fixed; inset:0; background:rgba(15,23,42,.35); z-index:10; }
  #drawer { position:fixed; top:0; right:0; bottom:0; width:min(760px, 100vw); background:#fff; z-index:11; box-shadow:-8px 0 30px rgba(15,23,42,.18); overflow-y:auto; }
  #drawer .dh { position:sticky; top:0; background:#fff; border-bottom:1px solid var(--line); padding:12px 18px; display:flex; justify-content:space-between; align-items:center; gap:10px; z-index:1; }
  #drawer .dh h2 { margin:0; font-size:15px; font-family:var(--mono); word-break:break-all; }
  #drawer .db { padding:16px 18px 40px; }
  .result { border:1px solid; border-radius:10px; padding:12px 14px; margin-bottom:14px; }
  .result.ACCEPTED { background:var(--okbg); border-color:var(--okline); color:#054f31; }
  .result.REJECTED { background:var(--badbg); border-color:var(--badline); color:#7a271a; }
  .result.ERROR { background:var(--errbg); border-color:var(--errline); color:#7a2e0e; }
  .result .big { font-size:16px; font-weight:700; margin-bottom:2px; }
  .result .rid { font-family:var(--mono); font-size:13px; display:flex; gap:8px; align-items:center; flex-wrap:wrap; margin-top:4px; }
  h3 { font-size:12px; text-transform:uppercase; letter-spacing:.05em; color:var(--mute); margin:18px 0 8px; }
  dl { display:grid; grid-template-columns:150px 1fr; gap:6px 12px; margin:0; font-size:13px; }
  dt { color:var(--mute); } dd { margin:0; word-break:break-all; }
  table.mini { border:1px solid var(--line); border-radius:8px; overflow:hidden; }
  table.mini td, table.mini th { padding:6px 10px; }
  table.mini td:nth-child(1), table.mini td:nth-child(2) { white-space:nowrap; word-break:normal; }
  .scroll { overflow-x:auto; }
  table.mini tbody tr { cursor:default; } table.mini tbody tr:hover { background:transparent; }
  .row { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-bottom:8px; }
  pre.body { margin:0; background:#0f172a; color:#e2e8f0; border-radius:8px; padding:12px 14px; font:12px/1.5 var(--mono); max-height:420px; overflow:auto; white-space:pre-wrap; word-break:break-all; }
  .small-btn { padding:3px 9px; font-size:12px; }
  .small-btn[aria-pressed=true] { background:#eaf1ff; color:#1d4ed8; border-color:#bfdbfe; font-weight:600; }
  @media (max-width:640px) { dl { grid-template-columns:1fr; } dt { margin-top:6px; } }
</style>
</head>
<body>
<header>
  <div class="brand">
    <h1>NADCS test server</h1>
    <span class="tag">TEST DOUBLE &middot; NOT THE AGENCY</span>
  </div>
  <div class="hright">
    <span id="mode"></span>
    <span id="conn" role="status" aria-live="polite"></span>
    <button id="signout" class="small-btn hidden" type="button">Forget key</button>
  </div>
</header>

<section id="login" class="hidden">
  <h2>API key</h2>
  <p>Enter the key this server was deployed with (<span class="mono">NADCS_MOCK_API_KEYS</span>). It is kept for this browser tab only and sent only to this server.</p>
  <form id="loginform">
    <input id="keyinput" type="password" autocomplete="off" spellcheck="false" placeholder="API key" aria-label="API key" required>
    <button class="primary" type="submit">Open</button>
  </form>
  <div id="loginmsg" class="msg" role="alert"></div>
</section>

<main id="app" class="hidden">
  <div id="modebanner" class="banner hidden" role="status"></div>
  <div class="tiles">
    <div class="tile"><b id="t-all">0</b><span>received (kept)</span></div>
    <div class="tile ok"><b id="t-ok">0</b><span>accepted, receipt issued</span></div>
    <div class="tile bad"><b id="t-bad">0</b><span>rejected</span></div>
    <div class="tile err"><b id="t-err">0</b><span>failed on purpose (error mode)</span></div>
  </div>
  <div class="toolbar">
    <div class="seg" role="group" aria-label="Filter by result">
      <button type="button" data-f="ALL" aria-pressed="true">All</button>
      <button type="button" data-f="ACCEPTED" aria-pressed="false">Accepted</button>
      <button type="button" data-f="REJECTED" aria-pressed="false">Rejected</button>
      <button type="button" data-f="ERROR" aria-pressed="false">Errors</button>
    </div>
    <input id="q" class="grow" type="search" placeholder="Search receipt, submission id, VinaBeef reference (TX-...), report, farm" aria-label="Search">
    <label class="note"><input id="auto" type="checkbox" checked> refresh every 5 s</label>
    <button id="refresh" type="button">Refresh</button>
  </div>
  <div class="note mb" id="keptnote"></div>
  <div class="tablewrap">
    <table>
      <thead><tr>
        <th>Received</th><th>Result</th><th>HTTP</th><th>Report</th><th>Records</th><th>Body</th><th>Warn.</th><th>VinaBeef ref.</th><th>Submission</th>
      </tr></thead>
      <tbody id="rows"></tbody>
    </table>
    <div id="empty" class="empty hidden"></div>
  </div>
</main>

<div id="shade" class="hidden"></div>
<aside id="drawer" class="hidden" role="dialog" aria-modal="true" aria-labelledby="dtitle">
  <div class="dh"><h2 id="dtitle"></h2><button id="dclose" type="button" aria-label="Close">Close (Esc)</button></div>
  <div class="db" id="dbody"></div>
</aside>

<script nonce="__NONCE__">
(() => {
  'use strict';
  const KEY_STORE = 'nadcs-mock-key';
  const $ = (id) => document.getElementById(id);

  // DOM builder: children that are strings become TEXT nodes, so nothing received from a sender can become markup.
  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat()) {
      if (kid === null || kid === undefined || kid === false) continue;
      el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
    }
    return el;
  }

  let key = null;
  try { key = sessionStorage.getItem(KEY_STORE); } catch (e) { key = null; }
  const state = { items: [], meta: null, filter: 'ALL', query: '', selected: null, busy: false, timer: null };

  const fmtBytes = (n) => n == null ? '' : n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
  const fmtTime = (iso) => { const d = new Date(iso); return isNaN(d) ? String(iso) : d.toLocaleString(); };

  async function api(path) {
    const res = await fetch(path, { headers: { 'X-API-Key': key || '', 'Accept': 'application/json' }, cache: 'no-store' });
    if (res.status === 401) { const e = new Error('That key was not accepted.'); e.auth = true; throw e; }
    let body = null;
    try { body = await res.json(); } catch (e) { body = null; }
    if (!res.ok) { const e = new Error((body && body.summary) || ('HTTP ' + res.status)); e.status = res.status; throw e; }
    return body;
  }

  function setConn(text, bad) { const el = $('conn'); el.textContent = text; el.style.color = bad ? '#b42318' : ''; }

  function showLogin(message) {
    $('login').classList.remove('hidden'); $('app').classList.add('hidden'); $('signout').classList.add('hidden');
    closeDrawer(); $('loginmsg').textContent = message || ''; setConn('', false); $('mode').textContent = '';
    $('keyinput').focus();
  }

  async function load(quiet) {
    if (state.busy || !key) return;
    state.busy = true;
    const slow = quiet ? null : setTimeout(() => setConn('Waiting for the server (a sleeping free-tier instance needs up to a minute)...', false), 3000);
    if (!quiet) setConn('Loading...', false);
    try {
      const data = await api('/v2/surveillance/submissions?limit=200');
      state.items = data.items || []; state.meta = data;
      $('login').classList.add('hidden'); $('app').classList.remove('hidden'); $('signout').classList.remove('hidden');
      setConn('Updated ' + new Date().toLocaleTimeString(), false);
      renderAll();
      if (!state.hashHandled) { state.hashHandled = true; handleHash(); }
    } catch (e) {
      if (e.auth) { key = null; try { sessionStorage.removeItem(KEY_STORE); } catch (x) { /* ignore */ } showLogin(e.message); }
      else setConn('Cannot reach the server: ' + e.message, true);
    } finally { if (slow) clearTimeout(slow); state.busy = false; }
  }

  function renderAll() {
    const m = state.meta || {}, c = m.counts || {};
    $('t-all').textContent = m.kept ?? 0; $('t-ok').textContent = c.ACCEPTED ?? 0; $('t-bad').textContent = c.REJECTED ?? 0; $('t-err').textContent = c.ERROR ?? 0;
    $('mode').textContent = m.behavior ? 'mode: ' + m.behavior : '';
    const mb = $('modebanner');
    if (m.behavior && m.behavior !== 'normal') { mb.textContent = 'This server is set to "' + m.behavior + '": it is answering on purpose in a way a normal server would not (POST /admin/behavior {"mode":"normal"} restores it).'; mb.classList.remove('hidden'); }
    else mb.classList.add('hidden');
    $('keptnote').textContent = 'Showing the last ' + (m.kept ?? 0) + ' of at most ' + (m.maxKept ?? 200) + ' submissions. They are kept in memory only and disappear when the server restarts or a free instance spins down.';
    for (const b of document.querySelectorAll('.seg button')) b.setAttribute('aria-pressed', String(b.dataset.f === state.filter));
    renderRows();
  }

  function matches(it, q) {
    if (!q) return true;
    return [it.submissionId, it.receiptId, it.clientRef, it.reportType, it.farmScope, it.summary].some((v) => v && String(v).toLowerCase().includes(q));
  }

  function renderRows() {
    const q = state.query.trim().toLowerCase();
    const rows = state.items.filter((it) => (state.filter === 'ALL' || it.outcome === state.filter) && matches(it, q));
    const tbody = $('rows'); tbody.replaceChildren();
    for (const it of rows) {
      const tr = h('tr', { tabindex: '0', 'data-id': it.submissionId, 'aria-label': 'Open ' + it.submissionId, class: it.submissionId === state.selected ? 'sel' : '',
        onclick: () => openDetail(it.submissionId), onkeydown: (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); openDetail(it.submissionId); } } },
        h('td', { class: 'nw', title: it.receivedAt }, fmtTime(it.receivedAt)),
        h('td', { class: 'res' }, h('span', { class: 'pill ' + it.outcome }, it.outcome),
          h('div', { class: it.receiptId ? 'mono' : 'small' }, it.receiptId || (it.summary || '').slice(0, 110))),
        h('td', { class: 'nw' }, it.httpStatus),
        h('td', { class: 'nw' }, h('div', {}, it.reportType || '-'), h('div', { class: 'small' }, it.farmScope || '')),
        h('td', {}, it.recordCount ?? ''),
        h('td', { class: 'nw' }, h('div', {}, fmtBytes(it.bytes)), h('div', { class: 'small' }, it.format || '')),
        h('td', {}, it.warningCount || ''),
        h('td', { class: 'mono nw' }, it.clientRef || ''),
        h('td', { class: 'mono nw' }, it.submissionId));
      tbody.append(tr);
    }
    const empty = $('empty');
    if (!rows.length) {
      empty.textContent = state.items.length ? 'No submission matches this filter.' : 'Nothing received yet. Transmit a report from VinaBeef, or POST one to /v2/surveillance/herd-health.';
      empty.classList.remove('hidden');
    } else empty.classList.add('hidden');
  }

  // ------------------------------------------------------------------------------------------- detail drawer
  function closeDrawer() {
    $('drawer').classList.add('hidden'); $('shade').classList.add('hidden'); state.selected = null;
    if (location.hash) history.replaceState(null, '', location.pathname + location.search);
    for (const r of document.querySelectorAll('tbody tr.sel')) r.classList.remove('sel');
  }

  async function copyText(text, btn) {
    const done = () => { const old = btn.textContent; btn.textContent = 'Copied'; setTimeout(() => { btn.textContent = old; }, 1200); };
    try { await navigator.clipboard.writeText(text); done(); return; } catch (e) { /* fall back below */ }
    const ta = h('textarea', { 'aria-hidden': 'true' }); ta.value = text; document.body.append(ta); ta.select();
    try { document.execCommand('copy'); done(); } catch (e) { btn.textContent = 'Copy failed'; }
    ta.remove();
  }

  function download(name, text, type) {
    const url = URL.createObjectURL(new Blob([text], { type: type + ';charset=utf-8' }));
    const a = h('a', { href: url, download: name }); document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  }

  function copyBtn(text, label) { const b = h('button', { type: 'button', class: 'small-btn' }, label || 'Copy'); b.addEventListener('click', () => copyText(text, b)); return b; }

  function fact(label, value, mono) {
    if (value === null || value === undefined || value === '') return [];
    return [h('dt', {}, label), h('dd', { class: mono ? 'mono' : '' }, value)];
  }

  function issueTable(list, what) {
    if (!list || !list.length) return h('div', { class: 'small' }, 'No ' + what + '.');
    return h('div', { class: 'scroll' }, h('table', { class: 'mini' },
      h('thead', {}, h('tr', {}, h('th', {}, 'Where'), h('th', {}, 'Code'), h('th', {}, 'Message'))),
      h('tbody', {}, list.map((e) => h('tr', {}, h('td', { class: 'mono' }, e.path), h('td', { class: 'mono' }, e.code), h('td', {}, e.message))))));
  }

  function bodySection(d) {
    const raw = d.payload || '';
    if (!raw) return [h('h3', {}, 'Received body'), h('div', { class: 'small' }, 'No body was kept for this request' + (d.bytes ? ' (it was ' + fmtBytes(d.bytes) + ').' : '.'))];
    let pretty = null;
    if (d.format === 'JSON' && !d.payloadTruncated) { try { pretty = JSON.stringify(JSON.parse(raw), null, 2); } catch (e) { pretty = null; } }
    let mode = pretty !== null ? 'pretty' : 'raw';
    const pre = h('pre', { class: 'body', tabindex: '0', 'aria-label': 'Received body' }, mode === 'pretty' ? pretty : raw);
    const bPretty = h('button', { type: 'button', class: 'small-btn', 'aria-pressed': String(mode === 'pretty') }, 'Pretty');
    const bRaw = h('button', { type: 'button', class: 'small-btn', 'aria-pressed': String(mode === 'raw') }, 'Raw (as received)');
    const set = (m) => { mode = m; pre.textContent = m === 'pretty' ? pretty : raw; bPretty.setAttribute('aria-pressed', String(m === 'pretty')); bRaw.setAttribute('aria-pressed', String(m === 'raw')); };
    bPretty.addEventListener('click', () => set('pretty')); bRaw.addEventListener('click', () => set('raw'));
    const ext = d.format === 'XML' ? 'xml' : 'json';
    const tools = h('div', { class: 'row' },
      pretty !== null ? [bPretty, bRaw] : [],
      h('button', { type: 'button', class: 'small-btn', onclick: (ev) => copyText(mode === 'pretty' ? pretty : raw, ev.currentTarget) }, 'Copy'),
      h('button', { type: 'button', class: 'small-btn', onclick: () => download((d.clientRef || d.submissionId) + '.' + ext, raw, d.format === 'XML' ? 'application/xml' : 'application/json') }, 'Download'));
    const note = d.payloadTruncated
      ? h('div', { class: 'banner' }, 'Only the first ' + fmtBytes(d.payloadStoredBytes) + ' of ' + fmtBytes(d.bytes) + ' are kept here. The SHA-256 above is of the complete body.')
      : null;
    return [h('h3', {}, 'Received body'), tools, note, pre];
  }

  function renderDrawer(d) {
    const out = h('div', {});
    const head = d.outcome === 'ACCEPTED' ? 'Accepted: receipt issued'
      : d.outcome === 'REJECTED' ? 'Rejected: ' + (d.errorCount || 0) + ' error' + (d.errorCount === 1 ? '' : 's')
      : 'Failed on purpose (HTTP ' + d.httpStatus + ')';
    out.append(h('div', { class: 'result ' + d.outcome },
      h('div', { class: 'big' }, head),
      d.summary ? h('div', {}, d.summary) : null,
      d.receiptId ? h('div', { class: 'rid' }, h('span', {}, 'Receipt ' + d.receiptId), copyBtn(d.receiptId)) : null));

    out.append(h('h3', {}, 'Request'));
    out.append(h('dl', {},
      fact('Received', fmtTime(d.receivedAt) + '  (' + d.receivedAt + ')'),
      fact('HTTP answer', d.httpStatus),
      fact('VinaBeef reference', d.clientRef, true),
      fact('Submission id', d.submissionId, true),
      fact('Receipt', d.receiptId, true),
      fact('Report', d.reportType ? d.reportType + ' / ' + d.farmScope : null),
      fact('Records', d.recordCount),
      fact('Content-Type', d.contentType, true),
      fact('Body size', d.bytes != null ? fmtBytes(d.bytes) + ' (' + d.bytes.toLocaleString() + ' bytes)' : null),
      fact('Body SHA-256', d.sha256, true),
      fact('API key label', d.keyLabel, true)));
    if (d.sha256) out.append(h('div', { class: 'row' }, h('span', { class: 'small' }, 'Compare with the SHA-256 in VinaBeef\'s view of this transmission: equal means the same bytes arrived.'), copyBtn(d.sha256, 'Copy SHA-256')));

    out.append(h('h3', {}, 'Errors (' + (d.errorCount || 0) + ')')); out.append(issueTable(d.errors, 'errors'));
    out.append(h('h3', {}, 'Warnings (' + (d.warningCount || 0) + ')')); out.append(issueTable(d.warnings, 'warnings'));
    for (const el of bodySection(d)) if (el) out.append(el);
    return out;
  }

  async function openDetail(id) {
    state.selected = id;
    for (const r of document.querySelectorAll('tbody tr')) r.classList.toggle('sel', r.dataset.id === id);
    $('dtitle').textContent = id; $('dbody').replaceChildren(h('div', { class: 'small' }, 'Loading...'));
    $('drawer').classList.remove('hidden'); $('shade').classList.remove('hidden');
    history.replaceState(null, '', '#' + encodeURIComponent(id));
    try {
      const d = await api('/v2/surveillance/submissions/' + encodeURIComponent(id));
      if (state.selected !== id) return;
      $('dtitle').textContent = d.submissionId; $('dbody').replaceChildren(renderDrawer(d)); $('dclose').focus();
    } catch (e) {
      if (e.auth) { showLogin(e.message); return; }
      $('dbody').replaceChildren(h('div', { class: 'msg' }, e.message));
    }
  }

  // #TX-ABC123, #SUB-... or #NADCS-MOCK-... opens that submission (VinaBeef links here with its transmission id)
  function handleHash() {
    const t = decodeURIComponent(location.hash.slice(1) || '').trim();
    if (!t) return;
    const hit = state.items.find((it) => [it.submissionId, it.receiptId, it.clientRef].includes(t));
    if (hit) openDetail(hit.submissionId);
    else { $('q').value = t; state.query = t; renderRows(); setConn('No submission for ' + t + ' is kept: it may predate the last restart.', true); }
  }

  // ------------------------------------------------------------------------------------------- wiring
  $('loginform').addEventListener('submit', (ev) => {
    ev.preventDefault();
    key = $('keyinput').value.trim(); $('keyinput').value = '';
    try { sessionStorage.setItem(KEY_STORE, key); } catch (e) { /* the key then lives only in this page */ }
    $('loginmsg').textContent = ''; load(false);
  });
  $('signout').addEventListener('click', () => { key = null; try { sessionStorage.removeItem(KEY_STORE); } catch (e) { /* ignore */ } state.hashHandled = false; showLogin(''); });
  $('refresh').addEventListener('click', () => load(false));
  $('dclose').addEventListener('click', closeDrawer);
  $('shade').addEventListener('click', closeDrawer);
  document.addEventListener('keydown', (ev) => { if (ev.key === 'Escape' && !$('drawer').classList.contains('hidden')) closeDrawer(); });
  $('q').addEventListener('input', (ev) => { state.query = ev.target.value; renderRows(); });
  for (const b of document.querySelectorAll('.seg button')) b.addEventListener('click', () => { state.filter = b.dataset.f; renderAll(); });
  $('auto').addEventListener('change', schedule);
  function schedule() {
    if (state.timer) { clearInterval(state.timer); state.timer = null; }
    if ($('auto').checked) state.timer = setInterval(() => { if (!document.hidden && key) load(true); }, 5000);
  }
  window.addEventListener('hashchange', () => { if (key && state.items.length) handleHash(); });

  schedule();
  if (key) load(false); else showLogin('');
})();
</script>
</body>
</html>
"""


def page(nonce: str) -> str:
    return _PAGE.replace("__NONCE__", nonce)
