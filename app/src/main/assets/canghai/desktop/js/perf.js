/* 沧海桌面 · 帧率诊断浮层（perf HUD）
   用途：拖动便签卡不卡，在手机上直接看数字，不用接电脑。
   开启：桌面地址后面加 ?perf=1（或 #perf=1），刷新即可；默认关闭，不影响正常使用。
   关闭：点浮层右上角 ✕，或去掉参数刷新。
   看法：
     FPS 60 且柱子全绿 = 流畅；黄色柱子（>17ms）多 = 开始掉帧；
     红色柱子（>33.3ms）出现 = 明显卡顿；拖动那一行单独统计拖动时的帧。
   成本：每帧只记一个数字进环形缓冲，界面 250ms 才刷新一次，工具本身不占帧。 */
(function(){
  var on = false;
  try{
    on = /(?:[?&]perf=1(?:&|$))/.test(location.search || '') ||
         /(?:#|&)perf=1(?:&|$)/.test(location.hash || '') ||
         !!window.__canghaiPerf;
  }catch(e){ on = false; }
  if(!on) return;

  var N = 180;                       // 环形缓冲：最近 180 帧
  var buf = new Float32Array(N);
  var head = 0, count = 0;
  var lastT = 0;
  var longTasks = 0, longTaskMax = 0;
  var dragging = false;
  var dragFrames = 0, dragLong = 0, dragMax = 0, dragStart = 0;

  function push(dt){
    buf[head] = dt; head = (head + 1) % N;
    if(count < N) count++;
    if(dragging){
      dragFrames++;
      if(dt > 17) dragLong++;
      if(dt > dragMax) dragMax = dt;
    }
  }
  function tick(t){
    if(lastT) push(t - lastT);
    lastT = t;
    if(hudOn) requestAnimationFrame(tick);
  }

  /* 拖动状态：事件驱动，不每帧查 DOM */
  function isDragTarget(el){
    if(!el || !el.closest) return false;
    return !!el.closest('.stickyNote .snHead');
  }
  document.addEventListener('pointerdown', function(e){
    if(isDragTarget(e.target)){
      dragging = true; dragFrames = 0; dragLong = 0; dragMax = 0; dragStart = performance.now();
    }
  }, true);
  document.addEventListener('pointerup', function(){ dragging = false; }, true);
  document.addEventListener('pointercancel', function(){ dragging = false; }, true);

  try{
    if(window.PerformanceObserver){
      new PerformanceObserver(function(list){
        var es = list.getEntries();
        for(var i = 0; i < es.length; i++){
          longTasks++;
          if(es[i].duration > longTaskMax) longTaskMax = es[i].duration;
        }
      }).observe({entryTypes:['longtask']});
    }
  }catch(e){}

  /* ---------- 浮层 DOM（样式自带，不碰桌面 CSS） ---------- */
  var css = '#chPerfHud{position:fixed;top:46px;right:8px;z-index:99999;width:216px;overflow:hidden;'
    + 'background:rgba(10,18,32,.82);color:#e8f0f8;border:1px solid rgba(255,255,255,.18);'
    + 'border-radius:10px;padding:8px 10px 9px;font:12px/1.5 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;'
    + 'pointer-events:none;backdrop-filter:blur(4px);-webkit-backdrop-filter:blur(4px);}'
    + '#chPerfHud .ph-t{display:flex;justify-content:space-between;align-items:baseline;}'
    + '#chPerfHud .ph-t b{font-size:12px;letter-spacing:1px;}'
    + '#chPerfHud .ph-fps{font-size:22px;font-weight:700;line-height:1;}'
    + '#chPerfHud .ph-fps small{font-size:11px;font-weight:400;opacity:.75;}'
    + '#chPerfHud .ph-x{pointer-events:auto;cursor:pointer;border:none;background:rgba(255,255,255,.14);'
    + 'color:#fff;width:18px;height:18px;border-radius:50%;font-size:11px;line-height:1;padding:0;}'
    + '#chPerfHud .ph-s{margin-top:2px;font-size:11px;opacity:.92;}'
    + '#chPerfHud canvas{display:block;width:176px;height:44px;margin-top:5px;background:rgba(255,255,255,.06);border-radius:4px;}'
    + '#chPerfHud .ph-d{margin-top:4px;font-size:11px;}'
    + '#chPerfHud .ph-d b{color:#ffd76a;}'
    + '#chPerfHud .ph-l{margin-top:3px;font-size:10px;opacity:.6;}';
  var st = document.createElement('style');
  st.textContent = css;
  document.head.appendChild(st);

  var hud = document.createElement('div');
  hud.id = 'chPerfHud';
  hud.innerHTML = '<div class="ph-t"><b>帧率诊断</b><span class="ph-fps">--<small> FPS</small></span>'
    + '<button class="ph-x" type="button" title="关闭">✕</button></div>'
    + '<div class="ph-s">平均 --ms · p95 --ms · 长帧 --/--</div>'
    + '<canvas width="352" height="88"></canvas>'
    + '<div class="ph-d">未拖动</div>'
    + '<div class="ph-l">绿≤17ms 黄>17 红>33.3 · 长任务 0 个</div>';
  document.body.appendChild(hud);
  var fpsEl = hud.querySelector('.ph-fps');
  var statEl = hud.querySelector('.ph-s');
  var dragEl = hud.querySelector('.ph-d');
  var legendEl = hud.querySelector('.ph-l');
  var cv = hud.querySelector('canvas');
  var ctx = cv.getContext('2d');
  hud.querySelector('.ph-x').addEventListener('click', function(){ stop(); });

  var hudOn = true;
  var timer = null;

  function stats(){
    if(!count) return null;
    var arr = [];
    for(var i = 0; i < count; i++) arr.push(buf[(head - count + i + N) % N]);
    var sum = 0, long = 0;
    for(var j = 0; j < arr.length; j++){ sum += arr[j]; if(arr[j] > 17) long++; }
    arr.sort(function(a, b){ return a - b; });
    var mean = sum / arr.length;
    var p95 = arr[Math.min(arr.length - 1, Math.floor(arr.length * 0.95))];
    return { n: arr.length, mean: mean, p95: p95, long: long,
      fps: mean > 0 ? 1000 / mean : 0, frames: arr };
  }
  function draw(frames){
    var W = cv.width, H = cv.height;
    ctx.clearRect(0, 0, W, H);
    var bw = W / N;
    /* 16.7ms 基线 */
    var y167 = H - (16.7 / 50) * H;
    ctx.strokeStyle = 'rgba(255,255,255,.35)';
    ctx.beginPath(); ctx.moveTo(0, y167); ctx.lineTo(W, y167); ctx.stroke();
    for(var i = 0; i < frames.length; i++){
      var dt = frames[i];
      var h = Math.min(50, dt) / 50 * H;
      ctx.fillStyle = dt > 33.3 ? '#ff5f57' : (dt > 17 ? '#febc2e' : '#35d07f');
      ctx.fillRect(i * bw, H - h, Math.max(1, bw - 0.5), h);
    }
  }
  function refresh(){
    if(!hudOn) return;
    var s = stats();
    if(!s) return;
    fpsEl.innerHTML = Math.round(s.fps) + '<small> FPS</small>';
    fpsEl.style.color = s.fps >= 55 ? '#35d07f' : (s.fps >= 30 ? '#febc2e' : '#ff5f57');
    statEl.textContent = '平均 ' + s.mean.toFixed(1) + 'ms · p95 ' + s.p95.toFixed(1) + 'ms · 长帧 ' + s.long + '/' + s.n;
    draw(s.frames);
    if(dragging){
      dragEl.innerHTML = '拖动中 <b>' + dragFrames + ' 帧 · 长帧 ' + dragLong + ' · 最长 ' + dragMax.toFixed(0) + 'ms</b>';
    }else if(dragFrames > 0){
      dragEl.innerHTML = '上次拖动 <b>' + dragFrames + ' 帧 · 长帧 ' + dragLong + ' · 最长 ' + dragMax.toFixed(0) + 'ms</b>';
    }else{
      dragEl.textContent = '未拖动（按住便签顶针拖一下试试）';
    }
    var dpr = window.devicePixelRatio || 1;
    legendEl.textContent = '绿≤17ms 黄>17 红>33.3 · 长任务 ' + longTasks + ' 个'
      + (longTaskMax ? '（最长 ' + longTaskMax.toFixed(0) + 'ms）' : '')
      + ' · ' + window.innerWidth + '×' + window.innerHeight + ' @' + dpr + 'x';
  }
  function stop(){
    hudOn = false;
    if(timer) clearInterval(timer);
    if(hud.parentNode) hud.parentNode.removeChild(hud);
    if(st.parentNode) st.parentNode.removeChild(st);
  }

  window.__chPerf = {
    stop: stop,
    getStats: function(){ var s = stats(); return s ? {fps:+s.fps.toFixed(1), mean:+s.mean.toFixed(2), p95:+s.p95.toFixed(2), long:s.long, n:s.n, longTasks:longTasks, drag:{frames:dragFrames, long:dragLong, max:+dragMax.toFixed(1)}} : null; }
  };

  timer = setInterval(refresh, 250);
  requestAnimationFrame(tick);
  refresh();
})();
