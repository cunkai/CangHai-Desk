/* ==========================================================================
 * 沧海一键调试工具 · 假原生核心 (fake-core.js)
 *
 * 顶层窗口上的唯一假 CanghaiStore：手机页 / 桌面页 / Dock 页三个同源
 * iframe 共享同一份状态。状态持久化到顶层 localStorage 单键 JSON。
 * 内存文件系统 (fs) 不持久化，刷新即空——与真机行为不同，见 README。
 *
 * 本文件会被调试服务器以内联 <script> 注入三个页面（在页面脚本之前），
 * 也会被壳页直接加载。只在顶层窗口自动安装 window.__chFakeNative；
 * iframe 里的页面通过 window.top.__chFakeNative 拿到同一份。
 * 注意：本文件绝不能出现 "</scr" + "ipt>" 字面量，否则会截断注入。
 * ========================================================================== */
(function (global) {
  'use strict';

  var LS_KEY = 'canghai.debug.fake.v1';
  var MAX_TEXT = 8 * 1024 * 1024;
  var MAX_BASE64 = 24 * 1024 * 1024;
  var MAX_IMPORT_BYTES = 2 * 1024 * 1024;
  var MAX_ITEMS = 500;

  function clone(o) { return JSON.parse(JSON.stringify(o)); }
  function isObj(o) { return o !== null && typeof o === 'object' && !Array.isArray(o); }

  /* ---- base64 / UTF-8 工具（分片，避免大字符串爆栈） ---- */
  function b64encodeBytes(u8) {
    var s = '';
    for (var i = 0; i < u8.length; i += 8192) {
      s += String.fromCharCode.apply(null, u8.subarray(i, i + 8192));
    }
    return btoa(s);
  }
  function b64decodeBytes(b64) {
    var s = atob(String(b64 || ''));
    var u8 = new Uint8Array(s.length);
    for (var i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
    return u8;
  }
  function textToB64(t) {
    return b64encodeBytes(new TextEncoder().encode(String(t == null ? '' : t)));
  }
  function b64ToText(b64) {
    try { return new TextDecoder('utf-8').decode(b64decodeBytes(b64)); }
    catch (e) { return ''; }
  }
  function validB64(s) {
    try { if (typeof s !== 'string') return false; atob(s); return true; }
    catch (e) { return false; }
  }

  function genUuid() {
    var s = 'debug-';
    for (var i = 0; i < 16; i++) s += '0123456789abcdef'.charAt(Math.floor(Math.random() * 16));
    return s;
  }

  function defaultState() {
    return {
      v: 1,
      desktopItems: '',
      dockPins: [],
      dockAutoHide: false,
      cursorLib: [],
      cursorCurrent: 'arrow',
      cursorSize: 28,
      cursorSpeed: 1.0,
      cursorSizeByDisplay: {},
      cursorCurrentByDisplay: {},
      wallpaper: '',
      gridCols: 15,
      gridRows: 7,
      userName: '沧海用户',
      userAvatar: '',
      language: 'zh',
      homeType: 'canghai',
      htmlApps: {},
      uuid: genUuid(),
      peripheralDisplay: {},
      resolutionKey: '',
      renderScale: 100,
      seq: 0
    };
  }

  function loadState() {
    try {
      var raw = global.localStorage.getItem(LS_KEY);
      if (!raw) return null;
      var s = JSON.parse(raw);
      if (!isObj(s) || s.v !== 1) return null;
      var d = defaultState();
      for (var k in d) if (s[k] === undefined) s[k] = d[k];
      return s;
    } catch (e) { return null; }
  }

  function createFakeNative() {
    var S = loadState() || defaultState();
    var core = {};

    /* 壳页注入的引用与钩子（独立打开页面时为空，全部降级为空操作） */
    core._wins = { desktop: null, dock: null, phone: null };
    core._calls = [];
    core._onCall = null;
    core._wvHooks = null;
    core._dockPointerHook = null;
    core._wv = { showing: false, url: '', title: '', progress: 0, depth: 0, maxDepth: 0, rect: null };
    core._fs = {};
    core._dockRects = null;
    core._perfOn = false;
    core._pendingOpen = '';
    core._pickedUri = '';
    core.lastToast = '';

    function save() {
      try { global.localStorage.setItem(LS_KEY, JSON.stringify(S)); }
      catch (e) { /* 配额满了就只留内存，照常跑 */ }
    }
    core._save = save;
    core._state = function () { return S; };

    /* ---------------- 调用记录 ---------------- */
    core.log = function (page, method, detail) {
      var entry = { t: new Date().toLocaleTimeString(), page: page, method: method, detail: String(detail == null ? '' : detail).slice(0, 300) };
      core._calls.push(entry);
      if (core._calls.length > 200) core._calls.shift();
      try { if (core._onCall) core._onCall(entry); } catch (e) {}
    };

    /* ---------------- 同步扇出（镜像 notifyDataChanged / notifyDesktopStateChanged） ---------------- */
    function callWin(win, fn, arg) {
      try { if (win && typeof win[fn] === 'function') { win[fn](arg); return true; } } catch (e) {}
      return false;
    }
    core.syncApps = function () { callWin(core._wins.desktop, '__canghaiSyncApps', core.getState(1)); };
    core.syncDesktop = function (s) { callWin(core._wins.desktop, '__canghaiSyncDesktop', s); };
    core.syncCursor = function () { callWin(core._wins.desktop, '__canghaiSyncCursor', core.getState(1)); };
    core.syncUserProfile = function () {
      callWin(core._wins.desktop, '__canghaiSyncUserProfile', { name: S.userName, avatar: S.userAvatar });
    };
    core.refreshDock = function () { callWin(core._wins.dock, '__dockRefresh'); };
    core.refreshPhone = function () { callWin(core._wins.phone, '__chReloadState'); };
    core.openLaunchpadOnDock = function () { callWin(core._wins.dock, '__lpSetOpen', true); };
    core.openAppOnDesktop = function (id) {
      try {
        var w = core._wins.desktop;
        if (w && typeof w.openApp === 'function') { w.openApp(String(id)); return true; }
      } catch (e) {}
      return false;
    };
    core.closeAppOnDesktop = function (id) {
      try {
        var w = core._wins.desktop;
        var wins = w && w.openWins;
        var win = wins && wins[id];
        var btn = win && win.querySelector && win.querySelector('.tl.r');
        if (btn) { btn.click(); return true; }
      } catch (e) {}
      return false;
    };

    /* ---------------- stateJson（镜像 CanghaiStore.stateJsonForDisplay） ---------------- */
    function snapshotPins() {
      try {
        var d = JSON.parse(S.desktopItems || '{}');
        if (d && Array.isArray(d.dockPins)) return d.dockPins;
      } catch (e) {}
      return [];
    }
    core.getState = function (displayId) {
      var did = (displayId == null ? 1 : displayId);
      var htmlApps = Object.keys(S.htmlApps).map(function (id) {
        var a = S.htmlApps[id];
        return { id: a.id, name: a.name, icon: a.icon || '', png: a.png || '', size: a.size || 0, ori: a.ori || 'auto' };
      });
      var pins = (S.dockPins && S.dockPins.length) ? S.dockPins.slice() : snapshotPins();
      return {
        apps: [],
        htmlApps: htmlApps,
        privilege: false,
        privMode: 'none',
        dockPins: pins,
        dockAutoHide: !!S.dockAutoHide,
        cursorLib: clone(S.cursorLib),
        cursorCurrent: S.cursorCurrentByDisplay[did] || S.cursorCurrent,
        cursorSize: S.cursorSizeByDisplay[did] || S.cursorSize,
        cursorSpeed: S.cursorSpeed,
        wallpaper: S.wallpaper,
        gridCols: S.gridCols,
        gridRows: S.gridRows,
        desktopItems: S.desktopItems,
        userName: S.userName,
        userAvatar: S.userAvatar,
        language: S.language,
        version: 1.0
      };
    };

    core.getHostStatus = function () {
      return {
        ip: '127.0.0.1',
        privilege: false,
        privRoot: false,
        privShizuku: false,
        privMode: 'none',
        isRoot: false,
        isShizuku: false,
        isShizukuGranted: false,
        privilegeMode: 'NONE',
        isOverlay: false,
        activeDisplayId: 1,
        streamTargetId: 1000000,
        streamTargetName: 'Moonlight',
        mainDisplayId: 1,
        homeType: S.homeType,
        cursorStyle: '',
        cursorCurrent: S.cursorCurrent,
        cursorSize: S.cursorSize,
        cursorSpeed: S.cursorSpeed,
        userName: S.userName,
        userAvatar: S.userAvatar,
        language: S.language,
        devices: [
          { id: 0, kind: 'phone', name: '手机本机', res: '1080 × 2400', fps: '60 Hz', desk: 'phone', active: true, cursorSize: S.cursorSizeByDisplay[0] || S.cursorSize },
          { id: 1, kind: 'wired', name: '外接屏（调试）', res: '1920 × 1080', fps: '60 Hz', desk: 'canghai', active: true, cursorSize: S.cursorSizeByDisplay[1] || S.cursorSize }
        ]
      };
    };

    core.getPerms = function () {
      return { root: false, shizuku: false, shizukuGranted: false, privilegeMode: 'NONE', overlay: false, network: true, battery: true, shortcut: true };
    };

    core.getPeripherals = function () { return []; };
    core.getAvailableDisplays = function () {
      return [
        { id: 0, name: '手机自身屏幕' },
        { id: 1, name: '外接屏（调试）', width: 1920, height: 1080 }
      ];
    };
    core.getResolutions = function () {
      var cur = S.resolutionKey || '';
      function tier(h, w, label) {
        return { key: label.split(' ')[0], name: label, width: w, height: h, desc: w + ' × ' + h,
          current: (cur !== '' ? (cur === label.split(' ')[0]) : (h === 1080)) };
      }
      return [tier(360, 640, '360p'), tier(720, 1280, '720p'), tier(1080, 1920, '1080p (推荐)')];
    };
    core.getSystemInfo = function () {
      return { battery: { level: 100, charging: true }, net: 'wifi',
        model: 'Canghai Debug', brand: 'Canghai', android: '14', sdk: 34,
        locale: 'zh_CN', width: 1920, height: 1080, dpi: 320, time: Date.now() };
    };
    core.getAboutInfo = function () {
      return { version: 1.0, apkVersion: '1.0.0', apkCode: 190, model: 'Canghai Debug',
        android: '14', htmlApps: Object.keys(S.htmlApps).length, installedApps: 0 };
    };
    core.checkUpdate = function () {
      return { ok: false, local: 1.0, message: '调试环境不检查更新' };
    };
    core.getDockOffsets = function () { return { x: 0, y: 0 }; };
    core.getLanguage = function () { return S.language; };
    core.takePendingOpen = function () { var v = core._pendingOpen; core._pendingOpen = ''; return v; };
    core.getPickedUri = function () { return core._pickedUri; };

    /* ---------------- 桌面快照 ---------------- */
    core.getDeskState = function () { return S.desktopItems; };
    core.saveDeskState = function (s) {
      S.desktopItems = String(s == null ? '' : s);
      save();
      core.refreshDock();
    };
    core.getDesktopItems = core.getDeskState;

    /* ---------------- HTML 应用 ---------------- */
    function slug(name) {
      var s = String(name || 'app').toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '');
      return s || 'app';
    }
    core.installHtml = function (name, html, iconSvg, png) {
      var base = slug(name), id = base, n = 2;
      while (S.htmlApps[id]) { id = base + '-' + (n++); }
      S.htmlApps[id] = { id: id, name: String(name || '未命名'), html: String(html == null ? '' : html),
        icon: String(iconSvg || ''), png: String(png || ''), ori: 'auto', size: String(html || '').length };
      save();
      core.syncApps();
      core.refreshPhone();
      return id;
    };
    core.removeHtml = function (id) {
      if (!S.htmlApps[id]) return false;
      delete S.htmlApps[id];
      save(); core.syncApps(); core.refreshPhone();
      return true;
    };
    core.renameHtml = function (id, name) {
      if (!S.htmlApps[id]) return false;
      S.htmlApps[id].name = String(name || S.htmlApps[id].name);
      save(); core.syncApps(); core.refreshPhone();
      return true;
    };
    core.setHtmlIcon = function (id, svg) {
      if (!S.htmlApps[id]) return false;
      S.htmlApps[id].icon = String(svg || '');
      save(); core.syncApps(); core.refreshPhone();
      return true;
    };
    core.setHtmlOrientation = function (id, ori) {
      if (!S.htmlApps[id]) return false;
      S.htmlApps[id].ori = String(ori || 'auto');
      save(); core.refreshPhone();
      return true;
    };
    core.getHtmlApp = function (id) {
      var a = S.htmlApps[id];
      return a ? a.html : '';
    };

    /* ---------------- 偏好设置 ---------------- */
    core.setDockPins = function (pins) {
      var arr = Array.isArray(pins) ? pins : [];
      S.dockPins = arr.map(String);
      save(); core.refreshDock(); core.syncApps();
    };
    core.setDockAutoHide = function (on) { S.dockAutoHide = !!on; save(); core.refreshDock(); };
    core.getDockAutoHide = function () { return !!S.dockAutoHide; };
    core.saveWallpaper = function (wp) { S.wallpaper = String(wp || ''); save(); };
    core.saveGrid = function (cols, rows) {
      S.gridCols = Math.max(4, Math.min(24, parseInt(cols, 10) || 15));
      S.gridRows = Math.max(3, Math.min(12, parseInt(rows, 10) || 7));
      save();
    };
    core.setLanguage = function (lang) { S.language = String(lang || 'zh'); save(); core.refreshDock(); core.syncApps(); };
    core.setUserProfile = function (name, avatar) {
      if (name != null) S.userName = String(name);
      if (avatar != null) S.userAvatar = String(avatar);
      save(); core.syncUserProfile(); core.refreshPhone();
    };
    core.setCursorSize = function (n) { S.cursorSize = parseInt(n, 10) || 28; save(); core.syncCursor(); };
    core.setCursorSizeForDisplay = function (did, n) {
      S.cursorSizeByDisplay[did] = parseInt(n, 10) || 28; save(); core.syncCursor();
    };
    core.selectCursor = function (id) { S.cursorCurrent = String(id || 'arrow'); save(); core.syncCursor(); };
    core.selectCursorForDisplay = function (did, cur) {
      S.cursorCurrentByDisplay[did] = String(cur || 'arrow'); save(); core.syncCursor();
    };
    core.setCursorSpeed = function (v) { S.cursorSpeed = parseFloat(v) || 1.0; save(); core.syncCursor(); };
    core.saveCursor = function (cursor) {
      if (!isObj(cursor)) return false;
      var lib = S.cursorLib;
      var id = cursor.id ? String(cursor.id) : '';
      var found = null;
      for (var i = 0; i < lib.length; i++) if (lib[i].id === id && id) { found = lib[i]; break; }
      if (!found) {
        S.seq += 1;
        found = { id: id || ('cur_' + S.seq) };
        lib.push(found);
      }
      found.name = cursor.name || found.name || '自定义指针';
      found.svg = cursor.svg || found.svg || '';
      if (cursor.hx != null) found.hx = cursor.hx;
      if (cursor.hy != null) found.hy = cursor.hy;
      save(); core.syncCursor(); core.refreshPhone();
      return true;
    };
    core.removeCursor = function (id) {
      S.cursorLib = S.cursorLib.filter(function (x) { return x.id !== id; });
      save(); core.syncCursor(); core.refreshPhone();
    };
    core.setDesktopType = function (v) {
      S.homeType = (v === 1 || v === '1' || v === 'canghai' || v === true) ? 'canghai' : 'native';
      save(); core.refreshPhone();
    };
    core.setPeripheralDisplay = function (pid, did) {
      S.peripheralDisplay[pid] = did; save();
    };
    core.setResolution = function (key) { S.resolutionKey = String(key || ''); save(); };

    /* ---------------- 桌面导出 / 导入（镜像 CanghaiStore，校验顺序与文案一致） ---------------- */
    core.exportDesktopBundle = function () {
      var snap = S.desktopItems;
      if (!snap || !String(snap).trim()) return null;
      try {
        var bundle = { format: 'canghai-desktop', version: 1, exportedAt: Date.now(), snapshot: JSON.parse(snap) };
        return JSON.stringify(bundle);
      } catch (e) { return null; }
    };
    core.importDesktopBundle = function (bundleJson) {
      try {
        if (bundleJson == null || bundleJson === '') return '文件是空的';
        if (String(bundleJson).length > MAX_IMPORT_BYTES) return '文件太大了';
        var bundle;
        try { bundle = JSON.parse(bundleJson); }
        catch (e) { return '这个文件读不出来'; }
        if (!isObj(bundle) || bundle.format !== 'canghai-desktop') return '不是沧海桌面文件';
        if (bundle.version !== 1) return '这个桌面文件版本不支持';
        var snap = bundle.snapshot;
        if (!isObj(snap)) return '文件里没有桌面快照';
        if (snap.v !== 1) return '快照版本不支持';
        var items = snap.items;
        if (!Array.isArray(items)) return '快照里没有桌面条目';
        if (items.length > MAX_ITEMS) return '桌面条目太多了';
        for (var i = 0; i < items.length; i++) if (!isObj(items[i])) return '桌面条目损坏';
        var clean = { v: 1, items: items };
        var pins = snap.dockPins;
        if (pins != null) {
          if (!Array.isArray(pins)) return 'Dock 固定项太多了';
          if (pins.length > 100) return 'Dock 固定项太多了';
          clean.dockPins = pins;
        }
        var stickies = snap.stickies;
        if (stickies != null) {
          if (!Array.isArray(stickies)) return '便签太多了';
          if (stickies.length > 200) return '便签太多了';
          clean.stickies = stickies;
        }
        var state = snap.state;
        if (isObj(state)) {
          var cs = {};
          if (state.light !== undefined) cs.light = !!state.light;
          if (state.iconSize !== undefined) cs.iconSize = String(state.iconSize);
          if (state.dockSide !== undefined) cs.dockSide = String(state.dockSide);
          if (state.dockAutoHide !== undefined) cs.dockAutoHide = !!state.dockAutoHide;
          if (state.showBrandLogo !== undefined) cs.showBrandLogo = !!state.showBrandLogo;
          clean.state = cs;
        }
        var wallpaper = (snap.wallpaper == null ? '' : String(snap.wallpaper));
        if (wallpaper.length > 200) return '壁纸名字太长了';
        if (wallpaper) clean.wallpaper = wallpaper;
        var cols = -1, rows = -1;
        var grid = snap.grid;
        if (Array.isArray(grid) && grid.length >= 2) {
          cols = Math.max(4, Math.min(24, parseInt(grid[0], 10) || 15));
          rows = Math.max(3, Math.min(12, parseInt(grid[1], 10) || 7));
          clean.grid = [cols, rows];
        }
        var cleanStr = JSON.stringify(clean);
        S.desktopItems = cleanStr;
        if (wallpaper) S.wallpaper = wallpaper;
        if (cols > 0 && rows > 0) { S.gridCols = cols; S.gridRows = rows; }
        if (pins != null) S.dockPins = pins.map(String);
        if (isObj(state) && state.dockAutoHide !== undefined) S.dockAutoHide = !!state.dockAutoHide;
        save();
        core.syncDesktop(cleanStr);
        core.syncApps();
        core.refreshDock();
        core.refreshPhone();
        return null;
      } catch (e) {
        return '这个文件读不出来';
      }
    };

    /* ---------------- Dock 可点区域（壳页用它开关 Dock iframe 的 pointer-events） ---------------- */
    core.setDockRects = function (arg) {
      if (arg === 'ALL') { core._dockRects = 'ALL'; return; }
      try {
        var r = (typeof arg === 'string') ? JSON.parse(arg) : arg;
        core._dockRects = Array.isArray(r) ? r : null;
      } catch (e) { core._dockRects = null; }
    };

    /* ================= CHCore.invoke 入口（镜像 CanghaiCore.invoke） ================= */
    function Err(code, message) { var e = new Error(message || code); e.code = code; return e; }
    function ok(data) { return { ok: true, data: (data === undefined ? null : data) }; }
    function fail(code, message) {
      var r = { ok: false, error: code };
      if (message && message !== code) r.message = message;
      return r;
    }

    /* ---------------- fs：内存文件系统（镜像 CoreFs.java） ---------------- */
    function validAppId(id) { return /^[a-zA-Z0-9._-]+$/.test(String(id || '')); }
    function normPath(p) {
      var parts = String(p == null ? '' : p).split('/');
      var out = [];
      for (var i = 0; i < parts.length; i++) {
        var s = parts[i];
        if (!s || s === '.') continue;
        if (s === '..') { if (!out.length) return null; out.pop(); }
        else out.push(s);
      }
      return out.join('/');
    }
    function appStore(appId) {
      if (!core._fs[appId]) core._fs[appId] = { files: {}, dirs: {} };
      return core._fs[appId];
    }
    function hasChildren(st, path) {
      var pfx = path ? path + '/' : '';
      for (var k in st.files) if (k.indexOf(pfx) === 0 && k !== path) return true;
      for (var k2 in st.dirs) if (k2.indexOf(pfx) === 0 && k2 !== path) return true;
      return false;
    }
    function isDirPath(st, path) {
      if (path === '') return true;
      if (st.files[path]) return false;
      if (st.dirs[path]) return true;
      return hasChildren(st, path);
    }
    function pathExists(st, path) {
      if (path === '') return true;
      return !!st.files[path] || isDirPath(st, path);
    }
    function dirSize(st, path) {
      var pfx = path ? path + '/' : '';
      var total = 0;
      for (var k in st.files) if (k === path || k.indexOf(pfx) === 0) total += st.files[k].size;
      return total;
    }
    function dirMtime(st, path) {
      var pfx = path ? path + '/' : '';
      var m = st.dirs[path] || 0;
      for (var k in st.files) if (k === path || k.indexOf(pfx) === 0) m = Math.max(m, st.files[k].mtime);
      for (var k2 in st.dirs) if (k2.indexOf(pfx) === 0) m = Math.max(m, st.dirs[k2]);
      return m;
    }
    function entryFor(st, path) {
      var name = path.split('/').pop();
      if (st.files[path]) {
        var f = st.files[path];
        return { name: name, dir: false, size: f.size, mtime: f.mtime };
      }
      return { name: name, dir: true, size: 0, mtime: dirMtime(st, path) };
    }
    function ensureParents(st, path) {
      var parts = path.split('/');
      for (var i = 1; i < parts.length; i++) {
        var p = parts.slice(0, i).join('/');
        if (!st.dirs[p] && !st.files[p]) st.dirs[p] = Date.now();
      }
    }
    function removeSubtree(st, path) {
      var pfx = path + '/';
      delete st.files[path];
      delete st.dirs[path];
      for (var k in st.files) if (k.indexOf(pfx) === 0) delete st.files[k];
      for (var k2 in st.dirs) if (k2.indexOf(pfx) === 0) delete st.dirs[k2];
    }
    function moveSubtree(st, from, to) {
      var pfx = from + '/';
      var filesToMove = [], dirsToMove = [];
      if (st.files[from]) filesToMove.push(from);
      if (st.dirs[from] != null) dirsToMove.push(from);
      for (var k in st.files) if (k.indexOf(pfx) === 0) filesToMove.push(k);
      for (var k2 in st.dirs) if (k2.indexOf(pfx) === 0) dirsToMove.push(k2);
      filesToMove.forEach(function (k) {
        var nk = to + k.slice(from.length);
        st.files[nk] = st.files[k];
        delete st.files[k];
      });
      dirsToMove.forEach(function (k) {
        var nk = to + k.slice(from.length);
        st.dirs[nk] = st.dirs[k];
        delete st.dirs[k];
      });
      ensureParents(st, to);
    }
    function copySubtree(st, from, to) {
      var pfx = from + '/';
      if (st.files[from]) {
        st.files[to] = { b64: st.files[from].b64, size: st.files[from].size, mtime: Date.now() };
        ensureParents(st, to);
        return;
      }
      st.dirs[to] = Date.now();
      ensureParents(st, to);
      for (var k in st.files) if (k.indexOf(pfx) === 0) {
        var nk = to + k.slice(from.length);
        st.files[nk] = { b64: st.files[k].b64, size: st.files[k].size, mtime: Date.now() };
      }
      for (var k2 in st.dirs) if (k2.indexOf(pfx) === 0) st.dirs[to + k2.slice(from.length)] = Date.now();
    }

    function fsInvoke(method, p, ctx) {
      var appId = String((p && p.appId) || 'desktop');
      if (!validAppId(appId)) throw Err('INVALID_ARGS', 'appId 非法');
      var st = appStore(appId);
      var path = null;
      if (method !== 'root' && method !== 'listApps' && method !== 'storage' && method !== 'appDir' && method !== 'pickRoot') {
        /* SDK 的 rename/copy/move 传 from 不传 path，这里两边都认。 */
        var rawPath = (p && p.path != null) ? p.path : (p ? p.from : '');
        path = normPath(rawPath);
        if (path == null) throw Err('PERMISSION_DENIED', '路径越界');
      }
      switch (method) {
        case 'root':
          return { mode: 'internal', path: '/debug/Canghai', uri: '', internalPath: '/debug/Canghai', dataPath: '/debug/Canghai/Data', moving: false };
        case 'setRootMode': {
          var mode = p ? String(p.mode || '') : '';
          if (mode !== 'internal') throw Err('EXTERNAL_ROOT_PENDING', '外置根目录尚未接入');
          try {
            var w = ctx && ctx.win;
            if (w && typeof w.__canghaiCoreEmit === 'function') {
              setTimeout(function () {
                try { w.__canghaiCoreEmit('fs:root', JSON.stringify({ type: 'fs:root', data: { status: 'done', mode: 'internal' }, ts: Date.now() })); } catch (e) {}
              }, 0);
            }
          } catch (e) {}
          return { started: false, from: 'internal', to: 'internal' };
        }
        case 'pickRoot':
          throw Err('EXTERNAL_ROOT_PENDING', '外置根目录尚未接入');
        case 'appDir':
          return { path: '/debug/Canghai/Data/' + appId };
        case 'list': {
          if (st.files[path]) throw Err('NOT_A_DIRECTORY', '目标不是目录');
          if (!pathExists(st, path)) return [];
          var pfx = path ? path + '/' : '';
          var kids = {};
          Object.keys(st.files).forEach(function (k) {
            if (k.indexOf(pfx) !== 0 || k === path) return;
            var rest = k.slice(pfx.length);
            var seg = rest.split('/')[0];
            if (rest.indexOf('/') >= 0) kids[seg] = { name: seg, dir: true, size: 0, mtime: dirMtime(st, pfx + seg) };
            else kids[seg] = { name: seg, dir: false, size: st.files[k].size, mtime: st.files[k].mtime };
          });
          Object.keys(st.dirs).forEach(function (k) {
            if (k.indexOf(pfx) !== 0 || k === path) return;
            var rest = k.slice(pfx.length);
            var seg = rest.split('/')[0];
            if (!kids[seg]) kids[seg] = { name: seg, dir: true, size: 0, mtime: dirMtime(st, pfx + seg) };
            else kids[seg].dir = true;
          });
          var arr = Object.keys(kids).map(function (k) { return kids[k]; });
          arr.sort(function (a, b) {
            if (a.dir !== b.dir) return a.dir ? -1 : 1;
            var an = a.name.toLowerCase(), bn = b.name.toLowerCase();
            return an < bn ? -1 : (an > bn ? 1 : 0);
          });
          return arr;
        }
        case 'stat':
          if (!pathExists(st, path)) throw Err('NOT_FOUND', '文件不存在');
          return entryFor(st, path);
        case 'exists':
          return pathExists(st, path);
        case 'mkdir': {
          if (pathExists(st, path)) return isDirPath(st, path);
          ensureParents(st, path);
          st.dirs[path] = Date.now();
          return true;
        }
        case 'delete': {
          if (path === '') throw Err('PERMISSION_DENIED', '不能删除沙盒根目录');
          var recursive = !!(p && p.recursive);
          if (isDirPath(st, path) && !recursive && hasChildren(st, path)) throw Err('NOT_EMPTY', '目录非空');
          removeSubtree(st, path);
          return true;
        }
        case 'rename': {
          var to = normPath(p ? p.to : '');
          if (to == null) throw Err('PERMISSION_DENIED', '路径越界');
          if (!pathExists(st, path)) throw Err('NOT_FOUND', '源文件不存在');
          if (to === '' || to === path || to.indexOf(path + '/') === 0) throw Err('IO_ERROR', '改名失败');
          if (pathExists(st, to)) removeSubtree(st, to);
          moveSubtree(st, path, to);
          return true;
        }
        case 'copy': {
          var cto = normPath(p ? p.to : '');
          if (cto == null) throw Err('PERMISSION_DENIED', '路径越界');
          if (!pathExists(st, path)) throw Err('NOT_FOUND', '源文件不存在');
          if (cto === '' || cto === path || cto.indexOf(path + '/') === 0) throw Err('IO_ERROR', '复制失败');
          if (pathExists(st, cto)) {
            if (!(p && p.overwrite)) throw Err('ALREADY_EXISTS', '目标已存在');
            removeSubtree(st, cto);
          }
          copySubtree(st, path, cto);
          return true;
        }
        case 'move': {
          var mto = normPath(p ? p.to : '');
          if (mto == null) throw Err('PERMISSION_DENIED', '路径越界');
          if (!pathExists(st, path)) throw Err('NOT_FOUND', '源文件不存在');
          if (mto === '' || mto === path || mto.indexOf(path + '/') === 0) throw Err('IO_ERROR', '移动失败');
          if (pathExists(st, mto)) {
            if (!(p && p.overwrite)) throw Err('ALREADY_EXISTS', '目标已存在');
            removeSubtree(st, mto);
          }
          moveSubtree(st, path, mto);
          return true;
        }
        case 'readText': {
          var f = st.files[path];
          if (!f) throw Err('NOT_FOUND', '文件不存在');
          var max = (p && typeof p.max === 'number') ? p.max : MAX_TEXT;
          if (!(max > 0) || max > MAX_TEXT) max = MAX_TEXT;
          if (f.size > max) throw Err('FILE_TOO_LARGE', '文件太大了');
          return b64ToText(f.b64);
        }
        case 'writeText': {
          var text = (p && p.text != null) ? String(p.text) : '';
          var cur = '';
          if (p && p.append && st.files[path]) cur = b64ToText(st.files[path].b64);
          var bytes = new TextEncoder().encode(cur + text);
          ensureParents(st, path);
          delete st.dirs[path];
          st.files[path] = { b64: b64encodeBytes(bytes), size: bytes.length, mtime: Date.now() };
          return { size: bytes.length };
        }
        case 'readBase64': {
          var fb = st.files[path];
          if (!fb) throw Err('NOT_FOUND', '文件不存在');
          var maxB = (p && typeof p.max === 'number') ? p.max : MAX_BASE64;
          if (!(maxB > 0) || maxB > MAX_BASE64) maxB = MAX_BASE64;
          if (fb.size > maxB) throw Err('FILE_TOO_LARGE', '文件太大了');
          return fb.b64;
        }
        case 'writeBase64': {
          var b64 = (p && p.base64 != null) ? String(p.base64) : '';
          if (!validB64(b64)) throw Err('INVALID_ARGS', 'base64 非法');
          var raw = b64decodeBytes(b64);
          ensureParents(st, path);
          delete st.dirs[path];
          st.files[path] = { b64: b64, size: raw.length, mtime: Date.now() };
          return { size: raw.length };
        }
        case 'listApps': {
          var out = [];
          Object.keys(core._fs).forEach(function (id) {
            var s2 = core._fs[id];
            if (!Object.keys(s2.files).length && !Object.keys(s2.dirs).length) return;
            out.push({ appId: id, size: dirSize(s2, ''), mtime: dirMtime(s2, '') });
          });
          return out;
        }
        case 'storage': {
          var total = 0;
          Object.keys(core._fs).forEach(function (id) { total += dirSize(core._fs[id], ''); });
          return { path: '/debug/Canghai/Data', free: 8 * 1024 * 1024 * 1024, total: 16 * 1024 * 1024 * 1024, dataSize: total };
        }
        default:
          throw Err('UNKNOWN_METHOD', '未知的 fs 方法: ' + method);
      }
    }

    /* ---------------- device ---------------- */
    function deviceInfo() {
      /* uuid/model/brand 是桌面要的三样；canghaiVersion/androidVersion
         是手机页存储设置里读的，顺手带上，桌面侧 canghai-core.js 会滤掉。 */
      return { uuid: S.uuid, model: 'Canghai Debug', brand: 'Canghai', canghaiVersion: 1.0, androidVersion: '14' };
    }
    function deviceInvoke(method, p, ctx) {
      switch (method) {
        case 'renderScale':
          return { value: S.renderScale };
        case 'setRenderScale': {
          var v = parseInt(p ? p.value : 100, 10) || 100;
          if (v < 20) v = 20;
          if (v > 100) v = 100;
          S.renderScale = v; save();
          return { value: v };
        }
        case 'streamStats':
          return { fps: 60, bitrate: 0, rtt: 0 };
        case 'webFps':
          return true;
        case 'saveDownload': {
          var name = String((p && p.filename) || 'download');
          var dataUrl = String((p && p.dataUrl) || '');
          if (!dataUrl) return { saved: false, error: '没有数据' };
          var done = false;
          try {
            var doc = ctx && ctx.doc;
            if (doc) {
              var a = doc.createElement('a');
              a.href = dataUrl;
              a.download = name;
              a.style.display = 'none';
              doc.body.appendChild(a);
              a.click();
              setTimeout(function () { try { a.remove(); } catch (e) {} }, 500);
              done = true;
            }
          } catch (e) { done = false; }
          return done ? { saved: true, name: name } : { saved: false, error: '下载失败' };
        }
        case 'info':
        case 'getInfo':
        default:
          return deviceInfo();
      }
    }

    /* ---------------- webview：壳页覆盖层 iframe ---------------- */
    function wvInvoke(method, p, ctx) {
      var wv = core._wv;
      var hooks = core._wvHooks;
      var dpr = (ctx && ctx.dpr) || 1;
      function toCssRect() {
        return { x: (Number(p.x) || 0) / dpr, y: (Number(p.y) || 0) / dpr,
          width: (Number(p.width) || 0) / dpr, height: (Number(p.height) || 0) / dpr };
      }
      switch (method) {
        case 'show': {
          var r = toCssRect();
          wv.showing = true;
          wv.rect = r;
          if (p.url) {
            wv.url = String(p.url);
            wv.depth += 1;
            wv.maxDepth = wv.depth;
            wv.progress = 0;
          }
          if (hooks && hooks.show) hooks.show(r, p.url ? String(p.url) : '');
          return true;
        }
        case 'move': {
          var r2 = toCssRect();
          wv.rect = r2;
          if (hooks && hooks.move) hooks.move(r2);
          return true;
        }
        case 'hide':
          wv.showing = false;
          if (hooks && hooks.hide) hooks.hide();
          return true;
        case 'navigate': {
          if (p.url) {
            wv.url = String(p.url);
            wv.depth += 1;
            wv.maxDepth = wv.depth;
            wv.progress = 0;
          }
          if (hooks && hooks.navigate) hooks.navigate(String(p.url || ''));
          return true;
        }
        case 'back':
          if (wv.depth > 0) wv.depth -= 1;
          if (hooks && hooks.back) hooks.back();
          return true;
        case 'forward':
          if (wv.depth < wv.maxDepth) wv.depth += 1;
          if (hooks && hooks.forward) hooks.forward();
          return true;
        case 'reload':
          wv.progress = 0;
          if (hooks && hooks.reload) hooks.reload();
          return true;
        case 'stop':
          if (hooks && hooks.stop) hooks.stop();
          return true;
        case 'close':
          wv.showing = false;
          if (hooks && hooks.close) hooks.close();
          return true;
        case 'status': {
          var s = {};
          try { if (hooks && hooks.status) s = hooks.status() || {}; } catch (e) {}
          return {
            showing: wv.showing,
            title: (s.title != null ? s.title : wv.title) || '',
            url: (s.url != null ? s.url : wv.url) || '',
            progress: (s.progress != null ? s.progress : wv.progress) || 0,
            canGoBack: wv.depth > 0,
            canGoForward: wv.depth < wv.maxDepth
          };
        }
        default:
          throw Err('UNKNOWN_METHOD', '未知方法: webview.' + method);
      }
    }

    /* ---------------- invoke 总入口 ---------------- */
    core.invoke = function (ns, method, payload, ctx) {
      try {
        var p = isObj(payload) ? payload : {};
        var data;
        switch (String(ns)) {
          case 'fs':
            data = fsInvoke(String(method), p, ctx);
            break;
          case 'device':
            data = deviceInvoke(String(method), p, ctx);
            break;
          case 'perf':
            if (method === 'enable') { core._perfOn = !!(p && p.on); data = { enabled: core._perfOn }; }
            else if (method === 'state') data = { enabled: core._perfOn };
            else if (method === 'snapshot') data = { pages: [], windows: [] };
            else if (method === 'report') data = true;
            else throw Err('UNKNOWN_METHOD', '未知方法: perf.' + method);
            break;
          case 'audio':
            if (method === 'request') data = { started: false, reason: 'NOT_WIRED' };
            else data = { permission: false, started: false, reason: 'NOT_WIRED' };
            break;
          case 'screen':
          case 'peripheral':
            data = [];
            break;
          case 'lan':
            if (method === 'start') data = { port: 0, url: '', ready: false };
            else if (method === 'url' || method === 'getUrl') data = { url: '' };
            else if (method === 'status') data = { port: 0, url: '', ready: false, lanUrl: '', pairCode: '------' };
            else throw Err('UNKNOWN_METHOD', '未知方法: lan.' + method);
            break;
          case 'github':
            if (method === 'request') {
              var url = String(p.url || '');
              var host = '';
              try { host = new URL(url).hostname; } catch (e) {}
              if (host !== 'github.com' && host !== 'api.github.com' && host !== 'uploads.github.com')
                throw Err('FORBIDDEN_HOST', '只允许 github.com 与 api.github.com');
              data = { status: 200, body: '[]', headers: {} };
            } else if (method === 'download') {
              return fail('harness: no download');
            } else throw Err('UNKNOWN_METHOD', '未知方法: github.' + method);
            break;
          case 'webview':
            data = wvInvoke(String(method), p, ctx);
            break;
          default:
            throw Err('UNKNOWN_NAMESPACE', '未知命名空间: ' + ns);
        }
        return ok(data);
      } catch (e) {
        if (e && e.code) return fail(e.code, e.message);
        return fail('INTERNAL_ERROR', '内部错误');
      }
    };

    return core;
  }

  global.__chFakeNativeFactory = createFakeNative;
  /* 只在顶层窗口自动安装：iframe 里的页面共享顶层的同一份。 */
  try {
    if (global.top === global && !global.__chFakeNative) {
      global.__chFakeNative = createFakeNative();
    }
  } catch (e) {}

})(window);
