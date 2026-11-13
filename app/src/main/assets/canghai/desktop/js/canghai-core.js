/* ==========================================================================
 * 沧海桌面 · CanghaiCore 桥接模块 (canghai-core.js)
 *
 * 封装安卓层注入的原生桥 window.CanghaiCore.invoke(namespace, method,
 * payloadJson)，返回统一格式 {ok:true, data} / {ok:false, error}。
 * 桌面自己调、也替应用 iframe 转调（应用一律不直接碰安卓桥，见 core.js
 * 里 postMessage {canghai:'fs', ...} 的转调）。
 *
 * 命名空间：fs（文件）/ device（设备信息）/ screen / peripheral。
 * 本模块只包 fs 与 device；screen / peripheral 由各自的调用方直接走
 * CHCore.invoke，不在这里另包一层。
 *
 * ---- 已按 CoreFs.java / CanghaiCore.java 核对（2026-11-11）----
 * fs 方法名（invoke('fs', method, payload)，方法名就是下面这些）：
 *   root / setRootMode / pickRoot / list / listApps / stat / mkdir /
 *   delete / rename / copy / move / readText / writeText / readBase64 /
 *   writeBase64 / exists / appDir / storage
 *   （pickRoot 是 CanghaiCore.invoke 里单独分支的方法，不是 CoreFs.handle
 *   里的；调它拉起系统目录选择器，立即返回 {picking:true}。）
 * payload：一律是一个 JSON 对象，里面有 appId + path（相对路径），再加
 *   方法自己的字段（text/base64/to/append/recursive/overwrite/max/mode/uri）。
 *   没有 uuid 字段——文件方法不要 uuid（CoreFs 里没有加密存放这一项）；
 *   没有 encoding 字段——读文本调 readText、读二进制调 readBase64，
 *   方法本身就定了编码，不用字段区分；调用方直接选方法，不做
 *   「按扩展名猜文本还是 base64」的启发式。
 * list 条目：{name, dir, size, mtime}——dir 是布尔值（不是 type 字段），
 *   文件夹排前面、再按名字不区分大小写排序；mtime 是毫秒时间戳。
 *   本模块归一化成 {name, type:'folder'|'file', size, date, mtime, path}，
 *   date 是 mtime 换成的 'YYYY-MM-DD HH:mm'（本地时区）。
 * listApps：列 Data/ 下所有应用文件夹，返回 [{appId, size, mtime}]。
 * readText / readBase64：返回裸字符串（UTF-8 文本 / base64），data 就是
 *   那个字符串；writeText / writeBase64：返回 {size}。
 *   readText 默认最大 8MB、readBase64 默认最大 24MB（超了桥会报错）。
 * rename / copy / move 的 to：同一 appId 沙盒内的相对路径，跨应用拷不出去。
 * root：返回 {mode, path, uri, internalPath, dataPath, moving}。
 * setRootMode：异步的——立即返回 {started, from, to}，搬完发 fs:root
 *   事件，事件里有 status done/failed（另见 pickRoot，选完同样走
 *   fs:root 事件；前端调完要等事件才知道搬完没）。
 * storage：返回 {path, free, total, dataSize}，是真实容量数字（字节）。
 * 根目录结构：只有 <root>/Data/<appId>/，一个应用一个文件夹；没有
 *   system/、gallery/、music/、videos/、documents/、apps/ 这些共享
 *   文件夹。内置根是应用自己的外部私有目录（getExternalFilesDir 下的
 *   Canghai），外置是用户 SAF 选的文件夹。
 * device：invoke('device', 任意方法名, ...) 都返回 deviceInfo()（方法名
 *   随便写都行，代码里忽略它）；前端只取 uuid、model、brand 三样。
 * 安全：CoreFs 的 safeAppId 只允许 [a-zA-Z0-9._-]，resolve() 取 canonical
 *   path 再检查前缀，越界抛错；Android 侧只认桌面传过来的 appId。
 *
 * 无桥降级：没有 window.CanghaiCore.invoke 时，所有调用静默返回
 *   {ok:false, error:'NO_BRIDGE'}，不抛错、不弹 toast、不写 console.error。
 *   在外面浏览器里跑桌面时，文件管理器自动退回内存示例数据。
 * ========================================================================== */
(function (w) {
  'use strict';

  /* ---- 工具 ---- */
  function isFn(f) { return typeof f === 'function'; }
  function tryParse(s) {
    if (s == null) return null;
    if (typeof s === 'object') return s;
    if (typeof s !== 'string') return null;
    try { return JSON.parse(s); } catch (e) { return null; }
  }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  /* mtime（毫秒时间戳）→ 'YYYY-MM-DD HH:mm'（本地时区）。 */
  function fmtMtime(ms) {
    var t = Number(ms);
    if (!t) return '';
    var d = new Date(t);
    if (isNaN(d.getTime())) return '';
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
      ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }
  /* 路径 confinement 的桌面侧预检：含 .. 想逃出根目录的直接拒绝，不发给安卓。
     安卓侧仍以 canonical path 再校验一次为准，这里只是早拦。 */
  function pathEscapes(p) {
    var parts = String(p || '').split('/');
    var depth = 0;
    for (var i = 0; i < parts.length; i++) {
      if (!parts[i] || parts[i] === '.') continue;
      if (parts[i] === '..') { if (--depth < 0) return true; }
      else depth++;
    }
    return false;
  }

  /* ---- 桥是否存在 ---- */
  function bridge() {
    try {
      var c = w.CanghaiCore;
      if (c && isFn(c.invoke)) return c;
    } catch (e) {}
    return null;
  }
  function available() { return !!bridge(); }

  /* ---- 原始调用 ----
     payload 一律是对象，本函数负责 JSON.stringify；返回统一解析成对象。
     安卓侧 @JavascriptInterface 方法是同步返回 JSON 字符串；若那边改成
     异步 Promise，这里同样接住。 */
  function invoke(namespace, method, payload) {
    var c = bridge();
    if (!c) return Promise.resolve({ ok: false, error: 'NO_BRIDGE' });
    var payloadJson;
    try {
      payloadJson = JSON.stringify(payload || {});
    } catch (e) {
      return Promise.resolve({ ok: false, error: 'INVALID_ARGS' });
    }
    var raw;
    try {
      raw = c.invoke(String(namespace), String(method), payloadJson);
    } catch (e) {
      return Promise.resolve({ ok: false, error: 'INTERNAL_ERROR' });
    }
    return Promise.resolve(raw).then(function (v) {
      var r = tryParse(v);
      if (r && typeof r === 'object' && ('ok' in r)) return r;
      if (typeof v === 'string' && v !== '') return { ok: true, data: v };
      if (v === true) return { ok: true, data: true };
      if (v === false) return { ok: false, error: 'INTERNAL_ERROR' };
      return { ok: false, error: 'INTERNAL_ERROR' };
    }, function () {
      return { ok: false, error: 'INTERNAL_ERROR' };
    });
  }

  /* ---- fs：按 CoreFs.java ----
     payload 统一带 appId（桌面按发送方 e.source 认，见 core.js；
     桌面自己调时 appId 传 'desktop'）与 path（相对路径）。
     没有 uuid，没有 encoding。 */
  function fsPayload(appId, path, extra) {
    var p = { appId: String(appId || 'desktop'), path: String(path == null ? '' : path) };
    if (extra) for (var k in extra) p[k] = extra[k];
    return p;
  }
  function fsGuard(path, to) {
    if (pathEscapes(path) || (to != null && pathEscapes(to)))
      return Promise.resolve({ ok: false, error: 'PERMISSION_DENIED' });
    return null;
  }
  /* list 条目归一化：CoreFs 给 {name, dir, size, mtime}，归一化成
     {name, type:'folder'|'file', size, date, mtime, path}。 */
  function normEntry(e, basePath) {
    if (!e || typeof e !== 'object') return null;
    var name = String(e.name || '').split('/').filter(Boolean).pop() || '';
    if (!name) return null;
    var isDir = e.dir === true;
    var p = (basePath ? basePath + '/' : '') + name;
    return {
      name: name,
      type: isDir ? 'folder' : 'file',
      size: typeof e.size === 'number' ? e.size : 0,
      date: fmtMtime(e.mtime),
      mtime: typeof e.mtime === 'number' ? e.mtime : 0,
      path: p
    };
  }
  function normList(data, basePath) {
    if (!Array.isArray(data)) return { ok: false, error: 'INVALID_ARGS' };
    var out = [];
    data.forEach(function (e) { var n = normEntry(e, basePath); if (n) out.push(n); });
    return { ok: true, data: out };
  }
  function cleanPath(p) { return String(p || '').replace(/^\/+|\/+$/g, ''); }

  var fs = {
    /* 根目录信息：{mode, path, uri, internalPath, dataPath, moving} */
    root: function (appId) {
      return invoke('fs', 'root', { appId: String(appId || 'desktop') });
    },
    /* 切内置/外置：{mode, uri}。异步：立即返回 {started, from, to}，
       搬完发 fs:root 事件（status done/failed），调完要等事件。 */
    setRootMode: function (appId, mode, uri) {
      return invoke('fs', 'setRootMode', fsPayload(appId, '', { mode: mode, uri: uri || '' }));
    },
    /* 拉起系统目录选择器：立即返回 {picking:true}，选完走 fs:root 事件。 */
    pickRoot: function () {
      return invoke('fs', 'pickRoot', {});
    },
    /* 列目录：data 归一化成 [{name, type, size, date, mtime, path}] */
    list: function (appId, path) {
      var g = fsGuard(path); if (g) return g;
      return invoke('fs', 'list', fsPayload(appId, path)).then(function (r) {
        if (!r.ok) return r;
        return normList(r.data, cleanPath(path));
      });
    },
    /* 列 Data/ 下所有应用文件夹：[{appId, size, mtime}]（原样返回，只补 date） */
    listApps: function (appId) {
      return invoke('fs', 'listApps', { appId: String(appId || 'desktop') }).then(function (r) {
        if (!r.ok || !Array.isArray(r.data)) return r;
        return {
          ok: true,
          data: r.data.map(function (a) {
            return {
              appId: String((a && a.appId) || ''),
              size: typeof a.size === 'number' ? a.size : 0,
              mtime: typeof a.mtime === 'number' ? a.mtime : 0,
              date: fmtMtime(a && a.mtime)
            };
          }).filter(function (a) { return !!a.appId; })
        };
      });
    },
    stat: function (appId, path) {
      var g = fsGuard(path); if (g) return g;
      return invoke('fs', 'stat', fsPayload(appId, path));
    },
    mkdir: function (appId, path) {
      var g = fsGuard(path); if (g) return g;
      return invoke('fs', 'mkdir', fsPayload(appId, path));
    },
    /* 删：{recursive}，文件夹非空时 recursive 必须为 true */
    delete: function (appId, path, opts) {
      var g = fsGuard(path); if (g) return g;
      return invoke('fs', 'delete', fsPayload(appId, path, {
        recursive: !!(opts && opts.recursive)
      }));
    },
    /* 改名：to 是同一沙盒内的相对路径 */
    rename: function (appId, path, to) {
      var g = fsGuard(path, to); if (g) return g;
      return invoke('fs', 'rename', fsPayload(appId, path, { to: String(to || '') }));
    },
    /* 拷 / 移：to 是同一沙盒内的相对路径；{overwrite} */
    copy: function (appId, path, to, opts) {
      var g = fsGuard(path, to); if (g) return g;
      return invoke('fs', 'copy', fsPayload(appId, path, {
        to: String(to || ''), overwrite: !!(opts && opts.overwrite)
      }));
    },
    move: function (appId, path, to, opts) {
      var g = fsGuard(path, to); if (g) return g;
      return invoke('fs', 'move', fsPayload(appId, path, {
        to: String(to || ''), overwrite: !!(opts && opts.overwrite)
      }));
    },
    /* 读文本：data 是裸 UTF-8 字符串；{max} 默认 8MB */
    readText: function (appId, path, opts) {
      var g = fsGuard(path); if (g) return g;
      var extra = {};
      if (opts && typeof opts.max === 'number') extra.max = opts.max;
      return invoke('fs', 'readText', fsPayload(appId, path, extra));
    },
    /* 写文本：{text, append}，返回 {size} */
    writeText: function (appId, path, text, opts) {
      var g = fsGuard(path); if (g) return g;
      return invoke('fs', 'writeText', fsPayload(appId, path, {
        text: (text == null ? '' : String(text)),
        append: !!(opts && opts.append)
      }));
    },
    /* 读二进制：data 是裸 base64 字符串；{max} 默认 24MB */
    readBase64: function (appId, path, opts) {
      var g = fsGuard(path); if (g) return g;
      var extra = {};
      if (opts && typeof opts.max === 'number') extra.max = opts.max;
      return invoke('fs', 'readBase64', fsPayload(appId, path, extra));
    },
    /* 写二进制：{base64}，返回 {size} */
    writeBase64: function (appId, path, base64) {
      var g = fsGuard(path); if (g) return g;
      return invoke('fs', 'writeBase64', fsPayload(appId, path, {
        base64: (base64 == null ? '' : String(base64))
      }));
    },
    exists: function (appId, path) {
      var g = fsGuard(path); if (g) return g;
      return invoke('fs', 'exists', fsPayload(appId, path));
    },
    /* 该应用沙盒的绝对路径：{path} */
    appDir: function (appId) {
      return invoke('fs', 'appDir', { appId: String(appId || 'desktop') });
    },
    /* 真实容量：{path, free, total, dataSize}（字节） */
    storage: function (appId) {
      return invoke('fs', 'storage', { appId: String(appId || 'desktop') });
    },
    _normEntry: normEntry,
    _normList: normList,
    _fmtMtime: fmtMtime
  };

  /* ---- device：只取 uuid / model / brand 三样 ----
     CanghaiCore.invoke('device', 任意方法名, ...) 都返回 deviceInfo()，
     方法名随便写都行；这里仍调 'getInfo'。万一桥多给了别的字段
     （版本号之类），这里直接丢掉，不存、不往外传。 */
  var device = {
    info: null, /* {uuid, model, brand} | null */
    getInfo: function () {
      return invoke('device', 'getInfo', {}).then(function (r) {
        if (!r.ok) return r;
        var d = r.data || {};
        var info = {
          uuid: String(d.uuid || ''),
          model: String(d.model || ''),
          brand: String(d.brand || '')
        };
        device.info = info;
        try { w.__chDeviceInfo = info; } catch (e) {}
        return { ok: true, data: info };
      });
    },
    getUuid: function () {
      if (device.info && device.info.uuid) return Promise.resolve(device.info.uuid);
      return device.getInfo().then(function (r) { return r.ok ? r.data.uuid : ''; });
    }
  };

  /* ---- 安卓 → 前端事件：window.__canghaiCoreEmit(type, payloadJson) ----
     CanghaiCore.emit(type, payload) 发来的 payloadJson 是 {type, data, ts}
     的信封，这里拆开 data 再分发（直接调 __canghaiCoreEmit 传裸对象时
     没有信封，同样接住）。deviceInfo / systemInfo 两种 type 到货时，
     顺手把 uuid/model/brand 三样缓存下来，别的字段忽略；fs:root 事件
     （setRootMode / pickRoot 搬完）原样分发给监听器。任何一步出错都
     静默吞掉，不抛到页面上。 */
  var listeners = {};
  function on(type, fn) {
    if (!listeners[type]) listeners[type] = [];
    listeners[type].push(fn);
  }
  function emit(type, payload) {
    var list = listeners[type] || [];
    list.forEach(function (fn) { try { fn(payload); } catch (e) {} });
  }
  function coreEmit(type, payloadJson) {
    try {
      var payload = tryParse(payloadJson);
      if (payload == null && typeof payloadJson === 'string' && payloadJson !== '') payload = payloadJson;
      /* 拆 CanghaiCore.emit 的信封 {type, data, ts} */
      if (payload && typeof payload === 'object' && 'data' in payload &&
          (payload.type === type || payload.type == null)) payload = payload.data;
      if ((type === 'deviceInfo' || type === 'systemInfo') && payload && typeof payload === 'object') {
        device.info = {
          uuid: String(payload.uuid || ''),
          model: String(payload.model || ''),
          brand: String(payload.brand || '')
        };
        try { w.__chDeviceInfo = device.info; } catch (e) {}
      }
      emit(String(type || ''), payload);
    } catch (e) {}
  }
  /* 安卓侧只认这个全局函数名；若页面/别的脚本已定义了一个，先留着它，
     再把我们的分发挂上去（两个都叫，不互相顶掉）。 */
  var prevEmit = w.__canghaiCoreEmit;
  w.__canghaiCoreEmit = function (type, payloadJson) {
    if (isFn(prevEmit)) { try { prevEmit(type, payloadJson); } catch (e) {} }
    coreEmit(type, payloadJson);
  };

  /* ---- 对外 ---- */
  var CHCore = {
    available: available,
    invoke: invoke,
    fs: fs,
    device: device,
    on: on,
    emit: emit,
    /* 事件名常量（供调用方拼 postMessage / 监听时复用，别手写字符串） */
    EVENTS: { DEVICE_INFO: 'deviceInfo', SYSTEM_INFO: 'systemInfo', FS_ROOT: 'fs:root' }
  };
  w.CHCore = CHCore;

  /* 有桥时启动即取一次设备信息（只取三样），失败静默——桌面不依赖它启动。 */
  try {
    if (available()) device.getInfo().then(function () {}, function () {});
  } catch (e) {}
})(window);
