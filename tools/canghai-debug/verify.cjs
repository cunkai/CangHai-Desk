/* 沧海一键调试工具 · 无头验证（CDP）
 * 跑法：node verify.cjs（从本目录跑；ws 模块先找本地 node_modules，
 * 再找 ~/workspace/tools/headless-test/node_modules/ws）。
 * 起 harness 服务器（端口 8932）+ headless_shell，跑完自己收尾，
 * 只杀自己启动的进程。
 */
'use strict';
const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const path = require('path');
let WebSocket;
try { WebSocket = require('ws'); }
catch (e) { WebSocket = require('/home/hatch/workspace/tools/headless-test/node_modules/ws'); }

const HERE = __dirname;
const CHROME = '/home/hatch/.cache/ms-playwright/chromium_headless_shell-1187/chrome-linux/headless_shell';
const PORT = 8932, CDP = 9417;
const DLDIR = '/tmp/ch-debug-dl';
const SHOTDIR = '/tmp/ch-debug-verify';
const sleep = ms => new Promise(r => setTimeout(r, ms));

let fails = 0;
const check = (name, ok, info = '') => {
  console.log((ok ? 'PASS' : 'FAIL') + ' | ' + name + (info ? ' | ' + info : ''));
  if (!ok) fails++;
};

function getJson(url) {
  return new Promise((res, rej) => {
    http.get(url, r => { let d = ''; r.on('data', c => d += c); r.on('end', () => { try { res(JSON.parse(d)); } catch (e) { rej(e); } }); }).on('error', rej);
  });
}

(async () => {
  fs.mkdirSync(DLDIR, { recursive: true });
  fs.mkdirSync(SHOTDIR, { recursive: true });
  for (const f of fs.readdirSync(DLDIR)) { try { fs.unlinkSync(path.join(DLDIR, f)); } catch (e) {} }

  /* ---- 起 harness 服务器 ---- */
  const server = spawn('node', ['server.js', String(PORT)], { cwd: HERE, stdio: 'ignore' });
  let serverUp = false;
  for (let i = 0; i < 40 && !serverUp; i++) {
    await sleep(250);
    try {
      await new Promise((res, rej) => { http.get('http://127.0.0.1:' + PORT + '/', r => { r.resume(); r.on('end', res); }).on('error', rej); });
      serverUp = true;
    } catch (e) {}
  }
  check('harness server up', serverUp, 'port ' + PORT);
  if (!serverUp) { try { server.kill('SIGKILL'); } catch (e) {} process.exit(1); }

  /* ---- 起浏览器 ---- */
  const chrome = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu',
    `--remote-debugging-port=${CDP}`, '--window-size=1500,950',
    `http://127.0.0.1:${PORT}/`], { stdio: 'ignore' });

  let ws = null, bws = null;
  try {
    /* 浏览器级 WS：下载行为 */
    let browserWsUrl = null;
    for (let i = 0; i < 40; i++) {
      try { const v = await getJson(`http://127.0.0.1:${CDP}/json/version`); if (v.webSocketDebuggerUrl) { browserWsUrl = v.webSocketDebuggerUrl; break; } } catch (e) {}
      await sleep(250);
    }
    let downloadBehaviorOk = false;
    if (browserWsUrl) {
      try {
        bws = new WebSocket(browserWsUrl, { maxPayload: 64 * 1024 * 1024 });
        await new Promise((res, rej) => { bws.on('open', res); bws.on('error', rej); });
        const r = await new Promise(res => {
          const h = d => { const m = JSON.parse(d); if (m.id === 1) { bws.off('message', h); res(m); } };
          bws.on('message', h);
          bws.send(JSON.stringify({ id: 1, method: 'Browser.setDownloadBehavior', params: { behavior: 'allow', downloadPath: DLDIR, eventsEnabled: true } }));
          setTimeout(() => res(null), 3000);
        });
        downloadBehaviorOk = !!(r && !r.error);
      } catch (e) { downloadBehaviorOk = false; }
    }
    console.log('INFO | download behavior: ' + (downloadBehaviorOk ? 'allow -> ' + DLDIR : 'UNAVAILABLE (download-file assertion skipped)'));

    /* 页面级 WS */
    let pageWsUrl = null;
    for (let i = 0; i < 40; i++) {
      try {
        const list = await getJson(`http://127.0.0.1:${CDP}/json`);
        const page = list.find(t => t.type === 'page');
        if (page) { pageWsUrl = page.webSocketDebuggerUrl; break; }
      } catch (e) {}
      await sleep(250);
    }
    if (!pageWsUrl) throw new Error('no page target');
    ws = new WebSocket(pageWsUrl, { maxPayload: 512 * 1024 * 1024 });
    await new Promise(r => ws.on('open', r));

    let id = 0; const pend = {};
    const errors = [];
    ws.on('message', d => {
      const m = JSON.parse(d);
      if (m.id && pend[m.id]) { pend[m.id](m); delete pend[m.id]; }
      if (m.method === 'Runtime.exceptionThrown') {
        const ed = m.params.exceptionDetails || {};
        errors.push('EXCEPTION: ' + (ed.text || '') + ' ' + ((ed.exception && ed.exception.description) || ''));
      }
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
        errors.push('CONSOLE: ' + JSON.stringify(m.params.args.map(a => a.value !== undefined ? a.value : (a.description || ''))));
      }
    });
    const send = (method, params = {}) => new Promise(res => { const i = ++id; pend[i] = res; ws.send(JSON.stringify({ id: i, method, params })); });
    const ev = async (expr) => {
      const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
      if (r.result && r.result.exceptionDetails) return 'EVALERR: ' + (r.result.exceptionDetails.exception && r.result.exceptionDetails.exception.description || r.result.exceptionDetails.text);
      return r.result && r.result.result ? r.result.result.value : undefined;
    };
    const shot = async (name) => {
      const r = await send('Page.captureScreenshot', { format: 'png' });
      if (r.result && r.result.data) fs.writeFileSync(path.join(SHOTDIR, name), Buffer.from(r.result.data, 'base64'));
    };
    await send('Runtime.enable');
    await send('Page.enable');
    await send('DOM.enable');
    await sleep(4500);

    /* ============ 1. 启动 ============ */
    const boot = await ev(`(function(){
      var ph = document.getElementById('phoneFrame');
      var dk = document.getElementById('deskFrame');
      var dc = document.getElementById('dockFrame');
      var pw = ph.contentWindow, dw = dk.contentWindow, cw = dc.contentWindow;
      var devCount = -1;
      try { devCount = pw.eval('state.devices.length'); } catch (e) {}
      var sdkRoot = null;
      try { sdkRoot = pw.CanghaiCore.fs.root(); } catch (e) { sdkRoot = 'ERR:' + e.message; }
      return {
        fake: !!window.__chFakeNative,
        phoneMain: !!pw.document.querySelector('#viewMain:not(.hidden)'),
        devCount: devCount,
        mascotCount: (pw.document.getElementById('mascotCount') || {}).textContent,
        periphText: (pw.document.getElementById('peripheralList') || {}).textContent || '',
        storageNote: (pw.document.getElementById('storageNote') || {}).textContent || '',
        sdkRoot: sdkRoot,
        deskItems: dw.document.querySelectorAll('.ditem').length,
        deskCanghai: typeof dw.Canghai === 'object' && typeof dw.CHCore === 'object',
        deskCore: typeof dw.CanghaiCore === 'object' && dw.CanghaiCore.version === 1,
        dockPins: Array.from(cw.document.querySelectorAll('#dock .dockItem[data-area="pin"]')).map(function (el) { return el.getAttribute('data-id'); }),
        dockIcons: cw.document.querySelectorAll('#dock > *').length
      };
    })()`);
    console.log('BOOT:', JSON.stringify(boot));
    check('shell: fake core present', boot && boot.fake === true);
    check('phone: main view + 2 devices in state', boot && boot.phoneMain && boot.devCount === 2 && (boot.mascotCount || '').indexOf('2') >= 0, JSON.stringify({ dev: boot && boot.devCount, mc: boot && boot.mascotCount }));
    check('phone: peripheral screen rows render', boot && boot.periphText.indexOf('手机自身屏幕') >= 0 && boot.periphText.indexOf('外接屏') >= 0, (boot && boot.periphText.slice(0, 80)) || '');
    check('phone: SDK fs.root() works via __CanghaiNative', boot && boot.sdkRoot && boot.sdkRoot.mode === 'internal', JSON.stringify(boot && boot.sdkRoot));
    check('desktop: icons rendered + bridges present', boot && boot.deskItems > 0 && boot.deskCanghai && boot.deskCore, 'ditems=' + (boot && boot.deskItems));
    check('dock: pins fall back to builtin apps', boot && Array.isArray(boot.dockPins) && boot.dockPins.length >= 14 && boot.dockIcons > 0, 'pins=' + (boot && boot.dockPins.length));
    await shot('1-boot.png');

    /* ============ 2a. 导出桌面 ============ */
    // 先让桌面存一份快照（走页面自己的 deskSaveNow，防抖 1.2s）
    await ev(`(function(){ var dw = document.getElementById('deskFrame').contentWindow; if (typeof dw.deskSaveNow === 'function') dw.deskSaveNow(); })()`);
    await sleep(2000);
    const snapBefore = await ev(`window.__chFakeNative.getDeskState()`);
    check('desktop: snapshot saved via deskSaveNow', typeof snapBefore === 'string' && snapBefore.length > 20, 'len=' + (snapBefore && snapBefore.length));

    await ev(`(function(){ var pw = document.getElementById('phoneFrame').contentWindow;
      var tab = pw.document.querySelector('#tabbar button[data-tab="Settings"]'); if (tab) tab.click(); })()`);
    await sleep(400);
    await ev(`(function(){ var pw = document.getElementById('phoneFrame').contentWindow;
      var b = pw.document.getElementById('btnDeskExport'); if (b) b.click(); })()`);
    await sleep(800);
    const expRes = await ev(`(function(){
      var pw = document.getElementById('phoneFrame').contentWindow;
      var calls = window.__chFakeNative._calls.filter(function(c){ return c.method.indexOf('exportDesktopState') >= 0; });
      return { calls: calls.length,
        toast: (pw.document.getElementById('toast') || {}).textContent || '',
        note: (pw.document.getElementById('deskExportNote') || {}).textContent || '' };
    })()`);
    console.log('EXPORT:', JSON.stringify(expRes));
    check('export: fake recorded the call', expRes && expRes.calls >= 1);
    check('export: toast shows exported', expRes && expRes.toast.indexOf('已导出') >= 0, expRes && expRes.toast);
    if (downloadBehaviorOk) {
      await sleep(2000);
      const dlFiles = fs.readdirSync(DLDIR).filter(f => !f.endsWith('.crdownload'));
      console.log('DLDIR:', JSON.stringify(dlFiles));
      /* 注意：此 headless_shell 构建会把中文下载文件名改成 download（ASCII 名正常），
         这是无头环境的 quirk，不是桩的问题——桩传的 download 属性就是中文名。
         这里断言下载确实落盘、且内容是合法 bundle；文件名以有头浏览器为准。 */
      check('export: real download file landed', dlFiles.length >= 1, dlFiles.join(','));
      if (dlFiles.length) {
        try {
          const bundle = JSON.parse(fs.readFileSync(path.join(DLDIR, dlFiles[0]), 'utf8'));
          check('export: bundle format valid', bundle.format === 'canghai-desktop' && bundle.version === 1 && bundle.snapshot && bundle.snapshot.v === 1);
        } catch (e) { check('export: bundle format valid', false, String(e)); }
      }
    }

    /* ============ 2b. 导入桌面 ============ */
    const bundleObj = { format: 'canghai-desktop', version: 1, exportedAt: 123456789,
      snapshot: { v: 1, items: [], dockPins: ['jump'],
        stickies: [{ x: 10, y: 10, xp: 0, yp: 0, w: 200, h: 120, wRatio: 0, hRatio: 0, color: 'yellow', bg: '', textColor: '', fontSize: 14, text: 'IMPORT_MARKER_7f3a', welcome: false }],
        state: { light: false, iconSize: 'm', dockSide: 'bottom', dockAutoHide: false, showBrandLogo: true },
        wallpaper: 'imported-wall', grid: [10, 5] } };
    const bundlePath = '/tmp/ch-debug-import.canghai-desktop';
    fs.writeFileSync(bundlePath, JSON.stringify(bundleObj));
    // 找到 phone iframe 里的 file input（pierce 穿透 iframe）
    const doc = await send('DOM.getDocument', { depth: -1, pierce: true });
    let inputNodeId = null;
    (function walk(n) {
      if (!n || inputNodeId) return;
      if (n.attributes) {
        for (let i = 0; i < n.attributes.length; i += 2) {
          if (n.attributes[i] === 'id' && n.attributes[i + 1] === 'deskImportFileInput') { inputNodeId = n.nodeId; return; }
        }
      }
      (n.children || []).forEach(walk);
      (n.contentDocument ? [n.contentDocument] : []).forEach(walk);
      if (n.shadowRoots) n.shadowRoots.forEach(walk);
    })(doc.result.root);
    check('import: file input found via pierced DOM', !!inputNodeId);
    if (inputNodeId) {
      const setRes = await send('DOM.setFileInputFiles', { nodeId: inputNodeId, files: [bundlePath] });
      check('import: setFileInputFiles ok', !setRes.error, setRes.error ? JSON.stringify(setRes.error) : '');
      await sleep(1000);
      const impRes = await ev(`(function(){
        var pw = document.getElementById('phoneFrame').contentWindow;
        var dw = document.getElementById('deskFrame').contentWindow;
        var cw = document.getElementById('dockFrame').contentWindow;
        var stored = window.__chFakeNative.getDeskState();
        var snap = null; try { snap = JSON.parse(stored); } catch (e) {}
        return {
          toast: (pw.document.getElementById('toast') || {}).textContent || '',
          storedWall: snap && snap.wallpaper,
          storedGrid: snap && snap.grid,
          storedPins: snap && snap.dockPins,
          storedSticky: snap && snap.stickies && snap.stickies[0] && snap.stickies[0].text,
          deskMatches: dw.Canghai.getDeskState() === stored,
          dockPins: Array.from(cw.document.querySelectorAll('#dock .dockItem[data-area="pin"]')).map(function (el) { return el.getAttribute('data-id'); })
        };
      })()`);
      console.log('IMPORT:', JSON.stringify(impRes));
      check('import: toast shows applied', impRes && impRes.toast.indexOf('已导入桌面') >= 0, impRes && impRes.toast);
      check('import: stored snapshot is the imported one', impRes && impRes.storedWall === 'imported-wall' &&
        JSON.stringify(impRes.storedGrid) === '[10,5]' && JSON.stringify(impRes.storedPins) === '["jump"]' &&
        impRes.storedSticky === 'IMPORT_MARKER_7f3a', JSON.stringify(impRes));
      check('import: desktop pane reads the new snapshot', impRes && impRes.deskMatches === true);
      check('import: dock pins synced', impRes && JSON.stringify(impRes.dockPins) === '["jump"]', JSON.stringify(impRes && impRes.dockPins));
    }

    /* ============ 3. webview-probe ============ */
    await ev(`(function(){ var dw = document.getElementById('deskFrame').contentWindow;
      if (typeof dw.openApp === 'function') dw.openApp('webview-probe'); })()`);
    let probeReady = false;
    for (let i = 0; i < 30; i++) {
      await sleep(300);
      probeReady = await ev(`(function(){
        var dw = document.getElementById('deskFrame').contentWindow;
        var fr = dw.document.querySelector('iframe.appFrame[src*="webview-probe"]');
        if (!fr || !fr.contentWindow) return false;
        try { return !!fr.contentWindow.document.getElementById('btnGo'); } catch (e) { return false; }
      })()`);
      if (probeReady) break;
    }
    check('probe: window opened with app iframe', probeReady === true);
    if (probeReady) {
      await ev(`(function(){
        var dw = document.getElementById('deskFrame').contentWindow;
        var fr = dw.document.querySelector('iframe.appFrame[src*="webview-probe"]');
        var pw = fr.contentWindow;
        pw.document.getElementById('inpUrl').value = 'data:text/html,<title>ProbeTest</title><body>hello</body>';
        pw.document.getElementById('btnGo').click();
      })()`);
      await sleep(1200);
      const ov1 = await ev(`(function(){
        var desk = document.getElementById('deskFrame');
        var dr = desk.getBoundingClientRect();
        var dw = desk.contentWindow;
        var fr = dw.document.querySelector('iframe.appFrame[src*="webview-probe"]');
        var frr = fr.getBoundingClientRect();
        var pw = fr.contentWindow;
        var hole = pw.document.getElementById('hole').getBoundingClientRect();
        var ovEl = document.getElementById('wvOverlay');
        var ov = ovEl.getBoundingClientRect();
        return {
          visible: ovEl.style.display !== 'none' && ov.width > 0,
          expected: { x: dr.left + frr.left + hole.left, y: dr.top + frr.top + hole.top, w: hole.width, h: hole.height },
          actual: { x: ov.left, y: ov.top, w: ov.width, h: ov.height },
          pill: pw.document.getElementById('pill').textContent,
          status: pw.document.getElementById('status').textContent
        };
      })()`);
      console.log('OVERLAY1:', JSON.stringify(ov1));
      const dx = Math.abs(ov1.expected.x - ov1.actual.x), dy = Math.abs(ov1.expected.y - ov1.actual.y);
      const dw2 = Math.abs(ov1.expected.w - ov1.actual.w), dh = Math.abs(ov1.expected.h - ov1.actual.h);
      check('probe: overlay visible after Open', ov1.visible === true);
      check('probe: overlay rect ~= hole rect (<=3px)', dx <= 3 && dy <= 3 && dw2 <= 3 && dh <= 3,
        'd=(' + dx.toFixed(1) + ',' + dy.toFixed(1) + ',' + dw2.toFixed(1) + ',' + dh.toFixed(1) + ')');
      check('probe: pill shows 已显示', ov1.pill === '已显示', ov1.pill);
      await sleep(1200);
      const statusText = await ev(`(function(){
        var dw = document.getElementById('deskFrame').contentWindow;
        var fr = dw.document.querySelector('iframe.appFrame[src*="webview-probe"]');
        return fr.contentWindow.document.getElementById('status').textContent;
      })()`);
      check('probe: status polling reports content window', typeof statusText === 'string' && statusText.indexOf('内容窗：') >= 0, statusText);
      await shot('2-overlay.png');

      /* ---- 拖动标题栏，覆盖层跟上 ---- */
      const bar = await ev(`(function(){
        var desk = document.getElementById('deskFrame');
        var dr = desk.getBoundingClientRect();
        var dw = desk.contentWindow;
        var win = dw.openWins && dw.openWins['webview-probe'];
        var el = win ? (win.querySelector('.wintitle') || win.querySelector('.winbar span') || win) : null;
        if (!el) return null;
        var r = el.getBoundingClientRect();
        var wr = win.getBoundingClientRect();
        return { x: dr.left + r.left + r.width / 2, y: dr.top + r.top + r.height / 2,
          winX: wr.left, winY: wr.top };
      })()`);
      check('probe: title bar located', !!bar, JSON.stringify(bar));
      if (bar) {
        // 先移过去，让 Dock iframe 的 pointer-events 切到 none
        await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: bar.x, y: bar.y });
        await sleep(200);
        const pe = await ev(`document.getElementById('dockFrame').style.pointerEvents`);
        check('probe: dock iframe pointer-events none over window', pe === 'none', 'pe=' + pe);
        const ovBefore = await ev(`(function(){ var r = document.getElementById('wvOverlay').getBoundingClientRect(); return {x:r.left,y:r.top}; })()`);
        await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: bar.x, y: bar.y, button: 'left', clickCount: 1 });
        const STEPS = 10, DX = 120, DY = 60;
        for (let s = 1; s <= STEPS; s++) {
          await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: bar.x + DX * s / STEPS, y: bar.y + DY * s / STEPS, button: 'left' });
          await sleep(30);
        }
        await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: bar.x + DX, y: bar.y + DY, button: 'left', clickCount: 1 });
        await sleep(1100);
        const after = await ev(`(function(){
          var desk = document.getElementById('deskFrame');
          var dr = desk.getBoundingClientRect();
          var dw = desk.contentWindow;
          var win = dw.openWins && dw.openWins['webview-probe'];
          var wr = win ? win.getBoundingClientRect() : null;
          var fr = dw.document.querySelector('iframe.appFrame[src*="webview-probe"]');
          var frr = fr.getBoundingClientRect();
          var hole = fr.contentWindow.document.getElementById('hole').getBoundingClientRect();
          var ov = document.getElementById('wvOverlay').getBoundingClientRect();
          return { winX: wr && wr.left, winY: wr && wr.top,
            expected: { x: dr.left + frr.left + hole.left, y: dr.top + frr.top + hole.top },
            actual: { x: ov.left, y: ov.top } };
        })()`);
        console.log('DRAG:', JSON.stringify({ before: ovBefore, after }));
        const mdx = after.actual.x - ovBefore.x, mdy = after.actual.y - ovBefore.y;
        check('probe: window moved by drag', Math.abs(after.winX - bar.winX - 120) <= 15 && Math.abs(after.winY - bar.winY - 60) <= 15,
          'win delta=(' + (after.winX - bar.winX).toFixed(1) + ',' + (after.winY - bar.winY).toFixed(1) + ')');
        check('probe: overlay followed within ~1s (<=12px)', Math.abs(mdx - 120) <= 12 && Math.abs(mdy - 60) <= 12,
          'overlay delta=(' + mdx.toFixed(1) + ',' + mdy.toFixed(1) + ')');
        const ex = Math.abs(after.expected.x - after.actual.x), ey = Math.abs(after.expected.y - after.actual.y);
        check('probe: overlay still aligned after drag (<=5px)', ex <= 5 && ey <= 5, 'd=(' + ex.toFixed(1) + ',' + ey.toFixed(1) + ')');
        await shot('3-dragged.png');
      }
    }

    /* ============ 错误汇总 ============ */
    await sleep(500);
    console.log('ERRORS(' + errors.length + '):');
    errors.forEach(e => console.log('  ' + e));
    check('zero page errors/exceptions in all panes', errors.length === 0, errors.length + ' errors');
  } finally {
    try { if (ws) ws.close(); } catch (e) {}
    try { if (bws) bws.close(); } catch (e) {}
    try { chrome.kill('SIGKILL'); } catch (e) {}
    try { server.kill('SIGKILL'); } catch (e) {}
  }
  console.log(fails === 0 ? 'ALL PASS' : ('FAILURES: ' + fails));
  process.exit(fails === 0 ? 0 : 1);
})().catch(e => { console.error('HARNESS ERROR: ' + (e && e.stack || e)); process.exit(2); });
