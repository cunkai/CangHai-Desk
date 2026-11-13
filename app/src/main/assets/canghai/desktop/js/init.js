/* ========================================================
 * 沧海系统桌面 - 初始化与双端同步 (init.js)
 * 职责：开机数据恢复、桌面布局初次呈现、跨屏增量平滑同步
 * ======================================================== */

/* ================= 快捷键 ================= */
document.addEventListener('keydown', function(e){
  if((e.ctrlKey || e.metaKey) && (e.key==='k' || e.key==='K')){
    e.preventDefault();
    if(spotMask.classList.contains('open')) closeSpot(); else openSpot();
    return;
  }
  if(e.key==='Escape'){ closeMenu(); ccPanel.classList.remove('open'); calPop.classList.remove('open'); if(lpIsOpen()){ if(lpOpenFolderRef) closeLpFolder(); else closeLaunchpad(); } }
  if(e.key==='Enter' && document.getElementById('lockScreen').classList.contains('show')) unlockScreen();
});

/* ================= 时钟 ================= */
function updateClocks(){
  var now = new Date();
  var wd = ['日','一','二','三','四','五','六'][now.getDay()];
  items.forEach(function(it){
    if(it.type!=='widget' || it.wtype!=='clock') return;
    var hm = it.el.querySelector('.ck-hm'), s = it.el.querySelector('.ck-s'), ap = it.el.querySelector('.ck-ap'), dt = it.el.querySelector('.ck-date');
    if(!hm) return;
    var h = now.getHours();
    if(it.h24===false){
      if(ap) ap.textContent = h<12 ? '上午' : '下午';
      h = h%12 || 12;
      hm.textContent = h+':'+pad(now.getMinutes())+':';
    } else {
      if(ap) ap.textContent = '';
      hm.textContent = pad(h)+':'+pad(now.getMinutes())+':';
    }
    if(s) s.textContent = pad(now.getSeconds());
    if(dt) dt.textContent = now.getFullYear()+' 年 '+(now.getMonth()+1)+' 月 '+now.getDate()+' 日 · 星期'+wd;
  });
}
function tick(){
  var now = new Date();
  var wd = ['日','一','二','三','四','五','六'][now.getDay()];
  var __ct = document.getElementById('clockTime'); if(__ct) __ct.textContent = pad(now.getHours())+':'+pad(now.getMinutes());
  var __cd = document.getElementById('clockDate'); if(__cd) __cd.textContent = (now.getMonth()+1)+'月'+now.getDate()+'日 星期'+wd;
  document.getElementById('lockTime').textContent = pad(now.getHours())+':'+pad(now.getMinutes());
  document.getElementById('lockDate').textContent = now.getFullYear()+' 年 '+(now.getMonth()+1)+' 月 '+now.getDate()+' 日 · 星期'+wd;
  updateClocks();
}
setInterval(tick, 1000);

/* ================= Toast ================= */
var toastTimer = null;
function toast(msg){
  if(state.dnd) return;
  var t = document.getElementById('toast');
  t.textContent = msg; t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(function(){ t.classList.remove('show'); }, 2600);
}

/* ================= 桌面持久化（按外屏唯一 key 存在 Native） =================
   曾经桌面每次启动都是全新默认、关闭也不保存。现在：
   - 保存：MutationObserver 盯着整页的条目增删 / 移动 / 改名 / 设置切换，
     防抖 1.2s 后把整份桌面快照写进 Native（内容没变就不写）；关页前补一次。
   - 恢复：启动时按这块屏的唯一 key 取回快照；取不到才走默认桌面。
   - 已卸载的软件不复活：恢复时按当前应用注册表过滤。 */
function deskSnapshot(){
  var clean = [];
  items.forEach(function(it){
    var o = {};
    for(var k in it){
      if(k==='el' || k.charAt(0)==='_') continue;
      var v = it[k];
      if(typeof v === 'function' || v == null) continue;
      o[k] = v;
    }
    clean.push(o);
  });
  var stickies = [];
  if(typeof stickyLayer !== 'undefined' && stickyLayer){
    var LW = (stickyLayer.clientWidth || window.innerWidth || 1);
    var LH = (stickyLayer.clientHeight || window.innerHeight || 1);
    stickyLayer.querySelectorAll('.stickyNote').forEach(function(el){
      var st = el._st || {};
      var ta = el.querySelector('.snText');
      var curX = st.x != null ? st.x : (el.offsetLeft || 0);
      var curY = st.y != null ? st.y : (el.offsetTop || 0);
      var xp = (st.xp != null) ? st.xp : ((LW > 50) ? Number((curX / LW).toFixed(4)) : 0.2);
      var yp = (st.yp != null) ? st.yp : ((LH > 50) ? Number((curY / LH).toFixed(4)) : 0.2);
      var curCell = (typeof CELL === 'number' && CELL > 0) ? CELL : 96;
      var curW = Math.round(st.w || el.offsetWidth || 216);
      var curH = Math.round(st.h || el.offsetHeight || 212);
      var wRatio = st.wRatio != null ? st.wRatio : Number((curW / curCell).toFixed(3));
      var hRatio = st.hRatio != null ? st.hRatio : Number((curH / curCell).toFixed(3));
      stickies.push({
        x: Math.round(curX),
        y: Math.round(curY),
        xp: xp,
        yp: yp,
        w: curW,
        h: curH,
        wRatio: wRatio,
        hRatio: hRatio,
        color: st.color != null ? st.color : 0,
        bg: st.bg || '',
        textColor: st.textColor || '',
        fontSize: st.fontSize || 0,
        text: ta ? ta.value : '',
        welcome: el.classList.contains('welcome')
      });
    });
  }
  return { v:1, items:clean, dockPins:dockPins.slice(),
    stickies: stickies,
    state:{ light:!!state.light, iconSize:state.iconSize||'m', dockSide:state.dockSide||'bottom', dockAutoHide:!!state.dockAutoHide, showBrandLogo:state.showBrandLogo!==false },
    wallpaper: window.__hasCustomWallpaper ? (window.__wpCls || '') : '',
    grid:[GRID_COLS, GRID_ROWS] };
}
var deskSaveTimer = null, deskSaveSig = '';
var isRemoteSyncing = false, remoteSyncTimer = null;
function deskSaveNow(){
  if(isRemoteSyncing) return; // 接受同步者绝不反向回写，杜绝双屏打架与无限循环
  try{
    if(!chNativeReady() || !Canghai.saveDeskState) return;
    var s = JSON.stringify(deskSnapshot());
    if(s === deskSaveSig) return;
    deskSaveSig = s;
    Canghai.saveDeskState(s);
  }catch(e){}
}
function deskSaveSoon(){
  if(isRemoteSyncing) return;
  if(deskSaveTimer) return;
  deskSaveTimer = setTimeout(function(){ deskSaveTimer = null; deskSaveNow(); }, 1200);
}
window.addEventListener('pagehide', function(){
  if(deskSaveTimer){ clearTimeout(deskSaveTimer); deskSaveTimer = null; }
  deskSaveNow();
});
var DESK_RESTORED = false;
function deskRestore(s){
  try{
    var d = JSON.parse(s);
    if(!d || d.v !== 1) return false;
    if(d.state){
      if(d.state.light) setTheme(true);
      if(d.state.iconSize) state.iconSize = d.state.iconSize;
      if(d.state.dockSide) state.dockSide = d.state.dockSide;
      if(d.state.dockAutoHide !== undefined) chSetDockAutoHide(d.state.dockAutoHide);
      if(d.state.showBrandLogo !== undefined) setBrandLogoVisible(d.state.showBrandLogo);
    }
    if(d.grid && d.grid[0] && d.grid[1]){ GRID_COLS = d.grid[0]; GRID_ROWS = d.grid[1]; computeGrid(); }
    if(d.wallpaper){
      window.__hasCustomWallpaper = true;
      setWallpaper(d.wallpaper, true);
    } else {
      window.__hasCustomWallpaper = false;
      setWallpaper(state.light ? 'wp-forest' : 'wp-dusk', false);
    }
    var alive = {};
    ALL_APPS.forEach(function(a){ alive[a.id] = 1; });
    (d.items||[]).forEach(function(o){
      try{
        if(o.type==='app'){
          if(!alive[o.appId]) return;               /* 已卸载：不复活 */
          renderAppCell(makeItem(o));
        } else if(o.type==='folder'){
          o.apps = (o.apps||[]).filter(function(id){ return alive[id]; });
          if(Array.isArray(o.entries)){
            o.entries = o.entries.filter(function(en){ return en && (en.kind==='txt' || alive[en.appId]); });
          }
          renderFolderCell(makeItem(o));
        } else if(o.type==='widget'){
          renderWidget(makeItem(o));
        } else if(o.type==='file' || (o.type==='txt' && o.name==='开发软件提示词.txt')){
          o.type = 'txt';
          if(!o.content) o.content = (typeof buildDevPrompt==='function') ? buildDevPrompt() : DEV_APP_PROMPT;
          renderTxtCell(makeItem(o));
        } else if(o.type==='txt'){
          renderTxtCell(makeItem(o));
        }
      }catch(e){}
    });
    if(d.dockPins) dockPins = d.dockPins.filter(function(id){ return alive[id]; });
    if(Array.isArray(d.stickies) && d.stickies.length > 0){
      if(typeof stickyLayer !== 'undefined' && stickyLayer){
        stickyLayer.innerHTML = '';
        d.stickies.forEach(function(sn){
          createStickyNote({
            silent: true,
            x: sn.x,
            y: sn.y,
            xp: sn.xp,
            yp: sn.yp,
            w: sn.w,
            h: sn.h,
            wRatio: sn.wRatio,
            hRatio: sn.hRatio,
            color: sn.color,
            bg: sn.bg,
            textColor: sn.textColor,
            fontSize: sn.fontSize,
            text: sn.text,
            welcome: !!sn.welcome
          });
        });
        DESK_RESTORED = true;
        setTimeout(function(){
          var LW = stickyLayer.clientWidth || window.innerWidth;
          var LH = stickyLayer.clientHeight || window.innerHeight;
          if(!LW || !LH || LW < 100 || LH < 100) return;
          stickyLayer.querySelectorAll('.stickyNote').forEach(function(el){
            var st = el._st;
            if(!st || st.dragging) return;
            if(st.xp != null && st.yp != null){
              st.x = clamp(Math.round(st.xp * LW), 4, Math.max(4, LW - st.w - 4));
              st.y = clamp(Math.round(st.yp * LH), 4, Math.max(4, LH - st.h - 4));
              st.rx = st.x; st.ry = st.y - 16;
              applySticky(st, el);
            }
          });
        }, 120);
      }
    }
    return true;
  }catch(e){ return false; }
}

window.addEventListener('resize', function(){
  if(typeof stickyLayer === 'undefined' || !stickyLayer) return;
  var LW = stickyLayer.clientWidth || window.innerWidth;
  var LH = stickyLayer.clientHeight || window.innerHeight;
  if(!LW || !LH || LW < 100 || LH < 100) return;
  stickyLayer.querySelectorAll('.stickyNote').forEach(function(el){
    var st = el._st;
    if(!st || st.dragging) return;
    if(st.xp != null && st.yp != null){
      st.x = clamp(Math.round(st.xp * LW), 4, Math.max(4, LW - st.w - 4));
      st.y = clamp(Math.round(st.yp * LH), 4, Math.max(4, LH - st.h - 4));
      st.rx = st.x; st.ry = st.y - 16;
      applySticky(st, el);
    }
  });
});

/* 跨屏平滑增量同步：杜绝全屏销毁重建，杜绝小组件白屏闪烁与双端死循环 */
window.__canghaiSyncDesktop = function(s){
  if(!s || s === deskSaveSig) return;
  deskSaveSig = s;
  isRemoteSyncing = true;
  clearTimeout(remoteSyncTimer);
  remoteSyncTimer = setTimeout(function(){ isRemoteSyncing = false; }, 3000);
  try{
    var d = (typeof s === 'string') ? JSON.parse(s) : s;
    if(!d || !Array.isArray(d.items)) return;
    var newItems = d.items;
    var aliveMap = {};
    newItems.forEach(function(ni, idx){
      var key = ni.type + ':' + (ni.appId || ni.widgetId || ni.name || idx);
      aliveMap[key] = ni;
    });

    // 1. 移除远端已删除的条目
    items.slice().forEach(function(it){
      var key = it.type + ':' + (it.appId || it.widgetId || it.name || '');
      if(!aliveMap[key]){
        removeItem(it);
      }
    });

    // 2. 存在则只更新位置和网格，不存在才新建（小组件绝不销毁 iframe！）
    newItems.forEach(function(ni, idx){
      var key = ni.type + ':' + (ni.appId || ni.widgetId || ni.name || idx);
      var existing = null;
      for(var i=0; i<items.length; i++){
        var it = items[i];
        if(it.type === ni.type && (it.appId === ni.appId || it.widgetId === ni.widgetId || (it.name && it.name === ni.name))){
          existing = it;
          break;
        }
      }
      if(existing){
        // 已有条目：仅在位置或尺寸变化时更新坐标，不重新渲染 DOM
        if(existing.col !== ni.col || existing.row !== ni.row || existing.w !== ni.w || existing.h !== ni.h){
          existing.col = ni.col; existing.row = ni.row;
          existing.w = ni.w; existing.h = ni.h;
          posEl(existing);
        }
      } else {
        // 新增条目
        if(ni.type === 'app'){
          renderAppCell(makeItem(ni));
        } else if(ni.type === 'widget'){
          renderWidget(makeItem(ni));
        } else if(ni.type === 'txt' || ni.type === 'file'){
          renderTxtCell(makeItem(ni));
        } else if(ni.type === 'folder'){
          renderFolderCell(makeItem(ni));
        }
      }
    });

    if(d.dockPins && Array.isArray(d.dockPins)){
      dockPins = d.dockPins.slice();
      chPushDockRunState();
    }
    if(Array.isArray(d.stickies) && typeof stickyLayer !== 'undefined' && stickyLayer){
      var existingNotes = stickyLayer.querySelectorAll('.stickyNote');
      d.stickies.forEach(function(sn, sIdx){
        var el = existingNotes[sIdx];
        if(el && el._st && !el._st.dragging){
          var LW = stickyLayer.clientWidth || window.innerWidth || 1;
          var LH = stickyLayer.clientHeight || window.innerHeight || 1;
          if(sn.xp != null && sn.yp != null && LW > 100 && LH > 100){
            el._st.x = Math.round(sn.xp * LW);
            el._st.y = Math.round(sn.yp * LH);
            el._st.rx = el._st.x; el._st.ry = el._st.y - 16;
            el._st.xp = sn.xp; el._st.yp = sn.yp;
            applySticky(el._st, el);
          }
        }
      });
    }
    applyDesk();
  }catch(e){}
};

/* ================= 初始化 ================= */
function initDesktopItems(){
  layoutDock();
  DESK_RESTORED = false;
  try{
    if(chNativeReady() && Canghai.getDeskState){
      var s = Canghai.getDeskState();
      if(s) DESK_RESTORED = deskRestore(s);
    }
  }catch(e){ DESK_RESTORED = false; }
  if(DESK_RESTORED){
    layoutDock();
  }else{
    placeApps();
    addWidget('clock', COLS - 2);
    addWidget('weather', COLS - 3);
    addWidget('todo', COLS - 2);
  }
  applyDesk();
  // 杜绝使用 MutationObserver 监听 body 触发保存（会导致每秒时钟小组件改变时死循环保存并整屏闪烁！）
}
/* 开机顺序：先把原生那份真数据装进注册表，再摆桌面、再画 Dock */
var BOOT = chBoot();
if(BOOT){
  if(BOOT.language && window.CHI18n){ CHI18n.setLang(BOOT.language); }
  if(BOOT.wallpaper){ window.__hasCustomWallpaper = true; setWallpaper(BOOT.wallpaper, true); }
  else { setWallpaper(state.light ? 'wp-forest' : 'wp-dusk', false); }
  if(BOOT.gridCols && BOOT.gridRows){
    GRID_COLS = BOOT.gridCols; GRID_ROWS = BOOT.gridRows; computeGrid();
  }
}
/* Dock 归属：悬浮层建起来了就由它接管（它永远在原生应用窗口之上） */
try{
  if(typeof Canghai !== 'undefined' && Canghai && Canghai.isDockOverlayOn && Canghai.isDockOverlayOn()){
    document.documentElement.classList.add('dockOverlay');
  }
  /* 开机读回原生持久化的「隐藏 Dock」设置（默认开），并同步控制中心开关状态 */
  if(typeof Canghai !== 'undefined' && Canghai && Canghai.getDockAutoHide){
    state.dockAutoHide = !!Canghai.getDockAutoHide();
    var ccHideSw = document.getElementById('ccHide');
    if(ccHideSw) ccHideSw.classList.toggle('on', state.dockAutoHide);
  }
}catch(e){}
if(!document.documentElement.classList.contains('dockOverlay')) renderDock();
refreshDockVis();
applyCursor();
buildPresets();
initDesktopItems();
tick();
chConsumePendingOpen();
/* 开机欢迎便签：贴在右侧空地（待办小组件左侧，不压任何组件），右上角有 ✕，点一下就能直接关掉；
   文字只留真正有用的几条，不再另弹欢迎 Toast */
setTimeout(function(){
  /* 恢复过保存的桌面就不弹欢迎便签：那是给第一次见面的人看的 */
  if(!DESK_RESTORED) {
    createStickyNote({ welcome:true, silent:true, at:'br', wRatio:2.875, hRatio:2.792,
      text:'欢迎来到沧海系统\n点 Dock 最左侧的启动台看全部应用；点「便签」在桌面贴一张漂浮便签。\n右键桌面有更多操作 · Ctrl+K 聚焦搜索' });
  }
}, 600);

var resizeT = null;
window.addEventListener('resize', function(){
  clearTimeout(resizeT);
  resizeT = setTimeout(function(){
    /* 分辨率变了只重算格子大小和中心位置，格坐标一个不动，布局不乱 */
    computeGrid();
    items.forEach(function(it){ posEl(it); });
  }, 180);
});

/* ================= 真实系统状态（电池 + WiFi / 有线 / 断网） ================= */
var NET_SVGS = {
  wifi: '<path d="M8 10.6a1.7 1.7 0 1 1 0 3.4 1.7 1.7 0 0 1 0-3.4zM8 6.4c1.7 0 3.3.7 4.5 1.9l-1.6 1.6A4.2 4.2 0 0 0 8 9.1c-1.1 0-2.1.4-2.9 1.2L3.5 8.3A6.3 6.3 0 0 1 8 6.4zM8 2c2.8 0 5.4 1.1 7.4 3L13.8 6.6A8.6 8.6 0 0 0 8 4.7c-2.1 0-4.2.8-5.8 2.3L.6 5A11.3 11.3 0 0 1 8 2z" transform="translate(0,-2.4)"/>',
  ethernet: '<path d="M3 1h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-1v2h2v2H2v-2h2v-2H3a2 2 0 0 1-2-2V3a2 2 0 0 1 2-2zm1 3v2h2V4H4zm4 0v2h2V4H8z" fill="currentColor"/>',
  cellular: '<path d="M1 10h2v2H1zm4-3h2v5H5zm4-3h2v8H9zm4-3h2v11h-2z" fill="currentColor"/>',
  none: '<g><path d="M8 10.6a1.7 1.7 0 1 1 0 3.4 1.7 1.7 0 0 1 0-3.4zM8 6.4c1.7 0 3.3.7 4.5 1.9l-1.6 1.6A4.2 4.2 0 0 0 8 9.1c-1.1 0-2.1.4-2.9 1.2L3.5 8.3A6.3 6.3 0 0 1 8 6.4zM8 2c2.8 0 5.4 1.1 7.4 3L13.8 6.6A8.6 8.6 0 0 0 8 4.7c-2.1 0-4.2.8-5.8 2.3L.6 5A11.3 11.3 0 0 1 8 2z" opacity="0.3" transform="translate(0,-2.4)"/><line x1="2" y1="1" x2="14" y2="11" stroke="#ff6b81" stroke-width="2.2" stroke-linecap="round"/></g>'
};

window.updateSystemStatus = function(info) {
  if (!info) return;
  try {
    if (typeof info === 'string') info = JSON.parse(info);
    var bat = info.battery;
    if (bat && typeof bat.level === 'number') {
      var lvl = Math.max(0, Math.min(100, bat.level));
      var batValEl = document.getElementById('topBatVal');
      var batLvlEl = document.getElementById('topBatLevel');
      var batBoltEl = document.getElementById('topBatBolt');
      var batBox = document.getElementById('topBatBox');
      if (batValEl) batValEl.textContent = lvl + '%';
      if (batLvlEl) {
        batLvlEl.setAttribute('width', Math.max(1, Math.round(14 * (lvl / 100))));
        batLvlEl.setAttribute('fill', lvl < 20 ? '#ff6b81' : (bat.charging ? '#4ade80' : '#4fd8e8'));
      }
      if (batBoltEl) batBoltEl.style.display = bat.charging ? 'inline' : 'none';
      if (batBox) batBox.title = '系统电池电量：' + lvl + '%' + (bat.charging ? ' (充电中)' : '');
    }

    var netType = info.net || 'none';
    var netIcoEl = document.getElementById('topNetIco');
    var netBox = document.getElementById('topNetBox');
    if (netIcoEl && NET_SVGS[netType]) {
      netIcoEl.innerHTML = NET_SVGS[netType];
      var netTitles = { wifi: '无线网络 WiFi 已连接', ethernet: '有线以太网已连接', cellular: '蜂窝移动网络已连接', none: '网络已断开' };
      if (netBox) netBox.title = netTitles[netType] || '网络状态';
    }
  } catch (e) {}
};

function pollSystemStatus() {
  try {
    var raw = '';
    if (typeof Canghai !== 'undefined' && Canghai.getSystemInfoJson) raw = Canghai.getSystemInfoJson();
    else if (typeof Aosen !== 'undefined' && Aosen.getSystemInfoJson) raw = Aosen.getSystemInfoJson();
    if (raw) window.updateSystemStatus(raw);
  } catch (e) {}
}
pollSystemStatus();
setInterval(pollSystemStatus, 12000);
