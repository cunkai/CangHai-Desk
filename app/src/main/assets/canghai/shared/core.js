/**
 * 沧海管理者 · 前端 SDK
 *
 * 前端跟安卓说话只有这一条路：
 *   CanghaiCore.fs.list({appId:'myapp', path:'/'})
 *   CanghaiCore.device()            设备身份
 *   CanghaiCore.screens()           屏幕快照
 *   CanghaiCore.peripherals()       外设快照
 *   CanghaiCore.on('screens', fn)   订阅变化
 *
 * 原生那一侧统一返回 {ok:true,data} / {ok:false,error}，这里把错误变成异常，
 * 调用方写 try/catch 就行，不用每次判断 ok。
 *
 * 注意：window.__CanghaiNative 是原生注入对象，业务代码不要直接用。
 */
(function () {
  if (window.CanghaiCore && window.CanghaiCore.version) return;   // 重复注入直接跳过

  var N = window.__CanghaiNative || null;
  var listeners = Object.create(null);

  function raw(ns, method, payload) {
    if (!N || typeof N.invoke !== 'function') {
      return { ok: false, error: '管理者未就绪（原生通道没挂上）' };
    }
    try {
      return JSON.parse(N.invoke(ns, method, JSON.stringify(payload || {})));
    } catch (e) {
      return { ok: false, error: '调用失败: ' + (e && e.message ? e.message : e) };
    }
  }

  function call(ns, method, payload) {
    var r = raw(ns, method, payload);
    if (!r || r.ok !== true) throw new Error((r && r.error) || '操作失败');
    return r.data;
  }

  /* ---------------- 事件 ---------------- */
  function on(type, fn) {
    if (typeof fn !== 'function') return function () {};
    (listeners[type] || (listeners[type] = [])).push(fn);
    return function () { off(type, fn); };
  }
  function off(type, fn) {
    var arr = listeners[type];
    if (!arr) return;
    var i = arr.indexOf(fn);
    if (i >= 0) arr.splice(i, 1);
  }
  function fire(type, env) {
    var arr = (listeners[type] || []).slice();
    var all = (listeners['*'] || []).slice();
    arr.concat(all).forEach(function (fn) {
      try { fn(env && env.data !== undefined ? env.data : env, env); } catch (e) {}
    });
  }
  /* 原生 → 前端唯一入口（原生 evaluateJavascript 调的就是它） */
  window.__canghaiCoreEmit = function (type, env) {
    try { fire(type, env || {}); } catch (e) {}
  };

  /* ---------------- 文件沙箱 ---------------- */
  var fs = {
    /** 根目录信息：{mode:'internal'|'external', path, dataPath, uri} */
    root: function () { return call('fs', 'root'); },
    /**
     * 切根目录。mode='internal' 用内置；mode='external' 必须带 uri（用户用系统选择器挑的目录）。
     * 数据会被整体搬过去，期间安卓层自己弹进度框。
     */
    setRootMode: function (mode, uri) { return call('fs', 'setRootMode', { mode: mode, uri: uri || '' }); },
    /**
     * 让用户挑一个文件夹当外置根目录（系统目录选择器）。
     * 选完原生会把现有数据整体搬过去，进度由安卓层自己弹窗显示；
     * 搬完订阅 CanghaiCore.on('fs:root', fn) 就能收到新位置。
     */
    pickRoot: function () { return call('fs', 'pickRoot'); },
    /** 列出沙盒里某个目录：返回 [{name,dir,size,mtime}] */
    list: function (appId, path) { return call('fs', 'list', { appId: appId, path: path || '' }); },
    stat: function (appId, path) { return call('fs', 'stat', { appId: appId, path: path || '' }); },
    mkdir: function (appId, path) { return call('fs', 'mkdir', { appId: appId, path: path }); },
    remove: function (appId, path, recursive) {
      return call('fs', 'delete', { appId: appId, path: path, recursive: !!recursive });
    },
    rename: function (appId, from, to) { return call('fs', 'rename', { appId: appId, from: from, to: to }); },
    copy: function (appId, from, to, overwrite) {
      return call('fs', 'copy', { appId: appId, from: from, to: to, overwrite: !!overwrite });
    },
    move: function (appId, from, to, overwrite) {
      return call('fs', 'move', { appId: appId, from: from, to: to, overwrite: !!overwrite });
    },
    readText: function (appId, path) { return call('fs', 'readText', { appId: appId, path: path }); },
    writeText: function (appId, path, text, append) {
      return call('fs', 'writeText', { appId: appId, path: path, text: text, append: !!append });
    },
    readBase64: function (appId, path) { return call('fs', 'readBase64', { appId: appId, path: path }); },
    writeBase64: function (appId, path, base64) {
      return call('fs', 'writeBase64', { appId: appId, path: path, base64: base64 });
    },
    exists: function (appId, path) { return call('fs', 'exists', { appId: appId, path: path || '' }); },
    /** 沙盒的真实绝对路径（给需要原生 File 的地方显示用） */
    appDir: function (appId) { return call('fs', 'appDir', { appId: appId }); },
    /** 所有沙盒列表 [{appId,size,mtime}] */
    listApps: function () { return call('fs', 'listApps'); },
    /** 剩余空间 / 沙盒总占用 */
    storage: function () { return call('fs', 'storage'); }
  };

  window.CanghaiCore = {
    version: 1,
    /** 原始调用：不抛异常，返回 {ok,data|error} */
    invoke: raw,
    /** 会抛异常的调用 */
    call: call,
    fs: fs,
    device: function () { return call('device', 'info'); },
    /** 渲染分辨率百分比：返回 {value}，100 = 按客户端请求的原尺寸，越小越省性能 */
    renderScale: function () { return call('device', 'renderScale'); },
    /** 设成 20~100，等比缩放宽高；下次建屏（进入大屏 / 重连 Moonlight）生效 */
    setRenderScale: function (percent) { return call('device', 'setRenderScale', { value: percent }); },
    /** 当前串流真实数字：编码尺寸 / 目标码率 / 实发码率 / 码率上限。 */
    streamStats: function () { return call('device', 'streamStats'); },
    /**
     * 前端绘制监听（默认关闭，只有手机端打开开关后才有数据）。
     *   perf.setEnabled(true)  打开，已登记的页面会立刻补注入探针
     *   perf.snapshot()        取一份全量：pages[] 每页 fps/卡顿/变更/节点，windows[] 每窗口逐帧耗时
     */
    perf: {
      setEnabled: function (on) { return call('perf', 'enable', { on: !!on }); },
      /** 只读开关状态，不动统计 */
      enabled: function () { return call('perf', 'state').enabled; },
      /** 取一份快照（会清空窗口逐帧累计，所以别频繁调，2 秒一次正好） */
      snapshot: function () { return call('perf', 'snapshot'); }
    },
    screens: function () { return call('screen', 'snapshot'); },
    peripherals: function () { return call('peripheral', 'snapshot'); },
    /**
     * 声音通道。
     *   audio.status()  只读：{permission, projection, capturing, streaming, audible, reason}
     *                   audible=false 就是真的没声音送出，界面上要显示「静音」，别猜。
     *   audio.request() 首次授权：拉起系统录屏弹窗（内录必须独占这颗 token）
     */
    audio: {
      status: function () { return call('audio', 'status'); },
      request: function () { return call('audio', 'request'); }
    },
    on: on,
    off: off
  };

  /* ---------------- rAF 帧率探针 ----------------
   * 只在大屏内的页面（/canghai/...）跑。空的 rAF 循环不会触发重绘，
   * 开销可以忽略；它的作用是让日志能回答「画面为什么只有 30 帧」：
   *   rAF 也 30  → 页面自己画不动（内容/JS 的锅）
   *   rAF 60     → 页面没问题，是采集或编码把帧丢了
   * 2 秒上报一次。
   */
  (function () {
    if (!window.requestAnimationFrame || location.pathname.indexOf('/canghai/') === -1) return;
    var frames = 0, last = performance.now();
    var name = location.pathname.substring(location.pathname.indexOf('/canghai/') + 9);
    function tick(now) {
      frames++;
      if (now - last >= 2000) {
        var fps = Math.round(frames * 10000 / (now - last)) / 10;
        frames = 0; last = now;
        try {
          if (window.__CanghaiNative && window.__CanghaiNative.invoke) {
            window.__CanghaiNative.invoke('device', 'webFps', JSON.stringify({ fps: fps, page: name }));
          }
        } catch (e) {}
      }
      window.requestAnimationFrame(tick);
    }
    window.requestAnimationFrame(tick);
  })();
})();
