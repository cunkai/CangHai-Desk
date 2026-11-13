/* ========================================================
 * 沧海系统桌面 - 核心基础设施 (core.js)
 * 职责：全局错误捕获、原生通信桥接、应用注册表、菜单栏状态、壁纸与主题、手柄快捷键
 * ======================================================== */
window.addEventListener('error', function(e){
  try {
    var msg = (e && e.message) ? e.message : String(e);
    var loc = (e && e.filename) ? (e.filename + ':' + e.lineno + ':' + e.colno) : '';
    if (window.Canghai && window.Canghai.reportJsError) window.Canghai.reportJsError('大屏桌面', msg, loc);
    else if (window.Aosen && window.Aosen.reportJsError) window.Aosen.reportJsError('大屏桌面', msg, loc);
  } catch(err){}
});
window.addEventListener('unhandledrejection', function(e){
  try {
    var reason = (e && e.reason) ? (e.reason.stack || e.reason.message || String(e.reason)) : 'Promise Rejected';
    if (window.Canghai && window.Canghai.reportJsError) window.Canghai.reportJsError('大屏桌面', reason, 'unhandledrejection');
    else if (window.Aosen && window.Aosen.reportJsError) window.Aosen.reportJsError('大屏桌面', reason, 'unhandledrejection');
  } catch(err){}
});


/* ================= 应用定义（文件管理 / 音乐 / 便签 / 设置） ================= */
var ICONS = {
  finder:'<svg viewBox="0 0 48 48"><rect x="4" y="4" width="40" height="40" rx="9" fill="rgba(255,255,255,.22)"/><path d="M14 17c3-2 6-2 8 1M26 17c3-2 6-2 8 1" stroke="#08306b" stroke-width="2.6" fill="none" stroke-linecap="round"/><circle cx="17" cy="23" r="2.2" fill="#08306b"/><circle cx="31" cy="23" r="2.2" fill="#08306b"/><path d="M24 20v7h4" stroke="#08306b" stroke-width="2.4" fill="none" stroke-linecap="round"/><path d="M14 31c6 5 14 5 20 0" stroke="#08306b" stroke-width="2.6" fill="none" stroke-linecap="round"/></svg>',
  settings:'<svg viewBox="0 0 48 48"><path fill="#e8e8ee" stroke="#9a9aa5" stroke-width="1" d="M24 5l3.2 4.6 5.4-1.5 1 5.5 5.4 1.8-2.3 5.1 2.3 5.1-5.4 1.8-1 5.5-5.4-1.5L24 36l-3.2-4.6-5.4 1.5-1-5.5L9 25.6l2.3-5.1L9 15.4l5.4-1.8 1-5.5 5.4 1.5z" transform="translate(0 3) scale(.94)"/><circle cx="24" cy="24" r="6.4" fill="#b9b9c4" stroke="#8a8a96"/></svg>',
  folder:'<svg viewBox="0 0 48 48"><path d="M6 15a4 4 0 0 1 4-4h8.6c1.2 0 2.3.5 3 1.4L24 15h14a4 4 0 0 1 4 4v2H6z" fill="#e8a020"/><path d="M6 20h36v15a4 4 0 0 1-4 4H10a4 4 0 0 1-4-4z" fill="#ffc93c"/><path d="M6 20h36v3.5H6z" fill="#ffd76a"/></svg>',
  launchpad:'<svg viewBox="0 0 48 48"><g fill="#ffffff"><rect x="7" y="7" width="9.5" height="9.5" rx="2.6"/><rect x="19.2" y="7" width="9.5" height="9.5" rx="2.6" opacity=".82"/><rect x="31.5" y="7" width="9.5" height="9.5" rx="2.6" opacity=".64"/><rect x="7" y="19.2" width="9.5" height="9.5" rx="2.6" opacity=".82"/><rect x="19.2" y="19.2" width="9.5" height="9.5" rx="2.6"/><rect x="31.5" y="19.2" width="9.5" height="9.5" rx="2.6" opacity=".82"/><rect x="7" y="31.5" width="9.5" height="9.5" rx="2.6" opacity=".64"/><rect x="19.2" y="31.5" width="9.5" height="9.5" rx="2.6" opacity=".82"/><rect x="31.5" y="31.5" width="9.5" height="9.5" rx="2.6"/></g></svg>',
  musicx:'<svg viewBox="0 0 48 48"><circle cx="24" cy="24" r="18" fill="rgba(255,255,255,.22)"/><path d="M29 13v16.2a5.4 5.4 0 1 1-3-4.8V17l9-2.2V29a5.4 5.4 0 1 1-3-4.8V13z" fill="#ffffff" transform="translate(-3.5 1) scale(.96)"/></svg>',
  sticky:'<svg viewBox="0 0 48 48"><path d="M11 7h26a1 1 0 0 1 1 1v25.5L29.5 42H11a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z" fill="#ffef9d"/><path d="M38 33.5 29.5 42v-6.8c0-.9.7-1.7 1.7-1.7z" fill="#e8cf6a"/><path d="M16 24h16M16 29.5h11" stroke="#c9ae4a" stroke-width="2.2" stroke-linecap="round"/><path d="M23.2 13.5h1.6l-.6 6.4h-.4z" fill="#9aa7b0"/><circle cx="24" cy="10.5" r="5.6" fill="#e2483d"/><ellipse cx="22.2" cy="8.6" rx="2" ry="1.4" fill="rgba(255,255,255,.55)"/></svg>',
  devdoc:'<svg viewBox="0 0 48 48"><path d="M13 5h15l8 8v29a2 2 0 0 1-2 2H13a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z" fill="#f4f9ff" stroke="#b9d2e8"/><path d="M28 5l8 8h-6a2 2 0 0 1-2-2z" fill="#cfe4f7"/><path d="M16 21h16M16 27h16M16 33h10" stroke="#2f7fe0" stroke-width="2.4" stroke-linecap="round"/></svg>'
};
/* 内置图标统一来自 ../shared/apps.js，页面里不再各存一份 */
if(window.CHApps && CHApps.ICONS){
  Object.keys(CHApps.ICONS).forEach(function(k){ if(!ICONS[k]) ICONS[k]=CHApps.ICONS[k]; });
}
/* ================= 应用注册表（全部来自真机，不再写死） =================
   三类来源：
   1) 系统自带：设置 / 调试外设 / 便签 —— 沧海自己的软件，自己渲染、自己管层级
   2) 沧海软件：手机端装进来的 HTML，源码由原生保管，这里只取 id / 名字 / 图标
   3) 手机应用：真机 LAUNCHER 列表，打开时交给原生投到这块屏（系统自由窗口）
   图标不落图片文件：原生给真机图标就走真机图标，没有就按名字生成稳定渐变 + 首字，
   手机端用同一套算法，两边永远对得上。 */
var BUILTIN_APPS = (window.CHApps ? CHApps.BUILTIN.slice() : []);
if(!BUILTIN_APPS.length){
  BUILTIN_APPS = [
    {id:'settings',   kind:'system', name:'设置',     grad:'linear-gradient(160deg,#e8eef4,#5a6b7d)'},
    {id:'inputtrace', kind:'system', name:'调试外设', grad:'linear-gradient(160deg,#7fe3ff,#1a6fd0)'},
    {id:'sticky',     kind:'system', name:'便签',     grad:'linear-gradient(160deg,#ffe97a,#f5b921)'}
  ];
}
var HTML_APPS = [], NATIVE_APPS = [];
var APPS = BUILTIN_APPS.slice();
var ALL_APPS = APPS.slice();
function appById(id){ for(var i=0;i<ALL_APPS.length;i++) if(ALL_APPS[i].id===id) return ALL_APPS[i]; return BUILTIN_APPS[0]; }
/* 沧海调色板：唯一实现在 ../shared/appcolor.js，这里只是取用，不要在页面里另存一份 */
var CH_GRADS = (window.CHColor ? CHColor.GRADS : ['linear-gradient(160deg,#6fd3ff,#2f7fe0)']);
function chGradFor(seed){ return window.CHColor ? CHColor.gradFor(seed) : CH_GRADS[0]; }
var lpItems = [];
function syncLaunchpadRegistry(){
  if(!Array.isArray(lpItems)) lpItems = [];
  var existingMap = {};
  lpItems.forEach(function(x){
    if(x.kind === 'app') existingMap[x.appId] = 1;
    else if(x.kind === 'folder' && Array.isArray(x.apps)) x.apps.forEach(function(aid){ existingMap[aid] = 1; });
  });
  ALL_APPS.forEach(function(a){
    if(!existingMap[a.id]){
      lpItems.push({kind:'app', appId:a.id});
    }
  });
}
function rebuildAppRegistry(html, native){
  HTML_APPS = html || []; NATIVE_APPS = native || [];
  APPS = BUILTIN_APPS.concat(HTML_APPS, NATIVE_APPS);
  ALL_APPS = APPS.slice();
  APPS.forEach(function(a){
    if(!a.grad) a.grad = chGradFor(a.name || a.id);
    if(!ICONS[a.id]) ICONS[a.id] = a.icon || instLetterIcon(a.name || a.id);
  });
  dockPins = chDefaultDockPins();
  syncLaunchpadRegistry();
}

/* ================= 全局状态 ================= */
var state = { brightness:1, volume:.8, dnd:false, light:false, dockSide:'bottom', dockAutoHide:false, showBrandLogo:true, iconSize:'m' };
var curDesk = 0; /* 单桌面：多桌面模式已去掉，桌面只有这一个 */
/* 默认 Dock 钉选（用户定，2026-11-13 晚改）：音乐、一个游戏、一个文本、便签四个，
   其余应用默认只在启动台。快照里有用户自己的钉选时以快照为准，这里只定「全新桌面」
   的默认。四个一个都没注册进来时（比如只剩系统应用的兜底注册表）退回旧规则，避免 Dock 全空。 */
var DEFAULT_DOCK_PINS = ['huiyin', 'jump', 'markdown', 'sticky', 'huaban'];
function chDefaultDockPins(){
  var pins = [];
  DEFAULT_DOCK_PINS.forEach(function(id){
    for(var i = 0; i < APPS.length; i++) if(APPS[i].id === id){ pins.push(id); break; }
  });
  if(!pins.length){
    pins = APPS.filter(function(a){ return a.dock !== false; }).map(function(a){ return a.id; });
  }
  return pins;
}
var dockPins = chDefaultDockPins();
var recentApps = [];

function clamp(v,a,b){ return Math.max(a, Math.min(b, v)); }
function pad(n){ return (n<10?'0':'')+n; }
function escapeHtml(s){ return String(s).replace(/[&<>"']/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }


/* 特权判断只有一个来源：原生 stateJson 里的 privilege 位。
   缺这个字段（老版本原生）按「有特权」处理，避免整个桌面突然空掉。 */
function chPrivOk(st){
  if(!st) return true;
  return (st.privilege === undefined) ? true : !!st.privilege;
}

function chBoot(){
  if(!chNativeReady()) return null;
  var st = null;
  try{ st = JSON.parse(Canghai.getState()); }catch(e){ return null; }
  /* 内置 HTML 软件（canghai/apps/ 文件夹，清单已注册进 BUILTIN）优先：桥再报一遍时去重，别出现两个 */
  var builtinIds = {};
  BUILTIN_APPS.forEach(function(b){ builtinIds[b.id] = 1; });
  var htmlApps = (st.htmlApps||[]).filter(function(a){ return !builtinIds[a.id]; }).map(function(a){
    return {id:a.id, name:a.name, kind:'html', installed:true, icon:chAppIcon(a), grad:chGradFor(a.name)};
  });
  /* 没有特权（Root / Shizuku 任一）就不往桌面塞安卓应用：
     列出来也点不开，不如干脆不显示，手机上会在应用台里说明原因。 */
  var nativeApps = chPrivOk(st) ? (st.apps||[]).map(function(a){
    return {id:a.id, name:a.name, kind:'native', comp:a.comp, pkg:a.pkg, icon:a.icon||null, grad:chGradFor(a.name), dock:false};
  }) : [];
  rebuildAppRegistry(htmlApps, nativeApps);
  chApplyCursorPrefs(st);
  __chLastAppSig = chAppSig(st);
  return st;
}
var userProfile = { name:'沧海用户', avatar:'沧' };
function renderAvatarBox(el, avatar, fallbackName){
  if(!el) return;
  var av = String(avatar || '').trim();
  if(/^data:image\//i.test(av)){
    el.innerHTML = '<img src="'+av.replace(/"/g,'&quot;')+'" alt="">';
  } else {
    el.textContent = av || String(fallbackName || '沧').trim().charAt(0) || '沧';
  }
}
function applyUserProfile(){
  var nm = (userProfile.name || '').trim() || '沧海用户';
  var av = (userProfile.avatar || '').trim() || nm.charAt(0) || '沧';
  var ab = document.getElementById('avatarBtn');
  renderAvatarBox(ab, av, nm);
  if(ab) ab.title = nm + ' · 点击锁定屏幕';
  renderAvatarBox(document.getElementById('lockAvatar'), av, nm);
  document.querySelectorAll('#lockScreen .lkName').forEach(function(n){ n.textContent = nm; });
  document.querySelectorAll('.win .setUserAvatarPrev').forEach(function(p){ renderAvatarBox(p, av, nm); });
  document.querySelectorAll('.win .setUserNameInp').forEach(function(inp){
    if(document.activeElement !== inp) inp.value = nm;
  });
}
function saveUserProfile(name, avatar){
  if(name != null) userProfile.name = String(name).trim() || '沧海用户';
  if(avatar != null) userProfile.avatar = String(avatar).trim() || (userProfile.name.charAt(0) || '沧');
  applyUserProfile();
  try{
    if(typeof Canghai !== 'undefined' && Canghai && Canghai.setUserProfile){
      Canghai.setUserProfile(userProfile.name, userProfile.avatar);
    }
  }catch(e){}
}
/* 鼠标样式 / 大小 / 速度只有一套：手机端改了，Native 直接推到这里，页面不用刷新 */
function chApplyCursorPrefs(st){
  if(!st) return;
  if(st.cursorLib){
    cursorLib = st.cursorLib.filter(function(x){ return !x.builtin; }).map(function(x){
      return {id:x.id, name:x.name||'自定义指针', svg:x.svg||'', hotX:(x.hx==null?50:x.hx), hotY:(x.hy==null?50:x.hy)};
    });
  }
  var cc = st.cursorCurrent || 'arrow';
  cursorSel = (cc==='dot'||cc==='block'||cc==='arrow') ? cc : ('lib:'+cc);
  if(st.cursorSize) cursorSize = clamp(st.cursorSize, 1, 9999);
  if(st.cursorSpeed) cursorSpeed = st.cursorSpeed;
  if(st.userName != null) userProfile.name = st.userName || '沧海用户';
  if(st.userAvatar != null) userProfile.avatar = st.userAvatar || '沧';
  applyCursor();
  applyUserProfile();
}
window.__canghaiSyncCursor = function(st){ try{ chApplyCursorPrefs(st); }catch(e){} };
window.__canghaiSyncUserProfile = function(p){
  try{
    var d = (typeof p === 'string') ? JSON.parse(p) : p;
    if(!d) return;
    if(d.name != null) userProfile.name = d.name || '沧海用户';
    if(d.avatar != null) userProfile.avatar = d.avatar || '沧';
    applyUserProfile();
  }catch(e){}
};

/* ================= 双端应用数据联动 =================
   唯一数据源在 Native。手机端装 / 卸 / 改名 / 换图标后，Native 把最新 stateJson
   推到这里；外屏自己的写操作也走同一条回路。不刷新页面，增量同步：
   撤掉消失的软件（窗口、桌面图标、Dock、启动台、小组件），补上新装的，
   名字和图标变了就重画图标，其余用户摆放的位置 / 文件夹 / Dock 固定项全部保留。 */
function chStrHash(s){ var h = 0, t = String(s || ''); for(var i=0;i<t.length;i++) h = (h*31 + t.charCodeAt(i)) | 0; return h; }
function chAppSig(st){
  try{
    return JSON.stringify((st.htmlApps||[]).map(function(a){
      return [a.id, a.name, a.icon ? chStrHash(a.icon) : 0, a.png ? chStrHash(a.png) : 0, a.size, a.ori];
    })) + '|' + JSON.stringify((st.apps||[]).map(function(a){ return [a.id, a.name]; }));
  }catch(e){ return String(Date.now()); }
}
var __chLastAppSig = '';
function chNormPath(p){
  var out = [], parts = String(p || '').split('/');
  parts.forEach(function(s){ if(!s || s === '.') return; if(s === '..'){ out.pop(); return; } out.push(s); });
  return out.join('/');
}
window.__canghaiSyncApps = function(st){
  try{
    if(!st) return;
    var sig = chAppSig(st);
    if(sig === __chLastAppSig) return;
    __chLastAppSig = sig;
    chApplyCursorPrefs(st);

    /* 清单里已有的内置应用不从桥里再取一份（去重，清单优先） */
    var builtinIds = {};
    BUILTIN_APPS.forEach(function(b){ builtinIds[b.id] = 1; });
    var html = (st.htmlApps||[]).filter(function(a){ return !builtinIds[a.id]; }).map(function(a){
      return {id:a.id, name:a.name, kind:'html', installed:true, icon:chAppIcon(a), grad:chGradFor(a.name)};
    });
    var native = chPrivOk(st) ? (st.apps||[]).map(function(a){
      return {id:a.id, name:a.name, kind:'native', comp:a.comp, pkg:a.pkg, icon:a.icon||null, grad:chGradFor(a.name), dock:false};
    }) : [];
    var oldIds = HTML_APPS.map(function(a){ return a.id; })
      .concat(NATIVE_APPS.map(function(a){ return a.id; }));
    var newIds = {};
    html.forEach(function(a){ newIds[a.id] = 1; });
    native.forEach(function(a){ newIds[a.id] = 1; });
    var gone = oldIds.filter(function(id){ return !newIds[id]; });

    /* 保留用户自己摆的 Dock 固定项 / 启动台分组 / 桌面图标 */
    var hadPins = dockPins.slice(), hadLp = lpItems.slice();
    gone.forEach(function(id){
      if(openWins[id]){ try{ openWins[id].remove(); }catch(e){} delete openWins[id]; }
      items.slice().forEach(function(it){
        if(it.type === 'app' && it.appId === id) removeItem(it);
        else if(it.type === 'folder') it.apps = it.apps.filter(function(x){ return x !== id; });
      });
      Object.keys(APP_WIDGETS || {}).forEach(function(wid){
        if(APP_WIDGETS[wid] && APP_WIDGETS[wid].appId === id){
          delete APP_WIDGETS[wid];
          if(typeof APP_WIDGET_ORDER !== 'undefined') APP_WIDGET_ORDER = APP_WIDGET_ORDER.filter(function(x){ return x !== wid; });
          items.slice().forEach(function(it){ if(it.type === 'widget' && it.wtype === 'app' && it.widgetId === wid) removeItem(it); });
        }
      });
      delete ICONS[id];
    });
    hadPins = hadPins.filter(function(id){ return gone.indexOf(id) < 0; });
    hadLp = hadLp.filter(function(x){
      if(x.kind === 'app') return gone.indexOf(x.appId) < 0;
      if(x.kind === 'folder') x.apps = x.apps.filter(function(id){ return gone.indexOf(id) < 0; });
      return true;
    });

    rebuildAppRegistry(html, native);
    /* 图标以唯一数据源为准；没有自定义图标的沿用页面生成的文字图标 */
    html.forEach(function(a){ if(chAppIcon(a)) ICONS[a.id] = chAppIcon(a); });
    dockPins = hadPins.filter(function(id){ return !!ALL_APPS.filter(function(a){ return a.id === id; }).length; });
    lpItems = hadLp.filter(function(x){
      return x.kind !== 'app' || !!ALL_APPS.filter(function(a){ return a.id === x.appId; }).length;
    });
    /* 新装 / 手机端刚装好的软件：Dock、启动台、桌面都补上 */
    syncLaunchpadRegistry();
    html.forEach(function(a){
      if(oldIds.indexOf(a.id) >= 0) return;
      if(dockPins.indexOf(a.id) < 0) dockPins.push(a.id);
      var inLp = lpItems.some(function(x){ return x.kind === 'app' && x.appId === a.id; });
      if(!inLp) lpItems.push({kind:'app', appId:a.id});
      var onDesk = items.some(function(it){ return it.type === 'app' && it.appId === a.id; });
      if(!onDesk){
        var spot = findFree(1,1,1,1,curDesk);
        var it = makeItem({type:'app', appId:a.id, name:a.name, col:spot.col, row:spot.row, w:1, h:1, desk:curDesk});
        renderAppCell(it);
      }
    });
    items.forEach(function(it){
      if(it.type === 'app'){ it.name = appById(it.appId).name; renderAppCell(it); }
      else if(it.type === 'folder') renderFolderCell(it);
    });
    Object.keys(openWins).forEach(function(k){
      var a = ALL_APPS.filter(function(x){ return x.id === k; })[0];
      if(!a) return;
      var ttl = openWins[k].querySelector && openWins[k].querySelector('.ttl');
      if(ttl) ttl.innerHTML = '<span style="display:inline-flex;width:18px;height:18px;border-radius:5px;background:'+a.grad+';align-items:center;justify-content:center;overflow:hidden">'+ICONS[k]+'</span>'+escapeHtml(a.name);
    });
    renderDock();
    if(typeof renderLaunchpad === 'function') renderLaunchpad();
    applyDesk();
    buildAppWidgetPresets();
    if(typeof deskSaveNow === 'function') deskSaveNow();
  }catch(e){ if(window.console) console.warn('[ch] 同步应用数据失败：', e); }
};

/* ---- 导出 .沧海软件：页面打包 zip → Native 拉起系统「另存为」 ---- */
var CH_CRC = (function(){ var t=[]; for(var n=0;n<256;n++){ var c=n; for(var k=0;k<8;k++) c = (c&1) ? (0xEDB88320 ^ (c>>>1)) : (c>>>1); t[n]=c>>>0; } return t; })();
function chCrc32(b){ var c=0xFFFFFFFF; for(var i=0;i<b.length;i++) c=CH_CRC[(c^b[i])&0xFF]^(c>>>8); return (c^0xFFFFFFFF)>>>0; }
function chZipStore(files){
  var enc = new TextEncoder(), chunks = [], central = [], offset = 0;
  var now = new Date();
  var tm = ((now.getHours()<<11)|(now.getMinutes()<<5)|(now.getSeconds()>>1))&0xFFFF;
  var dt = (((now.getFullYear()-1980)<<9)|((now.getMonth()+1)<<5)|now.getDate())&0xFFFF;
  files.forEach(function(f){
    var nb = enc.encode(f.name), data = f.data, crc = chCrc32(data);
    var lh = new DataView(new ArrayBuffer(30));
    lh.setUint32(0,0x04034b50,true); lh.setUint16(4,20,true); lh.setUint16(6,0x0800,true);
    lh.setUint16(8,0,true); lh.setUint16(10,tm,true); lh.setUint16(12,dt,true);
    lh.setUint32(14,crc,true); lh.setUint32(18,data.length,true); lh.setUint32(22,data.length,true);
    lh.setUint16(26,nb.length,true); lh.setUint16(28,0,true);
    chunks.push(new Uint8Array(lh.buffer), nb, data);
    var ch = new DataView(new ArrayBuffer(46));
    ch.setUint32(0,0x02014b50,true); ch.setUint16(4,20,true); ch.setUint16(6,20,true);
    ch.setUint16(8,0x0800,true); ch.setUint16(10,0,true); ch.setUint16(12,tm,true); ch.setUint16(14,dt,true);
    ch.setUint32(16,crc,true); ch.setUint32(20,data.length,true); ch.setUint32(24,data.length,true);
    ch.setUint16(28,nb.length,true); ch.setUint32(42,offset,true);
    central.push(new Uint8Array(ch.buffer), nb);
    offset += 30 + nb.length + data.length;
  });
  var cs = central.reduce(function(s,c){ return s + c.length; }, 0);
  var eo = new DataView(new ArrayBuffer(22));
  eo.setUint32(0,0x06054b50,true); eo.setUint16(8,files.length,true); eo.setUint16(10,files.length,true);
  eo.setUint32(12,cs,true); eo.setUint32(16,offset,true);
  return new Blob(chunks.concat(central,[new Uint8Array(eo.buffer)]),{type:'application/zip'});
}
function exportDesktopApp(appId){
  var app = appById(appId);
  if(!app || !app.installed){ toast('只有装进来的沧海软件才能导出'); return; }
  var src = '';
  try{ if(typeof Canghai !== 'undefined' && Canghai && Canghai.getHtmlAppSource) src = Canghai.getHtmlAppSource(appId) || ''; }catch(e){}
  if(!src){ toast('这个软件没有源码，导不出来'); return; }
  var enc = new TextEncoder();
  var icon = String(ICONS[appId] || '');
  if(!/<svg[\s>]/i.test(icon)) icon = '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100"><rect width="100" height="100" rx="22" fill="#2b93d8"/></svg>';
  var manifest = {name:app.name, entry:'index.html', icon:'icon.svg', version:'1.0'};
  var blob = chZipStore([
    {name:'index.html', data:enc.encode(src)},
    {name:'icon.svg', data:enc.encode(icon)},
    {name:'app.json', data:enc.encode(JSON.stringify(manifest,null,2))}
  ]);
  var safe = String(app.name || '沧海软件').replace(/[\\\/:*?"<>|]/g,'_').trim() || '沧海软件';
  var bridge = (typeof Canghai !== 'undefined') ? Canghai : null;
  if(!bridge || !bridge.exportPackage){ toast('这台设备没有导出通道'); return; }
  var reader = new FileReader();
  reader.onerror = function(){ toast('导出失败：包没能读出来'); };
  reader.onload = function(){
    var d = String(reader.result || ''), comma = d.indexOf(',');
    if(comma < 0){ toast('导出失败：包没能读出来'); return; }
    var ok = false;
    try{ ok = bridge.exportPackage(safe, d.slice(comma+1)); }catch(e){}
    var ext = window.CHI18n ? window.CHI18n.getExportExt() : '.沧海软件';
    if(!ok) toast('导出没能开始，稍后再试');
    else toast('选择保存位置后就会写出「'+safe+ext+'」');
  };
  reader.readAsDataURL(blob);
}
window.__chExportDone = function(raw){
  var r = {};
  try{ r = (typeof raw === 'string') ? JSON.parse(raw) : (raw || {}); }catch(e){}
  toast(r.msg || (r.ok ? '已导出' : '导出失败'));
};


/* 装进来的软件在自己的沙盒里跑，想跟宿主说话就 postMessage：
   {canghai:'openApp', appId:'...'} 或 {canghai:'toast', text:'...'} */
function bindAppFrameMessages(fr){ if(fr) fr._chBound = true; }
window.addEventListener('message', function(e){
  var d = e && e.data;
  if(!d || typeof d !== 'object' || !d.canghai) return;
  if(d.canghai === 'toast'){ toast(String(d.text || '')); return; }
  if(d.canghai === 'openApp' && d.appId){ openApp(String(d.appId)); return; }
  if(d.canghai === 'closeSelf'){
    /* 应用点了自己画的关闭按钮：按发送方找到它的窗口，走和点红点一样的关闭路 */
    var cid = chAppIdForSource(e.source);
    var cwin = cid && (typeof openWins !== 'undefined') && openWins[cid];
    var cbtn = cwin && cwin.querySelector && cwin.querySelector('.tl.r');
    if(cbtn){ cbtn.click(); } else { toast('软件请求关闭自己'); }
    return;
  }
  /* 应用声明无边框：去掉所在窗口的标题栏与窗口背景，应用自己画按钮、自己管拖拽 */
  if(d.canghai === 'frameless' || d.canghai === 'framed'){
    var fid = chAppIdForSource(e.source);
    var fwin = fid && (typeof openWins !== 'undefined') && openWins[fid];
    if(fwin) fwin.classList.toggle('frameless', d.canghai === 'frameless');
    return;
  }
  /* 应用内拖拽移动窗口：应用在自己的拖拽区里算好总位移发过来，桌面只管挪窗（带边界钳制，与标题栏拖拽同限） */
  if(d.canghai === 'winDrag'){
    var did = chAppIdForSource(e.source);
    var dwin = did && (typeof openWins !== 'undefined') && openWins[did];
    if(!dwin) return;
    if(d.phase === 'start'){
      dwin._dragAnchor = { l: dwin.offsetLeft, t: dwin.offsetTop, w: dwin.offsetWidth };
      return;
    }
    if(d.phase === 'end'){ dwin._dragAnchor = null; return; }
    var an = dwin._dragAnchor;
    if(!an) return;
    var nl = Math.max(-an.w + 120, Math.min(an.l + (Number(d.dx) || 0), window.innerWidth - 80));
    var nt = Math.max(0, Math.min(an.t + (Number(d.dy) || 0), window.innerHeight - 90));
    dwin.style.left = nl + 'px'; dwin.style.top = nt + 'px';
    return;
  }
  /* 应用发通知：进桌面消息中心（右上角滑入 + 历史记录） */
  if(d.canghai === 'notify'){
    try{ if(window.CHMsgCenter) CHMsgCenter.notify(d); }catch(err){}
    return;
  }
  /* 应用请宿主把文件写到下载目录（安卓层接口 Canghai.saveDownload(filename, dataUrl)）。
     没有这个接口时什么也不做：应用自己已经触发了浏览器下载 + 发了 notify。 */
  /* 应用要读写文件：应用只跟桌面说话（postMessage），桌面按发送方认 appId 后
     调 CanghaiCore.fs 转给安卓（方法名与 payload 以 CoreFs.java 为准）。
     绝不能信消息里自带的 appId 字段——只有文件管理器（file-manager，桌面
     自带的内置应用）可以在 payload 里用 targetAppId 指定目标应用去浏览
     别的应用的文件夹；普通应用的 targetAppId / appId 一律丢掉。结果原样
     postMessage 回去 {canghai:'fsResult', ...}。 */
  if(d.canghai === 'fs'){ chHandleFsRequest(e, d); return; }
  if(d.canghai === 'download'){
    try{
      if(typeof Canghai !== 'undefined' && Canghai && Canghai.saveDownload){
        Canghai.saveDownload(String(d.filename || 'download'), String(d.dataUrl || ''));
      }
    }catch(err){}
    return;
  }
});
/* ================= 文件转调：应用 → 桌面 → CanghaiCore fs =================
   应用 iframe 不直接碰安卓桥：应用 postMessage
   {canghai:'fs', requestId, method, path, text?, base64?, to?, append?,
   recursive?, overwrite?, max?, targetAppId?} 给桌面，桌面按发送方
   e.source 认 appId（对上 openWins 里哪个窗口的 iframe），再调
   CHCore.fs 转给安卓（方法名与 payload 以 CoreFs.java 为准：list /
   readText / readBase64 / writeText / writeBase64 / mkdir / delete /
   rename / copy / move / stat / exists / appDir / listApps / storage /
   root / setRootMode / pickRoot；没有 uuid，没有 encoding 字段），完了
   把结果 postMessage 回去 {canghai:'fsResult', requestId, ok, data?, error?}。
   分两种：
   · 普通应用：强制用发送方自己的 appId，消息里带的 appId / targetAppId
     一律丢掉——应用谎报一个别的 ID 就能读别人的文件夹。
   · 文件管理器（appId 是 file-manager，桌面自带的内置应用，不是第三方
     应用）：允许在 payload 里用 targetAppId 指定目标应用，去浏览别的
     应用的文件夹；targetAppId 同样只认 [a-zA-Z0-9._-]，不合法就拒绝。
   没有 CanghaiCore 桥时回 NO_BRIDGE，应用自行退回本地模式，不报错。 */
var CH_FS_METHODS = ['root','setRootMode','pickRoot','list','listApps','stat','mkdir',
  'delete','rename','copy','move','readText','writeText','readBase64','writeBase64',
  'exists','appDir','storage'];
var CH_FILE_MANAGER_ID = 'file-manager';
function chValidAppId(id){
  if(!id || typeof id !== 'string') return false;
  if(id === '.' || id === '..') return false;
  return /^[a-zA-Z0-9._-]+$/.test(id);
}
function chAppIdForSource(source){
  try{
    if(!source || typeof openWins === 'undefined') return '';
    var ids = Object.keys(openWins);
    for(var i = 0; i < ids.length; i++){
      var win = openWins[ids[i]];
      if(!win) continue;
      var fr = win.querySelector && win.querySelector('iframe.appFrame');
      if(fr && fr.contentWindow === source) return ids[i];
    }
  }catch(e){}
  return '';
}
function chHandleFsRequest(e, d){
  var requestId = d.requestId;
  function reply(r){
    try{
      var target = e.source;
      if(!target || !target.postMessage) return;
      target.postMessage({canghai:'fsResult', requestId:requestId, ok:!!r.ok, data:r.data, error:r.error || ''}, e.origin || '*');
    }catch(err){}
  }
  var senderId = chAppIdForSource(e.source);
  if(!senderId){ reply({ok:false, error:'PERMISSION_DENIED'}); return; }
  var method = String(d.method || '');
  if(CH_FS_METHODS.indexOf(method) < 0){ reply({ok:false, error:'INVALID_ARGS'}); return; }
  if(!window.CHCore || !CHCore.fs){ reply({ok:false, error:'NO_BRIDGE'}); return; }
  /* 生效的 appId：默认是发送方自己；只有文件管理器能用 targetAppId
     指定目标应用（且目标 id 必须合法），其余一律按发送方算。 */
  var appId = senderId;
  if(senderId === CH_FILE_MANAGER_ID && d.targetAppId){
    if(!chValidAppId(d.targetAppId)){ reply({ok:false, error:'INVALID_ARGS'}); return; }
    appId = d.targetAppId;
  }
  var p;
  try{
    var F = CHCore.fs;
    if(method === 'root' || method === 'listApps' || method === 'storage' || method === 'appDir')
      p = F[method](appId);
    else if(method === 'pickRoot')
      p = F.pickRoot();
    else if(method === 'setRootMode')
      p = F.setRootMode(appId, d.mode, d.uri);
    else if(method === 'readText' || method === 'readBase64')
      p = F[method](appId, d.path, {max: d.max});
    else if(method === 'writeText')
      p = F.writeText(appId, d.path, d.text, {append: !!d.append});
    else if(method === 'writeBase64')
      p = F.writeBase64(appId, d.path, d.base64);
    else if(method === 'delete')
      p = F.delete(appId, d.path, {recursive: !!d.recursive});
    else if(method === 'rename')
      p = F.rename(appId, d.path, d.to);
    else if(method === 'copy' || method === 'move')
      p = F[method](appId, d.path, d.to, {overwrite: !!d.overwrite});
    else
      p = F[method](appId, d.path);
  }catch(err){ reply({ok:false, error:'INTERNAL_ERROR'}); return; }
  Promise.resolve(p).then(reply, function(){ reply({ok:false, error:'INTERNAL_ERROR'}); });
}
window.__chAppIdForSource = chAppIdForSource;


/* ================= 文件关联：按扩展名把文件递给对应的应用 =================
   应用在注册表里写 handles: ['mp4','webm',...]。打开一个文件时，按扩展名找应用，
   打开它，等窗口里的 iframe 载好后把 File/Blob 递给应用的 openFile。 */
var _chPendingFile = {}; // appId -> File/Blob（窗口还没载好时先存着）
function chAppForExt(ext){
  ext = String(ext || '').toLowerCase().replace(/^\./, '');
  if(!ext) return null;
  for(var i = 0; i < ALL_APPS.length; i++){
    var a = ALL_APPS[i];
    if(a && a.handles && a.handles.indexOf(ext) >= 0) return a;
  }
  return null;
}
function chFlushPendingFile(appId, frI){
  var f = _chPendingFile[appId];
  if(!f) return;
  /* 只有递成功才取走：iframe 还停在 about:blank 时 readyState 也是 complete，
     直接递会失败（应用接口还没出来），留着等 load 事件再递 */
  if(chDeliverFile(appId, frI, f)) delete _chPendingFile[appId];
}
function chDeliverFile(appId, frI, file){
  if(!frI || !frI.contentWindow) return false;
  var app = appById(appId);
  var iface = app.iface || '';
  try{
    var w = frI.contentWindow;
    var api = iface ? w[iface] : null;
    if(api && typeof api.openFile === 'function'){ api.openFile(file); return true; }
  }catch(e){}
  return false;
}
/* 打开一个文件：按扩展名找应用并打开，文件随后递进去。返回应用 id，没找到返回 ''。 */
function chOpenFile(file, fileName){
  var name = String(fileName || (file && file.name) || '');
  var ext = name.indexOf('.') >= 0 ? name.split('.').pop() : '';
  var app = chAppForExt(ext);
  if(!app){ toast('没有能打开「' + ext + '」的软件'); return ''; }
  _chPendingFile[app.id] = file;
  openApp(app.id);
  /* 窗口已开着时 iframe 可能已载好：直接递一次（载好时 chFlushPendingFile 还会再递，取走后是空操作） */
  try{
    var win = openWins[app.id];
    if(win){
      var fr = win.querySelector('iframe.appFrame');
      if(fr && fr.contentWindow && fr.contentWindow.document && fr.contentWindow.document.readyState === 'complete'){
        chFlushPendingFile(app.id, fr);
      }
    }
  }catch(e){}
  return app.id;
}
window.__chOpenFile = chOpenFile;
/* 手机端在外屏上点「投到屏幕」→ Native 推到这里，跟桌面自己双击图标走同一条路 */
window.__canghaiOpenApp = function(id){
  /* 从悬浮 Dock 点图标时启动台可能还开着，先收起它，否则窗口会开在启动台后面 */
  try{ if(typeof lpIsOpen === 'function' && lpIsOpen()) closeLaunchpad(); }catch(e){}
  try{ openApp(String(id)); }catch(e){}
};
/* 原生侧仍在调的老钩子：统一在这里兜住，别让 evaluateJavascript 抛 ReferenceError。
   壁纸现在由沧海自己的数据源管（Canghai.getState().wallpaper），系统壁纸不参与。 */
window.applyWallpaper = window.applyWallpaper || function(){};
window.AosenUI = window.AosenUI || {};
['setWallpaper','showGrid','moveHint','widgetDropped','setL10n','setApps','setItems',
 'setDockApps','setCaption','setL','setActiveScreen','setTopBar','closeMenus'].forEach(function(k){
  if(typeof window.AosenUI[k] !== 'function') window.AosenUI[k] = function(){};
});
window.closeMenus = window.closeMenus || function(){
  try{
    document.querySelectorAll('.ctxMenu,.pop').forEach(function(el){
      el.style.display = 'none';
    });
  }catch(e){}
};
/* 悬浮 Dock 的「启动台」按钮走这里（原生先把桌面提到前台，再调这个） */
window.__canghaiLaunchpad = function(){
  try{ openLaunchpad(); }catch(e){}
};
/* 悬浮 Dock 起来了：页面里这条 Dock 立刻撤掉，同屏只留一条 */
window.__dockOverlayOn = function(){
  document.documentElement.classList.add('dockOverlay');
};
/* 整个页面跑在一个 IIFE 里，函数不是全局的；原生那侧（HOME 自愈、悬浮 Dock）
   却按全局名字调。这里补一层别名，两边的调用约定就固定下来了。 */
window.openLaunchpad = function(){ if(typeof openLaunchpad === 'function') openLaunchpad(); };
window.closeLaunchpad = function(){ if(typeof closeLaunchpad === 'function') closeLaunchpad(); };
window.toggleLaunchpad = function(){ if(typeof toggleLaunchpad === 'function') toggleLaunchpad(); };
window.__canghaiSyncDockPins = function(json){
  try{
    var arr = JSON.parse(json);
    if(Array.isArray(arr)){
      dockPins = arr;
      renderDock();
      deskSaveSoon();
      chPushDockRunState();
    }
  }catch(e){}
};
window.__canghaiCloseApp = function(appId){
  try{
    if(openWins[appId]) closeWin(openWins[appId]);
  }catch(e){}
};
/* 页面开机时取走手机端留下的那次意图（外屏没在跑时点开的），只消费一次 */
function chConsumePendingOpen(){
  try{
    if(!(typeof Canghai !== 'undefined' && Canghai && Canghai.takePendingOpen)) return;
    var id = Canghai.takePendingOpen();
    if(!id) return;
    setTimeout(function(){ try{ openApp(String(id)); }catch(e){} }, 320);
  }catch(e){}
}
/* ================= 输入监视：窗口开着才采集 ================= */
function wireInputTrace(win){
  function q(id){ return win.querySelector('#'+id); }
  var devName=q('itDevName'), devMeta=q('itDevMeta'), liveDot=q('itDot'),
      keysEl=q('itKeys'), btnsEl=q('itBtns'), logEl=q('itLog'),
      axesEl=q('itAxes'), wheelEl=q('itWheel'), stick=q('itStickDot');
  var timer=null, lastStamp=0, lastDevAt=-1;

  function stop(){
    if(timer){ clearInterval(timer); timer=null; }
    try{
      if(window.Canghai && window.Canghai.inputTraceEnable) window.Canghai.inputTraceEnable(false);
      else if(window.Aosen && window.Aosen.inputTraceEnable) window.Aosen.inputTraceEnable(false);
    }catch(e){}
  }
  win._onClose = stop;
  try{
    if(window.Canghai && window.Canghai.inputTraceEnable) window.Canghai.inputTraceEnable(true);
    else if(window.Aosen && window.Aosen.inputTraceEnable) window.Aosen.inputTraceEnable(true);
  }catch(e){}

  var clr=q('itClear');
  if(clr) clr.addEventListener('click', function(){
    try{
      if(window.Canghai && window.Canghai.inputTraceClear) window.Canghai.inputTraceClear();
      else if(window.Aosen && window.Aosen.inputTraceClear) window.Aosen.inputTraceClear();
    }catch(e){}
    if(logEl) logEl.innerHTML='';
    lastStamp = 0;
  });

  function chipText(list, empty){
    if(!list || !list.length) return empty;
    return list.map(function(x){ return x.name || x; }).join(' + ');
  }

  function rowHTML(r){
    var k = r.k || 'key';
    var bad = (k === 'leak');
    var note = (k === 'note');
    var cls = bad ? 'key' : (note ? 'axis' : (k==='pad' ? 'pad' : k==='mouse' ? 'mouse' : k==='scroll' ? 'scroll' : k==='axis' ? 'axis' : 'key'));
    var when = Math.max(0, Math.round(((window.__itNow||0) - (r.t||0))/1000));
    var act = note ? '' : (r.a || (r.d ? '按下' : '抬起'));
    var nm = r.n || ('#'+r.code);
    var extra = '';
    if(!bad && (k==='mouse' || k==='scroll' || k==='axis')){
      extra = ' · ' + (r.abText && r.abText!=='无' ? r.abText+' ' : '') + '(' + r.x + ',' + r.y + ')';
      if(k==='scroll') extra += ' 滚 ' + (r.v||0) + '/' + (r.h||0);
    }
    /* 大脑判定这一帧投给了哪块屏；系统层没有落点就不显示。 */
    if(r.to) extra += ' → ' + r.to;
    var layer = r.o ? ('[' + r.o + '] ') : '';
    var chip = note ? '窗口' : cls==='pad'?'手柄':cls==='mouse'?'鼠标':cls==='scroll'?'滚轮':cls==='axis'?'轴':bad?'异常':'按键';
    return '<div class="row"><span class="tm">'+(when===0?'现在':when+'s 前')+'</span>'
      + '<span class="kd '+cls+'">'+chip+'</span>'
      + '<span class="tx"'+(bad?' style="color:#ff9a9a"':'')+'>'+escapeHtml(layer + act + ' ' + nm + (extra||''))+'</span></div>';
  }

  timer = setInterval(function(){
    if(!win.isConnected){ stop(); return; }
    var s = null;
    try{
      var raw = (window.Canghai && window.Canghai.inputTrace) ? window.Canghai.inputTrace()
               : ((window.Aosen && window.Aosen.inputTrace) ? window.Aosen.inputTrace() : null);
      if(raw) s = JSON.parse(raw);
    }catch(e){ return; }
    if(!s) return;
    window.__itNow = s.now || window.__itNow || 0;

    /* 顶部：谁在按 */
    var dev = s.activeDevice || '';
    var ago = (s.activeDeviceAt && s.now) ? Math.max(0, Math.round((s.now - s.activeDeviceAt)/1000)) : -1;
    if(devName) devName.textContent = dev || '等待输入设备…';
    if(liveDot) liveDot.classList.toggle('on', ago >= 0 && ago < 120);
    if(devMeta){
      var meta = [];
      if(ago >= 0) meta.push(ago + ' 秒前活动');
      if(s.keys && s.keys.length) meta.push('按住 ' + s.keys.length + ' 键');
      devMeta.textContent = meta.join(' · ') || '按一下手柄或鼠标试试';
    }
    if(keysEl){
      var kt = chipText(s.keys, '按键 —');
      keysEl.textContent = kt;
      keysEl.classList.toggle('hot', !!(s.keys && s.keys.length));
    }
    if(btnsEl){
      var bt = chipText(s.buttons, '按钮 —');
      btnsEl.textContent = bt;
      btnsEl.classList.toggle('hot', !!(s.buttons && s.buttons.length));
    }
    /* 摇杆 */
    if(s.axes && stick){
      var a = s.axes;
      var x = Math.max(-1, Math.min(1, a.x || a.hatX || 0));
      var y = Math.max(-1, Math.min(1, a.y || a.hatY || 0));
      stick.style.transform = 'translate(' + (x*46).toFixed(1) + 'px,' + (y*46).toFixed(1) + 'px)';
      if(axesEl) axesEl.textContent = '摇杆  X ' + (a.x||0).toFixed(2) + '  Y ' + (a.y||0).toFixed(2)
        + ((a.hatX||a.hatY) ? '  ·  十字 ' + (a.hatX||0).toFixed(0) + '/' + (a.hatY||0).toFixed(0) : '');
    }
    if(s.wheel && wheelEl){
      var w = s.wheel;
      var dir = w.v > 0 ? '向上滚动' : w.v < 0 ? '向下滚动' : (w.h > 0 ? '向右' : w.h < 0 ? '向左' : '静止');
      var when = (w.t && s.now) ? Math.max(0, Math.round((s.now - w.t)/1000)) : -1;
      wheelEl.textContent = '滚轮  ' + (w.n ? dir + ' · 累计 ' + w.n + ' 次' : '—')
        + (when >= 0 ? ' · ' + when + 's 前' : '');
    }
    /* 日志：最新的事件在最上面；超过 500ms 间隔的分组隔离，500ms 内紧凑排列 */
    if(logEl && s.log){
      var newestT = (s.log.length && s.log[0]) ? (s.log[0].t || 0) : 0;
      var curSig = s.log.length + '_' + newestT;
      if(curSig !== lastStamp){
        lastStamp = curSig;
        var html = '';
        for(var i = 0; i < s.log.length && i < 70; i++){
          var cur = s.log[i];
          var next = (i + 1 < s.log.length) ? s.log[i + 1] : null;
          html += rowHTML(cur);
          if(next && cur.t && next.t){
            var diff = Math.abs(cur.t - next.t);
            if(diff > 500){
              var gapSec = (diff / 1000).toFixed(1);
              html += '<div class="itGap" data-gap="间隔 ' + gapSec + 's"></div>';
            }
          }
        }
        logEl.innerHTML = html || '<div class="row"><span class="tx" style="color:#5f8aa8">还没有输入事件</span></div>';
      }
    }
  }, 100);
}

function appContent(appId){
  var app0 = appById(appId);
  if(app0.installed){
    /* 装进来的软件：它自己的 HTML 装进沙盒窗口里跑（带 pointer-lock，游戏能锁鼠标）。
       allow-same-origin 必须有：3D 游戏要 localStorage / WebGL 纹理 / AudioContext，
       纯 opaque origin 的 sandbox iframe 会让这些直接抛 SecurityError，画面全黑。 */
    return '<iframe class="appFrame" sandbox="allow-scripts allow-same-origin allow-downloads allow-pointer-lock allow-popups" allow="pointer-lock; fullscreen" allowfullscreen title="'+escapeHtml(app0.name)+'"></iframe>';
  }
  if(appId==='inputtrace'){
    /* 输入监视：真机输入事件（手柄 / 鼠标 / 键盘）的实时画面，自己渲染在自己的窗口里 */
    return '<div class="itrace">'
      + '<div class="itHead"><div class="itDev"><span class="itDot" id="itDot"></span><b id="itDevName">等待输入设备…</b><span id="itDevMeta" class="itMeta"></span></div>'
      + '<div class="itLive"><span class="itChip" id="itKeys">按键 —</span><span class="itChip" id="itBtns">按钮 —</span></div></div>'
      + '<div class="itBody"><div class="itCol"><div class="itTitle">按键 / 摇杆 / 滚轮</div><div class="itPad" id="itPad"><i class="itDotc" id="itStickDot"></i></div>'
      + '<div class="itAxes" id="itAxes">摇杆 —</div><div class="itWheel" id="itWheel">滚轮 —</div></div>'
      + '<div class="itCol itColLog"><div class="itTitle">最近事件 <button type="button" class="itClear" id="itClear">清空</button></div><div class="itLog" id="itLog"></div></div></div>'
      + '</div>';
  }
  if(appId==='settings'){
    return '<div class="settings">'+
    '<h3 style="margin-top:2px">界面语言 / 語言 / Language</h3>'+
    '<div class="setLangSeg" style="display:flex;background:rgba(255,255,255,.08);padding:3px;border-radius:10px;gap:6px;margin:8px 0 14px">'+
    '<button type="button" class="miniBtn" data-lang="zh-Hans" style="flex:1">简体中文</button>'+
    '<button type="button" class="miniBtn" data-lang="zh-Hant" style="flex:1">繁體中文</button>'+
    '<button type="button" class="miniBtn" data-lang="en" style="flex:1">English</button>'+
    '</div>'+
    '<h3 style="margin-top:18px">顶栏外观</h3>'+
    '<div class="setrow"><span>显示左上角软件图标</span><button class="switch on" id="setBrandLogoSw" type="button" aria-label="显示左上角软件图标开关"></button></div>'+
    '<h3 style="margin-top:18px">屏幕分辨率 · 等比例缩放（独立保存，绝无黑条）</h3>'+
    '<div class="resRow" style="display:flex;flex-wrap:wrap;gap:8px;margin:8px 0 14px"></div>'+
    '<h3 style="margin-top:18px">桌面网格</h3>'+
    '<div class="setrow"><span>横向格子数</span><span class="stepper"><button type="button" data-gstep="cols,-1" aria-label="横向格子减少">−</button><b class="gColsVal">15 格</b><button type="button" data-gstep="cols,1" aria-label="横向格子增加">＋</button></span></div>'+
    '<div class="setrow"><span>竖向格子数</span><span class="stepper"><button type="button" data-gstep="rows,-1" aria-label="竖向格子减少">−</button><b class="gRowsVal">5 格</b><button type="button" data-gstep="rows,1" aria-label="竖向格子增加">＋</button></span></div>'+
    '<div class="setrow"><span>每格大小</span><span class="gridCellVal" style="font-size:12.5px;color:#999">— px · 居中</span></div>'+
    '<h3 style="margin-top:20px">鼠标样式</h3>'+
    '<div class="curRow"></div>'+
    '<div class="setrow"><span>指针大小</span><span class="stepper"><button type="button" data-cstep="-2" aria-label="指针变小">−</button><b class="csVal" title="点击直接输入数字">28</b><button type="button" data-cstep="2" aria-label="指针变大">＋</button></span></div>'+
    '<div class="setrow"><span>指针速度</span><span class="stepper"><button type="button" data-sstep="-0.2" aria-label="指针变慢">−</button><b class="ssVal">1.0×</b><button type="button" data-sstep="0.2" aria-label="指针变快">＋</button></span></div>'+
    '<div class="setrow"><span>鼠标样式管理</span><span style="display:flex;align-items:center;gap:9px"><span class="curLibCount" style="font-size:12px;color:#999">还没有自定义</span><button class="miniBtn" type="button" data-cmanage="1">管理</button></span></div>'+
    '<h3 style="margin-top:20px">壁纸</h3><div class="wps">'+
    '<button type="button" data-wp="wp-dusk" class="on" style="background:linear-gradient(160deg,#062544,#1479b8)">沧海</button>'+
    '<button type="button" data-wp="wp-sea" style="background:linear-gradient(160deg,#041f3a,#0a4a7a)">深海</button>'+
    '<button type="button" data-wp="wp-forest" style="background:linear-gradient(160deg,#0e6a9e,#d6f0ff)">浅滩</button>'+
    '<button type="button" data-wp="wp-aurora" style="background:linear-gradient(160deg,#010610,#173a5e)">极夜深海</button>'+
    '</div>'+
    '<div style="font-size:12px;color:#999;margin:12px 0 2px">动态壁纸 · three.js</div><div class="wps">'+
    '<button type="button" data-wp="wp3d-parallax" style="background:linear-gradient(160deg,#041f3a,#7a5c1e)">星海视差</button>'+
    '<button type="button" data-wp="wp3d-moon" style="background:linear-gradient(160deg,#010610,#8a6a25)">海上生明月</button>'+
    '<button type="button" data-wp="wp3d-tide" style="background:linear-gradient(160deg,#041f3a,#1a7ab8)">沧海潮汐</button>'+
    '<button type="button" data-wp="wp3d-plankton" style="background:linear-gradient(160deg,#010610,#6b5316)">金色浮游</button>'+
    '</div>'+
    '</div>';
  }
  return '';
}
/* 沙盒里 JS 报错时给一条可见的红条，别让用户对着纯黑屏猜原因。
   注意写在本页 <script> 里，结束标签必须转义成 <\/script>，否则会提前闭合本页脚本。 */
var CH_APP_DIAG = '<script>(function(){'+
  'function show(m){try{var d=document.getElementById("__ch_diag__");'+
  'if(!d){d=document.createElement("div");d.id="__ch_diag__";'+
  'd.style.cssText="position:fixed;left:0;right:0;top:0;z-index:2147483647;background:#3a0d12;color:#ffd9d9;font:12px/1.6 monospace;padding:9px 12px;white-space:pre-wrap;max-height:42%;overflow:auto";'+
  '(document.body||document.documentElement).appendChild(d);}'+
  'd.textContent="沧海软件运行出错：\\n"+m;}catch(_){}}'+
  'window.addEventListener("error",function(e){show((e.message||"脚本错误")+"\\n"+(e.filename||"")+":"+(e.lineno||""));});'+
  'window.addEventListener("unhandledrejection",function(e){show(String((e&&e.reason)||"Promise 未处理"));});'+
  '})();<\/script>';
var CH_APP_UI = '<style>'+
  ':root{--ch-bg:#071526;--ch-card:rgba(14,34,58,.78);--ch-border:rgba(120,190,255,.22);--ch-border-focus:rgba(77,184,245,.65);--ch-accent:#2f9bff;--ch-accent-glow:rgba(47,155,255,.35);--ch-text:#e8f3fc;--ch-sub:#8db5d4;--ch-danger:#ff5c5c;--ch-gold:#e5b95c;}'+
  'body{font-family:-apple-system,"HarmonyOS Sans SC","PingFang SC","Microsoft YaHei",sans-serif;color:var(--ch-text);background:transparent;}'+
  '.ch-card{background:var(--ch-card);border:1px solid var(--ch-border);border-radius:14px;padding:16px;-webkit-backdrop-filter:blur(24px);backdrop-filter:blur(24px);box-shadow:0 8px 24px rgba(0,0,0,.25);transition:all .18s ease;}'+
  '.ch-btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;padding:8px 16px;border-radius:99px;font-size:13.5px;font-weight:600;border:1px solid var(--ch-border);background:rgba(255,255,255,.08);color:var(--ch-text);cursor:pointer;outline:none;transition:all .16s ease;-webkit-user-select:none;user-select:none;}'+
  '.ch-btn:hover{background:rgba(255,255,255,.16);border-color:rgba(255,255,255,.3);transform:translateY(-1px);}'+
  '.ch-btn:active{transform:scale(.96);}'+
  '.ch-btn-primary{background:var(--ch-accent);color:#fff;border:none;box-shadow:0 4px 14px var(--ch-accent-glow);}'+
  '.ch-btn-primary:hover{background:#47aaff;box-shadow:0 6px 20px var(--ch-accent-glow);}'+
  '.ch-input{width:100%;box-sizing:border-box;padding:8px 12px;border-radius:10px;background:rgba(0,0,0,.25);border:1px solid var(--ch-border);color:#fff;font-size:13.5px;outline:none;transition:border-color .16s ease;}'+
  '.ch-input:focus{border-color:var(--ch-accent);box-shadow:0 0 0 2px var(--ch-accent-glow);}'+
  '.ch-row{display:flex;align-items:center;gap:10px;}'+
  '.ch-col{display:flex;flex-direction:column;gap:10px;}'+
  '.ch-badge{display:inline-flex;align-items:center;padding:2px 8px;border-radius:99px;font-size:11px;font-weight:700;background:rgba(47,155,255,.18);color:#80c8ff;}'+
  '</style>';
function wireApp(appId, win){
  /* 装进来的软件：把安装时存下的 HTML 源码装进窗口的沙盒里运行。
     Native 的 getHtmlApp 已经把内置 three 运行时注入好了，这里直接给 srcdoc。 */
  var appW = appById(appId);
  if(appW.installed){
    var frI = win.querySelector('iframe.appFrame');
    if(frI && appW.url){
      /* 系统自带 HTML 软件（canghai/apps/ 文件夹里的）：随前端一起发，直接按 url 加载，不走原生 getHtmlApp */
      frI.src = appW.url;
      frI.addEventListener('load', function(){
        try{
          bindAppFrameMessages(frI);
          var frDoc = frI.contentDocument || (frI.contentWindow && frI.contentWindow.document);
          if(frDoc){
            frDoc.addEventListener('pointerlockchange', onPointerLockStateChanged);
            frDoc.addEventListener('mozpointerlockchange', onPointerLockStateChanged);
            frDoc.addEventListener('webkitpointerlockchange', onPointerLockStateChanged);
          }
        }catch(e){}
        /* 打开时带了待打开的文件（文件关联）：等 iframe 载好就把文件递进去 */
        try{ chFlushPendingFile(appId, frI); }catch(e){}
      });
      return;
    }
    if(!appW.src && Canghai && Canghai.getHtmlApp) appW.src = Canghai.getHtmlApp(appW.id) || '';
    /* 占位符还在说明运行时没注进去（老版本数据 / 读取失败），给个明确提示而不是纯黑屏 */
    if(appW.src && appW.src.indexOf('/*__CH_RUNTIME__*/') >= 0){
      try{ toast('这个软件缺内置 three 运行时，重新导入一次试试'); }catch(e){}
    }
    var frI = win.querySelector('iframe.appFrame');
    if(frI){
      var src = appW.src || '<body style="margin:0;height:100%;display:grid;place-items:center;background:#0b1b2a;color:#8fb9d4;font:14px/1.8 system-ui">这个软件找不到源码，重新装一次试试</body>';
      /* 把错误提示、Canghai UI 库、以及 governor 的限帧阀门挂在 head 最前面。
         chGateSrc 必须在软件自己的脚本之前执行，否则 DPR 封顶和关 AA 都来不及生效。 */
      var inj = (typeof chGateSrc === 'function' ? chGateSrc() : '') + CH_APP_DIAG + CH_APP_UI;
      if(/<head[^>]*>/i.test(src)) src = src.replace(/<head[^>]*>/i, function(h){ return h + inj; });
      else src = inj + src;
      frI.srcdoc = src;
      /* 沙盒里的软件想调宿主（打开别的应用 / 弹提示）走 postMessage 协议 */
      frI.addEventListener('load', function(){
        try{
          bindAppFrameMessages(frI);
          var frDoc = frI.contentDocument || (frI.contentWindow && frI.contentWindow.document);
          if(frDoc){
            frDoc.addEventListener('pointerlockchange', onPointerLockStateChanged);
            frDoc.addEventListener('mozpointerlockchange', onPointerLockStateChanged);
            frDoc.addEventListener('webkitpointerlockchange', onPointerLockStateChanged);
          }
        }catch(e){}
        try{ chFlushPendingFile(appId, frI); }catch(e){}
      });
    }
    return;
  }
  /* 输视监视：窗口开着才采集，关窗立刻停（原生侧零开销） */
  if(appId==='inputtrace'){ wireInputTrace(win); return; }

  if(appId==='settings'){
    win.querySelectorAll('.switch').forEach(function(sw){
      sw.addEventListener('click', function(){ sw.classList.toggle('on'); });
    });
    applyUserProfile();
    var langSeg = win.querySelector('.setLangSeg');
    if(langSeg){
      var curL = window.CHI18n ? CHI18n.getLang() : 'zh-Hans';
      var paintLang = function(l){
        var light = document.body.classList.contains('light');
        langSeg.querySelectorAll('button').forEach(function(x){
          var on = x.getAttribute('data-lang') === l;
          x.style.background = on ? 'var(--accent)' : 'transparent';
          x.style.color = on ? '#fff' : (light ? '#1c1c22' : '#fff');
        });
      };
      paintLang(curL);
      langSeg.querySelectorAll('button').forEach(function(b){
        b.addEventListener('click', function(){
          var l = b.getAttribute('data-lang');
          if(window.CHI18n) CHI18n.setLang(l, true);
          paintLang(l);
          toast('已切换语言 · ' + (window.CHI18n ? CHI18n.t('lang_name') : l));
        });
      });
    }
    var uNameInp = win.querySelector('.setUserNameInp');
    if(uNameInp){
      uNameInp.addEventListener('input', function(){ saveUserProfile(uNameInp.value, null); });
      uNameInp.addEventListener('keydown', function(e){ e.stopPropagation(); if(e.key==='Enter') uNameInp.blur(); });
    }
    var uUpBtn = win.querySelector('.setUserUploadBtn'), uFileInp = win.querySelector('.setUserFileInp');
    if(uUpBtn && uFileInp){
      uUpBtn.addEventListener('click', function(){ uFileInp.value=''; uFileInp.click(); });
      uFileInp.addEventListener('change', function(){
        var f = uFileInp.files && uFileInp.files[0];
        if(!f) return;
        var reader = new FileReader();
        reader.onload = function(){
          var img = new Image();
          img.onload = function(){
            var c = document.createElement('canvas');
            c.width = 96; c.height = 96;
            var ctx = c.getContext('2d');
            var s = Math.min(img.width, img.height);
            ctx.drawImage(img, (img.width-s)/2, (img.height-s)/2, s, s, 0, 0, 96, 96);
            saveUserProfile(null, c.toDataURL('image/jpeg', 0.85));
            toast('自定义头像图片已保存');
          };
          img.src = String(reader.result||'');
        };
        reader.readAsDataURL(f);
      });
    }
    var crow = win.querySelector('.curRow');
    if(crow) renderCursorTiles(crow);
    var csv = win.querySelector('.csVal');
    if(csv){
      csv.textContent = String(cursorSize);
      csv.addEventListener('click', function(e){
        if(csv.querySelector('input')) return;
        e.stopPropagation();
        var inp = document.createElement('input');
        inp.type = 'number'; inp.min = '1'; inp.max = '9999'; inp.value = String(cursorSize);
        csv.textContent = '';
        csv.appendChild(inp);
        inp.focus(); inp.select();
        var done = false;
        function commit(save){
          if(done) return; done = true;
          if(save){
            var n = parseInt(inp.value, 10);
            if(!isNaN(n)) cursorSize = clamp(n, 1, 9999);
          }
          applyCursor();
          try{ if(Canghai && Canghai.setCursorSize) Canghai.setCursorSize(cursorSize); }catch(err){}
        }
        inp.addEventListener('blur', function(){ commit(true); });
        inp.addEventListener('keydown', function(ev){
          ev.stopPropagation();
          if(ev.key==='Enter'){ ev.preventDefault(); inp.blur(); }
          else if(ev.key==='Escape'){ ev.preventDefault(); commit(false); }
        });
      });
    }
    var spv = win.querySelector('.ssVal'); if(spv) spv.textContent = (Math.round(cursorSpeed*10)/10).toFixed(1)+'×';
    var clc = win.querySelector('.curLibCount'); if(clc) clc.textContent = cursorLib.length ? cursorLib.length+' 个自定义' : '还没有自定义';
    win.querySelectorAll('[data-cstep]').forEach(function(b){
      b.addEventListener('click', function(){
        cursorSize = clamp(cursorSize + parseInt(b.getAttribute('data-cstep'),10), 1, 9999);
        applyCursor();
        try{ if(Canghai && Canghai.setCursorSize) Canghai.setCursorSize(cursorSize); }catch(e){}
      });
    });
    win.querySelectorAll('[data-sstep]').forEach(function(b){
      b.addEventListener('click', function(){
        cursorSpeed = Math.round(clamp(cursorSpeed + parseFloat(b.getAttribute('data-sstep')), 0.4, 2.6)*10)/10;
        applyCursor();
        try{ if(Canghai && Canghai.setCursorSpeed) Canghai.setCursorSpeed(cursorSpeed); }catch(e){}
      });
    });
    var mg = win.querySelector('[data-cmanage]');
    if(mg) mg.addEventListener('click', openCursorManage);
    win.querySelectorAll('[data-gstep]').forEach(function(b){
      b.addEventListener('click', function(){
        var parts = b.getAttribute('data-gstep').split(','), d = parseInt(parts[1],10);
        if(parts[0]==='cols') setGridSize(GRID_COLS + d, GRID_ROWS);
        else setGridSize(GRID_COLS, GRID_ROWS + d);
        try{ if(Canghai && Canghai.saveGrid) Canghai.saveGrid(GRID_COLS, GRID_ROWS); }catch(e){}
      });
    });
    var logoSw = win.querySelector('#setBrandLogoSw');
    if(logoSw){
      logoSw.classList.toggle('on', state.showBrandLogo !== false);
      logoSw.addEventListener('click', function(e){
        e.stopPropagation();
        var willShow = !logoSw.classList.contains('on');
        setBrandLogoVisible(willShow);
      });
    }
    // 渲染分辨率选项
    var resRow = win.querySelector('.resRow');
    if(resRow && typeof Canghai !== 'undefined' && Canghai && Canghai.getResolutions){
      try{
        var resList = JSON.parse(Canghai.getResolutions() || '[]');
        var rHtml = '';
        resList.forEach(function(r){
          rHtml += '<button type="button" class="miniBtn'+(r.current?' on':'')+'" data-res="'+r.key+'" style="flex:1;min-width:96px;padding:8px 10px;text-align:center;">'
            + '<div style="font-weight:700;font-size:13px">'+escapeHtml(r.name)+'</div>'
            + '<div style="font-size:11px;opacity:.75;margin-top:2px">'+escapeHtml(r.desc)+'</div>'
            + '</button>';
        });
        resRow.innerHTML = rHtml;
        resRow.querySelectorAll('button[data-res]').forEach(function(btn){
          btn.addEventListener('click', function(){
            var key = btn.getAttribute('data-res');
            resRow.querySelectorAll('button').forEach(function(b){ b.classList.remove('on'); });
            btn.classList.add('on');
            if(Canghai.setResolution) Canghai.setResolution(key);
            toast('正在切换分辨率至 ' + key);
          });
        });
      }catch(e){}
    }
    updateGridInfo();
    win.querySelectorAll('.wps button').forEach(function(b){
      b.addEventListener('click', function(){
        win.querySelectorAll('.wps button').forEach(function(x){x.classList.remove('on')});
        b.classList.add('on');
        setWallpaper(b.getAttribute('data-wp'), true);
        deskSaveSoon();
        try{ if(Canghai && Canghai.saveWallpaper) Canghai.saveWallpaper(b.getAttribute('data-wp')); }catch(e){}
      });
    });
  }
}
function setBrandLogoVisible(show){
  state.showBrandLogo = !!show;
  var mb = document.getElementById('menubar');
  if(mb) mb.classList.toggle('hide-logo', !show);
  var sw = document.getElementById('setBrandLogoSw');
  if(sw) sw.classList.toggle('on', !!show);
  deskSaveSoon();
}
function setWallpaper(cls, isUserCustom){
  if(isUserCustom) window.__hasCustomWallpaper = true;
  window.__wpCls = cls;
  var wp = document.getElementById('wallpaper');
  /* 动态壁纸（three.js）：CSS 渐变打底、3D 画布盖在上面，SVG 场景藏起来 */
  var def = (window.CHWallpapers && window.CHWallpapers.get) ? window.CHWallpapers.get(cls) : null;
  wp.className = 'wallpaper ' + (def ? def.base : cls) + (def ? ' wp3d' : '');
  var sun = document.getElementById('wpSun');
  if(sun) sun.style.opacity = cls==='wp-dusk' ? '.9' : '.25';
  /* 观海 · 每片海有自己的岛色：浅滩的岛青浅，极夜的岛沉入夜色 */
  var fills = {'wp-dusk':['#07263f','#030e1c'],'wp-sea':['#062a44','#020d1a'],'wp-forest':['#0d4f6e','#07405c'],'wp-aurora':['#030c1a','#01040a']};
  var f = fills[cls];
  if(f){
    var m1 = document.getElementById('wpM1'), m2 = document.getElementById('wpM2');
    if(m1) m1.setAttribute('fill', f[0]);
    if(m2) m2.setAttribute('fill', f[1]);
  }
  if(window.CHWallpapers){
    if(def) window.CHWallpapers.activate(cls);
    else window.CHWallpapers.deactivate();
  }
}

/* ================= 桌面（单个） =================
   多桌面模式已去掉：所有图标、小组件和窗口都在这一个桌面上。 */
function applyDesk(){
  items.forEach(function(it){ it.el.style.display = ''; });
  Object.keys(openWins).forEach(function(k){
    var w = openWins[k];
    w.style.display = w._min ? 'none' : 'flex';
  });
}
function pulseItem(it){
  it.el.classList.remove('pulse'); void it.el.offsetWidth; it.el.classList.add('pulse');
  setSel([it]);
  setTimeout(function(){ it.el.classList.remove('pulse'); }, 1200);
}

/* ================= Dock 状态桥接（Dock 已由悬浮层接管，桌面只负责同步运行态） ================= */
var dock = null;
var dockWrap = null;
var dockTrigger = null;
function dockAppHTML(appId, kind){ return ''; }
function renderDock(){
  chPushDockRunState();
}