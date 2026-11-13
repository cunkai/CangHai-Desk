/* ==========================================================================
 * 沧海桌面 · 性能调度器 (governor.js)
 *
 * 目的：软件（iframe）再重，桌面也要保持流畅。不改你现有的窗口逻辑，只做四件事：
 *   1. 帧率阀门：接管每个软件里的 requestAnimationFrame，按状态限帧
 *        前台(最上层)窗口 60 · 其他可见窗口 10 · 最小化 / 被最大化窗口盖住 → 暂停
 *   2. 渲染降负：限制 devicePixelRatio（默认 1.5）、WebGL 默认关抗锯齿
 *        （three.js / 游戏在手机上帧率爆炸的头号原因：DPR=3 + MSAA）
 *   3. 自动降级：桌面自己的帧耗时变差时，前台软件按 60→30→20→15 逐档降，恢复后再升
 *   4. 桌面减负：前台有 3D 软件时关掉顶栏等毛玻璃(backdrop-filter)和壁纸动画
 *
 * 测试 / 对比：
 *   地址后加 ?gov=0 关闭调度器（对照组）；?perf=1 打开帧率浮层，浮层里会多一行 GOV 状态。
 *   控制台：CHGov.status()  CHGov.set({aa:true, dpr:2, bg:15})  CHGov.enable(false)
 * ========================================================================== */

/* 注入到软件 iframe 里的阀门。必须自包含：会被 toString() 直接塞进 srcdoc。 */
function chGateInstall(win, init){
  try{
    if(win.__chGate) return win.__chGate;
    init = init || {};
    var G = win.__chGate = { fps: 60, paused: false, dpr: init.dpr || 1.5, noAA: init.noAA !== false, gl: false };
    var doc = win.document;

    /* ---- 1. requestAnimationFrame 阀门：每一帧只判定一次，同帧的多个回调同进退 ---- */
    var raf = win.requestAnimationFrame.bind(win);
    var caf = win.cancelAnimationFrame.bind(win);
    var map = {}, n = 0, gT = -1, gOk = true, lastRun = 0;
    function gate(t){
      if(t !== gT){
        gT = t;
        if(G.paused) gOk = false;
        else if(G.fps >= 59) gOk = true;
        else { gOk = (t - lastRun) >= (1000 / G.fps - 2); if(gOk) lastRun = t; }
      }
      return gOk;
    }
    win.requestAnimationFrame = function(cb){
      var id = ++n;
      function step(t){
        if(!(id in map)) return;
        if(gate(t)){ delete map[id]; cb(t); }
        else map[id] = raf(step);
      }
      map[id] = raf(step);
      return id;
    };
    win.cancelAnimationFrame = function(id){
      if(id in map){ caf(map[id]); delete map[id]; }
    };

    /* ---- 2. devicePixelRatio 上限：three.js 的 setPixelRatio(devicePixelRatio) 自动吃到 ---- */
    try{
      var d = Object.getOwnPropertyDescriptor(win, 'devicePixelRatio');
      var real0 = win.devicePixelRatio || 1;
      var readReal = function(){
        try{ if(d && d.get) return d.get.call(win) || real0; if(d && 'value' in d) return d.value || real0; }catch(e){}
        return real0;
      };
      Object.defineProperty(win, 'devicePixelRatio', {
        configurable: true,
        get: function(){ var r = readReal(); return G.dpr > 0 ? Math.min(r, G.dpr) : r; }
      });
    }catch(e){}

    /* ---- 2b. 空闲态：非前台窗口把 CSS 动画 / 过渡冻住。
       box-shadow、backdrop-filter 这类属性是逐帧重绘的，后台窗口照样在烧 GPU。 ---- */
    try{
      G.idle = false;
      var gateCss = doc.createElement('style');
      gateCss.textContent =
        'html.__chGateIdle *,html.__chGateIdle *::before,html.__chGateIdle *::after' +
        '{animation-play-state:paused !important;transition:none !important}';
      (doc.head || doc.documentElement).appendChild(gateCss);
      G.setIdle = function(v){
        v = !!v;
        if(G.idle === v) return;
        G.idle = v;
        try{ doc.documentElement.classList.toggle('__chGateIdle', v); }catch(e){}
      };
    }catch(e){}

    /* ---- 3. WebGL：默认关抗锯齿，并记下「这是个 3D 软件」 ---- */
    try{
      var proto = win.HTMLCanvasElement && win.HTMLCanvasElement.prototype;
      var gc = proto.getContext;
      proto.getContext = function(type){
        if(type === 'webgl' || type === 'webgl2' || type === 'experimental-webgl'){
          G.gl = true;
          if(G.noAA){
            var a = Array.prototype.slice.call(arguments);
            a[1] = Object.assign({}, a[1] || {}, { antialias: false });
            return gc.apply(this, a);
          }
        }
        return gc.apply(this, arguments);
      };
    }catch(e){}

    /* ---- 4. 暂停时让软件自己也知道：document.hidden = true + visibilitychange ---- */
    try{
      Object.defineProperty(doc, 'hidden', { configurable: true, get: function(){ return G.paused; } });
      Object.defineProperty(doc, 'visibilityState', { configurable: true, get: function(){ return G.paused ? 'hidden' : 'visible'; } });
    }catch(e){}
    G.setPaused = function(p){
      p = !!p;
      if(G.paused === p) return;
      G.paused = p;
      try{ doc.dispatchEvent(new win.Event('visibilitychange')); }catch(e){}
    };
    return G;
  }catch(e){ return null; }
}

(function (w) {
  'use strict';

  var LEVELS = [60, 30, 20, 15];            // 自动降级的帧率档（都是 60Hz 的整除，间隔均匀）
  var KEY = 'chGovCfg';
  var cfg = { on: true, aa: false, dpr: 1.5, bg: 10 };
  try{ var saved = JSON.parse(localStorage.getItem(KEY) || 'null'); if(saved) for(var k in saved) if(k in cfg) cfg[k] = saved[k]; }catch(e){}
  try{ if(/[?&#]gov=0(?:&|$)/.test((location.search || '') + (location.hash || ''))) cfg.on = false; }catch(e){}

  var level = 0;            // 当前自动降级档位
  var frames = new Map();   // iframe -> {poll}
  var queued = false, busy = false;
  var winLayer = null;
  var lastState = {};       // 给 HUD 用

  /* ---------- 给 core.js 的 srcdoc 注入用：返回一段 <script> ---------- */
  w.chGateSrc = function(){
    if(!cfg.on) return '';
    return '<script>(' + chGateInstall.toString() + ')(window,' +
      JSON.stringify({ noAA: !cfg.aa, dpr: cfg.dpr }) + ');<\/script>';
  };

  /* ---------- 桌面减负 CSS ---------- */
  var st = document.createElement('style');
  st.textContent =
    'html.chBusy #menubar,html.chBusy .pop,html.chBusy .ctxMenu,html.chBusy .ctxSub,html.chBusy #toast,' +
    'html.chBusy .ch-toast,html.chBusy .msg-center-panel,html.chBusy #widgetPanel,html.chBusy #panelMask{' +
    'backdrop-filter:none !important;-webkit-backdrop-filter:none !important}' +
    'html.chBusy #menubar{background:rgba(3,16,30,.94) !important}' +
    'html.chBusy .wp-waves,html.chBusy .wp-shimmer,html.chBusy .wp-stars,html.chBusy #lockScreen .lkStars{animation:none !important}';
  document.head.appendChild(st);

  /* ---------- 帧上的阀门 ---------- */
  function installTo(fr){
    try{
      var cw = fr.contentWindow;
      if(!cw) return null;
      if(cw.__chGate) return cw.__chGate;
      return chGateInstall(cw, { noAA: !cfg.aa, dpr: cfg.dpr });
    }catch(e){ return null; }          // 跨域 iframe 碰不到，放过
  }
  function track(fr){
    if(frames.has(fr)) return;
    var rec = { poll: null };
    frames.set(fr, rec);
    fr.addEventListener('load', function(){ installTo(fr); queueSync(); });
    /* url 加载的软件拿不到「脚本之前」的时机，4ms 轮询尽早装；srcdoc 软件已由 core.js 提前注入 */
    var n = 0;
    rec.poll = setInterval(function(){
      n++;
      var g = installTo(fr), done = false;
      try{
        var cw = fr.contentWindow;
        done = g && cw.location.href !== 'about:blank' && cw.document.readyState === 'complete';
      }catch(e){ done = true; }
      if(done || n > 1500){ clearInterval(rec.poll); rec.poll = null; queueSync(); }
    }, 4);
  }
  function scanFrames(root){
    if(!root || !root.querySelectorAll) return;
    var list = root.matches && root.matches('iframe.appFrame') ? [root] : root.querySelectorAll('iframe.appFrame');
    for(var i = 0; i < list.length; i++) track(list[i]);
  }

  /* ---------- 核心：按窗口状态给每个软件定策略 ---------- */
  function queueSync(){
    if(queued) return;
    queued = true;
    requestAnimationFrame(sync);
  }
  function sync(){
    queued = false;
    if(!cfg.on || !winLayer) return;
    var kids = winLayer.children, top = null, topZ = -1, i;
    for(i = 0; i < kids.length; i++){
      var c = kids[i];
      if(!c.classList || !c.classList.contains('win')) continue;
      if(c._min || c.style.display === 'none') continue;
      var z = parseInt(c.style.zIndex, 10) || 0;
      if(z >= topZ){ top = c; topZ = z; }
    }
    var occluded = !!(top && top.classList.contains('max'));          // 最大化窗口盖住了其他窗口
    var dragging = document.body.classList.contains('win-dragging') || document.body.classList.contains('item-dragging');
    var fgGL = false, info = {};

    var wins = (typeof openWins !== 'undefined') ? openWins : {};
    Object.keys(wins).forEach(function(key){
      var win = wins[key];
      var fr = win && win.querySelector && win.querySelector('iframe.appFrame');
      if(!fr) return;
      track(fr);
      var G = installTo(fr);
      if(!G) return;
      var visible = !win._min && win.style.display !== 'none' && win.isConnected;
      var role = !visible ? 'hidden' : (win === top ? 'fg' : (occluded ? 'occluded' : 'bg'));
      var app = null; try{ app = appById(key); }catch(e){}
      var ov = (app && app.perf && typeof app.perf === 'object') ? app.perf : {};   // 软件可在清单里自带 perf:{fg,bg,dpr}

      var fps, paused = false;
      if(role === 'hidden' || role === 'occluded'){ paused = true; fps = 1; }
      else if(role === 'fg'){
        fps = Math.min(ov.fg || 60, LEVELS[level]);
        if(dragging) fps = Math.min(fps, 20);            // 拖窗口时前台软件让路
      }else{
        fps = ov.bg || (G.gl ? cfg.bg : Math.max(cfg.bg, 15));
      }
      G.fps = fps;
      G.dpr = ov.dpr || (level >= 2 ? 1 : cfg.dpr);
      G.noAA = !cfg.aa;
      G.setPaused(paused);
      if(G.setIdle) G.setIdle(role !== 'fg');   /* 后台 / 被遮挡的窗口把 CSS 动画冻掉 */
      if(role === 'fg' && G.gl) fgGL = true;
      info[key] = role + ' ' + (paused ? 'paused' : fps + 'fps') + (G.gl ? ' gl' : '');
    });
    lastState = info;

    document.documentElement.classList.toggle('chBusy', fgGL);
    if(fgGL !== busy){ busy = fgGL; busy ? startLoop() : stopLoop(); }
  }

  /* ---------- 自动降级：桌面自己的帧耗时变差 → 前台软件降一档；稳定后再升 ---------- */
  var ring = new Float32Array(120), rc = 0, rh = 0, lastT = 0, loopOn = false, evalTimer = null;
  var badSecs = 0, goodSecs = 0;
  function loop(t){
    if(!loopOn) return;
    if(lastT){ ring[rh] = t - lastT; rh = (rh + 1) % 120; if(rc < 120) rc++; }
    lastT = t;
    requestAnimationFrame(loop);
  }
  function startLoop(){
    if(loopOn) return;
    loopOn = true; lastT = 0; rc = 0; badSecs = goodSecs = 0;
    requestAnimationFrame(loop);
    evalTimer = setInterval(evaluate, 1000);
  }
  function stopLoop(){
    loopOn = false;
    if(evalTimer){ clearInterval(evalTimer); evalTimer = null; }
    if(level !== 0){ level = 0; queueSync(); }
  }
  function evaluate(){
    if(document.hidden || rc < 40) return;
    var arr = Array.prototype.slice.call(ring, 0, rc).sort(function(a, b){ return a - b; });
    /* 用「最快那批帧」当屏幕刷新基线（60Hz≈16.7ms，120Hz≈8.3ms），阈值按基线缩放。
       原来死写 25ms / p10×1.5，在 120Hz 屏上正常抖动就会越过阈值，一路误降档。 */
    var base = arr[Math.max(0, Math.floor(arr.length * 0.1))];
    var p90 = arr[Math.min(arr.length - 1, Math.floor(arr.length * 0.9))];
    var thr = Math.max(20, base * 2.5);          // 允许丢一帧
    var okThr = Math.max(16, base * 1.3);
    if(p90 > thr){
      badSecs++; goodSecs = 0;
      if(badSecs >= 2 && level < LEVELS.length - 1){ level++; badSecs = 0; rc = 0; queueSync(); }
    }else if(p90 < okThr){
      goodSecs++; badSecs = 0;
      if(goodSecs >= 6 && level > 0){ level--; goodSecs = 0; rc = 0; queueSync(); }
    }else{ badSecs = 0; }
  }

  /* ---------- perf.js 浮层里多一行 GOV ---------- */
  function paintHud(){
    var hud = document.getElementById('chPerfHud');
    if(!hud) return;
    var line = hud.querySelector('.ph-g');
    if(!line){
      line = document.createElement('div');
      line.className = 'ph-g';
      line.style.cssText = 'margin-top:3px;font-size:10px;color:#9fe3ff;word-break:break-all';
      hud.appendChild(line);
    }
    var keys = Object.keys(lastState);
    line.textContent = !cfg.on ? 'GOV 关闭' :
      'GOV 档' + level + '(' + LEVELS[level] + ') dpr≤' + cfg.dpr + (cfg.aa ? ' AA' : ' noAA') +
      (keys.length ? ' · ' + keys.map(function(k){ return k + ':' + lastState[k]; }).join(' | ') : ' · 无软件');
  }

  /* ---------- 启动 ---------- */
  function init(){
    winLayer = document.getElementById('winLayer');
    if(!winLayer){ setTimeout(init, 200); return; }
    scanFrames(winLayer);
    new MutationObserver(function(recs){
      var need = false;
      for(var i = 0; i < recs.length; i++){
        var r = recs[i];
        if(r.type === 'childList'){
          for(var j = 0; j < r.addedNodes.length; j++) if(r.addedNodes[j].nodeType === 1) scanFrames(r.addedNodes[j]);
          need = true;
        }else if(r.target.classList && r.target.classList.contains('win')){ need = true; }
      }
      if(need) queueSync();
    }).observe(winLayer, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style'] });
    new MutationObserver(queueSync).observe(document.body, { attributes: true, attributeFilter: ['class'] });
    setInterval(function(){ queueSync(); paintHud(); }, 1000);   // 兜底：_min 之类不触发 DOM 变化的状态
    queueSync();
  }

  w.CHGov = {
    get cfg(){ return Object.assign({}, cfg); },
    status: function(){ return { on: cfg.on, level: level, fpsCap: LEVELS[level], cfg: Object.assign({}, cfg), apps: lastState }; },
    set: function(o){
      for(var k in o) if(k in cfg) cfg[k] = o[k];
      try{ localStorage.setItem(KEY, JSON.stringify(cfg)); }catch(e){}
      queueSync();
    },
    enable: function(b){
      cfg.on = !!b;
      try{ localStorage.setItem(KEY, JSON.stringify(cfg)); }catch(e){}
      if(!b){   // 关闭时把已装的阀门放开
        frames.forEach(function(_, fr){ try{ var G = fr.contentWindow.__chGate; if(G){ G.fps = 60; G.dpr = 0; G.setPaused(false); } }catch(e){} });
        document.documentElement.classList.remove('chBusy');
        stopLoop(); busy = false;
      }else queueSync();
    }
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})(window);
