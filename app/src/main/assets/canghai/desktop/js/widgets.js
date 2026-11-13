/* ========================================================
 * 沧海系统桌面 - 小组件与便签系统 (widgets.js)
 * 职责：内置时钟/日历/待办小组件、便签百分比位置存储、跨屏防死循环同步、自定义HTML容器
 * ======================================================== */
function renderWidget(it){
  /* 小组件不再放右上角 ✕ 删除按钮：左键点它只会选中/拖动，删除只走右键菜单且要二次确认 */
  var chrome = '<div class="wgrip"></div><div class="wresize" title="拖动改尺寸"></div>';
  if(it.wtype==='app'){
    var adef = APP_WIDGETS[it.widgetId];
    it._scaleEl = null;
    if(it._cwTimer){ clearInterval(it._cwTimer); it._cwTimer = null; }
    if(!adef){
      it.el.innerHTML = '<div class="widget">' + chrome + '<div class="wbody"><div style="padding:16px;font-size:13px;opacity:.7">这个小组件的注册信息不见了</div></div></div>';
    } else {
      it.el.innerHTML = '<div class="widget custom">' + chrome + '<iframe sandbox="allow-scripts" title="' + escapeHtml(adef.name) + '"></iframe></div>';
      var aframe = it.el.querySelector('iframe');
      it._frameEl = aframe;
      aframe.srcdoc = buildAppWidgetSrcdoc(adef);
      if(adef.refreshSeconds > 0){
        it._cwTimer = setInterval(function(){ if(it._frameEl) it._frameEl.srcdoc = buildAppWidgetSrcdoc(adef); }, adef.refreshSeconds * 1000);
      }
    }
  } else if(it.wtype==='custom'){
    it._scaleEl = null;
    it.el.innerHTML = '<div class="widget custom">'+chrome+'<iframe sandbox="allow-scripts" title="'+escapeHtml(it.name||'自定义小组件')+'"></iframe></div>';
    var frame = it.el.querySelector('iframe');
    it._frameEl = frame; /* 宿主桥靠 _frameEl 认领消息来源，自定义小组件也要赋值，否则它里面按钮发的消息会被宿主丢掉 */
    /* 设计尺寸用创建时锚定的 it.dw / it.dh，不用当前框大小 it.w / it.h：
       框拖大时画布保持原尺寸、缩放等比变大，内容跟着变大而不是只多空白 */
    frame.srcdoc = buildCustomSrcdoc(it.html, it.dw||it.w, it.dh||it.h);
  } else {
    var def = WIDGET_TYPES[it.wtype];
    it.el.innerHTML = '<div class="widget">'+chrome+'<div class="wbody"><div class="wscale">'+def.html(it)+'</div></div></div>';
    it._scaleEl = it.el.querySelector('.wscale');
    fitWidgetScale(it);
  }
  var wEl = it.el.querySelector('.widget');
  if(it.opacity!=null) wEl.style.opacity = it.opacity;
  wireWidgetBits(it);
}
function wireWeather(it){
  var wth = it.el.querySelector('.w-weather');
  if(!wth) return;
  /* 点天气小组件直接打开城市选择，自己输入名字 / 拼音 / 英文匹配，不再轮换固定城市 */
  wth.addEventListener('click', function(){ openCityPicker(it); });
}
function wireTodo(it){
  /* 点一行切换完成状态：数据落在 it.todos 上，右键小组件可添加新待办 */
  it.el.querySelectorAll('.w-todo .trow').forEach(function(r){
    r.addEventListener('click', function(e){
      e.stopPropagation();
      var idx = +r.getAttribute('data-todo');
      if(!it.todos || !it.todos[idx]) return;
      it.todos[idx].done = !it.todos[idx].done;
      r.classList.toggle('done', it.todos[idx].done);
      deskSaveSoon();
    });
  });
}
function wireWidgetBits(it){
  attachResize(it, it.el.querySelector('.wresize'));
  // 待办：点按切换 + 输入添加
  wireTodo(it);
  // 时钟：点一下切 12/24 小时
  var clk = it.el.querySelector('.w-clock');
  if(clk){
    /* 12 小时制时行内容多出「上午/下午」，44px 大字在双位数小时会溢出画布被裁；
       加 h12 类走小一号字（见 .w-clock.h12），24 小时制不受影响 */
    clk.classList.toggle('h12', it.h24===false);
    clk.addEventListener('click', function(){
      it.h24 = it.h24===false ? true : false;
      clk.classList.toggle('h12', it.h24===false);
      updateClocks();
      toast('时钟已切换为 '+(it.h24===false?'12 小时制':'24 小时制'));
    });
  }
  // 天气：点一下打开城市选择（可输入搜索）
  wireWeather(it);
  // 音乐播放
  var mw = it.el.querySelector('.w-music');
  if(mw){
    var btn = mw.querySelector('.pbtn'), bar = mw.querySelector('.bar i'), prog = 0, timer = null;
    btn.addEventListener('pointerdown', function(e){ e.stopPropagation(); });
    btn.addEventListener('click', function(e){
      e.stopPropagation();
      if(timer){ clearInterval(timer); timer=null; btn.textContent='▶'; mw.classList.remove('playing'); }
      else{
        btn.textContent='⏸'; mw.classList.add('playing');
        timer = setInterval(function(){ prog=(prog+0.6)%100; bar.style.width=prog+'%'; },100);
      }
    });
  }
}
function addWidget(wtype, colHint){
  var def = WIDGET_TYPES[wtype];
  var spot = findFree(def.w, def.h, colHint!=null?colHint:COLS-def.w, 0, curDesk);
  /* dw / dh = 设计尺寸锚点：内容按这个格数排版，之后拖大拖小只改缩放、不改画布 */
  var it = makeItem({type:'widget', wtype:wtype, name:def.name, col:spot.col, row:spot.row, w:def.w, h:def.h, dw:def.w, dh:def.h});
  renderWidget(it);
  return it;
}
var addWidgetDesk = addWidget;
function addCustomWidget(name,w,h,html){
  var spot = findFree(w,h,COLS-w,0,curDesk);
  var it = makeItem({type:'widget', wtype:'custom', name:name, col:spot.col, row:spot.row, w:w, h:h, dw:w, dh:h, html:html});
  renderWidget(it);
  return it;
}
function duplicateWidget(it){
  var cfg = {type:'widget', wtype:it.wtype, name:it.name, w:it.w, h:it.h, dw:it.dw||it.w, dh:it.dh||it.h, html:it.html, widgetId:it.widgetId, opacity:it.opacity, h24:it.h24, cityIdx:it.cityIdx, desk:it.desk, todos: it.todos ? it.todos.map(function(x){ return {t:x.t, done:x.done}; }) : undefined};
  var spot = findFree(it.w, it.h, it.col+1, it.row+1, it.desk, null, null);
  cfg.col = spot.col; cfg.row = spot.row;
  var ni = makeItem(cfg);
  renderWidget(ni);
  return ni;
}

/* ================= 小组件右下角拖拽改尺寸 ================= */
function attachResize(it, handle){
  if(!handle) return;
  handle.addEventListener('pointerdown', function(e){
    e.stopPropagation(); e.preventDefault();
    if(e.button!==undefined && e.button!==0) return;
    var sx = e.clientX, sy = e.clientY;
    var pw = null, ph = null, moved = false;
    handle.setPointerCapture && handle.setPointerCapture(e.pointerId);
    function mv(ev){
      var nw = Math.round(it.w + (ev.clientX-sx)/CELL);
      var nh = Math.round(it.h + (ev.clientY-sy)/CELL);
      nw = clamp(nw, 1, Math.min(6, COLS-it.col));
      nh = clamp(nh, 1, Math.min(6, ROWS-it.row));
      if(nw===pw && nh===ph) return;
      pw = nw; ph = nh; moved = true;
      showGridFrame(true);
      it.el.style.width = (nw*CELL)+'px'; it.el.style.height = (nh*CELL)+'px';
      ghost.style.display='block';
      ghost.style.left=(gridX(it.col)+3)+'px'; ghost.style.top=(gridY(it.row)+3)+'px';
      ghost.style.width=(nw*CELL-6)+'px'; ghost.style.height=(nh*CELL-6)+'px';
    }
    function up(){
      handle.removeEventListener('pointermove', mv);
      handle.removeEventListener('pointerup', up);
      handle.removeEventListener('pointercancel', up);
      ghost.style.display='none';
      showGridFrame(false);
      if(moved && pw!=null){
        var ok = !rectHit(it.col, it.row, pw, ph, it.desk, [it]);
        if(ok){ it.w = pw; it.h = ph; }
        else{
          var spot = findFree(pw, ph, it.col, it.row, it.desk, [it]);
          if(spot && !rectHit(spot.col, spot.row, pw, ph, it.desk, [it])){ it.col = spot.col; it.row = spot.row; it.w = pw; it.h = ph; }
          else toast('周围没有足够的空格，保持原尺寸');
        }
        posEl(it);
        if(it.wtype==='custom') renderWidget(it);
        toast('「'+(it.name||'小组件')+'」尺寸：'+it.w+' × '+it.h+' 格');
      } else {
        posEl(it);
      }
      it.el._suppressClick = true;
      setTimeout(function(){ it.el._suppressClick = false; }, 60);
    }
    handle.addEventListener('pointermove', mv);
    handle.addEventListener('pointerup', up);
    handle.addEventListener('pointercancel', up);
  });
}

/* ================= 拖拽（多选 / 吸附 / 让位 / 固定到 Dock） ================= */
function displace(mover, gc, gr){
  var rect = {col:gc, row:gr, w:mover.w, h:mover.h};
  items.forEach(function(o){
    if(o===mover || o.desk!==mover.desk) return;
    if(rectsOverlap(rect, o)){
      var spot = findFree(o.w, o.h, o.col, o.row, o.desk, [mover, o], rect);
      if(spot && (spot.col!==o.col || spot.row!==o.row) && !rectsOverlap({col:spot.col,row:spot.row,w:o.w,h:o.h}, rect)){
        o.col = spot.col; o.row = spot.row;
        animatePos(o);
      }
    }
  });
}
function attachDrag(it){
  var el = it.el;
  el.addEventListener('pointerdown', function(e){
    closeMenu();
    if(e.button!==undefined && e.button!==0) return;
    if(e.target.closest('.pbtn') || e.target.closest('.wresize')) return;
    if(e.target.closest('input')) return;
    if(it.type==='widget' && e.target.closest('.w-note')){ setSel([it]); return; }
    /* 靠 click 工作的 widget 表面同样不能被指针捕获吃掉点击：待办行 / 待办输入行 /
       天气（点开城市选择）/ 时钟（点按切换 12/24 小时）——按下只选中，不启动拖动 */
    if(it.type==='widget' && (e.target.closest('.trow') || e.target.closest('.todoAdd') || e.target.closest('.w-weather') || e.target.closest('.w-clock'))){ setSel([it]); return; }
    /* 隔离框小组件（软件注册 / 自定义）：某些 WebView 会把框内按下以 iframe 为目标报给
       宿主页，此时若照常 setPointerCapture，抬起与点击会被重定向到桌面容器，
       框里的按钮（「打开文件管理」等）永远收不到 click。框内区域只选中、永不捕获，
       拖动这类小组件走顶部 wgrip 把手 */
    if(it.type==='widget' && (e.target.tagName==='IFRAME' || (e.target.closest && e.target.closest('iframe')))){ setSel([it]); return; }
    if(e.shiftKey || e.ctrlKey || e.metaKey){ toggleSel(it); return; }
    if(selItems.indexOf(it)<0) setSel([it]);
    var group = selItems.slice();
    var startX = e.clientX, startY = e.clientY, moved = false;
    var origs = group.map(function(g){ return {it:g, col:g.col, row:g.row}; });
    var lastGC = it.col, lastGR = it.row;
    el.setPointerCapture && el.setPointerCapture(e.pointerId);
    function onMove(ev){
      var dx = ev.clientX-startX, dy = ev.clientY-startY;
      if(!moved && Math.hypot(dx,dy) < 5) return;
      if(!moved){
        moved = true;
        document.body.classList.add('item-dragging');
        group.forEach(function(g){ g.el.classList.add('dragging'); g.el.style.transition=''; });
        showGridFrame(true);
      }
      group.forEach(function(g, i){
        var nl = clamp(gridX(origs[i].col) + dx, -10, gridArea.clientWidth - g.w*CELL + 10);
        var nt = clamp(gridY(origs[i].row) + dy, 0, gridArea.clientHeight - g.h*CELL + 10);
        g.el.style.transform = 'translate3d('+(nl - gridX(origs[i].col))+'px,'+(nt - gridY(origs[i].row))+'px,0)';
        g.el._dragL = nl; g.el._dragT = nt;
      });
      var pl = clamp(gridX(it.col) + dx, -10, gridArea.clientWidth - it.w*CELL + 10);
      var pt = clamp(gridY(it.row) + dy, 0, gridArea.clientHeight - it.h*CELL + 10);
      var gc = clamp(Math.round((pl-GOX)/CELL), 0, COLS-it.w), gr = clamp(Math.round((pt-GOY)/CELL), 0, ROWS-it.h);
      var hoverFv = (group.length===1 && (it.type==='app' || it.type==='txt')) ? findOpenFolderAtPoint(ev.clientX, ev.clientY) : null;
      clearAllFolderDropTargets(hoverFv ? hoverFv.win : null);
      if(hoverFv){
        ghost.style.display='none';
        if(hoverFv.it._previewDropAt) hoverFv.it._previewDropAt(ev.clientX, ev.clientY);
      } else {
        ghost.style.display='block';
        ghost.style.left=(gridX(gc)+3)+'px'; ghost.style.top=(gridY(gr)+3)+'px';
        ghost.style.width=(it.w*CELL-6)+'px'; ghost.style.height=(it.h*CELL-6)+'px';
      }
      el._gc = gc; el._gr = gr;
      // 小组件拖动时，被占位的小组件自动让位
      if(group.length===1 && it.type==='widget' && (gc!==lastGC || gr!==lastGR)){
        lastGC = gc; lastGR = gr;
        displace(it, gc, gr);
      }
    }
    function onUp(ev){
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
      el.removeEventListener('pointermove', onMove);
      el.removeEventListener('pointerup', onUp);
      el.removeEventListener('pointercancel', onUp);
      ghost.style.display='none';
      clearAllFolderDropTargets(null);
      showGridFrame(false);
      document.body.classList.remove('item-dragging');
      group.forEach(function(g){
        g.el.classList.remove('dragging');
        if(moved && g.el._dragL != null){
          g.el.style.transition = '';
          g.el.style.left = g.el._dragL+'px';
          g.el.style.top = g.el._dragT+'px';
          g.el._dragL = null; g.el._dragT = null;
        }
        g.el.style.transform = '';
      });
      if(!moved){
        /* 轻点没拖动：大文件夹里的迷你图标点一下直接开应用，「···」点一下打开文件夹 */
        if(ev && ev.clientX!=null){
          var tapEl = document.elementFromPoint(ev.clientX, ev.clientY);
          var mini = tapEl && tapEl.closest ? tapEl.closest('.bfMini') : null;
          if(mini && mini.getAttribute('data-app')){ openApp(mini.getAttribute('data-app')); return; }
          var more = tapEl && tapEl.closest ? tapEl.closest('.bfMore') : null;
          if(more && it.type==='folder'){ openFolder(it); return; }
        }
        return;
      }
      group.forEach(function(g){ g.el._suppressClick = true; setTimeout(function(){ g.el._suppressClick = false; }, 80); });
      var gc = el._gc!=null?el._gc:it.col, gr = el._gr!=null?el._gr:it.row;
      // 直接拖进已打开的特殊文件夹视图 → 收进该文件夹的对应格子里并双向同步
      if(group.length===1 && (it.type==='app' || it.type==='txt') && ev){
        var dropFv = findOpenFolderAtPoint(ev.clientX, ev.clientY);
        if(dropFv && dropDesktopItemIntoOpenFolder(dropFv.it, it, ev.clientX, ev.clientY)){
          deskSaveNow();
          return;
        }
      }
      // 拖到 Dock 上 → 固定
      if(group.length===1 && it.type==='app' && ev && pointInDock(ev.clientX, ev.clientY)){
        animatePos(it);
        pinToDock(it.appId);
        deskSaveNow();
        return;
      }
      // 拖到文件夹上 → 收进文件夹
      if(group.length===1 && (it.type==='app' || it.type==='txt')){
        var trect = {col:gc, row:gr, w:it.w, h:it.h};
        var folder = null;
        items.forEach(function(o){ if(o.type==='folder' && o.desk===it.desk && rectsOverlap(trect, o)) folder = o; });
        if(folder){
          if(it.type==='app'){
            if(folder.apps.indexOf(it.appId)>=0){ animatePos(it); toast('「'+appById(it.appId).name+'」已经在文件夹「'+folder.name+'」里了'); return; }
            folder.apps.push(it.appId);
            ensureFolderEntries(folder).push({kind:'app', appId:it.appId});
            var fname = folder.name;
            removeItem(it);
            renderFolderCell(folder);
            if(folder._refreshView) folder._refreshView();
            deskSaveNow();
            toast('「'+appById(it.appId).name+'」已收进文件夹「'+fname+'」');
            return;
          } else if(it.type==='txt'){
            ensureFolderEntries(folder).push({kind:'txt', name:it.name, content:it.content||''});
            var fnameT = folder.name;
            removeItem(it);
            renderFolderCell(folder);
            if(folder._refreshView) folder._refreshView();
            deskSaveNow();
            toast('「'+it.name+'」已收进文件夹「'+fnameT+'」');
            return;
          }
        }
        // 拖到另一个应用图标上 → 两者组成新文件夹
        var target = null;
        if(it.type==='app'){
          items.forEach(function(o){ if(o!==it && o.type==='app' && o.desk===it.desk && rectsOverlap(trect, o)) target = o; });
        }
        if(target){
          var desk2 = it.desk, tc = target.col, tr = target.row;
          var ids = [target.appId, it.appId];
          var fnames = items.filter(function(x){ return x.type==='folder'; }).length;
          removeItem(target);
          removeItem(it);
          var nf = makeItem({type:'folder', name: fnames ? '文件夹 '+(fnames+1) : '文件夹', col:tc, row:tr, w:1, h:1, desk:desk2, apps:ids});
          renderFolderCell(nf);
          pulseItem(nf);
          deskSaveNow();
          toast('已组成文件夹「'+nf.name+'」，双击打开，拖更多图标到它上面继续收纳');
          return;
        }
      }
      // 文件夹拖到文件夹上 → 合并
      if(group.length===1 && it.type==='folder'){
        var trect2 = {col:gc, row:gr, w:it.w, h:it.h};
        var other = null;
        items.forEach(function(o){ if(o!==it && o.type==='folder' && o.desk===it.desk && rectsOverlap(trect2, o)) other = o; });
        if(other){
          it.apps.forEach(function(aid){ if(other.apps.indexOf(aid)<0) other.apps.push(aid); });
          var oname = other.name;
          removeItem(it);
          renderFolderCell(other);
          deskSaveNow();
          toast('两个文件夹已合并为「'+oname+'」');
          return;
        }
      }
      // 整组按相同格位移落位，逐个解冲突
      var dc = gc - it.col, dr = gr - it.row;
      var remaining = group.slice();
      origs.forEach(function(o){
        var idx = remaining.indexOf(o.it); if(idx>=0) remaining.splice(idx,1);
        var tc = clamp(o.col + dc, 0, COLS - o.it.w);
        var tr = clamp(o.row + dr, 0, ROWS - o.it.h);
        var excl = remaining.concat([o.it]);
        if(rectHit(tc, tr, o.it.w, o.it.h, o.it.desk, excl)){
          var spot = findFree(o.it.w, o.it.h, tc, tr, o.it.desk, excl);
          tc = spot.col; tr = spot.row;
        }
        o.it.col = tc; o.it.row = tr;
      });
      group.forEach(function(g){ animatePos(g); });
      deskSaveNow();
    }
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    el.addEventListener('pointermove', onMove);
    el.addEventListener('pointerup', onUp);
    el.addEventListener('pointercancel', onUp);
  });
  el.addEventListener('click', function(e){ if(el._suppressClick){ e.stopPropagation(); e.preventDefault(); } }, true);
}

/* 框选 */
gridArea.addEventListener('pointerdown', function(e){
  if(e.target!==gridArea || (e.button!==undefined && e.button!==0)) return;
  e.preventDefault();
  closeMenu();
  try { gridArea.setPointerCapture(e.pointerId); } catch(err) {}
  var rect = gridArea.getBoundingClientRect();
  var sx = e.clientX - rect.left, sy = e.clientY - rect.top;
  var base = (e.shiftKey || e.ctrlKey || e.metaKey) ? selItems.slice() : [];
  var banding = false;
  function mv(ev){
    ev.preventDefault();
    var cx = ev.clientX - rect.left, cy = ev.clientY - rect.top;
    if(!banding && Math.hypot(cx-sx, cy-sy) < 4) return;
    banding = true;
    showGridFrame(true);
    var x1 = Math.min(sx,cx), y1 = Math.min(sy,cy), x2 = Math.max(sx,cx), y2 = Math.max(sy,cy);
    band.style.display='block';
    band.style.left=x1+'px'; band.style.top=y1+'px';
    band.style.width=(x2-x1)+'px'; band.style.height=(y2-y1)+'px';
    var hits = base.slice();
    items.forEach(function(it){
      if(it.desk!==curDesk) return;
      var ix1 = gridX(it.col), iy1 = gridY(it.row), ix2 = ix1 + it.w*CELL, iy2 = iy1 + it.h*CELL;
      if(ix1 < x2 && ix2 > x1 && iy1 < y2 && iy2 > y1 && hits.indexOf(it)<0) hits.push(it);
    });
    setSel(hits);
  }
  function up(ev){
    if(ev) ev.preventDefault();
    try { gridArea.releasePointerCapture(e.pointerId); } catch(err) {}
    window.removeEventListener('pointermove', mv);
    window.removeEventListener('pointerup', up);
    window.removeEventListener('pointercancel', up);
    band.style.display='none';
    showGridFrame(false);
    if(!banding) setSel([]);
  }
  window.addEventListener('pointermove', mv);
  window.addEventListener('pointerup', up);
  window.addEventListener('pointercancel', up);
});


/* ================= 鼠标样式（与沧海系统配对） =================
   与入口「沧海系统」同一套样式库：内置箭头 / 圆点 + 共享自定义库，
   同样的点击位置默认值、同样的默认小星星代码、同样的过滤规则。
   在这块桌面上选中的指针，就是桌面此刻真正用的光标。 */
var CURSOR_ART = {
  arrow:'<svg viewBox="0 0 20 20" fill="#fff" stroke="#111" stroke-width="1.4"><path d="M0 0l14 7.5-6.2 1.6L4.5 16 0 0z" stroke-linejoin="round"/></svg>',
  dot:'<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="6.4" fill="#fff" stroke="#111" stroke-width="1.6"/><circle cx="12" cy="12" r="1.8" fill="#111"/></svg>',
  block:'<svg viewBox="0 0 24 24"><rect x="4.5" y="4.5" width="15" height="15" rx="2.4" fill="#fff" stroke="#111" stroke-width="1.6"/><rect x="10" y="10" width="4" height="4" rx="1" fill="#111"/></svg>'
};
var CURSOR_NAMES = { arrow:'箭头', dot:'圆点', block:'方块' };
/* 内置样式 id ↔ Native 指针预置（0箭头 / 1圆点 / 2方块），两边同一套编号 */
var CURSOR_STYLE = { arrow:0, dot:1, block:2 };
var CURSOR_PLUS = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>';
var CURSOR_STAR_NAME = '小星星';
var CURSOR_STAR_SVG = '<svg viewBox="0 0 24 24" fill="#ffd34d" stroke="#7a5200" stroke-width="1.1" stroke-linejoin="round"><path d="M12 3.1l2.65 5.37 5.93.86-4.29 4.18 1.01 5.9L12 16.63l-5.3 2.78 1.01-5.9L3.42 9.33l5.93-.86L12 3.1z"/></svg>';
var HOTSPOT_DEFAULTS = { arrow:{x:0,y:0}, dot:{x:50,y:50}, block:{x:50,y:50}, custom:{x:50,y:50} };
function sanitizeCursorSvg(code){
  if(!code) return '';
  var t = String(code).trim().slice(0,6000);
  if(!/^<svg[\s>]/i.test(t)) return '';
  var end = t.toLowerCase().lastIndexOf('</svg>');
  if(end===-1) return '';
  t = t.slice(0, end+6);
  t = t.replace(/<script[\s\S]*?(<\/script>|$)/gi, '')
       .replace(/<foreignObject[\s\S]*?(<\/foreignObject>|$)/gi, '')
       .replace(/<iframe[\s\S]*?(<\/iframe>|$)/gi, '')
       .replace(/\son\w+\s*=\s*"[^"]*"/gi, '')
       .replace(/\son\w+\s*=\s*'[^']*'/gi, '')
       .replace(/\son\w+\s*=\s*[^\s>]+/gi, '')
       .replace(/(href|xlink:href)\s*=\s*"\s*(javascript:|data:text\/html|https?:)[^"]*"/gi, '$1="#"')
       .replace(/(href|xlink:href)\s*=\s*'\s*(javascript:|data:text\/html|https?:)[^']*'/gi, "$1='#'");
  t = t.replace(/^(<svg[^>]*?)\s(?:width|height)\s*=\s*"[^"]*"/i, '$1')
       .replace(/^(<svg[^>]*?)\s(?:width|height)\s*=\s*"[^"]*"/i, '$1');
  return /^<svg[\s>]/i.test(t) ? t : '';
}
var cursorLib = [];           /* 共享自定义样式库：{id,name,svg,hotX,hotY} */
var cursorSel = 'arrow';      /* 'arrow' | 'dot' | 'lib:<id>' */
var cursorSize = 28;          /* px：页面 CSS 光标 + Native 自绘光标共用 */
var cursorSpeed = 1.0;        /* 指针移动倍率 0.4 ~ 2.6，Native 手势实时读取 */
function findCursorLib(id){ for(var i=0;i<cursorLib.length;i++) if(cursorLib[i].id===id) return cursorLib[i]; return null; }
function cursorCurrent(){
  if(cursorSel.indexOf('lib:')===0){
    var it = findCursorLib(cursorSel.slice(4));
    if(it) return { name:(it.name||'').trim()||'自定义指针', svg:sanitizeCursorSvg(it.svg)||CURSOR_PLUS, hotX:it.hotX, hotY:it.hotY };
  }
  if(cursorSel==='dot') return { name:'圆点', svg:CURSOR_ART.dot, hotX:HOTSPOT_DEFAULTS.dot.x, hotY:HOTSPOT_DEFAULTS.dot.y };
  if(cursorSel==='block') return { name:'方块', svg:CURSOR_ART.block, hotX:50, hotY:50 };
  return { name:'箭头', svg:CURSOR_ART.arrow, hotX:HOTSPOT_DEFAULTS.arrow.x, hotY:HOTSPOT_DEFAULTS.arrow.y };
}
function applyCursor(){
  var cur = cursorCurrent();
  var cssSize = clamp(cursorSize, 1, 128);
  var sized = cur.svg.replace(/<svg/i, '<svg width="'+cssSize+'" height="'+cssSize+'"');
  var hx = Math.round(cssSize * cur.hotX / 100), hy = Math.round(cssSize * cur.hotY / 100);
  var val = 'url("data:image/svg+xml;utf8,'+encodeURIComponent(sized)+'") '+hx+' '+hy+', auto';
  document.body.style.cursor = val;
  document.getElementById('desktop').style.cursor = val;
  document.querySelectorAll('.win .curRow').forEach(function(row){ renderCursorTiles(row); });
  document.querySelectorAll('.win .csVal').forEach(function(v){ if(!v.querySelector('input')) v.textContent = String(cursorSize); });
  document.querySelectorAll('.win .ssVal').forEach(function(v){ v.textContent = (Math.round(cursorSpeed*10)/10).toFixed(1)+'×'; });
  document.querySelectorAll('.win .curLibCount').forEach(function(v){ v.textContent = cursorLib.length ? cursorLib.length+' 个自定义' : '还没有自定义'; });
}
function onPointerLockStateChanged(){
  var locked = !!(document.pointerLockElement);
  if(!locked){
    document.querySelectorAll('iframe.appFrame').forEach(function(fr){
      try{
        var doc = fr.contentDocument || (fr.contentWindow && fr.contentWindow.document);
        if(doc && doc.pointerLockElement) locked = true;
      }catch(e){}
    });
  }
  document.body.classList.toggle('pointerLocked', locked);
  try{ if(Canghai && Canghai.setPointerLocked) Canghai.setPointerLocked(locked); }catch(e){}
}
document.addEventListener('pointerlockchange', onPointerLockStateChanged);
document.addEventListener('mozpointerlockchange', onPointerLockStateChanged);
document.addEventListener('webkitpointerlockchange', onPointerLockStateChanged);
function selectCursor(v){
  cursorSel = v;
  applyCursor();
  /* 同步唯一数据源：外屏的系统级指针与手机端卡片都读这一份 */
  try{
    var nid = v.indexOf('lib:')===0 ? v.slice(4) : v;
    if(Canghai && Canghai.selectCursor) Canghai.selectCursor(nid);
  }catch(e){}
  toast('鼠标样式已换成「'+cursorCurrent().name+'」，手机和外屏共用这一套');
}
function cursorTilesHTML(){
  var h = '';
  ['arrow','dot','block'].forEach(function(k){
    h += '<button type="button" class="curTile'+(cursorSel===k?' sel':'')+'" data-cur="'+k+'"><span class="ca">'+CURSOR_ART[k]+'</span><span class="cl">'+CURSOR_NAMES[k]+'</span></button>';
  });
  cursorLib.forEach(function(it){
    var nm = (it.name||'').trim()||'自定义指针';
    h += '<button type="button" class="curTile'+(cursorSel==='lib:'+it.id?' sel':'')+'" data-cur="lib:'+it.id+'"><span class="ca">'+(sanitizeCursorSvg(it.svg)||CURSOR_PLUS)+'</span><span class="cl">'+escapeHtml(nm)+'</span></button>';
  });
  h += '<button type="button" class="curTile add" data-cur="__add"><span class="ca">'+CURSOR_PLUS+'</span><span class="cl">自定义</span></button>';
  return h;
}
function renderCursorTiles(row){
  row.innerHTML = cursorTilesHTML();
  row.querySelectorAll('[data-cur]').forEach(function(b){
    b.addEventListener('click', function(){
      var v = b.getAttribute('data-cur');
      if(v==='__add'){ openCursorEditor('add', null); return; }
      selectCursor(v);
    });
  });
}
/* ---- 样式管理弹窗 ---- */
var cursorLibModal = document.getElementById('cursorLibModal');
function bindCursorLibRows(scope){
  scope.querySelectorAll('[data-libsel]').forEach(function(row){
    function activate(){
      var v = row.getAttribute('data-libsel');
      if(!v || v===cursorSel){ if(v===cursorSel){ selectCursor(v); renderCursorManage(); } return; }
      selectCursor(v);
      renderCursorManage();
    }
    row.addEventListener('click', activate);
    row.addEventListener('keydown', function(e){
      if(e.key==='Enter' || e.key===' '){ e.preventDefault(); activate(); }
    });
  });
}
function renderCursorManage(){
  var bl = document.getElementById('cursorBuiltinList');
  bl.innerHTML = ['arrow','dot','block'].map(function(k){
    var hd = HOTSPOT_DEFAULTS[k];
    var sel = cursorSel===k;
    return '<div class="cur-lib-row'+(sel?' sel':'')+'" data-libsel="'+k+'" role="button" tabindex="0" title="点击使用"><span class="cur-lib-art">'+CURSOR_ART[k]+'</span><span class="cur-lib-info"><b>'+CURSOR_NAMES[k]+'</b><small>内置 · 点击位置 '+hd.x+'% · '+hd.y+'%</small></span>'+(sel?'<span class="tag use">使用中</span>':'')+'<span class="tag gray">内置</span></div>';
  }).join('');
  bindCursorLibRows(bl);
  var list = document.getElementById('cursorLibList');
  if(!cursorLib.length){
    list.innerHTML = '<div class="cur-lib-empty">还没有自定义样式<br>点下面的「添加自定义样式」，默认就是小星星</div>';
  } else {
    list.innerHTML = cursorLib.map(function(it){
      var nm = (it.name||'').trim()||'自定义指针';
      var sel = cursorSel==='lib:'+it.id;
      return '<div class="cur-lib-row'+(sel?' sel':'')+'" data-libsel="lib:'+it.id+'" role="button" tabindex="0" title="点击使用"><span class="cur-lib-art">'+(sanitizeCursorSvg(it.svg)||CURSOR_PLUS)+'</span><span class="cur-lib-info"><b>'+escapeHtml(nm)+'</b><small>点击位置 '+it.hotX+'% · '+it.hotY+'%</small></span>'+(sel?'<span class="tag use">使用中</span>':'')+'<button type="button" class="cur-lib-btn" data-libedit="'+it.id+'">编辑</button><button type="button" class="cur-lib-btn danger" data-libdel="'+it.id+'">删除</button></div>';
    }).join('');
    bindCursorLibRows(list);
    list.querySelectorAll('[data-libedit]').forEach(function(b){
      b.addEventListener('click', function(e){ e.stopPropagation(); var it = findCursorLib(b.getAttribute('data-libedit')); if(it) openCursorEditor('edit', it); });
    });
    list.querySelectorAll('[data-libdel]').forEach(function(b){
      b.addEventListener('click', function(e){
        e.stopPropagation();
        var id = b.getAttribute('data-libdel');
        var it = findCursorLib(id);
        var wasActive = cursorSel==='lib:'+id;
        cursorLib = cursorLib.filter(function(x){ return x.id!==id; });
        if(wasActive){ cursorSel = 'arrow'; }
        applyCursor();
        renderCursorManage();
        toast('已删除鼠标样式「'+((it&&it.name)||'自定义指针')+'」'+(wasActive?'，桌面已换回箭头':''));
      });
    });
  }
}
function openCursorManage(){ renderCursorManage(); cursorLibModal.classList.add('open'); }
function closeCursorManage(){ cursorLibModal.classList.remove('open'); }
document.getElementById('cursorLibAdd').addEventListener('click', function(){ openCursorEditor('add', null); });

/* ================= 关于沧海系统 / 在线检查更新 =================
   左上角品牌只留图标 + 名字，点它开这个类似 macOS「关于本机」的小窗。
   HTTPS 请求放在 Native（桌面是 file:// 页面，直接 fetch 会被同源策略挡住）。 */
var aboutModal = document.getElementById('aboutModal');
var aboutBtn = document.getElementById('aboutBtn');
var aboutUpdateUrl = '';
function abRow(k, v){
  return '<div class="abRow"><span class="k">'+escapeHtml(k)+'</span><span class="v">'
    + escapeHtml(String(v == null ? '—' : v)) + '</span></div>';
}
function openAbout(){
  var d = {};
  try{ d = JSON.parse((Canghai && Canghai.getAboutInfo && Canghai.getAboutInfo()) || '{}') || {}; }catch(e){}
  var icon = document.querySelector('#menubar .brand img');
  var iconEl = document.getElementById('aboutIcon');
  if(icon && icon.src){ iconEl.src = icon.src; iconEl.style.visibility = ''; }
  else iconEl.style.visibility = 'hidden';
  var ver = d.version || '1.0';
  document.getElementById('aboutVersion').textContent =
    '版本 ' + ver + (d.apkVersion ? ' · APK ' + d.apkVersion : '');
  document.getElementById('aboutRows').innerHTML =
      abRow('版本号', ver)
    + abRow('手机型号', d.model || '—')
    + abRow('Android', d.android || '—')
    + abRow('沧海软件', (d.htmlApps || 0) + ' 个')
    + abRow('安装软件', (d.installedApps || 0) + ' 个');
  var msg = document.getElementById('aboutMsg');
  msg.textContent = ''; msg.className = 'abMsg';
  document.getElementById('aboutDownload').style.display = 'none';
  aboutModal.classList.add('open');
}
function closeAbout(){ aboutModal.classList.remove('open'); }
function checkForUpdate(){
  var btn = document.getElementById('aboutCheck');
  var msg = document.getElementById('aboutMsg');
  var dl = document.getElementById('aboutDownload');
  btn.disabled = true; btn.textContent = '检查中…';
  msg.className = 'abMsg'; msg.textContent = '正在获取更新信息…';
  dl.style.display = 'none';
  /* 先把「检查中」画出来，再做同步的 Native 网络请求 */
  setTimeout(function(){
    var r = {};
    try{ r = JSON.parse((Canghai && Canghai.checkUpdate && Canghai.checkUpdate()) || '{}') || {}; }catch(e){}
    btn.disabled = false; btn.textContent = '检查更新';
    if(!r.ok){
      msg.className = 'abMsg err';
      msg.textContent = '无法获取更新信息，请检测网络';
      toast('无法获取更新信息，请检测网络');
      return;
    }
    if(r.hasUpdate){
      var nv = r.versionName || r.remote || '新版本';
      msg.className = 'abMsg ok';
      msg.textContent = '发现新版本 ' + nv + (r.notes ? '\n' + r.notes : '');
      aboutUpdateUrl = r.url || '';
      if(aboutUpdateUrl) dl.style.display = '';
      toast('发现新版本 ' + nv);
    } else {
      msg.className = 'abMsg ok';
      msg.textContent = '已经是最新版本';
      aboutUpdateUrl = '';
      if(dl) dl.style.display = 'none';
      toast('已经是最新版本');
    }
  }, 30);
}
if(aboutBtn){
  aboutBtn.addEventListener('click', openAbout);
  aboutBtn.addEventListener('keydown', function(e){
    if(e.key === 'Enter' || e.key === ' '){ e.preventDefault(); openAbout(); }
  });
}
document.getElementById('aboutClose').addEventListener('click', closeAbout);
aboutModal.addEventListener('click', function(e){ if(e.target === aboutModal) closeAbout(); });
document.getElementById('aboutCheck').addEventListener('click', checkForUpdate);
document.getElementById('aboutDownload').addEventListener('click', function(){
  if(aboutUpdateUrl){ try{ Canghai.openUpdateUrl(aboutUpdateUrl); }catch(e){} }
});
document.addEventListener('keydown', function(e){
  if(e.key === 'Escape' && aboutModal.classList.contains('open')){ closeAbout(); e.preventDefault(); }
});

/* ---- 自定义指针编辑弹窗（草稿，保存才生效） ---- */
var cursorModal = document.getElementById('cursorModal');
var curDraft = null;
function openCursorEditor(mode, it){
  if(mode==='edit' && it){
    curDraft = { mode:'edit', id:it.id, name:it.name||'', svg:it.svg||'', hotX:it.hotX, hotY:it.hotY };
    document.getElementById('curEditTitle').textContent = '编辑指针 · '+((it.name||'').trim()||'自定义指针');
    document.getElementById('curEditSub').textContent = '改名字、代码或点击位置，点保存才用上，取消就不改。';
  } else {
    curDraft = { mode:'add', id:null, name:CURSOR_STAR_NAME, svg:CURSOR_STAR_SVG, hotX:50, hotY:50 };
    document.getElementById('curEditTitle').textContent = '添加自定义指针';
    document.getElementById('curEditSub').textContent = '点一次就多一个，数量不限：代码框里先放了一段小星星的 SVG，名字也写好了，不喜欢就换成自己的；定好点击位置，点保存就存进样式库，桌面先用上。';
  }
  document.getElementById('curName').value = curDraft.name;
  document.getElementById('curSvg').value = curDraft.svg;
  document.getElementById('curErr').textContent = '';
  renderCursorDraft();
  cursorModal.classList.add('open');
}
function closeCursorEditor(){ cursorModal.classList.remove('open'); curDraft = null; }
function renderCursorDraft(){
  if(!curDraft) return;
  var clean = sanitizeCursorSvg(curDraft.svg);
  document.getElementById('curPrevArt').innerHTML = clean || CURSOR_PLUS;
  var ring = document.getElementById('curPrevRing');
  ring.style.left = 'calc(50% + '+((curDraft.hotX-50)*0.64)+'px)';
  ring.style.top = 'calc(50% + '+((curDraft.hotY-50)*0.64)+'px)';
  document.getElementById('curHotXVal').textContent = curDraft.hotX+'%';
  document.getElementById('curHotYVal').textContent = curDraft.hotY+'%';
  document.querySelectorAll('#curPresets button').forEach(function(b){
    var xy = b.getAttribute('data-hp').split(',');
    b.classList.toggle('sel', parseInt(xy[0],10)===curDraft.hotX && parseInt(xy[1],10)===curDraft.hotY);
  });
}
document.getElementById('curName').addEventListener('input', function(){ if(curDraft) curDraft.name = this.value; });
document.getElementById('curSvg').addEventListener('input', function(){ if(curDraft){ curDraft.svg = this.value; renderCursorDraft(); } });
document.querySelectorAll('#curPresets button').forEach(function(b){
  b.addEventListener('click', function(){
    if(!curDraft) return;
    var xy = b.getAttribute('data-hp').split(',');
    curDraft.hotX = parseInt(xy[0],10); curDraft.hotY = parseInt(xy[1],10);
    renderCursorDraft();
  });
});
document.querySelectorAll('[data-hs]').forEach(function(b){
  b.addEventListener('click', function(){
    if(!curDraft) return;
    var parts = b.getAttribute('data-hs').split(','), d = parseInt(parts[1],10);
    if(parts[0]==='x') curDraft.hotX = clamp(curDraft.hotX + d, 0, 100);
    else curDraft.hotY = clamp(curDraft.hotY + d, 0, 100);
    renderCursorDraft();
  });
});
document.getElementById('curSave').addEventListener('click', function(){
  if(!curDraft) return;
  var clean = sanitizeCursorSvg(curDraft.svg);
  if(!clean){ document.getElementById('curErr').textContent = '先粘一段有效的 SVG 代码（以 <svg 开头）再保存。'; return; }
  var nm = (document.getElementById('curName').value||'').trim() || '自定义指针';
  var savedId = null;
  if(curDraft.mode==='edit'){
    var it = findCursorLib(curDraft.id);
    if(it){ it.name = nm; it.svg = clean; it.hotX = curDraft.hotX; it.hotY = curDraft.hotY; savedId = it.id; }
    toast('鼠标样式「'+nm+'」已更新');
  } else {
    var nid = 'c'+Date.now().toString(36);
    cursorLib.push({ id:nid, name:nm, svg:clean, hotX:curDraft.hotX, hotY:curDraft.hotY });
    cursorSel = 'lib:'+nid;
    savedId = nid;
    toast('已添加鼠标样式「'+nm+'」，存进样式库，桌面先用上了');
  }
  /* 落进唯一数据源：手机端「鼠标样式管理」里立刻能看到这一条 */
  try{
    if(Canghai && Canghai.saveCursor){
      var realId = Canghai.saveCursor(savedId || null, nm, clean, curDraft.hotX, curDraft.hotY);
      if(realId && !curDraft.id){ /* 原生分配了正式 id，把临时 id 换掉 */ }
    }
    if(Canghai && Canghai.selectCursor) Canghai.selectCursor(savedId || 'arrow');
  }catch(e){}
  closeCursorEditor();
  applyCursor();
  if(cursorLibModal.classList.contains('open')) renderCursorManage();
});
document.querySelectorAll('[data-cur-close]').forEach(function(el){
  el.addEventListener('click', function(){
    if(el.getAttribute('data-cur-close')==='lib') closeCursorManage(); else closeCursorEditor();
  });
});

/* ================= 漂浮便签（便签应用） =================
   点一次便签应用，就在桌面上贴一张漂浮便签：纸被一枚顶针钉在顶部正中，
   整张纸绕顶针这个支点转。拖动走的是弹簧物理——位置跟随带一点滞后和
   过冲，倾角由水平速度驱动，松手后像挂在针上一样来回摆几下、慢慢停稳。
   便签只存在于本次打开期间（静态页不存本地）。 */

var stickyLayer = document.getElementById('stickyLayer');
var stickyZ = 1, stickyCount = 0;
/* 便签纸色：和 .stickyNote.c0~c3 的底色一一对应，右键菜单里选 */
var STICKY_COLORS = [
  {name:'黄色', dot:'#ffef9d'},
  {name:'天蓝', dot:'#d2e6fc'},
  {name:'粉色', dot:'#ffd8e4'},
  {name:'绿色', dot:'#d6f0c6'}
];
/* 删除便签要二次确认：右键菜单点「删除便签」先把这张便签的关闭动作存起来，弹窗里点确认才真删 */
var stickyDelModal = document.getElementById('stickyDelModal');
var stickyDelPending = null;
function askDeleteSticky(fn){ stickyDelPending = fn; stickyDelModal.classList.add('open'); }
function closeStickyDel(){ stickyDelPending = null; stickyDelModal.classList.remove('open'); }
document.getElementById('stickyDelBk').addEventListener('click', closeStickyDel);
document.getElementById('stickyDelCancel').addEventListener('click', closeStickyDel);
document.getElementById('stickyDelOk').addEventListener('click', function(){
  var fn = stickyDelPending; closeStickyDel(); if(fn) fn();
});
/* 删除小组件要二次确认：右键菜单点「删除小组件」先记下这一个，弹窗里点确认才真删；
   小组件本身不再有右上角 ✕ 按钮，左键点它不会冒出删除入口 */
var widgetDelModal = document.getElementById('widgetDelModal');
var widgetDelPending = null;
function askDeleteWidget(it){
  widgetDelPending = it;
  document.getElementById('widgetDelName').textContent = it.name || '小组件';
  widgetDelModal.classList.add('open');
}
function closeWidgetDel(){ widgetDelPending = null; widgetDelModal.classList.remove('open'); }
document.getElementById('widgetDelBk').addEventListener('click', closeWidgetDel);
document.getElementById('widgetDelCancel').addEventListener('click', closeWidgetDel);
document.getElementById('widgetDelOk').addEventListener('click', function(){
  var it = widgetDelPending; closeWidgetDel();
  if(it){ removeItem(it); toast('已删除小组件「'+(it.name||'小组件')+'」'); }
});

/* ================= 便签外观颜色选择器（RGB 滑块 + 黑/白字切换） ================= */
var stickyColorModal = document.getElementById('stickyColorModal');
var activeStickyTarget = null;
var activeStickyEl = null;
var curStickyTextColor = '#1a1a1a';
var curStickyFontSize = 1;

function parseRgb(str, defR, defG, defB){
  if(!str) return [defR, defG, defB];
  var m = str.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/i);
  if(m) return [+m[1], +m[2], +m[3]];
  return [defR, defG, defB];
}

function updateStickyModalPreview(){
  var r = document.getElementById('scSliderR').value;
  var g = document.getElementById('scSliderG').value;
  var b = document.getElementById('scSliderB').value;
  document.getElementById('scValR').textContent = r;
  document.getElementById('scValG').textContent = g;
  document.getElementById('scValB').textContent = b;
  var prev = document.getElementById('stickyColorPreview');
  if(prev){
    prev.style.background = 'rgb('+r+','+g+','+b+')';
    prev.style.color = curStickyTextColor;
  }
  var pt = document.getElementById('scPreviewText');
  if(pt) pt.style.fontSize = (13 * curStickyFontSize) + 'px';
}

function openStickyColorModal(st, el){
  activeStickyTarget = st;
  activeStickyEl = el;
  var defaults = [
    [255, 239, 157],
    [210, 230, 252],
    [255, 216, 228],
    [214, 240, 198]
  ];
  var def = defaults[(st.color || 0) % 4] || [255, 239, 157];
  var rgb = parseRgb(st.bg, def[0], def[1], def[2]);
  document.getElementById('scSliderR').value = rgb[0];
  document.getElementById('scSliderG').value = rgb[1];
  document.getElementById('scSliderB').value = rgb[2];
  curStickyTextColor = st.textColor || '#1a1a1a';
  document.getElementById('scTextDark').classList.toggle('sel', curStickyTextColor === '#1a1a1a');
  document.getElementById('scTextLight').classList.toggle('sel', curStickyTextColor !== '#1a1a1a');
  curStickyFontSize = st.fontSize || 1;
  var fsRow = document.getElementById('scFsRow');
  if(fsRow) fsRow.querySelectorAll('button').forEach(function(b){ b.classList.toggle('sel', Number(b.getAttribute('data-fs')) === curStickyFontSize); });
  updateStickyModalPreview();
  if(stickyColorModal) stickyColorModal.classList.add('open');
}

function closeStickyColorModal(){
  activeStickyTarget = null;
  activeStickyEl = null;
  if(stickyColorModal) stickyColorModal.classList.remove('open');
}

['scSliderR', 'scSliderG', 'scSliderB'].forEach(function(id){
  var el = document.getElementById(id);
  if(el) el.addEventListener('input', updateStickyModalPreview);
});

var scDarkBtn = document.getElementById('scTextDark');
if(scDarkBtn) scDarkBtn.addEventListener('click', function(){
  curStickyTextColor = '#1a1a1a';
  scDarkBtn.classList.add('sel');
  var light = document.getElementById('scTextLight');
  if(light) light.classList.remove('sel');
  updateStickyModalPreview();
});

var scLightBtn = document.getElementById('scTextLight');
if(scLightBtn) scLightBtn.addEventListener('click', function(){
  curStickyTextColor = '#ffffff';
  scLightBtn.classList.add('sel');
  var dark = document.getElementById('scTextDark');
  if(dark) dark.classList.remove('sel');
  updateStickyModalPreview();
});

var scFsRow = document.getElementById('scFsRow');
if(scFsRow) scFsRow.addEventListener('click', function(e){
  var b = e.target.closest ? e.target.closest('button[data-fs]') : null;
  if(!b) return;
  curStickyFontSize = Number(b.getAttribute('data-fs')) || 1;
  scFsRow.querySelectorAll('button').forEach(function(x){ x.classList.toggle('sel', x === b); });
  updateStickyModalPreview();
});

var scCancelBtn = document.getElementById('stickyColorCancel');
if(scCancelBtn) scCancelBtn.addEventListener('click', closeStickyColorModal);
var scBk = document.getElementById('stickyColorBk');
if(scBk) scBk.addEventListener('click', closeStickyColorModal);

var scOkBtn = document.getElementById('stickyColorOk');
if(scOkBtn) scOkBtn.addEventListener('click', function(){
  if(!activeStickyTarget){ closeStickyColorModal(); return; }
  var r = document.getElementById('scSliderR').value;
  var g = document.getElementById('scSliderG').value;
  var b = document.getElementById('scSliderB').value;
  activeStickyTarget.bg = 'rgb('+r+','+g+','+b+')';
  activeStickyTarget.textColor = curStickyTextColor;
  activeStickyTarget.fontSize = curStickyFontSize;
  if(activeStickyEl){
    var paper = activeStickyEl.querySelector('.snPaper');
    if(paper) paper.style.background = activeStickyTarget.bg;
    var ta = activeStickyEl.querySelector('.snText');
    if(ta) ta.style.color = activeStickyTarget.textColor;
    if(curStickyFontSize === 1) activeStickyEl.style.removeProperty('--snfs');
    else activeStickyEl.style.setProperty('--snfs', curStickyFontSize);
  }
  deskSaveSoon();
  toast('便签外观已更新');
  closeStickyColorModal();
});

/* ================= 待办清单右键添加事项模态弹窗 ================= */
var todoAddModal = document.getElementById('todoAddModal');
var activeTodoWidget = null;

window.openTodoAddModal = function(it){
  activeTodoWidget = it;
  var inp = document.getElementById('todoAddInput');
  if(inp) inp.value = '';
  if(todoAddModal) todoAddModal.classList.add('open');
  setTimeout(function(){ if(inp) inp.focus(); }, 120);
};

function closeTodoAddModal(){
  activeTodoWidget = null;
  if(todoAddModal) todoAddModal.classList.remove('open');
}

function submitTodoAdd(){
  var inp = document.getElementById('todoAddInput');
  var val = inp ? inp.value.trim() : '';
  if(!val){ toast('请输入待办事项内容'); return; }
  if(!activeTodoWidget){ closeTodoAddModal(); return; }
  if(!activeTodoWidget.todos) activeTodoWidget.todos = [];
  activeTodoWidget.todos.push({t: val, done: false});
  var wb = activeTodoWidget.el ? activeTodoWidget.el.querySelector('.wbody') : null;
  if(wb){
    wb.innerHTML = '<div class="wscale">' + todoHTML(activeTodoWidget) + '</div>';
    activeTodoWidget._scaleEl = wb.querySelector('.wscale');
    wireTodo(activeTodoWidget);
    fitWidgetScale(activeTodoWidget);
  }
  deskSaveSoon();
  toast('已添加待办：「' + val + '」');
  closeTodoAddModal();
}

var todoOkBtn = document.getElementById('todoAddOk');
if(todoOkBtn) todoOkBtn.addEventListener('click', submitTodoAdd);
var todoCancelBtn = document.getElementById('todoAddCancel');
if(todoCancelBtn) todoCancelBtn.addEventListener('click', closeTodoAddModal);
var todoBk = document.getElementById('todoAddBk');
if(todoBk) todoBk.addEventListener('click', closeTodoAddModal);
var todoInp = document.getElementById('todoAddInput');
if(todoInp) todoInp.addEventListener('keydown', function(e){
  if(e.key === 'Enter'){ e.preventDefault(); submitTodoAdd(); }
  else if(e.key === 'Escape'){ closeTodoAddModal(); }
});

/* ================= 自定义小组件居中弹窗 ================= */
window.openCustomWidgetModal = function(title, htmlContent){
  var m = document.getElementById('customWidgetModal');
  if(!m) return;
  var t = document.getElementById('customWidgetTitle');
  if(t) t.textContent = title || '小组件操作';
  var b = document.getElementById('customWidgetBody');
  if(b) b.innerHTML = htmlContent || '';
  m.classList.add('open');
};

window.closeCustomWidgetModal = function(){
  var m = document.getElementById('customWidgetModal');
  if(m) m.classList.remove('open');
};

var cwCloseBtn = document.getElementById('customWidgetClose');
if(cwCloseBtn) cwCloseBtn.addEventListener('click', window.closeCustomWidgetModal);
var cwBk = document.getElementById('customWidgetBk');
if(cwBk) cwBk.addEventListener('click', window.closeCustomWidgetModal);
/* 天气选城市：点小组件或右键「编辑」打开。顶部有输入框，自己打城市中文名、拼音或
   英文就实时匹配（带不带「市」都行），点一行立即换；回车直接选第一个匹配。
   没收录的城市不编数据，空结果会如实说明收录范围。 */
var cityModal = document.getElementById('cityModal');
var cityPickItem = null;
var cityFirstIdx = -1;
function pickCity(idx){
  var target = cityPickItem;
  closeCityPicker();
  if(target && CITIES[idx]){ target.cityIdx = idx; renderWidget(target); toast('天气城市：' + CITIES[idx].name); }
}
function renderCityList(){
  if(!cityPickItem) return;
  var inp = document.getElementById('citySearch');
  var raw = (inp.value || '').trim();
  var q = raw.toLowerCase().replace(/\s+/g, '').replace(/市$/, '');
  var zh = raw.replace(/市$/, '');
  var list = document.getElementById('cityList');
  var hits = [];
  CITIES.forEach(function(c, i){
    if(!q || c.name.indexOf(zh) > -1 || c.py.indexOf(q) > -1 || c.en.toLowerCase().replace(/[\s']/g, '').indexOf(q) > -1) hits.push(i);
  });
  cityFirstIdx = hits.length ? hits[0] : -1;
  if(!hits.length){
    list.innerHTML = '<div class="cityEmpty">没有收录「' + escapeHtml(raw) + '」的天气。<br>已收录全国 ' + CITIES.length + ' 个主要城市，换个名字、拼音或英文试试。</div>';
    return;
  }
  list.innerHTML = hits.map(function(i){
    var c = CITIES[i];
    var cur = (cityPickItem.cityIdx || 0) === i;
    return '<button type="button" class="cityRow' + (cur ? ' sel' : '') + '" data-city="' + i + '"><span class="ci">' + c.icon + '</span><span class="cb"><b>' + c.name + '</b><span>' + c.desc + ' · 最高 ' + c.hi + '° / 最低 ' + c.lo + '°</span></span><span class="ct">' + c.t + '°</span>' + (cur ? '<span class="ck">✓</span>' : '') + '</button>';
  }).join('');
  list.querySelectorAll('.cityRow').forEach(function(b){
    b.addEventListener('click', function(){ pickCity(+b.getAttribute('data-city')); });
  });
}
function openCityPicker(it){
  cityPickItem = it;
  document.getElementById('citySrc').textContent = '共 ' + CITIES.length + ' 个城市 · 数据来源 ' + WX_SOURCE + ' · ' + WX_AS_OF + ' 更新';
  var inp = document.getElementById('citySearch');
  inp.value = '';
  renderCityList();
  cityModal.classList.add('open');
  setTimeout(function(){ inp.focus(); }, 80);
}
function closeCityPicker(){ cityPickItem = null; cityModal.classList.remove('open'); }
document.getElementById('cityBk').addEventListener('click', closeCityPicker);
document.getElementById('cityCancel').addEventListener('click', closeCityPicker);
document.getElementById('citySearch').addEventListener('input', renderCityList);
document.getElementById('citySearch').addEventListener('keydown', function(e){
  e.stopPropagation();
  if(e.key === 'Enter' && cityFirstIdx >= 0) pickCity(cityFirstIdx);
  if(e.key === 'Escape') closeCityPicker();
});
/* ================= 软件右键：修改名字和图标 =================
   改的是软件本身（ALL_APPS 里的记录 + ICONS 图样），保存后桌面图标、文件夹迷你图标、
   Dock、启动台、聚焦搜索和已打开窗口的标题栏一起跟着变；桌面上那份图标的名字也同步，
   不会出现 Dock 叫一个名、桌面叫另一个名。图样可选现有软件的图标、一组通用图样，
   或用新名字首字当图标；底色从一组渐变里选。装进来的软件同时写回手机端。 */
var APP_GLYPH_SVGS = [
  '<svg viewBox="0 0 48 48"><path d="M24 5l5.6 12.3 13.4 1.6-9.9 9.2 2.6 13.2L24 34.9l-11.7 6.4 2.6-13.2-9.9-9.2 13.4-1.6z" fill="#fff"/></svg>',
  '<svg viewBox="0 0 48 48"><path d="M24 41S6 29.6 6 17.9C6 11.6 10.9 7 16.6 7c3 0 5.8 1.6 7.4 4 1.6-2.4 4.4-4 7.4-4C37.1 7 42 11.6 42 17.9 42 29.6 24 41 24 41z" fill="#fff"/></svg>',
  '<svg viewBox="0 0 48 48"><path d="M27 4L10 27h10l-2 17 19-24H26z" fill="#fff"/></svg>',
  '<svg viewBox="0 0 48 48"><circle cx="24" cy="24" r="17.5" fill="none" stroke="#fff" stroke-width="3"/><path d="M6.5 24h35M24 6.5c6 5.2 6 29.8 0 35M24 6.5c-6 5.2-6 29.8 0 35" fill="none" stroke="#fff" stroke-width="2.4"/></svg>',
  '<svg viewBox="0 0 48 48"><rect x="5" y="14" width="38" height="26" rx="5" fill="#fff"/><circle cx="24" cy="27" r="7.5" fill="#2f7fe0"/><path d="M17 14l3.5-6h7L31 14z" fill="#fff"/></svg>',
  '<svg viewBox="0 0 48 48"><path d="M10 7h22a6 6 0 0 1 6 6v22H16a6 6 0 0 0-6 6z" fill="#fff"/><path d="M10 41a6 6 0 0 1 6-6h22v6H16a6 6 0 0 0-6 0z" fill="rgba(255,255,255,.6)"/></svg>',
  '<svg viewBox="0 0 48 48"><path d="M8 6h32a6 6 0 0 1 6 6v17a6 6 0 0 1-6 6H21l-10 9v-9H8a6 6 0 0 1-6-6V12a6 6 0 0 1 6-6z" fill="#fff"/></svg>',
  '<svg viewBox="0 0 48 48"><path d="M24 6a13 13 0 0 1 13 13c0 8 2 10 3 11H8c1-1 3-3 3-11a13 13 0 0 1 13-13z" fill="#fff"/><circle cx="24" cy="37.5" r="4.5" fill="#fff"/></svg>',
  '<svg viewBox="0 0 48 48"><path d="M14 36a9 9 0 1 1 1.6-17.8A13 13 0 0 1 40 21.5 7.5 7.5 0 0 1 38.5 36z" fill="#fff"/></svg>',
  '<svg viewBox="0 0 48 48"><circle cx="24" cy="24" r="19" fill="rgba(255,255,255,.35)"/><path d="M19 15l14 9-14 9z" fill="#fff"/></svg>',
  '<svg viewBox="0 0 48 48"><rect x="6" y="11" width="36" height="26" rx="6" fill="#fff"/><path d="M15 20v8M11 24h8" stroke="#2f7fe0" stroke-width="3" stroke-linecap="round"/><circle cx="32" cy="21" r="2.6" fill="#2f7fe0"/><circle cx="37" cy="27" r="2.6" fill="#2f7fe0"/></svg>',
  '<svg viewBox="0 0 48 48"><path d="M12 6h24v36l-4-3.4L28 42l-4-3.4L20 42l-4-3.4L12 42z" fill="#fff"/><path d="M17 15h14M17 22h14M17 29h9" stroke="#2f7fe0" stroke-width="2.6" stroke-linecap="round"/></svg>'
];
var appEditModal = document.getElementById('appEditModal');
var aeAppId = null, aeGlyph = '', aeGrad = '', aeLetter = false;
function aeGlyphOptions(){
  var app = appById(aeAppId);
  var list = [ICONS[app.id]];
  ['finder','musicx','sticky','settings','folder'].forEach(function(k){ if(list.indexOf(ICONS[k]) < 0) list.push(ICONS[k]); });
  APP_GLYPH_SVGS.forEach(function(s){ if(list.indexOf(s) < 0) list.push(s); });
  return list;
}
function renderAppEditor(){
  var nameVal = document.getElementById('appEditName').value;
  var prev = document.getElementById('appEditIcon');
  prev.style.background = aeGrad;
  prev.innerHTML = aeLetter ? instLetterIcon(nameVal) : aeGlyph;
  var gw = document.getElementById('appEditGlyphs');
  var opts = aeGlyphOptions();
  var tiles = opts.map(function(s, i){
    return '<button type="button" class="aeTile' + (!aeLetter && aeGlyph === s ? ' sel' : '') + '" data-glyph="' + i + '" style="background:' + aeGrad + '">' + s + '</button>';
  }).join('');
  tiles += '<button type="button" class="aeTile' + (aeLetter ? ' sel' : '') + '" data-glyph="letter" style="background:' + aeGrad + '">' + instLetterIcon(nameVal) + '</button>';
  gw.innerHTML = tiles;
  gw.querySelectorAll('.aeTile').forEach(function(b){
    b.addEventListener('click', function(){
      var g = b.getAttribute('data-glyph');
      if(g === 'letter'){ aeLetter = true; }
      else { aeLetter = false; aeGlyph = opts[+g]; }
      renderAppEditor();
    });
  });
  var grw = document.getElementById('appEditGrads');
  var grads = [];
  APPS.concat(EXTRA_APPS).forEach(function(a){ if(grads.indexOf(a.grad) < 0) grads.push(a.grad); });
  INST_GRADS.forEach(function(g){ if(grads.indexOf(g) < 0) grads.push(g); });
  ['linear-gradient(160deg,#ffb35c,#e05c2f)','linear-gradient(160deg,#5fd68a,#14663e)','linear-gradient(160deg,#f78fb0,#a02f5c)','linear-gradient(160deg,#8f9bb0,#2c3646)'].forEach(function(g){ if(grads.indexOf(g) < 0) grads.push(g); });
  grw.innerHTML = grads.map(function(g, i){
    return '<button type="button" class="aeGrad' + (aeGrad === g ? ' sel' : '') + '" data-grad="' + i + '" style="background:' + g + '" aria-label="底色 ' + (i + 1) + '"></button>';
  }).join('');
  grw.querySelectorAll('.aeGrad').forEach(function(b){
    b.addEventListener('click', function(){ aeGrad = grads[+b.getAttribute('data-grad')]; renderAppEditor(); });
  });
}
function refreshAppSurfaces(appId){
  var app = appById(appId);
  items.forEach(function(it){
    if(it.type === 'app' && it.appId === appId){ it.name = app.name; renderAppCell(it); }
    else if(it.type === 'folder' && it.apps.indexOf(appId) >= 0) renderFolderCell(it);
  });
  renderDock();
  renderLaunchpad();
  var win = openWins[appId];
  if(win){
    var ttl = win.querySelector('.ttl');
    if(ttl) ttl.innerHTML = '<span style="display:inline-flex;width:18px;height:18px;border-radius:5px;background:' + app.grad + ';align-items:center;justify-content:center;overflow:hidden">' + ICONS[appId] + '</span>' + escapeHtml(app.name);
  }
}
function openAppEditor(appId){
  var app = appById(appId);
  aeAppId = appId; aeGlyph = ICONS[appId]; aeGrad = app.grad; aeLetter = false;
  document.getElementById('appEditName').value = app.name;
  renderAppEditor();
  appEditModal.classList.add('open');
}
function closeAppEditor(){ aeAppId = null; appEditModal.classList.remove('open'); }
document.getElementById('appEditBk').addEventListener('click', closeAppEditor);
document.getElementById('appEditCancel').addEventListener('click', closeAppEditor);
document.getElementById('appEditName').addEventListener('input', renderAppEditor);
document.getElementById('appEditName').addEventListener('keydown', function(e){ e.stopPropagation(); if(e.key === 'Enter') document.getElementById('appEditOk').click(); });
document.getElementById('appEditOk').addEventListener('click', function(){
  if(!aeAppId) return;
  var app = appById(aeAppId);
  var name = document.getElementById('appEditName').value.trim().slice(0, 16) || app.name;
  var id = aeAppId;
  var svg = aeLetter ? instLetterIcon(name) : aeGlyph;
  /* 装进来的软件写回唯一数据源，手机端列表 / 快捷方式名一起跟着变 */
  if(app.installed && typeof Canghai !== 'undefined' && Canghai){
    try{
      if(Canghai.renameHtml){ var n = Canghai.renameHtml(id, name); if(n) name = n; }
      if(Canghai.setHtmlIcon){
        var iconSvg = /<svg[\s>]/i.test(String(svg)) ? svg : instLetterIcon(name);
        Canghai.setHtmlIcon(id, iconSvg);
      }
    }catch(e){}
  }
  app.name = name;
  ICONS[id] = svg;
  app.grad = aeGrad;
  closeAppEditor();
  refreshAppSurfaces(id);
  toast('「' + name + '」的名字和图标已更新 · 手机端已同步');
});
var STICKY_PIN_SVG = '<svg viewBox="0 0 36 36" aria-hidden="true"><path d="M17.1 15h1.8l-.8 10.6c-.1 1-1.9 1-2 0z" fill="#9aa7b0"/><circle cx="18" cy="11" r="8" fill="#e2483d"/><circle cx="18" cy="11" r="8" fill="none" stroke="rgba(120,20,10,.45)" stroke-width="1"/><ellipse cx="15.2" cy="8.2" rx="3" ry="2.1" fill="rgba(255,255,255,.55)"/></svg>';
function applySticky(st, el){
  if(st._lastW !== st.w){ el.style.width = st.w+'px'; st._lastW = st.w; }
  if(st._lastH !== st.h){ el.style.height = st.h+'px'; st._lastH = st.h; }
  el.style.transform = 'translate3d('+st.rx.toFixed(2)+'px,'+st.ry.toFixed(2)+'px,0) rotate('+st.angle.toFixed(2)+'deg)';
}
function stickyFrame(st, el){
  /* 位置弹簧：目标 (x,y)，渲染 (rx,ry) 带速度追赶，天然有滞后与回弹 */
  st.pxv += (st.x - st.rx) * 0.20; st.pxv *= 0.66; st.rx += st.pxv;
  st.pyv += (st.y - st.ry) * 0.20; st.pyv *= 0.66; st.ry += st.pyv;
  /* 摆角弹簧：绕顶针的摆动。拖动时摆角跟着水平速度倾斜，松手后回到静止角 */
  var desired = st.dragging ? st.rest + clamp(st.pvx * 0.55, -17, 17) : st.rest;
  st.angVel += (desired - st.angle) * 0.055;
  st.angVel *= 0.90;
  st.angle += st.angVel;
  if(st.reduced){ st.rx = st.x; st.ry = st.y; st.angle = st.rest; st.angVel = 0; }
  applySticky(st, el);
  var settled = Math.abs(st.x-st.rx)<0.4 && Math.abs(st.y-st.ry)<0.4 &&
    Math.abs(st.pxv)<0.06 && Math.abs(st.pyv)<0.06 &&
    Math.abs(st.angle-st.rest)<0.06 && Math.abs(st.angVel)<0.03;
  if(st.dragging || !settled){
    st.raf = requestAnimationFrame(function(){ stickyFrame(st, el); });
  } else {
    st.raf = 0;
    st.rx = st.x; st.ry = st.y; st.angle = st.rest;
    applySticky(st, el);
  }
}
function kickSticky(st, el){
  if(!st.raf) st.raf = requestAnimationFrame(function(){ stickyFrame(st, el); });
}
function createStickyNote(opts){
  opts = opts || {};
  stickyCount++;
  var n = stickyCount;
  var col = (opts.color != null) ? (Number(opts.color) % 4) : ((n-1)%4);
  var el = document.createElement('div');
  el.className = 'stickyNote c'+col+(opts.welcome?' welcome':'');
  el.innerHTML = '<div class="snPaper">'
    + '<div class="snHead" title="按住顶针拖动"><span class="snPin">'+STICKY_PIN_SVG+'</span></div>'
    + (opts.welcome ? '<button class="snClose" type="button" aria-label="关闭便签" title="关闭">✕</button>' : '')
    + '<textarea class="snText" placeholder="写点什么…" aria-label="便签内容" spellcheck="false"></textarea>'
    + '<div class="snResize" title="拖动右下角改大小" aria-hidden="true"></div></div>';
  if(opts.w) el.style.width = opts.w+'px';
  if(opts.h) el.style.height = opts.h+'px';
  stickyLayer.appendChild(el);
  var LW = stickyLayer.clientWidth || window.innerWidth || 1;
  var LH = stickyLayer.clientHeight || window.innerHeight || 1;
  var curCell = (typeof CELL === 'number' && CELL > 0) ? CELL : 96;
  /* 便签尺寸严格根据桌面网格格子(CELL)比例计算：
     以前标准大屏(CELL=96)下欢迎便签是 276x268，比例为 2.875 x 2.792；
     普通便签是 216x212，比例为 2.25 x 2.208。所有的显示都根据格子大小来 */
  var wRatio = opts.wRatio != null ? Number(opts.wRatio) : (opts.w ? (Number(opts.w) / 96) : (opts.welcome ? 2.875 : 2.25));
  var hRatio = opts.hRatio != null ? Number(opts.hRatio) : (opts.h ? (Number(opts.h) / 96) : (opts.welcome ? 2.792 : 2.208));
  var w = Math.max(36, Math.round(curCell * wRatio));
  var h = Math.max(36, Math.round(curCell * hRatio));
  el.style.width = w+'px';
  el.style.height = h+'px';
  var x, y;
  var xp = (opts.xp != null) ? Number(opts.xp) : null;
  var yp = (opts.yp != null) ? Number(opts.yp) : null;
  if(xp != null && yp != null && LW > 50 && LH > 50){
    x = clamp(Math.round(xp * LW), 4, Math.max(4, LW - w - 4));
    y = clamp(Math.round(yp * LH), 4, Math.max(4, LH - h - 4));
  } else if(opts.x != null && opts.y != null){
    x = clamp(Number(opts.x), 4, Math.max(4, LW - w - 4));
    y = clamp(Number(opts.y), 4, Math.max(4, LH - h - 4));
    if(xp == null && LW > 50) xp = Number((x / LW).toFixed(4));
    if(yp == null && LH > 50) yp = Number((y / LH).toFixed(4));
  } else if(opts.at==='bl'){
    var gx0 = (typeof GOX === 'number') ? GOX : 16;
    var gy0 = (typeof GOY === 'number') ? GOY : 16;
    x = clamp(gx0 + Math.round(curCell * 0.5), 8, Math.max(8, LW - w - 8));
    y = clamp(gy0 + Math.round(curCell * 2.8), 38, Math.max(38, LH - h - 8));
    if(xp == null && LW > 50) xp = Number((x / LW).toFixed(4));
    if(yp == null && LH > 50) yp = Number((y / LH).toFixed(4));
  } else if(opts.at==='br'){
    var gx0 = (typeof GOX === 'number') ? GOX : 16;
    var gy0 = (typeof GOY === 'number') ? GOY : 16;
    var cols = (typeof COLS === 'number') ? COLS : 15;
    var gridRight = gx0 + cols * curCell;
    /* 右缘距网格右缘 2.5 格：默认布局里待办占了右列 (13,2)，0.5 格会压住它一半 */
    x = clamp(gridRight - w - Math.round(curCell * 2.5), 8, Math.max(8, LW - w - 8));
    y = clamp(gy0 + Math.round(curCell * 2.8), 38, Math.max(38, LH - h - 8));
    if(xp == null && LW > 50) xp = Number((x / LW).toFixed(4));
    if(yp == null && LH > 50) yp = Number((y / LH).toFixed(4));
  } else {
    x = clamp(Math.round(LW*0.36) + ((n-1)%5)*34, 8, Math.max(8, LW-w-8));
    y = clamp(Math.round(LH*0.14) + ((n-1)%5)*30, 12, Math.max(12, LH-h-8));
    if(xp == null && LW > 50) xp = Number((x / LW).toFixed(4));
    if(yp == null && LH > 50) yp = Number((y / LH).toFixed(4));
  }
  var rests = [-1.6, 1.3, -0.7, 1.8];
  var st = { x:x, y:y, rx:x, ry:y-16, w:w, h:h, wRatio:wRatio, hRatio:hRatio, xp:xp, yp:yp, pxv:0, pyv:0, pvx:0, color:col,
    bg: opts.bg || '', textColor: opts.textColor || '', fontSize: opts.fontSize || 0,
    angle: rests[col] + 5, angVel:0, rest: rests[col],
    dragging:false, raf:0,
    reduced: !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) };
  el._st = st;
  if(st.bg){
    var paper = el.querySelector('.snPaper');
    if(paper) paper.style.background = st.bg;
  }
  if(st.textColor){
    var ta = el.querySelector('.snText');
    if(ta) ta.style.color = st.textColor;
  }
  if(st.fontSize){
    el.style.setProperty('--snfs', st.fontSize);
  }
  el.style.zIndex = ++stickyZ;
  applySticky(st, el);
  kickSticky(st, el);
  /* 点任意处先把这张便签提到最前 */
  el.addEventListener('pointerdown', function(){ el.style.zIndex = ++stickyZ; });
  /* 没有关闭按钮：右键这张便签弹出菜单——选外观配色，或删除（删除要再确认一次） */
  function closeSticky(){
    if(st.raf) cancelAnimationFrame(st.raf);
    el.style.transition = 'opacity .16s ease';
    el.style.opacity = '0';
    setTimeout(function(){ el.remove(); deskSaveSoon(); }, 170);
  }
  /* 欢迎便签右上角的 ✕：点一下直接关闭，不用右键、不用二次确认 */
  var snCloseBtn = el.querySelector('.snClose');
  if(snCloseBtn){
    snCloseBtn.addEventListener('pointerdown', function(e){ e.stopPropagation(); });
    snCloseBtn.addEventListener('click', function(e){ e.stopPropagation(); closeSticky(); });
  }
  el.addEventListener('contextmenu', function(e){
    e.preventDefault();
    e.stopPropagation();
    el.style.zIndex = ++stickyZ;
    var entries = [
      { label:'设置便签外观…', fn:function(){ openStickyColorModal(st, el); } },
      '-',
      { label:'删除便签', danger:true, fn:function(){ askDeleteSticky(closeSticky); } }
    ];
    showMenu(e.clientX, e.clientY, entries);
  });
  /* 改大小：拖右下角，宽高跟着走（纸面与横线纸文本框是弹性布局，会自动跟随） */
  var grip = el.querySelector('.snResize');
  grip.addEventListener('pointerdown', function(e){
    if(e.button!==undefined && e.button!==0) return;
    e.preventDefault();
    e.stopPropagation();
    el.style.zIndex = ++stickyZ;
    var sx = e.clientX, sy = e.clientY, ow = st.w, oh = st.h;
    try{ grip.setPointerCapture(e.pointerId); }catch(err){}
    el.classList.add('resizing');
    function mv(ev){
      var LW2 = stickyLayer.clientWidth || window.innerWidth;
      var LH2 = stickyLayer.clientHeight || window.innerHeight;
      var cCell = (typeof CELL === 'number' && CELL > 0) ? CELL : 96;
      st.w = clamp(Math.round(ow + ev.clientX - sx), Math.round(cCell * 1.1), Math.max(Math.round(cCell * 1.1), LW2 - st.x - 4));
      st.h = clamp(Math.round(oh + ev.clientY - sy), Math.round(cCell * 1.0), Math.max(Math.round(cCell * 1.0), LH2 - st.y - 4));
      st.wRatio = Number((st.w / cCell).toFixed(3));
      st.hRatio = Number((st.h / cCell).toFixed(3));
      applySticky(st, el);
    }
    function up(){
      grip.removeEventListener('pointermove', mv);
      grip.removeEventListener('pointerup', up);
      grip.removeEventListener('pointercancel', up);
      el.classList.remove('resizing');
      deskSaveSoon();
    }
    grip.addEventListener('pointermove', mv);
    grip.addEventListener('pointerup', up);
    grip.addEventListener('pointercancel', up);
  });
  /* 拖动：抓住顶针所在的头部拖；纸面跟着弹簧走，摆角绕顶针 */
  var head = el.querySelector('.snHead');
  head.addEventListener('pointerdown', function(e){
    if(e.button!==undefined && e.button!==0) return;
    e.preventDefault();
    el.style.zIndex = ++stickyZ;
    st.dragging = true; st.pvx = 0;
    var sx = e.clientX, sy = e.clientY, ox = st.x, oy = st.y, lastX = e.clientX;
    try{ head.setPointerCapture(e.pointerId); }catch(err){}
    el.classList.add('dragging');
    function mv(ev){
      st.x = clamp(ox + ev.clientX - sx, -50, (stickyLayer.clientWidth||window.innerWidth) - 66);
      st.y = clamp(oy + ev.clientY - sy, 6, (stickyLayer.clientHeight||window.innerHeight) - 54);
      st.pvx = st.pvx*0.6 + (ev.clientX - lastX)*0.4;
      lastX = ev.clientX;
      kickSticky(st, el);
    }
    function up(){
      head.removeEventListener('pointermove', mv);
      head.removeEventListener('pointerup', up);
      head.removeEventListener('pointercancel', up);
      st.dragging = false; st.pvx = 0;
      el.classList.remove('dragging');
      var LW2 = stickyLayer.clientWidth || window.innerWidth || 1;
      var LH2 = stickyLayer.clientHeight || window.innerHeight || 1;
      if(LW2 > 50) st.xp = Number((st.x / LW2).toFixed(4));
      if(LH2 > 50) st.yp = Number((st.y / LH2).toFixed(4));
      kickSticky(st, el);
      deskSaveSoon();
    }
    head.addEventListener('pointermove', mv);
    head.addEventListener('pointerup', up);
    head.addEventListener('pointercancel', up);
    kickSticky(st, el);
  });
  var ta = el.querySelector('.snText');
  if(opts.text) ta.value = opts.text;
  ta.addEventListener('input', deskSaveSoon);
  if(!opts.welcome) setTimeout(function(){ try{ ta.focus({preventScroll:true}); }catch(err){ try{ ta.focus(); }catch(e2){} } }, 60);
  if(n<=2 && !opts.silent) toast('便签贴好了 · 按住顶针拖动，拖右下角改大小，右键设置外观或删除');
}

window.updateAllStickyScales = function(){
  if(typeof stickyLayer === 'undefined' || !stickyLayer) return;
  var curCell = (typeof CELL === 'number' && CELL > 0) ? CELL : 96;
  var LW = stickyLayer.clientWidth || window.innerWidth || 1;
  var LH = stickyLayer.clientHeight || window.innerHeight || 1;
  stickyLayer.querySelectorAll('.stickyNote').forEach(function(el){
    var st = el._st;
    if(!st) return;
    if(st.wRatio == null) st.wRatio = st.w ? (st.w / 96) : (el.classList.contains('welcome') ? 2.875 : 2.25);
    if(st.hRatio == null) st.hRatio = st.h ? (st.h / 96) : (el.classList.contains('welcome') ? 2.792 : 2.208);
    st.w = Math.max(36, Math.round(st.wRatio * curCell));
    st.h = Math.max(36, Math.round(st.hRatio * curCell));
    if(st.xp != null && st.yp != null && !st.dragging){
      st.x = clamp(Math.round(st.xp * LW), 4, Math.max(4, LW - st.w - 4));
      st.y = clamp(Math.round(st.yp * LH), 4, Math.max(4, LH - st.h - 4));
      st.rx = st.x; st.ry = st.y;
    }
    applySticky(st, el);
  });
};
