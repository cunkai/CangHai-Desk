/* ==========================================================================
 * 沧海一键调试工具 · 注入桩 (stub.js)
 *
 * 由调试服务器以内联 <script> 注入三个页面（phonehome / desktop / dock），
 * 在页面自己的脚本之前执行。按页面定义桥对象，全部委托给顶层窗口的
 * 共享假核心 window.__chFakeNative；页面独立打开（无壳页）时退回本页
 * 一份假核心，照样能跑。
 * 注意：本文件绝不能出现 "</scr" + "ipt>" 字面量，否则会截断注入。
 * ========================================================================== */
(function () {
  'use strict';

  var PAGE = window.__CH_PAGE || 'unknown';

  /* ---- 解析共享假核心 ---- */
  var FAKE = null;
  try { if (window.top && window.top.__chFakeNative) FAKE = window.top.__chFakeNative; } catch (e) {}
  if (!FAKE) {
    try {
      if (window.__chFakeNative) FAKE = window.__chFakeNative;
      else if (window.__chFakeNativeFactory) FAKE = window.__chFakeNativeFactory();
      else if (window.top && window.top.__chFakeNativeFactory) FAKE = window.top.__chFakeNativeFactory();
    } catch (e) {}
  }
  if (!FAKE) return;
  if (window.top === window) {
    try { if (!window.__chFakeNative) window.__chFakeNative = FAKE; } catch (e) {}
  }

  function J(o) { try { return JSON.stringify(o); } catch (e) { return '{}'; } }
  function log(method, detail) { try { FAKE.log(PAGE, method, detail); } catch (e) {} }
  function wrap(method, fn) {
    return function () {
      var args = Array.prototype.slice.call(arguments);
      var detail = args.map(function (a) {
        if (typeof a === 'string') return a.length > 120 ? a.slice(0, 120) + '…' : a;
        if (a == null) return String(a);
        try { var s = JSON.stringify(a); return s.length > 120 ? s.slice(0, 120) + '…' : s; } catch (e) { return String(a); }
      }).join(', ');
      log(method, detail);
      try { return fn.apply(null, args); }
      catch (e) { log(method + ' ERROR', String((e && e.message) || e)); throw e; }
    };
  }

  /* ---- 本页触发一次真实浏览器下载（手势上下文内调用才不会被拦） ---- */
  function triggerDownload(filename, dataUrl) {
    try {
      var a = document.createElement('a');
      a.href = dataUrl;
      a.download = filename || 'download';
      a.style.display = 'none';
      document.body.appendChild(a);
      a.click();
      setTimeout(function () { try { a.remove(); } catch (e) {} }, 500);
      return true;
    } catch (e) { return false; }
  }
  function b64encodeUtf8(s) {
    try { return btoa(unescape(encodeURIComponent(String(s)))); }
    catch (e) { return btoa(String(s)); }
  }
  function stamp() {
    var d = new Date();
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return '' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes());
  }

  /* ---- Dock 指针上报：壳页据此开关 Dock iframe 的 pointer-events ---- */
  var lastPtrReport = 0;
  function reportPointer(e) {
    try {
      if (!FAKE._dockPointerHook) return;
      var now = Date.now();
      if (now - lastPtrReport < 40) return;
      lastPtrReport = now;
      var ox = 0, oy = 0;
      try {
        var fr = window.frameElement;
        if (fr) { var r = fr.getBoundingClientRect(); ox = r.left; oy = r.top; }
      } catch (e) {}
      FAKE._dockPointerHook(ox + e.clientX, oy + e.clientY);
    } catch (e) {}
  }
  if (PAGE === 'desktop' || PAGE === 'dock') {
    try { document.addEventListener('mousemove', reportPointer, true); } catch (e) {}
  }

  /* ================= 手机页：window.CanghaiPhone ================= */
  if (PAGE === 'phone') {
    var Phone = {};
    Phone.getState = wrap('CanghaiPhone.getState', function () { return J(FAKE.getState(0)); });
    Phone.getHostStatus = wrap('CanghaiPhone.getHostStatus', function () { return J(FAKE.getHostStatus()); });
    Phone.getPerms = wrap('CanghaiPhone.getPerms', function () { return J(FAKE.getPerms()); });
    Phone.getPeripherals = wrap('CanghaiPhone.getPeripherals', function () { return '[]'; });
    Phone.getAvailableDisplays = wrap('CanghaiPhone.getAvailableDisplays', function () { return J(FAKE.getAvailableDisplays()); });
    Phone.getHtmlApp = wrap('CanghaiPhone.getHtmlApp', function (id) { return FAKE.getHtmlApp(id); });
    Phone.installHtml = wrap('CanghaiPhone.installHtml', function (name, html, iconSvg, png) {
      return FAKE.installHtml(name, html, iconSvg, png);
    });
    Phone.removeHtml = wrap('CanghaiPhone.removeHtml', function (id) { FAKE.removeHtml(id); return ''; });
    Phone.renameHtml = wrap('CanghaiPhone.renameHtml', function (id, name) { FAKE.renameHtml(id, name); return ''; });
    Phone.setHtmlIcon = wrap('CanghaiPhone.setHtmlIcon', function (id, svg) { FAKE.setHtmlIcon(id, svg); return ''; });
    Phone.setHtmlOrientation = wrap('CanghaiPhone.setHtmlOrientation', function (id, ori) { FAKE.setHtmlOrientation(id, ori); return ''; });
    Phone.saveCursor = wrap('CanghaiPhone.saveCursor', function (json) {
      try { FAKE.saveCursor(JSON.parse(json)); } catch (e) {}
      return '';
    });
    Phone.removeCursor = wrap('CanghaiPhone.removeCursor', function (id) { FAKE.removeCursor(id); return ''; });
    Phone.requestRoot = wrap('CanghaiPhone.requestRoot', function () { return false; });
    Phone.requestShizuku = wrap('CanghaiPhone.requestShizuku', function () { return false; });
    Phone.openOverlay = wrap('CanghaiPhone.openOverlay', function () { return false; });
    Phone.openShortcutSettings = wrap('CanghaiPhone.openShortcutSettings', function () {});
    Phone.setDefaultDesktopType = wrap('CanghaiPhone.setDefaultDesktopType', function (v) { FAKE.setDesktopType(v); });
    Phone.setDesktopType = wrap('CanghaiPhone.setDesktopType', function (v) { FAKE.setDesktopType(v); });
    Phone.setUserProfile = wrap('CanghaiPhone.setUserProfile', function (name, avatar) { FAKE.setUserProfile(name, avatar); });
    Phone.setCursorSize = wrap('CanghaiPhone.setCursorSize', function (n) { FAKE.setCursorSize(n); });
    Phone.setCursorSizeForDisplay = wrap('CanghaiPhone.setCursorSizeForDisplay', function (id, n) { FAKE.setCursorSizeForDisplay(id, n); });
    Phone.setCursorSpeed = wrap('CanghaiPhone.setCursorSpeed', function (v) { FAKE.setCursorSpeed(v); });
    Phone.selectCursor = wrap('CanghaiPhone.selectCursor', function (id) { FAKE.selectCursor(id); });
    Phone.selectCursorForDisplay = wrap('CanghaiPhone.selectCursorForDisplay', function (id, cur) { FAKE.selectCursorForDisplay(id, cur); });
    Phone.createShortcut = wrap('CanghaiPhone.createShortcut', function () { return false; });
    Phone.createAppShortcut = wrap('CanghaiPhone.createAppShortcut', function () { return false; });
    Phone.enterBlackTouchpad = wrap('CanghaiPhone.enterBlackTouchpad', function () {});
    Phone.setMainDisplay = wrap('CanghaiPhone.setMainDisplay', function () {});
    Phone.showSoftKeyboard = wrap('CanghaiPhone.showSoftKeyboard', function () {});
    Phone.hideSoftKeyboard = wrap('CanghaiPhone.hideSoftKeyboard', function () {});
    Phone.exportPackage = wrap('CanghaiPhone.exportPackage', function (name, base64) {
      var ok = false;
      try {
        if (base64) ok = triggerDownload(name || 'package.canghai', 'data:application/zip;base64,' + base64);
      } catch (e) {}
      return ok;
    });
    Phone.exportDesktopState = wrap('CanghaiPhone.exportDesktopState', function () {
      var bundle = FAKE.exportDesktopBundle();
      if (!bundle) return '{"ok":false,"error":"桌面还没有保存过布局"}';
      var name = '沧海桌面-' + stamp() + '.canghai-desktop';
      /* Blob URL：data: URL 触发的下载在 Chrome 里文件名会被改成 download，
         Blob 才能保住中文文件名与 .canghai-desktop 扩展名。 */
      try {
        var blob = new Blob([bundle], { type: 'application/json' });
        var url = URL.createObjectURL(blob);
        triggerDownload(name, url);
        setTimeout(function () { try { URL.revokeObjectURL(url); } catch (e) {} }, 5000);
      } catch (e) {
        triggerDownload(name, 'data:application/json;base64,' + b64encodeUtf8(bundle));
      }
      return J({ ok: true, name: name });
    });
    Phone.importDesktopState = wrap('CanghaiPhone.importDesktopState', function (bundleJson) {
      var err = FAKE.importDesktopBundle(bundleJson);
      if (err) return J({ ok: false, error: err });
      return '{"ok":true}';
    });
    Phone.isDebug = wrap('CanghaiPhone.isDebug', function () { return true; });
    Phone.getDebugLogs = wrap('CanghaiPhone.getDebugLogs', function () { return '[]'; });
    Phone.clearDebugLogs = wrap('CanghaiPhone.clearDebugLogs', function () {});
    Phone.copyLogToClipboard = wrap('CanghaiPhone.copyLogToClipboard', function () { return false; });
    Phone.getPendingIncomingFiles = wrap('CanghaiPhone.getPendingIncomingFiles', function () { return '[]'; });
    window.CanghaiPhone = Phone;
    window.AosenPhone = Phone;
    /* 管理者 SDK（shared/core.js）要的底层通道：它在页面脚本里自己装出
       window.CanghaiCore（fs/device/on），调用走这里。只给 __CanghaiNative，
       不直接定义 CanghaiCore，否则垫片跳过、手机页的存储设置就没 fs 可调。 */
    window.__CanghaiNative = {
      invoke: function (namespace, method, payloadJson) {
        var payload = {};
        try { payload = payloadJson ? JSON.parse(payloadJson) : {}; }
        catch (e) { return '{"ok":false,"error":"INVALID_ARGS","message":"payload 不是合法 JSON"}'; }
        log('__CanghaiNative.invoke', String(namespace) + '.' + String(method));
        var r;
        try {
          r = FAKE.invoke(String(namespace), String(method), payload,
            { win: window, doc: document, dpr: window.devicePixelRatio || 1 });
        } catch (e) {
          r = { ok: false, error: 'INTERNAL_ERROR', message: '内部错误' };
        }
        return J(r);
      }
    };
    return;
  }

  /* ================= 桌面页：window.Canghai / window.Aosen / window.CanghaiCore ================= */
  if (PAGE === 'desktop') {
    var Canghai = {};
    Canghai.getState = wrap('Canghai.getState', function () { return J(FAKE.getState(1)); });
    Canghai.getDeskState = wrap('Canghai.getDeskState', function () { return FAKE.getDeskState(); });
    Canghai.getDesktopItems = wrap('Canghai.getDesktopItems', function () { return FAKE.getDeskState(); });
    Canghai.saveDeskState = wrap('Canghai.saveDeskState', function (s) { FAKE.saveDeskState(s); });
    Canghai.getHtmlApp = wrap('Canghai.getHtmlApp', function (id) { return FAKE.getHtmlApp(id); });
    Canghai.getHtmlAppSource = wrap('Canghai.getHtmlAppSource', function (id) { return FAKE.getHtmlApp(id); });
    Canghai.installHtml = wrap('Canghai.installHtml', function (name, html, iconSvg) {
      return FAKE.installHtml(name, html, iconSvg, '');
    });
    Canghai.removeHtml = wrap('Canghai.removeHtml', function (id) { FAKE.removeHtml(id); return ''; });
    Canghai.renameHtml = wrap('Canghai.renameHtml', function (id, name) { FAKE.renameHtml(id, name); return ''; });
    Canghai.setHtmlIcon = wrap('Canghai.setHtmlIcon', function (id, svg) { FAKE.setHtmlIcon(id, svg); return ''; });
    Canghai.saveWallpaper = wrap('Canghai.saveWallpaper', function (wp) { FAKE.saveWallpaper(wp); });
    Canghai.saveGrid = wrap('Canghai.saveGrid', function (c, r) { FAKE.saveGrid(c, r); });
    Canghai.setDockPins = wrap('Canghai.setDockPins', function (json) {
      try { FAKE.setDockPins(JSON.parse(json)); } catch (e) {}
    });
    Canghai.setDockAutoHide = wrap('Canghai.setDockAutoHide', function (on) { FAKE.setDockAutoHide(on); });
    Canghai.getDockAutoHide = wrap('Canghai.getDockAutoHide', function () { return FAKE.getDockAutoHide(); });
    Canghai.isDockOverlayOn = wrap('Canghai.isDockOverlayOn', function () { return true; });
    Canghai.setLanguage = wrap('Canghai.setLanguage', function (l) { FAKE.setLanguage(l); });
    Canghai.getLanguage = wrap('Canghai.getLanguage', function () { return FAKE.getLanguage(); });
    Canghai.setUserProfile = wrap('Canghai.setUserProfile', function (name, avatar) { FAKE.setUserProfile(name, avatar); });
    Canghai.setCursorSize = wrap('Canghai.setCursorSize', function (n) { FAKE.setCursorSize(n); });
    Canghai.selectCursor = wrap('Canghai.selectCursor', function (id) { FAKE.selectCursor(id); });
    Canghai.setCursorSpeed = wrap('Canghai.setCursorSpeed', function (v) { FAKE.setCursorSpeed(v); });
    Canghai.saveCursor = wrap('Canghai.saveCursor', function (json) {
      try { FAKE.saveCursor(typeof json === 'string' ? JSON.parse(json) : json); } catch (e) {}
    });
    Canghai.saveDownload = wrap('Canghai.saveDownload', function (filename, dataUrl) {
      var ok = triggerDownload(filename, dataUrl);
      return J(ok ? { saved: true, name: filename } : { saved: false, error: '下载失败' });
    });
    Canghai.reportJsError = wrap('Canghai.reportJsError', function (source, message, stack) {
      log('reportJsError', (source || '') + ': ' + (message || ''));
    });
    Canghai.toast = wrap('Canghai.toast', function (msg) { FAKE.lastToast = String(msg || ''); });
    Canghai.openApp = wrap('Canghai.openApp', function (id) {
      try { if (typeof window.openApp === 'function') window.openApp(String(id)); } catch (e) {}
    });
    Canghai.launchHtml = wrap('Canghai.launchHtml', function (id) {
      try { if (typeof window.openApp === 'function') window.openApp(String(id)); } catch (e) {}
    });
    Canghai.openLaunchpad = wrap('Canghai.openLaunchpad', function () { FAKE.openLaunchpadOnDock(); });
    /* 小组件 API 占位：settings.js 加载时会用真实现覆盖这几项。 */
    Canghai.version = 1;
    Canghai.widgets = {};
    Canghai.registerWidget = wrap('Canghai.registerWidget', function () {});
    Canghai.parseWidgets = wrap('Canghai.parseWidgets', function () { return []; });
    Canghai.setContextMenu = wrap('Canghai.setContextMenu', function () {});
    Canghai.openModal = wrap('Canghai.openModal', function () {});
    Canghai.closeModal = wrap('Canghai.closeModal', function () {});
    Canghai.openSelf = wrap('Canghai.openSelf', function () {});
    Canghai.getAppsJson = wrap('Canghai.getAppsJson', function () { return '[]'; });
    Canghai.getConnectedScreens = wrap('Canghai.getConnectedScreens', function () { return '[]'; });
    Canghai.setResolution = wrap('Canghai.setResolution', function (key) { FAKE.setResolution(key); });
    Canghai.getResolutions = wrap('Canghai.getResolutions', function () { return J(FAKE.getResolutions()); });
    Canghai.getAvailableDisplays = wrap('Canghai.getAvailableDisplays', function () { return J(FAKE.getAvailableDisplays()); });
    Canghai.getPeripherals = wrap('Canghai.getPeripherals', function () { return '[]'; });
    Canghai.setPointerLocked = wrap('Canghai.setPointerLocked', function () {});
    Canghai.setPeripheralDisplay = wrap('Canghai.setPeripheralDisplay', function (pid, did) { FAKE.setPeripheralDisplay(pid, did); });
    Canghai.setDockRunState = wrap('Canghai.setDockRunState', function () {});
    Canghai.setDockFullscreen = wrap('Canghai.setDockFullscreen', function () {});
    Canghai.setActiveWinTitle = wrap('Canghai.setActiveWinTitle', function () {});
    Canghai.inputTraceEnable = wrap('Canghai.inputTraceEnable', function () {});
    Canghai.inputTraceClear = wrap('Canghai.inputTraceClear', function () {});
    Canghai.inputTrace = wrap('Canghai.inputTrace', function () { return ''; });
    Canghai.lockDevice = wrap('Canghai.lockDevice', function () {});
    Canghai.takePendingOpen = wrap('Canghai.takePendingOpen', function () { return FAKE.takePendingOpen(); });
    Canghai.exitDesktop = wrap('Canghai.exitDesktop', function () {});
    Canghai.unzipUri = wrap('Canghai.unzipUri', function () { return ''; });
    Canghai.unzipBase = wrap('Canghai.unzipBase', function () { return ''; });
    Canghai.unzipBase64 = wrap('Canghai.unzipBase64', function () { return ''; });
    Canghai.getPickedUri = wrap('Canghai.getPickedUri', function () { return ''; });
    Canghai.disconnectDisplay = wrap('Canghai.disconnectDisplay', function () {});
    Canghai.launch = wrap('Canghai.launch', function () {});
    Canghai.getSystemInfoJson = wrap('Canghai.getSystemInfoJson', function () { return J(FAKE.getSystemInfo()); });
    Canghai.getAboutInfo = wrap('Canghai.getAboutInfo', function () { return J(FAKE.getAboutInfo()); });
    Canghai.checkUpdate = wrap('Canghai.checkUpdate', function () { return J(FAKE.checkUpdate()); });
    Canghai.openUpdateUrl = wrap('Canghai.openUpdateUrl', function () {});
    Canghai.getDockOffsets = wrap('Canghai.getDockOffsets', function () { return '{"x":0,"y":0}'; });
    window.Canghai = Canghai;
    window.Aosen = Canghai;

    /* CHCore 底层：canghai-core.js 会包出 window.CHCore。同步返回 JSON 字符串。
       version: 1 是关键——真机原生 CanghaiCore 自带 version，shared/core.js
       垫片见了就跳过；没 version 垫片会覆掉这里、改走 __CanghaiNative，
       而那条路在桌面页是不通的（报「管理者未就绪」）。 */
    var coreInvoke = function (namespace, method, payloadJson) {
      var payload = {};
      try { payload = payloadJson ? JSON.parse(payloadJson) : {}; }
      catch (e) { return '{"ok":false,"error":"INVALID_ARGS","message":"payload 不是合法 JSON"}'; }
      var summary = String(namespace) + '.' + String(method);
      try {
        var p = payload || {};
        if (p.appId) summary += ' app=' + p.appId;
        if (p.path != null) summary += ' path=' + String(p.path).slice(0, 60);
        if (p.url) summary += ' url=' + String(p.url).slice(0, 60);
      } catch (e) {}
      log('CanghaiCore.invoke', summary);
      var r;
      try {
        r = FAKE.invoke(String(namespace), String(method), payload,
          { win: window, doc: document, dpr: window.devicePixelRatio || 1 });
      } catch (e) {
        r = { ok: false, error: 'INTERNAL_ERROR', message: '内部错误' };
      }
      return J(r);
    };
    window.CanghaiCore = { version: 1, invoke: coreInvoke };
    /* perf.js 的 rAF 探针直接调 __CanghaiNative.invoke，同一路。 */
    window.__CanghaiNative = { invoke: coreInvoke };
    return;
  }

  /* ================= Dock 页：window.CanghaiDock（+ window.Canghai 兜底） ================= */
  if (PAGE === 'dock') {
    var Dock = {};
    Dock.getState = wrap('CanghaiDock.getState', function () {
      var st = FAKE.getState(1);
      st.displayId = 1;
      return J(st);
    });
    Dock.getAboutInfo = wrap('CanghaiDock.getAboutInfo', function () { return J(FAKE.getAboutInfo()); });
    Dock.getLanguage = wrap('CanghaiDock.getLanguage', function () { return FAKE.getLanguage(); });
    Dock.getSystemInfoJson = wrap('CanghaiDock.getSystemInfoJson', function () { return J(FAKE.getSystemInfo()); });
    Dock.getDockOffsets = wrap('CanghaiDock.getDockOffsets', function () { return '{"x":0,"y":0}'; });
    Dock.setDockOffsets = wrap('CanghaiDock.setDockOffsets', function () {});
    Dock.reportTouchableRegions = wrap('CanghaiDock.reportTouchableRegions', function (arg) { FAKE.setDockRects(arg); });
    Dock.syncWindowsToDesktop = wrap('CanghaiDock.syncWindowsToDesktop', function () {});
    Dock.launch = wrap('CanghaiDock.launch', function () {});
    Dock.openApp = wrap('CanghaiDock.openApp', function (id) { FAKE.openAppOnDesktop(id); });
    Dock.closeAppById = wrap('CanghaiDock.closeAppById', function (id) { FAKE.closeAppOnDesktop(id); });
    Dock.setDockPins = wrap('CanghaiDock.setDockPins', function (json) {
      try { FAKE.setDockPins(JSON.parse(json)); } catch (e) {}
    });
    Dock.setDockLaunchpad = wrap('CanghaiDock.setDockLaunchpad', function () {});
    Dock.setDockMenuOpen = wrap('CanghaiDock.setDockMenuOpen', function () {});
    Dock.setDockHover = wrap('CanghaiDock.setDockHover', function () {});
    Dock.setDockHitRect = wrap('CanghaiDock.setDockHitRect', function () {});
    Dock.setDockBounds = wrap('CanghaiDock.setDockBounds', function () {});
    Dock.setDockBoundsFraction = wrap('CanghaiDock.setDockBoundsFraction', function () {});
    Dock.notifyLaunchpadState = wrap('CanghaiDock.notifyLaunchpadState', function () {});
    Dock.lockDevice = wrap('CanghaiDock.lockDevice', function () {});
    Dock.openLaunchpad = wrap('CanghaiDock.openLaunchpad', function () {
      try { if (typeof window.__lpSetOpen === 'function') window.__lpSetOpen(true); } catch (e) {}
    });
    Dock.getDockAutoHide = wrap('CanghaiDock.getDockAutoHide', function () { return FAKE.getDockAutoHide(); });
    Dock.setDockAutoHide = wrap('CanghaiDock.setDockAutoHide', function (on) { FAKE.setDockAutoHide(on); });
    Dock.checkUpdate = wrap('CanghaiDock.checkUpdate', function () { return J(FAKE.checkUpdate()); });
    Dock.openUpdateUrl = wrap('CanghaiDock.openUpdateUrl', function () {});
    Dock.reportJsError = wrap('CanghaiDock.reportJsError', function (source, message) {
      log('reportJsError', (source || '') + ': ' + (message || ''));
    });
    window.CanghaiDock = Dock;
    /* Dock 页个别路径也读 window.Canghai，给同一份只读桥，避免 typeof 报错。 */
    if (!window.Canghai) {
      window.Canghai = {
        getState: Dock.getState,
        getAboutInfo: Dock.getAboutInfo,
        getLanguage: Dock.getLanguage,
        getSystemInfoJson: Dock.getSystemInfoJson,
        checkUpdate: Dock.checkUpdate,
        openUpdateUrl: Dock.openUpdateUrl,
        reportJsError: Dock.reportJsError,
        toast: wrap('Canghai.toast', function (msg) { FAKE.lastToast = String(msg || ''); })
      };
    }
    return;
  }
})();
