/* ========================================================
 * 沧海系统桌面 - 网格与图标系统 (grid.js)
 * 职责：桌面网格几何计算、图标渲染、拖拽吸附、框选、右键菜单、文件夹
 * ======================================================== */
/* ================= 网格系统（固定格数 · 居中） =================
   格数是设置里的固定值（默认横 15 × 竖 5），不随分辨率变：
   先按格数在桌面可用区正中心算好每格大小和网格起点，
   图标 / 小组件只记格坐标（第几列第几行），像素位置一律由格坐标换算。
   分辨率怎么变，都只是重算格子大小和中心，格坐标一个不动，布局不会乱。 */
var gridArea = document.getElementById('gridArea');
var ghost = document.getElementById('dropGhost');
var band = document.getElementById('selBand');
var gridFrame = document.getElementById('gridFrame');
var GRID_COLS = 15, GRID_ROWS = 7;
var CELL = 96, COLS = GRID_COLS, ROWS = GRID_ROWS, GOX = 0, GOY = 0;
window.chApplyResolution = function(resKey){
  if(!resKey) return;
  window.__chResKey = resKey;
  computeGrid();
  if(Array.isArray(items)) items.forEach(function(it){ posEl(it); });
};
function computeGrid(){
  var w = gridArea.clientWidth, h = gridArea.clientHeight;
  if(!w || !h) return;
  /* 严格遵循 15:7 统一网格坐标系统（或用户在设置中自定义的格数），横竖屏及多屏间绝对同步 */
  COLS = GRID_COLS;
  ROWS = GRID_ROWS;
  /* 格子大小居中计算，确保完整呈现网格区域 */
  CELL = Math.max(18, Math.floor(Math.min(w / COLS, h / ROWS)));
  GOX = Math.floor((w - CELL * COLS) / 2);
  GOY = Math.floor((h - CELL * ROWS) / 2);
  document.documentElement.style.setProperty('--cell', CELL+'px');
  var ratio = state.iconSize==='s' ? 0.44 : state.iconSize==='l' ? 0.66 : 0.55;
  /* 图标边长 = 格子 × 比例（小 0.44 / 中 0.55 / 大 0.66），再乘分辨率缩放 --rs：
     4K 下格子变大、76px 的上限也会跟着放大，图标才不会在高分辨率屏上显得过小；
     低分辨率下限 14px 不再乘 --rs，保住最小可读尺寸 */
  var rs = (typeof window.chUiScale === 'function') ? window.chUiScale() : 1;
  document.documentElement.style.setProperty('--isz', Math.max(14, Math.round(clamp(CELL * ratio, 14, 76) * rs))+'px');
  if(gridFrame){
    gridFrame.style.left = GOX+'px'; gridFrame.style.top = GOY+'px';
    gridFrame.style.width = (CELL*COLS)+'px'; gridFrame.style.height = (CELL*ROWS)+'px';
    gridFrame.style.backgroundSize = CELL+'px '+CELL+'px';
  }
  updateGridInfo();
  if(typeof window.updateAllStickyScales === 'function') window.updateAllStickyScales();
}
function gridX(col){ return GOX + col * CELL; }
function gridY(row){ return GOY + row * CELL; }
function showGridFrame(on){ if(gridFrame) gridFrame.classList.toggle('show', !!on); }
function updateGridInfo(){
  document.querySelectorAll('.gColsVal').forEach(function(v){ v.textContent = GRID_COLS+' 格'; });
  document.querySelectorAll('.gRowsVal').forEach(function(v){ v.textContent = GRID_ROWS+' 格'; });
  document.querySelectorAll('.gridCellVal').forEach(function(v){ v.textContent = CELL+' px · 居中'; });
}
function setGridSize(cols, rows, silent){
  GRID_COLS = clamp(Math.round(cols)||15, 4, 24);
  GRID_ROWS = clamp(Math.round(rows)||7, 3, 12);
  computeGrid();
  /* 格数变小装不下时才收拾：先钳回范围内，再把重叠的挪到最近空格；其余格坐标不动 */
  items.forEach(function(it){
    it.w = Math.min(it.w, COLS); it.h = Math.min(it.h, ROWS);
    it.col = clamp(it.col, 0, COLS - it.w);
    it.row = clamp(it.row, 0, ROWS - it.h);
  });
  items.forEach(function(it){
    if(rectHit(it.col, it.row, it.w, it.h, it.desk, [it])){
      var spot = findFree(it.w, it.h, it.col, it.row, it.desk, [it]);
      it.col = spot.col; it.row = spot.row;
    }
    posEl(it);
  });
  updateGridInfo();
  if(!silent) toast('桌面网格已改为 '+GRID_COLS+' × '+GRID_ROWS+' 格，居中排布，每格 '+CELL+' px');
}
var uid = 0;
var items = []; // {id,type:'app'|'widget'|'folder',appId,wtype,name,col,row,w,h,html,desk,el,...}
function rectsOverlap(a,b){ return a.col < b.col+b.w && a.col+a.w > b.col && a.row < b.row+b.h && a.row+a.h > b.row; }
function rectHit(col,row,w,h,desk,exclude,avoid){
  if(avoid && rectsOverlap({col:col,row:row,w:w,h:h}, avoid)) return true;
  for(var i=0;i<items.length;i++){
    var o = items[i];
    if(o.desk!==desk) continue;
    if(exclude && exclude.indexOf(o)>=0) continue;
    if(rectsOverlap({col:col,row:row,w:w,h:h}, o)) return true;
  }
  return false;
}
function findFree(w,h,sc,sr,desk,exclude,avoid){
  w = Math.min(w,COLS); h = Math.min(h,ROWS);
  var c0 = clamp(sc||0, 0, COLS-w), r0 = clamp(sr||0, 0, ROWS-h);
  if(!rectHit(c0,r0,w,h,desk,exclude,avoid)) return {col:c0,row:r0};
  var best=null, bd=1e9;
  for(var r=0;r<=ROWS-h;r++) for(var c=0;c<=COLS-w;c++){
    if(!rectHit(c,r,w,h,desk,exclude,avoid)){
      var d = Math.abs(c-c0)+Math.abs(r-r0);
      if(d<bd){ bd=d; best={col:c,row:r}; }
    }
  }
  return best || {col:c0,row:r0};
}
/* 内置小组件内容缩放：内容在「每格 96px」的设计画布（.wscale）里排版，
   再按 实际格子÷设计格子 整体等比缩放贴进小组件框——格子多小字就多小、
   行数与比例永远不变。实际框要扣掉 .widget 的内边距（与 CSS 里那条 clamp 一致）。
   设计尺寸锚定在小组件「创建时」的格数（it.dw / it.dh），不随当前框大小变：
   把框拖大，画布不变、缩放变大，字和画跟着等比变大；拖小则等比变小。
   若设计尺寸跟着框一起长，内容永远是创建时那个大小，拉大只多出空白。 */
var DESIGN_CELL = 96;
function fitWidgetScale(it){
  var sc = it._scaleEl;
  if(!sc) return;
  var inset = Math.min(16, Math.max(6, CELL*0.12));
  /* 四边留白 + 居中：画布按扣掉留白后的尺寸算缩放，再居中放进框内——
     宽框不再左贴、内容不再贴边，各占格下都居中。 */
  var pad = Math.min(12, Math.max(6, CELL*0.08));
  var boxW = it.w*CELL - inset, boxH = it.h*CELL - inset;
  var bw = Math.max(10, boxW - pad*2 - 2), bh = Math.max(10, boxH - pad*2 - 2);
  var dw = (it.dw||it.w)*DESIGN_CELL, dh = (it.dh||it.h)*DESIGN_CELL;
  var s = Math.min(bw/dw, bh/dh);
  sc.style.width = dw+'px';
  sc.style.height = dh+'px';
  /* .wscale 定位在 .wbody 内，.wbody 比 .widget 外框小 2px（1px 边框 ×2）：居中要按 .wbody
     的尺寸算，否则内容整体右偏、下偏各 1px（缩放算式里那 2px 早就扣了，这里漏扣）。 */
  sc.style.left = Math.max(0, (boxW - 2 - dw*s)/2)+'px';
  sc.style.top = Math.max(0, (boxH - 2 - dh*s)/2)+'px';
  sc.style.transform = 'scale('+s+')';
}
/* 大文件夹图标锁正方形：网格格子的宽高随文件夹占格走（2×1 的格子天然是扁的），
   迷你图标若拉满格子就会被压扁。这里按卡片可用区（扣掉和 CSS 相同的内边距、内衬、
   名称行）算出每个格子的宽与高，取短边作迷你图标的正方形边长写进 --bfmini；
   空文件夹的大图标同理取可用区短边写进 --bfempty。posEl 在改尺寸与分辨率变化时都会走，
   所以边长始终跟着格子大小走，任何占格比例下图标都不变形 */
function fitBigFolder(it){
  if(it.type!=='folder' || (it.w<=1 && it.h<=1)) return;
  var inset = Math.min(16, Math.max(6, CELL*0.12));
  var pad = Math.min(10, Math.max(5, CELL*0.09));
  var gap = Math.min(7, Math.max(3, CELL*0.06));
  var nameFont = Math.min(12, Math.max(6, CELL*0.118));
  var nameH = nameFont*1.2 + Math.min(5, Math.max(2, CELL*0.04));
  var availW = it.w*CELL - inset - pad*2;
  var availH = it.h*CELL - inset - pad*2 - nameH;
  var cellW = (availW - (it.w-1)*gap) / it.w;
  var cellH = (availH - (it.h-1)*gap) / it.h;
  var side = Math.max(8, Math.floor(Math.min(cellW, cellH)));
  it.el.style.setProperty('--bfmini', side+'px');
  var empty = Math.max(12, Math.min(130, Math.floor(Math.min(availW, availH)*0.92)));
  it.el.style.setProperty('--bfempty', empty+'px');
}
function posEl(it){
  it.el.style.left = gridX(it.col)+'px';
  it.el.style.top = gridY(it.row)+'px';
  it.el.style.width = (it.w*CELL)+'px';
  it.el.style.height = (it.h*CELL)+'px';
  if(it._scaleEl) fitWidgetScale(it);
  fitBigFolder(it);
}
function animatePos(it){
  it.el.style.transition = 'left .25s ease, top .25s ease, width .2s ease, height .2s ease';
  posEl(it);
  setTimeout(function(){ it.el.style.transition=''; }, 300);
}
function makeItem(cfg){
  cfg.id = ++uid;
  if(cfg.createdAt==null) cfg.createdAt = Date.now(); /* 右键「排序方式 → 日期」按这个时间排 */
  if(cfg.desk==null) cfg.desk = curDesk;
  var el = document.createElement('div');
  el.className = 'ditem';
  cfg.el = el;
  gridArea.appendChild(el);
  items.push(cfg);
  posEl(cfg);
  attachDrag(cfg);
  return cfg;
}
function removeItem(it){
  if(it._cwTimer){ clearInterval(it._cwTimer); it._cwTimer = null; }
  it.el.remove();
  items = items.filter(function(x){ return x!==it; });
  selItems = selItems.filter(function(x){ return x!==it; });
}
function itemByEl(el){ for(var i=0;i<items.length;i++) if(items[i].el===el) return items[i]; return null; }

/* ================= 选择（多选） ================= */
var selItems = [];
function setSel(arr){
  selItems = arr.slice();
  items.forEach(function(it){
    var on = selItems.indexOf(it)>=0;
    it.el.classList.toggle('selW', on && it.type==='widget');
    var c = it.el.querySelector('.appCell');
    if(c) c.classList.toggle('selected', on);
  });
}
function toggleSel(it){
  var a = selItems.slice(), i = a.indexOf(it);
  if(i>=0) a.splice(i,1); else a.push(it);
  setSel(a);
}

/* ================= 桌面图标（应用 / 文件夹） ================= */
function bindCell(it, cell, openFn){
  it._openFn = openFn;
  var nameEl = cell.querySelector('.appName');
  if(nameEl){
    nameEl.addEventListener('dblclick', function(e){
      e.stopPropagation();
      renameItem(it);
    });
  }
  cell.addEventListener('dblclick', function(e){
    e.stopPropagation();
    if(!it._openFn) return;
    /* 指针兜底已经在这个时间窗内打开过，别再开第二个 */
    if(cell._openedAt && Date.now() - cell._openedAt < 600) return;
    it._openFn(e);
  });
  /* 双击兜底：Android WebView 里鼠标的 dblclick 是由触摸双击那条链路合成的，
   * 真鼠标（SOURCE_MOUSE）常常只来 click 不来 dblclick —— 于是「单击能选中、
   * 双击打不开」。这里自己数时间：320ms 内、同一图标、位置相差 8px 以内的
   * 第二次按下就算双击。和上面的 dblclick 互斥（_openedAt 挡重复）。 */
  cell.addEventListener('pointerdown', function(e){
    if(e.pointerType === 'mouse' && e.button !== 0) return;
    var now = Date.now();
    var last = cell._lastDownAt || 0;
    var dx = Math.abs(e.clientX - (cell._lastDownX || 0));
    var dy = Math.abs(e.clientY - (cell._lastDownY || 0));
    cell._lastDownAt = now;
    cell._lastDownX = e.clientX;
    cell._lastDownY = e.clientY;
    if(now - last > 60 && now - last < 320 && dx < 8 && dy < 8){
      cell._openedAt = now;
      cell._lastDownAt = 0;
      if(it._openFn) it._openFn(e);
    }
  });
}
function renderAppCell(it){
  var app = appById(it.appId);
  it.el.innerHTML = '<div class="appCell"><div class="appIcon" style="background:'+app.grad+'">'+ICONS[app.id]+'</div><div class="appName">'+escapeHtml(it.name)+'</div></div>';
  bindCell(it, it.el.firstChild, function(){ openApp(it.appId); });
}
function ensureFolderEntries(it){
  if(!it.apps) it.apps = [];
  if(!Array.isArray(it.entries)){
    it.entries = it.apps.map(function(aid, idx){ return {kind:'app', appId:aid, col:idx%4, row:Math.floor(idx/4)}; });
  } else {
    /* 保证 it.apps 里所有应用都在 entries 中 */
    it.apps.forEach(function(aid){
      if(!it.entries.some(function(e){ return e && e.kind==='app' && e.appId===aid; })){
        it.entries.push({kind:'app', appId:aid});
      }
    });
  }
  it.apps = it.entries.filter(function(e){ return e && e.kind==='app' && e.appId; }).map(function(e){ return e.appId; });
  return it.entries;
}
function renderFolderCell(it){
  var entries = ensureFolderEntries(it);
  /* 占多格 = 大文件夹（iPhone / 鸿蒙式）：卡片按格子数排迷你图标，从左上起逐个填，
     最后一格永远留给「···」打开按钮；空文件夹整面显示一个大文件夹图标。
     格子数 = 槽位数：2 格里有 1 个应用就是「应用图标 + ···」，2 个应用也是先排 1 个再加「···」 */
  if(it.w>1 || it.h>1){
    var slots = it.w*it.h, inner;
    if(!entries.length){
      inner = '<div class="bfEmpty">'+ICONS.folder+'</div>';
    } else {
      var cellsHtml = entries.slice(0, slots-1).map(function(en){
        if(en.kind==='txt'){
          return '<div class="bfMini" title="'+escapeHtml(en.name||'文本文档.txt')+'" style="background:linear-gradient(160deg,#fdfdff,#d7e6f5)">'+ICONS.devdoc+'</div>';
        }
        var a = appById(en.appId);
        return '<div class="bfMini" data-app="'+en.appId+'" title="'+escapeHtml(a.name)+'" style="background:'+a.grad+'">'+ICONS[en.appId]+'</div>';
      }).join('');
      cellsHtml += '<div class="bfMore" title="打开文件夹"><span>···</span></div>';
      inner = '<div class="bfGrid" style="grid-template-columns:repeat('+it.w+',1fr);grid-template-rows:repeat('+it.h+',1fr)">'+cellsHtml+'</div>';
    }
    it.el.innerHTML = '<div class="bigFolder">'+inner+'<div class="bfName appName">'+escapeHtml(it.name)+'</div></div>';
    bindCell(it, it.el.firstChild, function(e){
      if(e){
        var t = document.elementFromPoint(e.clientX, e.clientY);
        var mini = t && t.closest ? t.closest('.bfMini') : null;
        if(mini && mini.getAttribute('data-app')){ openApp(mini.getAttribute('data-app')); return; }
      }
      openFolder(it);
    });
    return;
  }
  var badge = entries.length ? '<span class="folderBadge">'+entries.length+'</span>' : '';
  it.el.innerHTML = '<div class="appCell"><div class="appIcon" style="background:linear-gradient(160deg,#eaf3ff,#bcd6f5)">'+ICONS.folder+badge+'</div><div class="appName">'+escapeHtml(it.name)+'</div></div>';
  bindCell(it, it.el.firstChild, function(){ openFolder(it); });
}
/* 右键改文件夹占格数：放不下就找最近的空格，再不行保持原样并提示 */
function setFolderSize(it, w, h){
  w = clamp(Math.round(w)||1, 1, COLS); h = clamp(Math.round(h)||1, 1, ROWS);
  if(w===it.w && h===it.h){ renderFolderCell(it); return; }
  var ow = it.w, oh = it.h;
  it.w = w; it.h = h;
  if(rectHit(it.col, it.row, w, h, it.desk, [it])){
    var spot = findFree(w, h, it.col, it.row, it.desk, [it]);
    if(spot && !rectHit(spot.col, spot.row, w, h, it.desk, [it])){ it.col = spot.col; it.row = spot.row; }
    else { it.w = ow; it.h = oh; posEl(it); renderFolderCell(it); toast('周围没有足够的空格放 '+w+' × '+h+' 的文件夹'); return; }
  }
  posEl(it);
  renderFolderCell(it);
  pulseItem(it);
  toast('文件夹「'+it.name+'」已改为 '+w+' × '+h+' 格');
}
function renameItem(it){
  var nameEl = it.el.querySelector('.appName');
  if(!nameEl || nameEl.querySelector('input')) return;
  var cur = it.name;
  nameEl.innerHTML = '';
  var inp = document.createElement('input');
  inp.className = 'renameInp'; inp.value = cur; inp.maxLength = 16;
  nameEl.appendChild(inp);
  inp.focus(); inp.select();
  var done = false;
  function commit(save){
    if(done) return; done = true;
    var v = save ? (inp.value.trim() || cur) : cur;
    it.name = v;
    nameEl.textContent = v;
    if(it.type==='folder' && openWins['folder_'+it.id]){
      var fvt = openWins['folder_'+it.id].querySelector('.fvTitle');
      if(fvt && fvt.value !== v) fvt.value = v;
    }
    if(it.type==='txt' && openWins['txt_'+it.id]){
      var tttl = openWins['txt_'+it.id].querySelector('.ttl .txtWinName');
      if(tttl) tttl.textContent = v;
    }
    if(save && v!==cur){ toast('已改名为「'+v+'」'); deskSaveSoon(); }
  }
  inp.addEventListener('blur', function(){ commit(true); });
  inp.addEventListener('keydown', function(e){
    e.stopPropagation();
    if(e.key==='Enter') inp.blur();
    if(e.key==='Escape'){ commit(false); }
  });
  inp.addEventListener('pointerdown', function(e){ e.stopPropagation(); });
  inp.addEventListener('dblclick', function(e){ e.stopPropagation(); });
}
function placeApps(){
  /* 默认桌面只摆一个图标：便签；其余应用在启动台里，不占桌面 */
  var want = ['sticky'];
  var n = 0;
  want.forEach(function(id){
    for(var i=0;i<APPS.length;i++){
      if(APPS[i].id===id){
        var it = makeItem({type:'app', appId:APPS[i].id, name:APPS[i].name, col:0, row:n, w:1, h:1, desk:0});
        renderAppCell(it);
        n++;
        break;
      }
    }
  });
}

/* ================= 小组件渲染 ================= */
/* 天气数据是构建时从 wttr.in（World Weather Online）逐城抓取的真实快照（静态页不在
   打开时联网取数，抓取时间和来源都渲染在小组件上）；共 48 个城市——全部直辖市与省会
   加主要城市，每城带 py（拼音）与 en（英文名），选城市时可自己输入中文名、拼音或英文
   实时匹配。默认城市北京（下标 0）；点小组件或右键「编辑」打开城市选择。
   快照时间：2026年11月7日 09:10（CST） */
var WX_SOURCE = 'wttr.in';
var WX_AS_OF = '2026年11月7日 09:10';
var CITIES = [
  {name:'北京', py:'beijing', en:'Beijing', icon:'🌫', t:4, hi:9, lo:2, desc:'霾'},
  {name:'上海', py:'shanghai', en:'Shanghai', icon:'☀️', t:17, hi:20, lo:16, desc:'晴'},
  {name:'天津', py:'tianjin', en:'Tianjin', icon:'🌫', t:6, hi:11, lo:4, desc:'霾'},
  {name:'重庆', py:'chongqing', en:'Chongqing', icon:'🌧', t:17, hi:19, lo:17, desc:'小雨'},
  {name:'广州', py:'guangzhou', en:'Guangzhou', icon:'🌦', t:23, hi:29, lo:22, desc:'阵雨'},
  {name:'深圳', py:'shenzhen', en:'Shenzhen', icon:'☀️', t:25, hi:28, lo:24, desc:'晴'},
  {name:'成都', py:'chengdu', en:'Chengdu', icon:'🌫', t:16, hi:20, lo:16, desc:'霾'},
  {name:'杭州', py:'hangzhou', en:'Hangzhou', icon:'🌫', t:17, hi:19, lo:15, desc:'霾'},
  {name:'武汉', py:'wuhan', en:'Wuhan', icon:'🌫', t:15, hi:19, lo:15, desc:'霾'},
  {name:'西安', py:'xian', en:"Xi'an", icon:'🌫', t:12, hi:14, lo:9, desc:'霾'},
  {name:'南京', py:'nanjing', en:'Nanjing', icon:'☀️', t:14, hi:19, lo:13, desc:'晴'},
  {name:'长沙', py:'changsha', en:'Changsha', icon:'🌧', t:15, hi:17, lo:15, desc:'小雨'},
  {name:'郑州', py:'zhengzhou', en:'Zhengzhou', icon:'🌫', t:9, hi:17, lo:9, desc:'霾'},
  {name:'沈阳', py:'shenyang', en:'Shenyang', icon:'☁️', t:-3, hi:2, lo:-4, desc:'多云'},
  {name:'青岛', py:'qingdao', en:'Qingdao', icon:'☁️', t:14, hi:15, lo:13, desc:'阴'},
  {name:'大连', py:'dalian', en:'Dalian', icon:'☀️', t:4, hi:8, lo:4, desc:'晴'},
  {name:'厦门', py:'xiamen', en:'Xiamen', icon:'⛅', t:23, hi:26, lo:22, desc:'多云'},
  {name:'昆明', py:'kunming', en:'Kunming', icon:'⛅', t:16, hi:20, lo:14, desc:'多云'},
  {name:'哈尔滨', py:'haerbin', en:'Harbin', icon:'☀️', t:-10, hi:-5, lo:-10, desc:'晴'},
  {name:'长春', py:'changchun', en:'Changchun', icon:'☀️', t:-11, hi:-3, lo:-11, desc:'晴'},
  {name:'石家庄', py:'shijiazhuang', en:'Shijiazhuang', icon:'🌫', t:7, hi:11, lo:5, desc:'霾'},
  {name:'太原', py:'taiyuan', en:'Taiyuan', icon:'🌫', t:3, hi:12, lo:2, desc:'霾'},
  {name:'济南', py:'jinan', en:'Jinan', icon:'🌦', t:12, hi:18, lo:11, desc:'阵雨'},
  {name:'合肥', py:'hefei', en:'Hefei', icon:'☀️', t:15, hi:19, lo:13, desc:'晴'},
  {name:'福州', py:'fuzhou', en:'Fuzhou', icon:'🌦', t:19, hi:21, lo:18, desc:'阵雨'},
  {name:'南昌', py:'nanchang', en:'Nanchang', icon:'🌦', t:16, hi:17, lo:15, desc:'小雨'},
  {name:'南宁', py:'nanning', en:'Nanning', icon:'⛅', t:20, hi:29, lo:20, desc:'多云'},
  {name:'海口', py:'haikou', en:'Haikou', icon:'🌦', t:26, hi:31, lo:25, desc:'阵雨'},
  {name:'三亚', py:'sanya', en:'Sanya', icon:'🌦', t:27, hi:29, lo:23, desc:'阵雨'},
  {name:'贵阳', py:'guiyang', en:'Guiyang', icon:'🌫', t:14, hi:18, lo:13, desc:'雾'},
  {name:'兰州', py:'lanzhou', en:'Lanzhou', icon:'🌦', t:8, hi:9, lo:3, desc:'阵雨'},
  {name:'西宁', py:'xining', en:'Xining', icon:'🌧', t:-1, hi:6, lo:-1, desc:'冻雨'},
  {name:'银川', py:'yinchuan', en:'Yinchuan', icon:'☁️', t:4, hi:11, lo:4, desc:'多云'},
  {name:'呼和浩特', py:'huhehaote', en:'Hohhot', icon:'☁️', t:3, hi:8, lo:1, desc:'阴'},
  {name:'乌鲁木齐', py:'wulumuqi', en:'Urumqi', icon:'🌫', t:2, hi:9, lo:0, desc:'霾'},
  {name:'拉萨', py:'lasa', en:'Lhasa', icon:'☁️', t:3, hi:7, lo:-7, desc:'多云'},
  {name:'苏州', py:'suzhou', en:'Suzhou', icon:'☀️', t:16, hi:19, lo:14, desc:'晴'},
  {name:'无锡', py:'wuxi', en:'Wuxi', icon:'☀️', t:15, hi:20, lo:14, desc:'晴'},
  {name:'宁波', py:'ningbo', en:'Ningbo', icon:'☁️', t:18, hi:19, lo:15, desc:'多云'},
  {name:'东莞', py:'dongguan', en:'Dongguan', icon:'🌦', t:24, hi:31, lo:23, desc:'阵雨'},
  {name:'佛山', py:'foshan', en:'Foshan', icon:'🌦', t:24, hi:30, lo:23, desc:'阵雨'},
  {name:'珠海', py:'zhuhai', en:'Zhuhai', icon:'🌦', t:25, hi:26, lo:25, desc:'阵雨'},
  {name:'洛阳', py:'luoyang', en:'Luoyang', icon:'🌫', t:9, hi:16, lo:8, desc:'霾'},
  {name:'桂林', py:'guilin', en:'Guilin', icon:'🌧', t:16, hi:20, lo:16, desc:'小雨'},
  {name:'大理', py:'dali', en:'Dali', icon:'☀️', t:17, hi:22, lo:7, desc:'晴'},
  {name:'丽江', py:'lijiang', en:'Lijiang', icon:'☀️', t:7, hi:21, lo:6, desc:'晴'},
  {name:'香港', py:'xianggang', en:'Hong Kong', icon:'🌦', t:25, hi:26, lo:25, desc:'阵雨'},
  {name:'台北', py:'taibei', en:'Taipei', icon:'🌦', t:23, hi:24, lo:22, desc:'阵雨'}
];
function clockHTML(){
  return '<div class="w-clock"><div class="big"><span class="ap ck-ap"></span><span class="ck-hm">--:--</span><span class="sec ck-s">--</span></div><div class="sub ck-date">—</div></div>';
}
function calHTML(){
  var now = new Date(), y = now.getFullYear(), m = now.getMonth();
  var first = new Date(y,m,1).getDay(), days = new Date(y,m+1,0).getDate();
  var prev = new Date(y,m,0).getDate();
  var s = '<div class="w-cal"><h4>'+y+'年'+(m+1)+'月 <span>日历</span></h4><table><tr><th>日</th><th>一</th><th>二</th><th>三</th><th>四</th><th>五</th><th>六</th></tr><tr>';
  var cellN = 0;
  for(var i=first-1;i>=0;i--){ s += '<td class="dim">'+(prev-i)+'</td>'; cellN++; }
  for(var d=1;d<=days;d++){ s += '<td class="'+(d===now.getDate()?'today':'')+'">'+d+'</td>'; cellN++; if(cellN%7===0 && d<days) s+='</tr><tr>'; }
  var tail = (7 - cellN%7)%7;
  for(var t=1;t<=tail;t++){ s += '<td class="dim">'+t+'</td>'; }
  return s+'</tr></table></div>';
}
function weatherHTML(it){
  var c = CITIES[it.cityIdx||0];
  return '<div class="w-weather"><div class="wic">'+c.icon+'</div><div class="temp">'+c.t+'°</div><div class="meta"><b>'+c.name+' · '+c.desc+'</b><span>最高 '+c.hi+'° / 最低 '+c.lo+'°</span></div><div class="wsrc">数据来源 '+WX_SOURCE+' · '+WX_AS_OF+' 更新</div></div>';
}
function noteHTML(){
  return '<div class="w-note" contenteditable="true" spellcheck="false">下午三点练琴：卡农第 1—8 小节慢练。\n记得给阳台的花浇水 🌷</div>';
}
var TODO_DEFAULTS = [{t:'完成卡农跟弹练习',done:true},{t:'整理桌面小组件布局',done:false},{t:'给阳台的花浇水',done:false},{t:'读完《夜晚的潜水艇》',done:false}];
function todoHTML(it){
  if(!it.todos) it.todos = TODO_DEFAULTS.map(function(x){ return {t:x.t, done:x.done}; });
  var s = '<div class="w-todo"><h4>今日待办</h4><div class="tlist">';
  if(!it.todos.length) s += '<div class="tempty">还没有待办（右键小组件可添加待办）</div>';
  it.todos.forEach(function(r, i){ s += '<div class="trow'+(r.done?' done':'')+'" data-todo="'+i+'"><span class="dot">✓</span><span class="txt">'+escapeHtml(r.t)+'</span></div>'; });
  return s+'</div></div>';
}
function musicHTML(){
  return '<div class="w-music"><div class="cover">♪</div><div class="mi"><b>卡农 · Canon in C</b><span>帕赫贝尔 — 轻音乐电台</span><div class="bar"><i></i></div></div><button class="pbtn" type="button" aria-label="播放或暂停">▶</button></div>';
}
var WIDGET_TYPES = {
  clock:{name:'时钟', icon:'🕐', desc:'大字数字时钟', w:2,h:2, html:clockHTML},
  calendar:{name:'日历', icon:'📅', desc:'当月月历，今天高亮', w:2,h:2, html:calHTML},
  weather:{name:'天气', icon:'⛅', desc:'城市天气速览', w:3,h:1, html:function(it){ return weatherHTML(it); }},
  note:{name:'便签', icon:'📝', desc:'随手写，可直接编辑', w:2,h:2, html:noteHTML},
  todo:{name:'待办清单', icon:'✅', desc:'点一行划掉完成，右键可添加新待办', w:2,h:2, html:function(it){ return todoHTML(it); }},
  music:{name:'音乐', icon:'🎵', desc:'迷你播放器小组件', w:3,h:1, html:musicHTML}
};
/* 自定义小组件的整面画布：用户代码铺满整个小组件（没有系统标题栏），
   底色透明——不画背景时透出 .widget 那层和内置小组件一样的毛玻璃；
   用户自己的 <style>/<script> 在隔离框里正常跑，可以画满整面、放按钮、画 canvas。
   内容和内置小组件一样走「设计画布」：先按每格 96px 的设计尺寸（w×96 × h×96）放进
   #cwFit，再整体等比缩放贴进小组件的实际显示区——区域多大，字和画就按比例多大。
   这里的 w / h 是小组件「创建时」的设计格数（调用方传 it.dw / it.dh），不是当前框格数：
   框被拖大时设计画布不变、只有缩放变大，内容才会真的跟着变大；若每次重建都按当前
   框格数算设计尺寸，固定像素写的内容永远是一创建时那么大，拉大只会多出空白。
   缩放只靠隔离框里的小脚本 cwFit()：算好比例写成内联 transform，并在 DOMContentLoaded /
   load / resize / ResizeObserver / 加载后 300ms 多个时机反复纠正——某些手机 WebView
   里 iframe 的 resize 不触发、或脚本在尺寸为 0 时先跑过，也能自己恢复，不会缩在角落。
   （注：这里没有纯 CSS 的 vw/vh 兜底，cwFit() 没跑起来时内容保持 visibility:hidden；
   2026-11-13 审计时各路径均已实测跑通。）
   隔离框里还备好一组和内置小组件同款的观海配色变量（--cw-bg 毛玻璃底、--cw-ink 主文字、
   --cw-sub 次文字、--cw-line 描边、--cw-chip 浅蓝衬底、--cw-accent 海蓝、--cw-gold 浅金），
   随深浅外观自动换值；例子和用户代码用它们，就和内置小组件一个样子，不会再出现
   纯白底配纯白字这种看不见的画法。 */
function buildCustomSrcdoc(html, w, h){
  var light = document.body.classList.contains('light');
  var ink = light ? '#182238' : '#ffffff';
  var dw = Math.max(1, w||2)*96, dh = Math.max(1, h||2)*96;
  var vars = light
    ? '--cw-bg:linear-gradient(160deg,rgba(255,255,255,.80),rgba(203,227,248,.64));--cw-ink:#182238;--cw-sub:rgba(24,34,56,.62);--cw-line:rgba(23,32,64,.18);--cw-chip:rgba(47,155,255,.13);--cw-accent:#1490d8;--cw-gold:#9a7f4a'
    : '--cw-bg:linear-gradient(160deg,rgba(17,60,100,.66),rgba(4,20,38,.76));--cw-ink:#ffffff;--cw-sub:rgba(226,240,255,.72);--cw-line:rgba(168,216,255,.32);--cw-chip:rgba(125,211,252,.16);--cw-accent:#7dd3fc;--cw-gold:#c9a86a';
  var bridge = '<script>window.Canghai={'
    + 'openApp:function(id){parent.postMessage({__canghai:1,type:"openApp",appId:id},"*")},'
    + 'openSelf:function(){parent.postMessage({__canghai:1,type:"openApp"},"*")},'
    + 'toast:function(m){parent.postMessage({__canghai:1,type:"toast",message:String(m)},"*")},'
    + 'setContextMenu:function(items){parent.postMessage({__canghai:1,type:"setContextMenu",items:items},"*")},'
    + 'openModal:function(opts){opts=opts||{};parent.postMessage({__canghai:1,type:"openModal",title:opts.title||"",html:opts.html||""},"*")},'
    + 'closeModal:function(){parent.postMessage({__canghai:1,type:"closeModal"},"*")}};'
    + '<\/script>';
  return '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
    + '<meta name="color-scheme" content="'+(light?'light':'dark')+'">'
    + '<style>:root{'+vars+'}html,body{height:100%;background:transparent}body{margin:0;padding:0;position:relative;color:'+ink+';font-family:"PingFang SC","Microsoft YaHei",system-ui,sans-serif;overflow:hidden}'
    + '*{box-sizing:border-box}img,svg,canvas{max-width:100%}'
    + '#cwFit{position:absolute;left:50%;top:50%;width:'+dw+'px;height:'+dh+'px;transform-origin:center;overflow:hidden;visibility:hidden;transform:translate(-50%,-50%)}</style></head>'
    + '<body>' + bridge + '<div id="cwFit">'+(html||'')+'</div>'
    + '<script>function cwFit(){var f=document.getElementById("cwFit");if(!f)return;var w=innerWidth,h=innerHeight;if(!w||!h)return;var s=Math.min(w/'+dw+',h/'+dh+');f.style.transform="translate(-50%,-50%) scale("+s+")";f.style.visibility="visible"}'
    + 'cwFit();document.addEventListener("DOMContentLoaded",cwFit);addEventListener("load",cwFit);addEventListener("resize",cwFit);'
    + 'if(window.ResizeObserver){try{new ResizeObserver(cwFit).observe(document.documentElement)}catch(e){}}'
    + 'requestAnimationFrame(cwFit);setTimeout(cwFit,300);<\/script>'
    + '</body></html>';
}