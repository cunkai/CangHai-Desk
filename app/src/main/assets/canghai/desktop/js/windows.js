/* ========================================================
 * 沧海系统桌面 - 多窗口管理系统 (windows.js)
 * 职责：窗口创建、最大化/全屏/最小化、自由拖动、八向尺寸调整、层级聚焦、iframe穿透控制
 * ======================================================== */

/* ================= 窗口系统 ================= */
var winLayer = document.getElementById('winLayer');
var zTop = 310, openWins = {};
function focusWin(win){
  winLayer.querySelectorAll('.win').forEach(function(w){ w.classList.remove('focused'); });
  win.classList.add('focused'); win.style.zIndex = ++zTop;
  var titleEl = win.querySelector('.wintitle') || win.querySelector('.winbar span');
  var title = titleEl ? titleEl.textContent.trim() : (win.getAttribute('data-id') || '应用');
  try {
    if (window.Canghai && Canghai.setActiveWinTitle) Canghai.setActiveWinTitle(title);
  } catch(e) {}
}
function makeDraggable(win, bar, onUp){
  bar.addEventListener('pointerdown', function(e){
    if(e.target.closest('.tl')) return;
    var sx=e.clientX, sy=e.clientY, ol=win.offsetLeft, ot=win.offsetTop, w=win.offsetWidth;
    bar.setPointerCapture && bar.setPointerCapture(e.pointerId);
    var lastL = ol, lastT = ot;
    win.style.willChange = 'transform';
    document.body.classList.add('win-dragging');
    function mv(ev){
      var nl = Math.max(-w+120, Math.min(ol+ev.clientX-sx, window.innerWidth-80));
      var nt = Math.max(0, Math.min(ot+ev.clientY-sy, window.innerHeight-90));
      lastL = nl; lastT = nt;
      win.style.transform = 'translate3d('+(nl-ol)+'px,'+(nt-ot)+'px,0)';
    }
    function up(){
      bar.removeEventListener('pointermove',mv); bar.removeEventListener('pointerup',up); bar.removeEventListener('pointercancel',up);
      win.style.transform = '';
      win.style.willChange = '';
      document.body.classList.remove('win-dragging');
      win.style.left = lastL+'px'; win.style.top = lastT+'px';
      if(onUp) onUp();
    }
    bar.addEventListener('pointermove', mv); bar.addEventListener('pointerup', up); bar.addEventListener('pointercancel', up);
  });
}
function noteRecent(appId){
  if(!appId || appId==='launchpad' || appId==='widgets') return;
  recentApps = [appId].concat(recentApps.filter(function(x){ return x!==appId; })).slice(0,4);
  chPushDockRunState();
}

/* ================= 最大化 / 全屏标题栏 =================
   绿点 = 最大化 / 还原；最大化后标题栏隐藏，鼠标移到窗口层最上方 8px
   唤出标题栏可操作；标题栏空白处双击同样在最大化与窗口之间切换。
   只要有窗口最大化，Dock 无条件自动隐藏（设置里没勾也一样）。 */
function chSyncFullscreenWin(){
  /* 最小化 / 已隐藏的窗口不算「软件全屏」，否则最小化后 Dock 还一直藏着 */
  var maxed = Array.prototype.some.call(winLayer.querySelectorAll('.win.max'), function(w){
    return !w._min && w.style.display !== 'none';
  });
  document.documentElement.classList.toggle('hasMaxWin', maxed);
  chSetWinFullscreen(maxed);
}
function toggleWinMaximize(win){
  if(!win || !win.parentNode) return;
  if(!win.classList.contains('max')){
    win._rx = win.offsetLeft; win._ry = win.offsetTop;
    win._rw = win.offsetWidth; win._rh = win.offsetHeight;
    win.classList.add('max');
  }else{
    win.classList.remove('max');
    win.style.left = win._rx + 'px'; win.style.top = win._ry + 'px';
    win.style.width = win._rw + 'px'; win.style.height = win._rh + 'px';
  }
  win.classList.remove('peek');
  document.documentElement.classList.remove('winPeek');
  focusWin(win);
  chSyncFullscreenWin();
}
/* 事件委托：三种窗口（应用 / 文件夹 / 便签）共用一个绿点与双击逻辑 */
winLayer.addEventListener('click', function(e){
  var g = e.target.closest && e.target.closest('.tl.g');
  if(!g) return;
  var w = g.closest('.win');
  if(w){ e.stopPropagation(); toggleWinMaximize(w); }
});
winLayer.addEventListener('dblclick', function(e){
  if(e.target.closest && e.target.closest('.tl')) return;
  var bar = e.target.closest && e.target.closest('.winbar');
  if(!bar) return;
  var w = bar.closest('.win');
  if(w) toggleWinMaximize(w);
});
var chTopProbe = document.getElementById('winTopProbe');
var chPeekTimer = null;
function chPeekWin(on){
  var w = winLayer.querySelector('.win.max.focused') || winLayer.querySelector('.win.max');
  if(!w) return;
  clearTimeout(chPeekTimer);
  if(on){ w.classList.add('peek'); document.documentElement.classList.add('winPeek'); }
  else{
    chPeekTimer = setTimeout(function(){
      w.classList.remove('peek'); document.documentElement.classList.remove('winPeek');
    }, 300);
  }
}
if(chTopProbe){
  chTopProbe.addEventListener('pointerenter', function(){ chPeekWin(true); });
  chTopProbe.addEventListener('pointerleave', function(){ chPeekWin(false); });
}
winLayer.addEventListener('pointerover', function(e){
  if(e.target.closest && e.target.closest('.win.max .winbar')) chPeekWin(true);
});
winLayer.addEventListener('pointerout', function(e){
  if(e.target.closest && e.target.closest('.win.max .winbar')) chPeekWin(false);
});
/* 窗口被关掉 / 新窗口挂上来时，重新判断还有没有最大化窗口 */
if(window.MutationObserver){
  new MutationObserver(function(){ chSyncFullscreenWin(); })
    .observe(winLayer, { childList:true, subtree:false });
}
/* 最小化动画：窗口整体飞向自己的图标（Dock 图标 / 桌面文件夹图标），边飞边缩边淡，
   落点到了才真正隐藏；没有可飞的目标或系统开了「减少动态」时，退回原来的淡出收起。
   done 在隐藏完成后回调（应用窗口靠它 renderDock，把倒影从宽切成窄） */
/* 最小化动画：窗口整体飞向自己的图标（Dock 图标 / 桌面图标），边飞边缩边淡，
   落点到了才真正隐藏；没有可飞的目标或系统开了「减少动态」时，退回原来的淡出收起。
   飞行走 GSAP：Emphasized-accelerate，300ms——飞的距离长，200ms 看不清落点，
   但仍比 500ms 的进场短，符合「出场永远比进场短」；GSAP 不可用时退回 WAAPI 同款。
   done 在隐藏完成后回调（应用窗口靠它 renderDock，把倒影从宽切成窄） */
function minimizeWinTo(win, targetEl, done){
  if(win._minBusy) return;
  var tr = null;
  try{ tr = targetEl ? targetEl.getBoundingClientRect() : null; }catch(e){}
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if(!tr || (!tr.width && !tr.height) || reduced || (typeof win.animate !== 'function' && !window.gsap)){
    win.classList.add('out');
    setTimeout(function(){ win._min = true; win.style.display = 'none'; win.classList.remove('out'); if(done) done(); }, 250);
    return;
  }
  win._minBusy = true;
  win.classList.remove('rise'); win.classList.remove('out');
  var wr = win.getBoundingClientRect();
  var dx = (tr.left + tr.width / 2) - (wr.left + wr.width / 2);
  var dy = (tr.top + tr.height / 2) - (wr.top + wr.height / 2);
  var sx = Math.max(0.04, tr.width / Math.max(1, wr.width));
  var sy = Math.max(0.04, tr.height / Math.max(1, wr.height));
  var anim = null, finished = false;
  function finish(){
    if(finished) return;
    finished = true;
    win._min = true; win.style.display = 'none'; win._minBusy = false;
    if(window.gsap){ try{ gsap.set(win, {clearProps: 'transform,opacity'}); }catch(e){} }
    if(anim){ try{ anim.cancel(); }catch(e){} }
    if(done) done();
  }
  /* GSAP 优先：x/y 直线飞向图标中心，同时缩到图标大小、淡到 0.2 */
  if(window.gsap && window.gsap.to){
    try{
      gsap.killTweensOf(win);
      gsap.to(win, {x: dx, y: dy, scaleX: sx, scaleY: sy, opacity: 0.2, duration: 0.3, ease: 'power3.in', overwrite: 'auto', onComplete: finish});
      setTimeout(finish, 400);
      return;
    }catch(e){}
  }
  try{
    anim = win.animate([
      { transform: 'translate(0px,0px) scale(1,1)', opacity: 1 },
      { transform: 'translate(' + dx.toFixed(1) + 'px,' + dy.toFixed(1) + 'px) scale(' + sx.toFixed(3) + ',' + sy.toFixed(3) + ')', opacity: 0.2 }
    ], { duration: 300, easing: 'cubic-bezier(0.3,0,0.8,0.15)' });
  }catch(e){ anim = null; }
  if(anim && anim.finished && anim.finished.then) anim.finished.then(finish, finish);
  setTimeout(finish, 380);
}
/* 展开/收回动画（与启动台同款）：所有软件窗口都从自己的图标里「长」出来，
   关闭时沿原路缩回图标里。原点按图标实时位置算：新开优先桌面图标、恢复/关闭
   优先 Dock 图标，另一个作兜底；图标不在当前桌面或被隐藏（rect 为 0）时跳过。
   找不到可用图标、系统开了「减少动态」或浏览器不支持 WAAPI 时，
   展开退回 .rise 升起、关闭退回 .out 沉下（winGrowFrom 返回 false 由调用方处理） */
function winIconEl(appId, preferDock){
  var deskEl = null, dockEl = null;
  for(var i=0;i<items.length;i++){
    if(items[i].type==='app' && items[i].appId===appId && items[i].el){ deskEl = items[i].el.querySelector('.appIcon') || items[i].el; break; }
  }
  dockEl = dock ? (dock.querySelector('.dockItem[data-app="'+appId+'"] .dockIcon') || dock.querySelector('.dockItem[data-app="'+appId+'"]')) : null;
  var cands = preferDock ? [dockEl, deskEl] : [deskEl, dockEl];
  for(var j=0;j<cands.length;j++){
    var el = cands[j];
    if(!el) continue;
    try{ var r = el.getBoundingClientRect(); if(r.width > 0 && r.height > 0) return el; }catch(e){}
  }
  return null;
}
function winGrowFrom(win, originEl){
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var tr = null;
  try{ tr = originEl ? originEl.getBoundingClientRect() : null; }catch(e){}
  if(!tr || (!tr.width && !tr.height) || reduced || (typeof win.animate !== 'function' && !window.gsap)) return false;
  var wr = win.getBoundingClientRect();
  if(!wr.width || !wr.height) return false;
  var cx = Math.max(8, Math.min(window.innerWidth - 8, tr.left + tr.width / 2));
  var cy = Math.max(8, Math.min(window.innerHeight - 8, tr.top + tr.height / 2));
  var dx = cx - (wr.left + wr.width / 2);
  var dy = cy - (wr.top + wr.height / 2);
  var s = Math.max(0.03, Math.min(0.6, Math.min(tr.width, tr.height) / Math.max(wr.width, wr.height)));
  /* 先摘掉 CSS 入场/退场动画：CSS 动画压过脚本动画，它还在跑时展开会被它盖住 */
  win.classList.remove('rise'); win.classList.remove('out');
  /* GSAP 优先：进场 long 500ms + Emphasized-decelerate（power3.out 近似）；
     落点后清掉行内 transform/opacity，之后窗口的 left/top 完全由拖拽与布局接管 */
  if(window.gsap && window.gsap.fromTo){
    try{
      gsap.killTweensOf(win);
      gsap.fromTo(win,
        {x: dx, y: dy, scale: s, opacity: 0},
        {x: 0, y: 0, scale: 1, opacity: 1, duration: 0.5, ease: 'power3.out', overwrite: 'auto',
         onComplete: function(){ try{ gsap.set(win, {clearProps: 'transform,opacity'}); }catch(e){} }});
      return true;
    }catch(e){}
  }
  try{
    win.animate([
      { transform: 'translate(' + dx.toFixed(1) + 'px,' + dy.toFixed(1) + 'px) scale(' + s.toFixed(3) + ')', opacity: 0, offset: 0 },
      { opacity: 1, offset: 0.45 },
      { transform: 'translate(0px,0px) scale(1)', opacity: 1, offset: 1 }
    ], { duration: 500, easing: 'cubic-bezier(0.05,0.7,0.1,1)' });
    return true;
  }catch(e){ return false; }
}
function winShrinkTo(win, originEl, done){
  if(win._closeBusy){ return; }
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var tr = null;
  try{ tr = originEl ? originEl.getBoundingClientRect() : null; }catch(e){}
  if(!tr || (!tr.width && !tr.height) || reduced || (typeof win.animate !== 'function' && !window.gsap)){
    win.classList.add('out');
    setTimeout(done, 250);
    return;
  }
  win._closeBusy = true;
  win.classList.remove('rise'); win.classList.remove('out');
  var wr = win.getBoundingClientRect();
  var cx = Math.max(8, Math.min(window.innerWidth - 8, tr.left + tr.width / 2));
  var cy = Math.max(8, Math.min(window.innerHeight - 8, tr.top + tr.height / 2));
  var dx = cx - (wr.left + wr.width / 2);
  var dy = cy - (wr.top + wr.height / 2);
  var s = Math.max(0.03, Math.min(0.6, Math.min(tr.width, tr.height) / Math.max(wr.width, wr.height)));
  var anim = null, finished = false;
  function finish(){
    if(finished) return;
    finished = true;
    if(window.gsap){ try{ gsap.set(win, {clearProps: 'transform,opacity'}); }catch(e){} }
    if(anim){ try{ anim.cancel(); }catch(e){} }
    done();
  }
  /* GSAP 优先：关闭沿原路缩回图标，出场 200ms Emphasized-accelerate（power3.in 近似） */
  if(window.gsap && window.gsap.to){
    try{
      gsap.killTweensOf(win);
      gsap.to(win, {x: dx, y: dy, scale: s, opacity: 0, duration: 0.2, ease: 'power3.in', overwrite: 'auto', onComplete: finish});
      setTimeout(finish, 300);
      return;
    }catch(e){}
  }
  try{
    anim = win.animate([
      { transform: 'translate(0px,0px) scale(1)', opacity: 1 },
      { transform: 'translate(' + dx.toFixed(1) + 'px,' + dy.toFixed(1) + 'px) scale(' + s.toFixed(3) + ')', opacity: 0 }
    ], { duration: 200, easing: 'cubic-bezier(0.3,0,0.8,0.15)', fill: 'forwards' });
  }catch(e){ anim = null; }
  if(anim && anim.finished && anim.finished.then) anim.finished.then(finish, finish);
  setTimeout(finish, 300);
}
var _lastStickyOpenTime = 0;
function openApp(appId){
  closeLaunchpad();
  var app = appById(appId);
  noteRecent(appId);
  renderDock();
  var di = dock ? dock.querySelector('.dockItem[data-app="'+appId+'"]') : null;
  if(di){ di.classList.remove('bounce'); void di.offsetWidth; di.classList.add('bounce'); }
  if(appId==='sticky'){
    var now = Date.now();
    if(now - _lastStickyOpenTime < 450) return;
    _lastStickyOpenTime = now;
    createStickyNote();
    return;
  }
  /* 手机应用：不归我们渲染，交给原生投到这块屏（系统自由窗口，自己带标题栏） */
  if(app.kind==='native'){
    if(di){ di.classList.remove('bounce'); void di.offsetWidth; di.classList.add('bounce'); }
    try{ Canghai.launch(app.comp); toast('正在打开「'+app.name+'」'); }
    catch(e){ toast('打不开「'+app.name+'」：' + e.message); }
    return;
  }
  if(openWins[appId]){
    var ex = openWins[appId];
    ex._min = false; ex.style.display='flex';
    /* 恢复也走展开动画：从 Dock 图标里长回来；没有可用图标时退回原来的升起 */
    if(!winGrowFrom(ex, winIconEl(appId, true))){
      ex.classList.remove('rise'); void ex.offsetWidth; ex.classList.add('rise');
    }
    focusWin(ex);
    /* 顶部那次 renderDock 时窗口还记着最小化状态，倒影是窄的；恢复后把倒影切回宽 */
    var diEx = dock ? dock.querySelector('.dockItem[data-app="'+appId+'"]') : null;
    if(diEx){ diEx.classList.add('running'); diEx.classList.remove('minned'); chPushDockRunState(); } else { renderDock(); }
    return;
  }
  var win = document.createElement('div');
  win.className = 'win focused';
  win._min = false;
  var isMobile = window.innerWidth < 680;
  /* 大小按「内容是什么」定：装进来的软件给大窗（它自己的界面通常按桌面设计），系统小工具给小窗 */
  var w = isMobile ? Math.min(window.innerWidth-20, 560) : (app.installed ? 860 : appId==='inputtrace' ? 720 : 620);
  var h = isMobile ? 0 : (app.installed ? 580 : appId==='inputtrace' ? 520 : 470);
  if(isMobile) h = Math.min(window.innerHeight-170, 520);
  win.style.width = w+'px'; win.style.height = h+'px';
  win.style.left = Math.max(8, (window.innerWidth-w)/2 + (Math.random()*36-18))+'px';
  win.style.top = Math.max(46, (window.innerHeight-h)/2 - 30 + (Math.random()*24-12))+'px';
  win.style.zIndex = ++zTop;
  win.innerHTML = '<div class="winbar"><button class="tl r" type="button" aria-label="关闭"></button><button class="tl y" type="button" aria-label="最小化"></button><button class="tl g" type="button" aria-label="缩放"></button><div class="ttl"><span style="display:inline-flex;width:18px;height:18px;border-radius:5px;background:'+app.grad+';align-items:center;justify-content:center;overflow:hidden">'+ICONS[app.id]+'</span>'+escapeHtml(app.name)+'</div><span style="width:97px"></span></div><div class="winbody"></div>';
  win.querySelector('.winbody').innerHTML = appContent(appId);
  if(app.installed || appId==='inputtrace') win.querySelector('.winbody').style.overflow = 'hidden';
  winLayer.appendChild(win);
  openWins[appId] = win;
  focusWin(win);
  /* 和启动台同款展开动画：窗口从自己的图标（优先桌面图标）里长出来；
     没有可用图标 / 不支持脚本动画时退回 .rise 升起（.win 已不自带入场动画） */
  if(!winGrowFrom(win, winIconEl(appId, false))) win.classList.add('rise');
  /* 倒影第一次打开就亮：顶部那次 renderDock 在窗口登记进 openWins 之前跑，
     读不到运行状态、倒影不亮，此前只有最小化后的回调再刷一次才补上；
     现在窗口一登记就把对应 Dock 图标点亮成宽倒影（找不到图标时整刷兜底） */
  var diRun = dock ? dock.querySelector('.dockItem[data-app="'+appId+'"]') : null;
  if(diRun){ diRun.classList.add('running'); diRun.classList.remove('minned'); chPushDockRunState(); } else { renderDock(); }
  win.addEventListener('pointerdown', function(){ focusWin(win); });
  win.querySelector('.tl.r').addEventListener('click', function(){
    delete openWins[appId];
    renderDock();
    /* 关闭沿原路缩回自己的图标（优先 Dock 图标），落点到了才移除窗口 */
    winShrinkTo(win, winIconEl(appId, true), function(){ win.remove(); });
  });
  win.querySelector('.tl.y').addEventListener('click', function(){
    /* 落点必须是「这个应用自己的图标」：Dock 图标优先（悬浮层里有）、桌面图标兜底；
       本层 dock 为 null（Dock 在独立悬浮层），实际飞向桌面上的同款图标 */
    var ic = winIconEl(appId, true);
    minimizeWinTo(win, ic, function(){ renderDock(); });
    var toDock = !!(ic && ic.closest && ic.closest('#dock'));
    toast('「'+app.name+'」已最小化到 ' + (toDock ? 'Dock，点 Dock 图标恢复' : '桌面图标，点桌面图标恢复'));
  });
  makeDraggable(win, win.querySelector('.winbar'));
  wireApp(appId, win);
}
/* ================= 特殊文件夹视图（非普通应用窗口 · 与桌面网格双向拖拽互动） ================= */
function findOpenFolderAtPoint(x, y){
  var best = null, bestZ = -1;
  items.forEach(function(it){
    if(it.type!=='folder') return;
    var fv = openWins['folder_'+it.id];
    if(!fv || fv._min || fv.style.display==='none') return;
    var r = fv.getBoundingClientRect();
    if(x>=r.left && x<=r.right && y>=r.top && y<=r.bottom){
      var z = parseInt(fv.style.zIndex, 10) || 0;
      if(z >= bestZ){ bestZ = z; best = {it:it, win:fv}; }
    }
  });
  return best;
}
function clearAllFolderDropTargets(keepEl){
  winLayer.querySelectorAll('.folderView').forEach(function(fv){
    if(fv !== keepEl){
      fv.classList.remove('dropTarget');
      var fg = fv.querySelector('.fvGhost'), ff = fv.querySelector('.fvFrame');
      if(fg) fg.style.display = 'none';
      if(ff) ff.classList.remove('show');
    }
  });
}
function dropDesktopItemIntoOpenFolder(folderIt, deskIt, x, y){
  if(!folderIt || !deskIt || folderIt===deskIt) return false;
  var entries = ensureFolderEntries(folderIt);
  var cell = folderIt._cellAtPoint ? folderIt._cellAtPoint(x, y) : {col:0, row:0};
  if(deskIt.type==='app'){
    if(folderIt.apps.indexOf(deskIt.appId)>=0){
      animatePos(deskIt);
      toast('「'+appById(deskIt.appId).name+'」已经在文件夹「'+folderIt.name+'」里了');
      return true;
    }
    var spotA = folderIt._findFreeInFolder ? folderIt._findFreeInFolder(cell.col, cell.row, null) : cell;
    entries.push({kind:'app', appId:deskIt.appId, col:spotA.col, row:spotA.row});
    folderIt._sortEntriesByGrid && folderIt._sortEntriesByGrid();
    removeItem(deskIt);
    renderFolderCell(folderIt);
    if(folderIt._refreshView) folderIt._refreshView();
    deskSaveSoon();
    toast('「'+appById(deskIt.appId).name+'」已拖入文件夹「'+folderIt.name+'」');
    return true;
  }
  if(deskIt.type==='txt'){
    var spotT = folderIt._findFreeInFolder ? folderIt._findFreeInFolder(cell.col, cell.row, null) : cell;
    entries.push({kind:'txt', name:deskIt.name, content:deskIt.content||'', col:spotT.col, row:spotT.row});
    folderIt._sortEntriesByGrid && folderIt._sortEntriesByGrid();
    removeItem(deskIt);
    renderFolderCell(folderIt);
    if(folderIt._refreshView) folderIt._refreshView();
    deskSaveSoon();
    toast('「'+deskIt.name+'」已拖入文件夹「'+folderIt.name+'」');
    return true;
  }
  return false;
}
function openFolder(it){
  ensureFolderEntries(it);
  var key = 'folder_'+it.id;
  if(openWins[key]){
    var ex = openWins[key];
    if(!ex._min && ex.style.display !== 'none'){
      // 再次双击已打开的文件夹图标：执行平滑收起关闭
      delete openWins[key];
      delete it._refreshView;
      winShrinkTo(ex, it.el.querySelector('.appIcon') || it.el, function(){ ex.remove(); });
      return;
    }
    ex._min = false; ex.style.display='flex';
    if(!winGrowFrom(ex, it.el.querySelector('.appIcon') || it.el)){
      ex.classList.remove('rise'); void ex.offsetWidth; ex.classList.add('rise');
    }
    focusWin(ex);
    if(it._refreshView) it._refreshView();
    return;
  }
  var fv = document.createElement('div');
  fv.className = 'win folderView focused';
  fv._min = false;
  var w = it.fvW || Math.min(window.innerWidth-28, Math.max(380, CELL*4 + 40));
  var h = it.fvH || Math.min(window.innerHeight-90, Math.max(300, CELL*3 + 72));
  fv.style.width = w+'px'; fv.style.height = h+'px';
  fv.style.left = (it.fvX!=null ? clamp(it.fvX, 8, Math.max(8, window.innerWidth-w-8)) : Math.max(8, Math.round((window.innerWidth-w)/2)))+'px';
  fv.style.top = (it.fvY!=null ? clamp(it.fvY, 42, Math.max(42, window.innerHeight-h-8)) : Math.max(46, Math.round((window.innerHeight-h)/2 - 16)))+'px';
  fv.style.zIndex = ++zTop;
  fv.innerHTML = '<div class="winbar fvHead">'
    + '<button class="tl r fvClose" type="button" aria-label="关闭" title="关闭"></button>'
    + '<button class="tl y" type="button" aria-label="最小化" title="最小化"></button>'
    + '<button class="tl g" type="button" aria-label="缩放" title="全屏/还原"></button>'
    + '<div class="ttl">'
    +   '<span class="fvBadge" style="display:inline-flex;width:18px;height:18px;border-radius:5px;align-items:center;justify-content:center;overflow:hidden;flex:none">'+ICONS.folder+'</span>'
    +   '<input class="fvTitle" type="text" maxlength="16" value="'+escapeHtml(it.name)+'" aria-label="文件夹名称" title="点击修改文件夹名字（与桌面同步）" />'
    +   '<span class="fvMeta" style="font-size:11px;opacity:0.65;margin-left:6px;font-weight:normal;"></span>'
    + '</div>'
    + '<span style="width:20px;flex:none"></span>'
    + '</div>'
    + '<div class="winbody fvBody"><div class="fvFrame"></div><div class="fvGhost"></div><div class="fvEmpty" style="display:none">文件夹是空的<br>可直接把桌面上的图标拖进来，或把里面的图标拖回桌面</div></div>'
    + '<div class="fvResize" title="拖动调整文件夹视图大小（内部网格随窗口大小自动计算）"></div>';
  winLayer.appendChild(fv);
  openWins[key] = fv;
  focusWin(fv);
  if(!winGrowFrom(fv, it.el.querySelector('.appIcon') || it.el)) fv.classList.add('rise');

  var head = fv.querySelector('.winbar');
  var titleInp = fv.querySelector('.fvTitle');
  var metaEl = fv.querySelector('.fvMeta');
  var bodyEl = fv.querySelector('.fvBody');
  var frameEl = fv.querySelector('.fvFrame');
  var titleInp = fv.querySelector('.fvTitle');
  var metaEl = fv.querySelector('.fvMeta');
  var bodyEl = fv.querySelector('.fvBody');
  var frameEl = fv.querySelector('.fvFrame');
  var ghostEl = fv.querySelector('.fvGhost');
  var emptyEl = fv.querySelector('.fvEmpty');
  var resizeEl = fv.querySelector('.fvResize');

  var fCols = 4, fRows = 3, fCell = 92, fOx = 0, fOy = 0;
  function computeFolderGrid(){
    var bw = bodyEl.clientWidth || (fv.offsetWidth - 4);
    var bh = bodyEl.clientHeight || (fv.offsetHeight - 52);
    var targetCell = clamp(CELL || 96, 76, 112);
    fCols = Math.max(2, Math.floor((bw - 16) / targetCell));
    fRows = Math.max(2, Math.floor((bh - 16) / targetCell));
    fCell = Math.max(68, Math.floor(Math.min((bw - 16) / fCols, (bh - 16) / fRows)));
    fOx = Math.floor((bw - fCols * fCell) / 2);
    fOy = Math.floor((bh - fRows * fCell) / 2);
    frameEl.style.left = fOx+'px'; frameEl.style.top = fOy+'px';
    frameEl.style.width = (fCols * fCell)+'px'; frameEl.style.height = (fRows * fCell)+'px';
    frameEl.style.backgroundSize = fCell+'px '+fCell+'px';
    var entries = ensureFolderEntries(it);
    metaEl.textContent = entries.length + ' 项 · ' + fCols + '×' + fRows + ' 格';
  }
  function fGridX(c){ return fOx + c * fCell; }
  function fGridY(r){ return fOy + r * fCell; }
  function fCellHit(c, r, excludeEn){
    var entries = ensureFolderEntries(it);
    for(var i=0;i<entries.length;i++){
      var e = entries[i];
      if(e === excludeEn) continue;
      if(e.col === c && e.row === r) return true;
    }
    return false;
  }
  function findFreeInFolder(sc, sr, excludeEn){
    var c0 = clamp(sc||0, 0, fCols-1), r0 = clamp(sr||0, 0, fRows-1);
    if(!fCellHit(c0, r0, excludeEn)) return {col:c0, row:r0};
    var best = null, bd = 1e9;
    for(var r=0;r<fRows;r++) for(var c=0;c<fCols;c++){
      if(!fCellHit(c, r, excludeEn)){
        var d = Math.abs(c-c0) + Math.abs(r-r0);
        if(d < bd){ bd = d; best = {col:c, row:r}; }
      }
    }
    return best || {col:c0, row:r0};
  }
  function sortEntriesByGrid(){
    var entries = ensureFolderEntries(it);
    entries.sort(function(a, b){
      var ra = a.row||0, rb = b.row||0;
      if(ra !== rb) return ra - rb;
      return (a.col||0) - (b.col||0);
    });
    it.apps = entries.filter(function(e){ return e && e.kind==='app' && e.appId; }).map(function(e){ return e.appId; });
  }
  it._findFreeInFolder = findFreeInFolder;
  it._sortEntriesByGrid = sortEntriesByGrid;
  it._cellAtPoint = function(clientX, clientY){
    computeFolderGrid();
    var br = bodyEl.getBoundingClientRect();
    var c = clamp(Math.floor((clientX - br.left - fOx) / fCell), 0, fCols - 1);
    var r = clamp(Math.floor((clientY - br.top - fOy) / fCell), 0, fRows - 1);
    return {col:c, row:r};
  };
  it._previewDropAt = function(clientX, clientY){
    fv.classList.add('dropTarget');
    frameEl.classList.add('show');
    var cell = it._cellAtPoint(clientX, clientY);
    var spot = findFreeInFolder(cell.col, cell.row, null);
    ghostEl.style.display = 'block';
    ghostEl.style.left = (fGridX(spot.col)+3)+'px';
    ghostEl.style.top = (fGridY(spot.row)+3)+'px';
    ghostEl.style.width = (fCell-6)+'px';
    ghostEl.style.height = (fCell-6)+'px';
  };

  function renderFolderItems(){
    computeFolderGrid();
    bodyEl.querySelectorAll('.fvItem').forEach(function(n){ n.remove(); });
    var entries = ensureFolderEntries(it);
    emptyEl.style.display = entries.length ? 'none' : 'flex';
    entries.forEach(function(en, idx){
      if(en.col == null || en.row == null || en.col >= fCols || en.row >= fRows || fCellHit(en.col, en.row, en)){
        var sp = findFreeInFolder(en.col!=null ? en.col : (idx % fCols), en.row!=null ? en.row : Math.floor(idx / fCols), en);
        en.col = sp.col; en.row = sp.row;
      }
      var cellEl = document.createElement('div');
      cellEl.className = 'fvItem';
      cellEl.style.width = fCell+'px';
      cellEl.style.height = fCell+'px';
      cellEl.style.left = fGridX(en.col)+'px';
      cellEl.style.top = fGridY(en.row)+'px';
      var isz = Math.round(clamp(fCell * 0.54, 34, 62));
      cellEl.style.setProperty('--isz', isz+'px');
      if(en.kind==='txt'){
        cellEl.innerHTML = '<div class="appCell fileCell"><div class="appIcon" style="background:linear-gradient(160deg,#fdfdff,#d7e6f5)">'+ICONS.devdoc+'</div><div class="appName">'+escapeHtml(en.name||'新建文本文档.txt')+'</div></div>';
      } else {
        var a = appById(en.appId);
        cellEl.innerHTML = '<div class="appCell"><div class="appIcon" style="background:'+a.grad+'">'+ICONS[a.id]+'</div><div class="appName">'+escapeHtml(a.name)+'</div></div>';
      }
      cellEl.addEventListener('dblclick', function(e){
        e.stopPropagation();
        if(en.kind==='txt') openTxtEntryModal(it, en);
        else openApp(en.appId);
      });
      cellEl.addEventListener('contextmenu', function(e){
        e.preventDefault(); e.stopPropagation();
        var menuItems = [];
        if(en.kind==='txt'){
          menuItems.push({label:'打开「'+(en.name||'文本文档.txt')+'」', fn:function(){ openTxtEntryModal(it, en); }});
        } else {
          menuItems.push({label:'打开「'+appById(en.appId).name+'」', fn:function(){ openApp(en.appId); }});
        }
        menuItems.push({label:'移出文件夹（放回桌面）', fn:function(){
          moveFolderEntryToDesktop(it, en, null, null);
        }});
        showMenu(e.clientX, e.clientY, menuItems);
      });
      /* 文件夹内拖拽：在框内松手 = 调整文件夹内格子位置（同步更新桌面大文件夹预览顺序）；
         拖出文件夹框到桌面松手 = 直接落位到桌面对应格子！ */
      cellEl.addEventListener('pointerdown', function(e){
        if(e.button!==undefined && e.button!==0) return;
        e.stopPropagation();
        closeMenu();
        var sx = e.clientX, sy = e.clientY, moved = false;
        var fly = null;
        cellEl.setPointerCapture && cellEl.setPointerCapture(e.pointerId);
        function mv(ev){
          var dx = ev.clientX - sx, dy = ev.clientY - sy;
          if(!moved && Math.hypot(dx, dy) < 5) return;
          if(!moved){
            moved = true;
            cellEl.style.opacity = '.35';
            frameEl.classList.add('show');
            fly = document.createElement('div');
            fly.className = 'fvFlyGhost';
            fly.style.width = fCell+'px'; fly.style.height = fCell+'px';
            fly.style.setProperty('--isz', isz+'px');
            fly.innerHTML = cellEl.innerHTML;
            document.body.appendChild(fly);
          }
          fly.style.transform = 'translate3d('+(ev.clientX - fCell/2)+'px,'+(ev.clientY - fCell/2)+'px,0) scale(1.06)';
          var fvr = fv.getBoundingClientRect();
          var inside = ev.clientX>=fvr.left && ev.clientX<=fvr.right && ev.clientY>=fvr.top && ev.clientY<=fvr.bottom;
          if(inside){
            ghost.style.display = 'none';
            showGridFrame(false);
            var cell = it._cellAtPoint(ev.clientX, ev.clientY);
            var sp = findFreeInFolder(cell.col, cell.row, en);
            ghostEl.style.display = 'block';
            ghostEl.style.left = (fGridX(sp.col)+3)+'px';
            ghostEl.style.top = (fGridY(sp.row)+3)+'px';
            ghostEl.style.width = (fCell-6)+'px';
            ghostEl.style.height = (fCell-6)+'px';
          } else {
            ghostEl.style.display = 'none';
            showGridFrame(true);
            var gr = gridArea.getBoundingClientRect();
            var dc = clamp(Math.floor((ev.clientX - gr.left - GOX) / CELL), 0, COLS - 1);
            var dr = clamp(Math.floor((ev.clientY - gr.top - GOY) / CELL), 0, ROWS - 1);
            ghost.style.display = 'block';
            ghost.style.left = (gridX(dc)+3)+'px';
            ghost.style.top = (gridY(dr)+3)+'px';
            ghost.style.width = (CELL-6)+'px';
            ghost.style.height = (CELL-6)+'px';
          }
        }
        function up(ev){
          cellEl.removeEventListener('pointermove', mv);
          cellEl.removeEventListener('pointerup', up);
          cellEl.removeEventListener('pointercancel', up);
          if(fly) fly.remove();
          cellEl.style.opacity = '';
          frameEl.classList.remove('show');
          ghostEl.style.display = 'none';
          ghost.style.display = 'none';
          showGridFrame(false);
          if(!moved) return;
          var fvr = fv.getBoundingClientRect();
          var inside = ev.clientX>=fvr.left && ev.clientX<=fvr.right && ev.clientY>=fvr.top && ev.clientY<=fvr.bottom;
          if(inside){
            var cell = it._cellAtPoint(ev.clientX, ev.clientY);
            var sp = findFreeInFolder(cell.col, cell.row, en);
            en.col = sp.col; en.row = sp.row;
            sortEntriesByGrid();
            renderFolderItems();
            renderFolderCell(it);
            deskSaveSoon();
          } else {
            moveFolderEntryToDesktop(it, en, ev.clientX, ev.clientY);
          }
        }
        cellEl.addEventListener('pointermove', mv);
        cellEl.addEventListener('pointerup', up);
        cellEl.addEventListener('pointercancel', up);
      });
      bodyEl.appendChild(cellEl);
    });
  }
  /* 只更新位置的轻量版：resize 时不销毁重建图标 DOM，只改现有节点的位置/尺寸 */
  function repositionFolderItems(){
    computeFolderGrid();
    var entries = ensureFolderEntries(it);
    var nodes = bodyEl.querySelectorAll('.fvItem');
    if(nodes.length !== entries.length){ renderFolderItems(); return; }
    var isz = Math.round(clamp(fCell * 0.54, 34, 62));
    entries.forEach(function(en, idx){
      if(en.col == null || en.row == null || en.col >= fCols || en.row >= fRows || fCellHit(en.col, en.row, en)){
        var sp = findFreeInFolder(en.col!=null ? en.col : (idx % fCols), en.row!=null ? en.row : Math.floor(idx / fCols), en);
        en.col = sp.col; en.row = sp.row;
      }
      var node = nodes[idx];
      node.style.width = fCell+'px';
      node.style.height = fCell+'px';
      node.style.left = fGridX(en.col)+'px';
      node.style.top = fGridY(en.row)+'px';
      node.style.setProperty('--isz', isz+'px');
    });
  }
  it._refreshView = renderFolderItems;
  renderFolderItems();

  /* 顶部标题栏直接改名：输入时实时同步桌面文件夹名字与内容 */
  titleInp.addEventListener('pointerdown', function(e){ e.stopPropagation(); });
  titleInp.addEventListener('input', function(){
    var v = titleInp.value.trim() || '文件夹';
    it.name = v;
    var nm = it.el.querySelector('.appName');
    if(nm && !nm.querySelector('input')) nm.textContent = v;
    deskSaveSoon();
  });
  titleInp.addEventListener('keydown', function(e){
    e.stopPropagation();
    if(e.key==='Enter' || e.key==='Escape') titleInp.blur();
  });
  titleInp.addEventListener('blur', function(){
    var v = titleInp.value.trim() || '文件夹';
    titleInp.value = v;
    it.name = v;
    renderFolderCell(it);
    deskSaveSoon();
  });

  fv.addEventListener('pointerdown', function(){ focusWin(fv); });
  fv.querySelector('.tl.r').addEventListener('click', function(){
    delete openWins[key];
    delete it._refreshView;
    winShrinkTo(fv, it.el.querySelector('.appIcon') || it.el, function(){ fv.remove(); });
  });
  fv.querySelector('.tl.y').addEventListener('click', function(){
    delete openWins[key];
    delete it._refreshView;
    winShrinkTo(fv, it.el.querySelector('.appIcon') || it.el, function(){ fv.remove(); });
  });
  fv.querySelector('.tl.g').addEventListener('click', function(){
    toggleWinMaximize(fv);
  });

  /* 拖动顶部移动文件夹视图 */
  makeDraggable(fv, head, function(){
    it.fvX = fv.offsetLeft; it.fvY = fv.offsetTop;
    deskSaveSoon();
  });

  /* 右下角拖动调整文件夹视图大小：内部格子数与位置随窗口大小自动实时重算 */
  resizeEl.addEventListener('pointerdown', function(e){
    if(e.button!==undefined && e.button!==0) return;
    e.stopPropagation(); e.preventDefault();
    var sx = e.clientX, sy = e.clientY, ow = fv.offsetWidth, oh = fv.offsetHeight;
    resizeEl.setPointerCapture && resizeEl.setPointerCapture(e.pointerId);
    frameEl.classList.add('show');
    var resizeRaf = 0;
    function mv(ev){
      var nw = clamp(Math.round(ow + ev.clientX - sx), 260, Math.max(260, window.innerWidth - fv.offsetLeft - 10));
      var nh = clamp(Math.round(oh + ev.clientY - sy), 220, Math.max(220, window.innerHeight - fv.offsetTop - 20));
      fv.style.width = nw+'px'; fv.style.height = nh+'px';
      it.fvW = nw; it.fvH = nh;
      if(!resizeRaf){
        resizeRaf = requestAnimationFrame(function(){
          resizeRaf = 0;
          repositionFolderItems();
        });
      }
    }
    function up(){
      resizeEl.removeEventListener('pointermove', mv);
      resizeEl.removeEventListener('pointerup', up);
      resizeEl.removeEventListener('pointercancel', up);
      frameEl.classList.remove('show');
      if(resizeRaf){ cancelAnimationFrame(resizeRaf); resizeRaf = 0; }
      repositionFolderItems();
      deskSaveSoon();
    }
    resizeEl.addEventListener('pointermove', mv);
    resizeEl.addEventListener('pointerup', up);
    resizeEl.addEventListener('pointercancel', up);
  });
}
function moveFolderEntryToDesktop(folderIt, en, clientX, clientY){
  var entries = ensureFolderEntries(folderIt);
  folderIt.entries = entries.filter(function(x){ return x !== en; });
  folderIt.apps = folderIt.entries.filter(function(x){ return x && x.kind==='app' && x.appId; }).map(function(x){ return x.appId; });
  var dc = folderIt.col, dr = folderIt.row;
  if(clientX != null && clientY != null){
    var gr = gridArea.getBoundingClientRect();
    dc = clamp(Math.floor((clientX - gr.left - GOX) / CELL), 0, COLS - 1);
    dr = clamp(Math.floor((clientY - gr.top - GOY) / CELL), 0, ROWS - 1);
  }
  var spot = findFree(1, 1, dc, dr, folderIt.desk);
  var ni;
  if(en.kind==='txt'){
    ni = makeItem({type:'txt', name:en.name||'新建文本文档.txt', content:en.content||'', col:spot.col, row:spot.row, w:1, h:1, desk:folderIt.desk});
    renderTxtCell(ni);
    toast('「'+ni.name+'」已从文件夹移回桌面');
  } else {
    var a = appById(en.appId);
    ni = makeItem({type:'app', appId:en.appId, name:a.name, col:spot.col, row:spot.row, w:1, h:1, desk:folderIt.desk});
    renderAppCell(ni);
    toast('「'+a.name+'」已从文件夹移回桌面');
  }
  pulseItem(ni);
  renderFolderCell(folderIt);
  if(folderIt._refreshView) folderIt._refreshView();
  deskSaveSoon();
}

/* ================= 沧海 · 原生数据桥（唯一数据源在 Native） ================= */
function chNativeReady(){ return (typeof Canghai !== 'undefined') && !!Canghai && !!Canghai.getState; }
/* 手机端存的图标有两种：SVG 源码，或渲染好的 PNG data URL；都变成能塞进 innerHTML 的标记 */
function chAppIcon(a){
  if(a && a.icon) return a.icon;
  if(a && a.png) return '<img src="'+String(a.png).replace(/"/g,'&quot;')+'" style="width:100%;height:100%;object-fit:contain">';
  return null;
}
window.handleDesktopBack = function(){
  try {
    if(typeof lpIsOpen==='function' && lpIsOpen()){
      if(typeof lpOpenFolderRef!=='undefined' && lpOpenFolderRef) closeLpFolder();
      else closeLaunchpad();
      return true;
    }
    var menuLayer = document.getElementById('menuLayer');
    if(menuLayer && menuLayer.classList.contains('show')){
      if(typeof closeMenu==='function') closeMenu();
      return true;
    }
    var cc = document.getElementById('controlCenter');
    if(cc && cc.classList.contains('open')){
      cc.classList.remove('open');
      return true;
    }
    var maxedWins = winLayer ? winLayer.querySelectorAll('.win.max') : null;
    if(maxedWins && maxedWins.length > 0){
      var target = winLayer.querySelector('.win.max.focused') || maxedWins[maxedWins.length - 1];
      if(target){
        toggleWinMaximize(target);
        if(typeof toast==='function') toast('已退出全屏模式');
        return true;
      }
    }
  } catch(e){}
  return false;
};
