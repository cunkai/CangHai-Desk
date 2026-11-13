/* ========================================================
 * 沧海系统桌面 - 设置与对话框系统 (settings.js)
 * 职责：关于本机、屏幕独立鼠标样式与大小、壁纸选择器、分辨率调节、左上角图标圆形与隐藏开关
 * ======================================================== */
/* ================= 右键菜单 ================= */
var ctxMenu = null;
function closeMenu(){ if(ctxMenu){ ctxMenu.remove(); ctxMenu = null; } }
function createMenuItem(en){
  if(en==='-'){ var s = document.createElement('div'); s.className='ctxSep'; return s; }
  var d = document.createElement('div');
  d.className = 'ctxItem'+(en.danger?' danger':'')+(en.sub && en.sub.length ? ' hasSub':'');
  var dl = document.createElement('span'); dl.textContent = en.label; d.appendChild(dl);
  if(en.checked){ var dck = document.createElement('span'); dck.className = 'ck'; dck.textContent = '✓'; d.appendChild(dck); }
  if(en.sub && en.sub.length){
    var dar = document.createElement('span'); dar.className = 'arr'; dar.textContent = '›'; d.appendChild(dar);
    var sub = document.createElement('div');
    sub.className = 'ctxSub';
    en.sub.forEach(function(se){
      sub.appendChild(createMenuItem(se));
    });
    d.appendChild(sub);
  } else if(en.fn){
    d.addEventListener('click', function(ev){
      ev.stopPropagation();
      closeMenu();
      en.fn();
    });
  }
  return d;
}

function showMenu(x, y, entries){
  closeMenu();
  var m = document.createElement('div');
  m.className = 'ctxMenu';
  entries.forEach(function(en){
    m.appendChild(createMenuItem(en));
  });
  document.body.appendChild(m);
  var mw = m.offsetWidth, mh = m.offsetHeight;
  var lx = Math.min(x, window.innerWidth - mw - 8), ly = Math.min(y, window.innerHeight - mh - 8);
  m.style.left = Math.max(4, lx)+'px'; m.style.top = Math.max(4, ly)+'px';
  if(lx + mw + 170 > window.innerWidth) m.querySelectorAll('.ctxSub').forEach(function(s){ s.classList.add('flip'); });
  ctxMenu = m;
}
document.addEventListener('pointerdown', function(e){
  if(ctxMenu && !e.target.closest('.ctxMenu')) closeMenu();
  if(!e.target.closest('#ccPanel') && !e.target.closest('#ccBtn')) document.getElementById('ccPanel').classList.remove('open');
  if(!e.target.closest('#calPop') && !e.target.closest('#clockBox')) document.getElementById('calPop').classList.remove('open');
});
function itemMenu(e, it){
  var entries = [];
  if(it.type==='app'){
    entries.push({label:'打开「'+appById(it.appId).name+'」', fn:function(){ openApp(it.appId); }});
    entries.push({label:'修改名字和图标…', fn:function(){ openAppEditor(it.appId); }});
    if(dockPins.indexOf(it.appId)<0) entries.push({label:'固定到 Dock', fn:function(){ pinToDock(it.appId); }});
    if(appById(it.appId).installed){
      entries.push('-');
      entries.push({label:(window.CHI18n ? CHI18n.t('export_pkg', '导出沧海软件…') : '导出沧海软件…'), fn:function(){ exportDesktopApp(it.appId); }});
      entries.push({label:'卸载软件', danger:true, fn:function(){ uninstallApp(it.appId); }});
    }
  } else if(it.type==='folder'){
    entries.push({label:'打开文件夹', fn:function(){ openFolder(it); }});
    entries.push({label:'重命名', fn:function(){ renameItem(it); }});
    entries.push({label:'文件夹大小', sub:[[1,1],[2,1],[1,2],[2,2],[3,2]].map(function(sz){
      return {label:sz[0]+' × '+sz[1]+' 格', checked: it.w===sz[0] && it.h===sz[1], fn:function(){ setFolderSize(it, sz[0], sz[1]); }};
    })});
    entries.push('-');
    entries.push({label:'删除文件夹（图标放回桌面）', danger:true, fn:function(){ deleteFolder(it); }});
  } else if(it.type==='file'){
    entries.push({label:'打开「' + it.name + '」（查看并复制提示词）', fn:function(){ openDevDoc(); }});
    entries.push({label:'复制提示词给 AI', fn:function(){ copyTextForAI(DEV_APP_PROMPT, '提示词已复制，粘贴给 AI 再补上你的需求'); }});
  } else if(it.type==='txt'){
    entries.push({label:'打开「' + it.name + '」', fn:function(){ openTxtWindow(it); }});
    entries.push({label:'重命名', fn:function(){ renameItem(it); }});
    entries.push({label:'复制内容', fn:function(){ copyTextForAI(it.content || '', '文件内容已复制'); }});
    entries.push('-');
    entries.push({label:'删除文件', danger:true, fn:function(){
      var key = 'txt_' + it.id;
      if(openWins[key]){ openWins[key].remove(); delete openWins[key]; }
      removeItem(it);
      deskSaveSoon();
      toast('已删除「' + it.name + '」');
    }});
  } else if(it.type==='widget'){
    /* 「编辑」只给真正有东西可编辑的小组件：自定义小组件改内容，
       天气小组件选城市；待办清单小组件添加待办；其它小组件没有可编辑项，就不放这个假入口 */
    if(it.wtype==='todo'){
      entries.push({label:'添加待办事项…', fn:function(){ if(typeof openTodoAddModal==='function') openTodoAddModal(it); }});
    } else if(it.wtype==='custom'){
      entries.push({label:'编辑', fn:function(){ openEditPanel(it); }});
    } else if(it.wtype==='weather'){
      entries.push({label:'编辑', fn:function(){ openCityPicker(it); }});
    }
    if(Array.isArray(it.customMenu) && it.customMenu.length > 0){
      it.customMenu.forEach(function(cm){
        if(!cm || !cm.label) return;
        entries.push({
          label: cm.label,
          fn: function(){
            var frame = it._frameEl || (it.el ? it.el.querySelector('iframe') : null);
            if(frame && frame.contentWindow){
              frame.contentWindow.postMessage({ __canghai: 1, type: 'contextMenuClick', id: cm.id || cm.label }, '*');
            }
          }
        });
      });
    }
    entries.push({label:'复制一个', fn:function(){ var ni = duplicateWidget(it); pulseItem(ni); toast('已复制「'+(it.name||'小组件')+'」'); }});
    entries.push({label:'透明度', sub:[100,85,70,50].map(function(v){
      return {label:v+'%', checked: Math.round((it.opacity==null?1:it.opacity)*100)===v, fn:function(){
        it.opacity = v/100;
        var wEl = it.el.querySelector('.widget'); if(wEl) wEl.style.opacity = it.opacity;
      }};
    })});
    entries.push('-');
    entries.push({label:'删除小组件', danger:true, fn:function(){ askDeleteWidget(it); }});
  }
  showMenu(e.clientX, e.clientY, entries);
}
function deleteFolder(it){
  var key = 'folder_'+it.id;
  if(openWins[key]){ openWins[key].remove(); delete openWins[key]; }
  var entries = ensureFolderEntries(it).slice(), desk = it.desk;
  removeItem(it);
  entries.forEach(function(en){
    var spot = findFree(1,1,0,0,desk);
    if(en.kind==='txt'){
      var nt = makeItem({type:'txt', name:en.name||'新建文本文档.txt', content:en.content||'', col:spot.col, row:spot.row, w:1, h:1, desk:desk});
      renderTxtCell(nt);
    } else {
      var a = appById(en.appId);
      var ni = makeItem({type:'app', appId:en.appId, name:a.name, col:spot.col, row:spot.row, w:1, h:1, desk:desk});
      renderAppCell(ni);
    }
  });
  applyDesk();
  deskSaveSoon();
  toast('文件夹已删除，里面的图标已放回桌面');
}
function desktopMenu(e){
  var periphSubs = [];
  try {
    var rawP = (window.Canghai && typeof window.Canghai.getPeripherals === 'function')
      ? window.Canghai.getPeripherals() : '[]';
    var pList = JSON.parse(rawP || '[]');
    var rawD = (window.Canghai && typeof window.Canghai.getAvailableDisplays === 'function')
      ? window.Canghai.getAvailableDisplays() : '[]';
    var dList = JSON.parse(rawD || '[]');
    if (!Array.isArray(dList) || dList.length === 0) {
      dList = [
        { id: 0, name: '手机自身屏幕' },
        { id: 46, name: '外接显示器' }
      ];
    }
    if (Array.isArray(pList) && pList.length > 0) {
      pList.forEach(function(p){
        var screenOptions = dList.map(function(d){
          var isCur = (p.targetDisplayId === d.id) || (d.id === 0 && p.targetDisplayId <= 0);
          return {
            label: d.name,
            checked: isCur,
            fn: function(){
              if (window.Canghai && typeof window.Canghai.setPeripheralDisplay === 'function') {
                window.Canghai.setPeripheralDisplay(p.id, d.id);
                toast('已将「' + (p.name || '外设') + '」切换至 ' + d.name);
              }
            }
          };
        });
        periphSubs.push({
          label: (p.name || '未知外设') + (p.type ? ' (' + p.type + ')' : ''),
          sub: screenOptions
        });
      });
    } else {
      periphSubs.push({ label: '（暂无已连接外设）' });
    }
  } catch(err) {
    periphSubs.push({ label: '（外设查询失败）' });
  }

  showMenu(e.clientX, e.clientY, [
    {label:'新建文件夹', fn:newFolder},
    {label:'新建文本文档', fn:newTxtFile},
    {label:'设备穿越', sub: periphSubs},
    '-',
    {label:'安装软件…', fn:function(){ openInstall(true); }},
    {label:'添加小组件', fn:openPanel},
    {label:'桌面网格 '+GRID_COLS+' × '+GRID_ROWS, sub:[
      {label:'15 × 7（默认）', checked: GRID_COLS===15 && GRID_ROWS===7, fn:function(){ setGridSize(15,7); }},
      {label:'12 × 6', checked: GRID_COLS===12 && GRID_ROWS===6, fn:function(){ setGridSize(12,6); }},
      {label:'18 × 8', checked: GRID_COLS===18 && GRID_ROWS===8, fn:function(){ setGridSize(18,8); }},
      {label:'20 × 10', checked: GRID_COLS===20 && GRID_ROWS===10, fn:function(){ setGridSize(20,10); }},
      {label:'自定义…', checked: !((GRID_COLS===15&&GRID_ROWS===7)||(GRID_COLS===12&&GRID_ROWS===6)||(GRID_COLS===18&&GRID_ROWS===8)||(GRID_COLS===20&&GRID_ROWS===10)), fn:openGridCustom}
    ]},
    '-',
    {label:'图标大小', sub:[
      {label:'小', checked: state.iconSize==='s', fn:function(){ setIconSize('s'); }},
      {label:'中', checked: state.iconSize==='m', fn:function(){ setIconSize('m'); }},
      {label:'大', checked: state.iconSize==='l', fn:function(){ setIconSize('l'); }}
    ]},
    {label:'排序方式', sub:[
      {label:'名称', checked: sortKey==='name', fn:function(){ sortDesktop('name'); }},
      {label:'类型', checked: sortKey==='type', fn:function(){ sortDesktop('type'); }},
      {label:'日期', checked: sortKey==='date', fn:function(){ sortDesktop('date'); }}
    ]}
  ]);
}
/* 右键菜单「桌面网格 → 自定义」：弹窗里填横/竖格子数，填写期间网格一直显形，
   每改一下立刻按新格数静默重排预览；完成才保留，取消（或点遮罩）回到打开前的格数 */
var gridModal = document.getElementById('gridModal');
var gridPrev = null;
function openGridCustom(){
  gridPrev = {cols: GRID_COLS, rows: GRID_ROWS};
  gridModal.classList.add('open');
  showGridFrame(true);
}
function closeGridCustom(keep){
  gridModal.classList.remove('open');
  showGridFrame(false);
  if(!keep && gridPrev) setGridSize(gridPrev.cols, gridPrev.rows, true);
  else if(keep) toast('桌面网格已改为 '+GRID_COLS+' × '+GRID_ROWS+' 格，居中排布，每格 '+CELL+' px');
  gridPrev = null;
}
gridModal.querySelectorAll('[data-gm]').forEach(function(b){
  b.addEventListener('click', function(){
    var p = b.getAttribute('data-gm').split(','), d = +p[1];
    if(p[0]==='cols') setGridSize(GRID_COLS + d, GRID_ROWS, true);
    else setGridSize(GRID_COLS, GRID_ROWS + d, true);
  });
});
document.getElementById('gridDone').addEventListener('click', function(){ closeGridCustom(true); });
document.getElementById('gridCancel').addEventListener('click', function(){ closeGridCustom(false); });
document.getElementById('gridModalBk').addEventListener('click', function(){ closeGridCustom(false); });
function setIconSize(sz){
  state.iconSize = sz;
  computeGrid(); /* 图标大小按格子大小的比例走，格子缩放时图标跟着缩放 */
  toast('图标大小：'+(sz==='s'?'小':sz==='l'?'大':'中'));
}
/* 排序方式（和 Windows 一样，右键 → 排序方式里选）：名称 / 类型 / 日期，
   选中的那项打勾；图标从左上起逐列排好，小组件占着的格子跳过 */
var sortKey = 'name';
var SORT_LABEL = {name:'名称', type:'类型', date:'日期'};
var TYPE_ORDER = {folder:0, app:1, file:2, widget:3};
function sortDesktop(key){
  sortKey = key;
  var mine = items.filter(function(it){ return it.desk===curDesk && it.type!=='widget'; });
  mine.sort(function(a,b){
    if(key==='type'){
      var d = (TYPE_ORDER[a.type]==null?9:TYPE_ORDER[a.type]) - (TYPE_ORDER[b.type]==null?9:TYPE_ORDER[b.type]);
      if(d) return d;
      return a.name.localeCompare(b.name, 'zh');
    }
    if(key==='date'){
      var dd = (a.createdAt||0) - (b.createdAt||0);
      if(dd) return dd;
      return a.id - b.id;
    }
    return a.name.localeCompare(b.name, 'zh');
  });
  var blockers = items.filter(function(it){ return it.desk===curDesk && it.type==='widget'; });
  var occupied = [];
  for(var c=0; c<COLS; c++){
    occupied[c] = [];
    for(var r=0; r<ROWS; r++){
      occupied[c][r] = false;
    }
  }
  blockers.forEach(function(b){
    var bw = b.w || 1, bh = b.h || 1;
    for(var dx=0; dx<bw; dx++){
      for(var dy=0; dy<bh; dy++){
        var xc = b.col + dx, yr = b.row + dy;
        if(xc < COLS && yr < ROWS) occupied[xc][yr] = true;
      }
    }
  });

  function canFit(c, r, w, h){
    if(c + w > COLS || r + h > ROWS) return false;
    for(var dx=0; dx<w; dx++){
      for(var dy=0; dy<h; dy++){
        if(occupied[c + dx][r + dy]) return false;
      }
    }
    return true;
  }

  mine.forEach(function(it){
    var iw = it.w || 1, ih = it.h || 1;
    var placed = false;
    for(var c=0; c<=COLS-iw && !placed; c++){
      for(var r=0; r<=ROWS-ih && !placed; r++){
        if(canFit(c, r, iw, ih)){
          it.col = c; it.row = r;
          for(var dx=0; dx<iw; dx++){
            for(var dy=0; dy<ih; dy++){
              occupied[c + dx][r + dy] = true;
            }
          }
          animatePos(it);
          placed = true;
        }
      }
    }
  });
  saveDesk();
  toast('已按'+SORT_LABEL[key]+'排序');
}

/* ================= 安装软件（桌面右键 → 安装软件） =================
   和手机端一样：HTML 文件 / 粘贴代码，或沧海软件包（.沧海软件 / .zip，
   包里 app.json 写名字、入口、图标）。装好把软件注册进 ALL_APPS、
   图标放到桌面，软件 HTML 里的小组件注册块一并扫出来注册。
   装完弹结果界面：成功能直接打开软件，失败写清原因。
   软件源码存进 CanghaiStore：手机端、外屏、刷新重连后都是同一份。 */
var installModal = document.getElementById('installModal');
var installResultModal = document.getElementById('installResultModal');
var instHtmlFile = document.getElementById('instHtmlFile');
var instPkgFile = document.getElementById('instPkgFile');
var instCodeEl = document.getElementById('instCode');
var instErrEl = document.getElementById('instErr');
var instSeq = 0, instBusy = false, instResultApp = null;
var INST_GRADS = [
  'linear-gradient(160deg,#38bdf8,#0b4a7a)',
  'linear-gradient(160deg,#2f9bff,#0a2f5c)',
  'linear-gradient(160deg,#7dd3fc,#14666e)',
  'linear-gradient(160deg,#4a7aa8,#0a1f33)',
  'linear-gradient(160deg,#c9a86a,#0a4a7a)'
];
function instGradFor(name){ var h=0; for(var i=0;i<name.length;i++) h=(h*31+name.charCodeAt(i))>>>0; return INST_GRADS[h%INST_GRADS.length]; }
function instLetterIcon(name){
  var ch = escapeHtml(String(name||'软').trim().charAt(0) || '软');
  return '<svg viewBox="0 0 48 48"><text x="24" y="34" text-anchor="middle" font-size="25" font-weight="800" fill="#ffffff">'+ch+'</text></svg>';
}
function looksLikeHtml(t){ return /<!doctype\s+html|<html[\s>]|<head[\s>]|<body[\s>]/i.test(t||''); }
function htmlTitle(t, fb){
  var m = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(t||'');
  var s = m ? m[1].replace(/<[^>]+>/g,'').replace(/\s+/g,' ').trim() : '';
  return (s || fb || '未命名软件').slice(0, 24);
}
function fileBaseName(n){ return String(n||'').replace(/\.[^.]+$/, '') || '未命名软件'; }
/* 一次性收掉所有临时弹层：启动台开 / 关的时候都调，避免「关掉的安装弹窗
   又被启动台唤起」。以前只 remove('open')，别处残留的一次 openInstall 会让它复活。 */
function chForceCloseTransientModals(){
  try{
    document.querySelectorAll('.curModal.open').forEach(function(m){ m.classList.remove('open'); });
    var p = document.getElementById('widgetPanel'), mk = document.getElementById('panelMask');
    if(p) p.classList.remove('open');
    if(mk) mk.classList.remove('open');
    var sm = document.getElementById('spotMask'); if(sm) sm.classList.remove('open');
    installModal.classList.remove('dragOver');
    instErrEl.textContent = '';
    instResultApp = null;
    setInstBusy(false);
  }catch(e){}
}
function openInstall(fromUser){
  /* 只有桌面右键菜单能打开它。启动台 / Dock 的点击回放之类不该凭空再弹一次。 */
  if(fromUser !== true) return;
  chForceCloseTransientModals();
  window.__chInstallDismissed = false;
  instErrEl.textContent = '';
  installModal.classList.add('open');
}
function closeInstall(){
  window.__chInstallDismissed = true;
  chForceCloseTransientModals();
}
/* 兜底：只要用户关过一次，任何代码再把 .open 戴回来都立刻摘掉 */
try{
  if(window.MutationObserver){
    new MutationObserver(function(){
      if(window.__chInstallDismissed && installModal.classList.contains('open'))
        installModal.classList.remove('open');
    }).observe(installModal, {attributes:true, attributeFilter:['class']});
  }
}catch(e){}
function setInstBusy(b){
  instBusy = b;
  ['instHtmlBtn','instPkgBtn','instCodeBtn'].forEach(function(id){ document.getElementById(id).disabled = b; });
}
function showInstallResult(ok, app, reason){
  closeInstall();
  setInstBusy(false);
  instResultApp = ok ? app : null;
  var badge = document.getElementById('instResBadge');
  badge.textContent = ok ? '✓' : '✕';
  badge.classList.toggle('fail', !ok);
  document.getElementById('instResTitle').textContent = ok ? '安装成功' : '安装失败';
  var row = document.getElementById('instResApp');
  if(ok && app){
    row.style.display = 'flex';
    row.innerHTML = '<span class="ia" style="background:'+app.grad+'">'+ICONS[app.id]+'</span><b>'+escapeHtml(app.name)+'</b>';
    document.getElementById('instResMsg').textContent = '已经放进沧海系统，桌面、启动台和手机端都会同步出现，刷新或重连外屏也不会丢。';
  } else {
    row.style.display = 'none';
    document.getElementById('instResMsg').textContent = reason || '这个文件装不上，换一个再试。';
  }
  document.getElementById('instResOpen').style.display = ok ? '' : 'none';
  installResultModal.classList.add('open');
}
function failInstall(reason){ showInstallResult(false, null, reason); }
function registerInstalledApp(name, html, iconMarkup){
  /* 软件名来自文件标题 / 软件包 app.json，是不可信文本：先剥掉尖括号引号再入库（纵深防御） */
  name = String(name || '未命名软件').replace(/[<>&"']/g, '').trim().slice(0, 24) || '未命名软件';
  /* 落进 CanghaiStore：手机端和这块外屏读同一份，刷新 / 重连都不会丢 */
  var nativeId = '';
  try{
    if(typeof Canghai !== 'undefined' && Canghai && Canghai.installHtml)
      nativeId = Canghai.installHtml(name, html, iconMarkup || null) || '';
  }catch(e){}
  var id = nativeId || ('u' + Date.now().toString(36) + (++instSeq));
  var app = {id:id, name:name, grad:instGradFor(name), installed:true, src:html};
  ALL_APPS.push(app);
  HTML_APPS.push(app);
  ICONS[id] = iconMarkup || instLetterIcon(name);
  lpItems.push({kind:'app', appId:id});
  /* 扫它的小组件注册块：appId 统一改成本软件，openSelf 才打得开它自己 */
  var re = /<script\b[^>]*\bdata-canghai-widget\b[^>]*>([\s\S]*?)<\/script>/gi, m;
  while((m = re.exec(html))){
    try {
      var def = JSON.parse(m[1].trim());
      def.appId = id; def.appName = name;
      registerCanghaiWidget(def);
    } catch(err){ if(window.console) console.warn('安装软件时小组件注册块解析失败（'+name+'）：', err); }
  }
  var spot = findFree(1,1,0,0,curDesk);
  var it = makeItem({type:'app', appId:id, name:name, col:spot.col, row:spot.row, w:1, h:1, desk:curDesk});
  renderAppCell(it);
  pulseItem(it);
  return app;
}
function installFromHtmlText(text, fallbackName){
  if(instBusy) return;
  if(!text || !String(text).trim()){ failInstall('没有读到内容：文件是空的，或者粘贴框里什么都没有。'); return; }
  if(!looksLikeHtml(text)){ failInstall('这不像一个完整的 HTML 软件：没有找到 <html> 结构。请选择软件的 .html 文件，或粘贴完整的 HTML 代码。'); return; }
  var app = registerInstalledApp(htmlTitle(text, fallbackName), String(text), null);
  showInstallResult(true, app);
}
function readFileText(file, cb){
  var r = new FileReader();
  r.onload = function(){ cb(null, String(r.result||'')); };
  r.onerror = function(){ cb(new Error('read')); };
  r.readAsText(file);
}
function handleHtmlFile(f){
  if(!/\.html?$/i.test(f.name) && f.type!=='text/html'){ failInstall('选的不是 HTML 文件：「'+f.name+'」。软件应是一个 .html 文件。'); return; }
  setInstBusy(true);
  readFileText(f, function(err, text){
    setInstBusy(false);
    if(err){ failInstall('文件「'+f.name+'」没读出来，换个文件再试。'); return; }
    installFromHtmlText(text, fileBaseName(f.name));
  });
}
function pkgFindFile(zip, names, want){
  var w = String(want||'').replace(/^\.?\//, '');
  if(!w) return null;
  var hit = null;
  names.forEach(function(n){
    if(n===w || n.slice(-(w.length+1))==='/'+w){ if(!hit || n.length<hit.length) hit = n; }
  });
  return hit;
}
function readPackageAsBase64(file, cb){
  var r = new FileReader();
  r.onload = function(){
    var s = String(r.result || '');
    var comma = s.indexOf(',');
    if(comma >= 0) s = s.slice(comma + 1);
    cb(null, s);
  };
  r.onerror = function(){ cb(new Error('read')); };
  r.readAsDataURL(file);
}
function handlePackageFile(f){
  if(instBusy) return;
  if(!/\.(zip|沧海软件|canghai)$/i.test(f.name)){ failInstall('选的不是软件包：「'+f.name+'」。沧海软件包是 .沧海软件 / .canghai 或 .zip 文件。'); return; }
  /* 优先使用原生 Java 解压通道：支持 Base64 直接解包（拖拽/点选均可），老版本降级走 unzipUri */
  var bridge = (typeof window.Canghai !== 'undefined' && window.Canghai && (window.Canghai.unzipBase64 || window.Canghai.unzipUri)) ? window.Canghai
             : ((typeof window.Aosen !== 'undefined' && window.Aosen && (window.Aosen.unzipBase64 || window.Aosen.unzipUri)) ? window.Aosen : null);
  if(!bridge){
    failInstall('这台设备没有解包通道；可以先在手机端导入软件包，装好会自动同步到外屏。'); return;
  }
  setInstBusy(true);
  readPackageAsBase64(f, function(err, b64){
    var res = null;
    try{
      if(!err && b64 && bridge.unzipBase64){
        res = JSON.parse(bridge.unzipBase64(b64));
      } else if(bridge.unzipUri && bridge.getPickedUri){
        var uri = bridge.getPickedUri();
        if(uri) res = JSON.parse(bridge.unzipUri(uri));
      }
    }catch(e){ res = null; }
    setInstBusy(false);
    if(!res || !res.ok){
      failInstall('软件包解不开：' + ((res && res.err) || '读取或解压失败') + '。也可以先在手机端导入，装好会自动同步过来。');
      return;
    }
    var files = {}, names = [];
    try{
      (res.files || []).forEach(function(it){
        var bin = atob(it.b), u8 = new Uint8Array(bin.length);
        for(var i=0;i<bin.length;i++) u8[i] = bin.charCodeAt(i);
        var n = chNormPath(it.n);
        if(n && n.indexOf('__MACOSX') !== 0){ files[n] = u8; names.push(n); }
      });
    }catch(e){ failInstall('软件包内容读不出来，换一个包再试。'); return; }
    if(!names.length){ failInstall('这个包是空的。'); return; }
    var dec = new TextDecoder('utf-8');
    var manifest = null, jsonName = pkgFindFile(files, names, 'app.json');
    if(jsonName){ try{ manifest = JSON.parse(dec.decode(files[jsonName])); }catch(e){ manifest = null; } }
    var entryName = null;
    if(manifest && manifest.entry) entryName = pkgFindFile(files, names, manifest.entry);
    if(!entryName) entryName = pkgFindFile(files, names, 'index.html');
    if(!entryName){
      var htmls = names.filter(function(n){ return /\.html?$/i.test(n); });
      htmls.sort(function(a,b){ return a.length-b.length; });
      entryName = htmls[0] || null;
    }
    if(!entryName){ failInstall('软件包里找不到 HTML 入口文件：包里需要有一个 .html（最好在 app.json 里用 entry 写明，或直接放 index.html）。'); return; }
    var html = dec.decode(files[entryName]);
    if(!looksLikeHtml(html)){ failInstall('包里的入口文件「'+entryName+'」不是完整的 HTML，装不上。'); return; }
    /* 源码工程包（带 ES module / 多文件）需要手机端那套整包打包器才能跑；
       这里说清楚，别装出一个打不开的黑屏软件。 */
    if(/<script[^>]+type=["']?module|import\s+.*\s+from\s+['"]/i.test(html)){
      failInstall('这是多文件源码工程包，请先在手机端「安装软件」里导入；导入后会自动同步到外屏，两边都能打开。');
      return;
    }
    var name = (manifest && manifest.name ? String(manifest.name) : '') || htmlTitle(html, fileBaseName(f.name));
    name = name.slice(0, 24);
    var iconMarkup = null, iconName = manifest && manifest.icon ? pkgFindFile(files, names, manifest.icon) : null;
    if(iconName && /\.svg$/i.test(iconName)){
      var svg = dec.decode(files[iconName]).replace(/<script[\s\S]*?<\/script>/gi,'').replace(/\son\w+\s*=\s*"[^"]*"/gi,'').replace(/\son\w+\s*=\s*'[^']*'/gi,'');
      if(/<svg[\s>]/i.test(svg)) iconMarkup = svg;
    } else if(iconName && /\.(png|jpe?g|gif|webp)$/i.test(iconName)){
      var mime = /\.png$/i.test(iconName) ? 'image/png' : /\.gif$/i.test(iconName) ? 'image/gif' : /\.webp$/i.test(iconName) ? 'image/webp' : 'image/jpeg';
      var raw = '', bytes = files[iconName];
      for(var bi=0;bi<bytes.length;bi++) raw += String.fromCharCode(bytes[bi]);
      iconMarkup = '<svg viewBox="0 0 48 48"><image href="data:'+mime+';base64,'+btoa(raw)+'" x="0" y="0" width="48" height="48" preserveAspectRatio="xMidYMid slice"/></svg>';
    }
    var app = registerInstalledApp(name, html, iconMarkup);
    showInstallResult(true, app);
  });
}
function routeInstallFile(f){
  if(!f) return;
  if(/\.html?$/i.test(f.name) || f.type==='text/html') handleHtmlFile(f);
  else if(/\.(zip|沧海软件|canghai)$/i.test(f.name)) handlePackageFile(f);
  else failInstall('不认识这个文件：「'+f.name+'」。只收 .html 文件和 .沧海软件 / .canghai / .zip 软件包。');
}
document.getElementById('instHtmlBtn').addEventListener('click', function(){ instHtmlFile.value=''; instHtmlFile.click(); });
document.getElementById('instPkgBtn').addEventListener('click', function(){ instPkgFile.value=''; instPkgFile.click(); });
instHtmlFile.addEventListener('change', function(){ var f = instHtmlFile.files && instHtmlFile.files[0]; if(f) handleHtmlFile(f); });
instPkgFile.addEventListener('change', function(){ var f = instPkgFile.files && instPkgFile.files[0]; if(f) handlePackageFile(f); });
document.getElementById('instCodeBtn').addEventListener('click', function(){ installFromHtmlText(instCodeEl.value, '粘贴安装的软件'); });
document.getElementById('instCancel').addEventListener('click', closeInstall);
document.getElementById('installBk').addEventListener('click', closeInstall);
['dragover','dragenter'].forEach(function(ev){
  installModal.addEventListener(ev, function(e){ e.preventDefault(); installModal.classList.add('dragOver'); });
});
installModal.addEventListener('dragleave', function(e){ if(e.target===installModal) installModal.classList.remove('dragOver'); });
installModal.addEventListener('drop', function(e){
  e.preventDefault();
  installModal.classList.remove('dragOver');
  var f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
  if(f) routeInstallFile(f);
});
document.getElementById('instResClose').addEventListener('click', function(){ installResultModal.classList.remove('open'); });
document.getElementById('instResOpen').addEventListener('click', function(){
  installResultModal.classList.remove('open');
  if(instResultApp) openApp(instResultApp.id);
});
/* 卸载装进来的软件：窗口关掉、桌面 / 文件夹 / 启动台 / Dock 里的都撤下，
   它注册的小组件也一并撤下 */
function uninstallApp(appId){
  var app = appById(appId);
  /* 卸载必须落到 CanghaiStore，手机端才会同步消失；以前只改本页内存 */
  try{
    if(app.installed && typeof Canghai !== 'undefined' && Canghai && Canghai.removeHtml)
      Canghai.removeHtml(appId);
  }catch(e){}
  if(openWins[appId]){ openWins[appId].remove(); delete openWins[appId]; }
  items.slice().forEach(function(it){
    if(it.type==='app' && it.appId===appId) removeItem(it);
    if(it.type==='folder') it.apps = it.apps.filter(function(x){ return x!==appId; });
  });
  Object.keys(APP_WIDGETS).forEach(function(wid){
    if(APP_WIDGETS[wid].appId===appId){
      delete APP_WIDGETS[wid];
      APP_WIDGET_ORDER = APP_WIDGET_ORDER.filter(function(x){ return x!==wid; });
      items.slice().forEach(function(it){ if(it.type==='widget' && it.wtype==='app' && it.widgetId===wid) removeItem(it); });
    }
  });
  /* 已装软件的注册块在「打开窗口时」扫描它自己的源码（见 wireApp），不再写死示例 */
buildAppWidgetPresets();
  lpItems = lpItems.filter(function(x){ return !(x.kind==='app' && x.appId===appId); });
  lpItems.forEach(function(x){ if(x.kind==='folder') x.apps = x.apps.filter(function(a){ return a!==appId; }); });
  dockPins = dockPins.filter(function(x){ return x!==appId; });
  recentApps = recentApps.filter(function(x){ return x!==appId; });
  for(var i=ALL_APPS.length-1;i>=0;i--) if(ALL_APPS[i].id===appId) ALL_APPS.splice(i,1);
  HTML_APPS = HTML_APPS.filter(function(a){ return a.id !== appId; });
  delete ICONS[appId];
  renderDock();
  if(typeof lpIsOpen === 'function' && lpIsOpen()) renderLaunchpad();
  applyDesk();
  toast('已卸载「'+app.name+'」· 手机端也同步移除');
}
function newFolder(){
  var spot = findFree(1,1,1,1,curDesk);
  var names = items.filter(function(it){ return it.type==='folder'; }).length;
  var it = makeItem({type:'folder', name:'新建文件夹'+(names?' '+(names+1):''), col:spot.col, row:spot.row, w:1, h:1, apps:[]});
  renderFolderCell(it);
  pulseItem(it);
  toast('已新建文件夹，把应用图标拖到它上面就能收进去');
}
function dockItemMenu(e, di){
  var appId = di.getAttribute('data-app');
  if(!appId){
    var act = di.getAttribute('data-act');
    if(act==='launchpad') showMenu(e.clientX, e.clientY, [{label:'打开启动台（全部应用）', fn:openLaunchpad}]);
    else if(act==='widgets') showMenu(e.clientX, e.clientY, [{label:'打开小组件面板', fn:openPanel}]);
    return;
  }
  var app = appById(appId);
  var entries = [{label:'打开「'+app.name+'」', fn:function(){
    var win = openWins[appId];
    if(win){ win._min=false; win.style.display='flex'; focusWin(win); renderDock(); }
    else openApp(appId);
  }}];
  if(openWins[appId]) entries.push({label:'退出「'+app.name+'」', fn:function(){
    openWins[appId].remove(); delete openWins[appId]; renderDock(); toast('已退出「'+app.name+'」');
  }});
  if(app.installed) entries.push({label:(window.CHI18n ? CHI18n.t('export_pkg', '导出沧海软件…') : '导出沧海软件…'), fn:function(){ exportDesktopApp(appId); }});
  entries.push('-');
  if(di.getAttribute('data-pin')) entries.push({label:'从 Dock 中移除', danger:true, fn:function(){
    dockPins = dockPins.filter(function(x){ return x!==appId; }); renderDock(); toast('已从 Dock 移除「'+app.name+'」');
  }});
  else entries.push({label:'固定到 Dock', fn:function(){ pinToDock(appId); }});
  showMenu(e.clientX, e.clientY, entries);
}
function dockBgMenu(e){
  showMenu(e.clientX, e.clientY, [
    {label:'Dock 位置', sub:[
      {label:'底部', checked: state.dockSide==='bottom', fn:function(){ setDockSide('bottom'); }},
      {label:'左侧', checked: state.dockSide==='left', fn:function(){ setDockSide('left'); }},
      {label:'右侧', checked: state.dockSide==='right', fn:function(){ setDockSide('right'); }}
    ]},
    {label:'自动隐藏 Dock', checked: state.dockAutoHide, fn:function(){
      chSetDockAutoHide(!state.dockAutoHide);
      toast(state.dockAutoHide ? 'Dock 自动隐藏已开，鼠标贴边唤出' : 'Dock 自动隐藏已关');
    }},
    '-',
    {label:'添加小组件', fn:openPanel}
  ]);
}
document.getElementById('desktop').addEventListener('contextmenu', function(e){
  e.preventDefault();
  if(e.target.closest && e.target.closest('#launchpad')) return;
  if(e.target.closest && e.target.closest('.stickyNote')) return;
  if(e.target.closest && (e.target.closest('.win') || e.target.closest('#menubar'))) return;
  var di = e.target.closest ? e.target.closest('.dockItem') : null;
  if(di && di.closest('#dock')){ dockItemMenu(e, di); return; }
  if(e.target.closest && e.target.closest('#dock')){ dockBgMenu(e); return; }
  var ditem = e.target.closest ? e.target.closest('.ditem') : null;
  if(ditem){ var it = itemByEl(ditem); if(it){ setSel([it]); itemMenu(e, it); return; } }
  desktopMenu(e);
});

/* ================= 小组件面板 ================= */
var panel = document.getElementById('widgetPanel'), mask = document.getElementById('panelMask');
var editingWidget = null;
function openPanel(){ panel.classList.add('open'); mask.classList.add('open'); refreshPreview(); }
function closePanel(){
  panel.classList.remove('open'); mask.classList.remove('open');
  if(editingWidget){
    editingWidget = null;
    document.getElementById('addCustomBtn').textContent = '放到桌面上';
    document.getElementById('panelTitle').textContent = '添加小组件';
  }
}
document.getElementById('panelClose').addEventListener('click', closePanel);
mask.addEventListener('click', closePanel);
function openEditPanel(it){
  editingWidget = it;
  document.getElementById('cwName').value = it.name || '';
  /* 面板里的宽高是「设计尺寸」：填内容排版时的格数，不是被拖大后的框格数 */
  document.getElementById('cwW').value = String(it.dw||it.w);
  document.getElementById('cwH').value = String(it.dh||it.h);
  document.getElementById('cwHtml').value = it.html || '';
  refreshPreview();
  document.getElementById('addCustomBtn').textContent = '保存修改';
  document.getElementById('panelTitle').textContent = '编辑小组件';
  openPanel();
  toast('正在编辑「'+(it.name||'小组件')+'」，改完点「保存修改」');
}
function buildPresets(){
  var g = document.getElementById('presetGrid');
  g.innerHTML = Object.keys(WIDGET_TYPES).map(function(k){
    var d = WIDGET_TYPES[k];
    return '<button class="preset" type="button" data-w="'+k+'"><span class="sz">'+d.w+'×'+d.h+'</span><span class="pi">'+d.icon+'</span><b>'+d.name+'</b><span>'+d.desc+'</span></button>';
  }).join('');
  g.querySelectorAll('.preset').forEach(function(b){
    b.addEventListener('click', function(){
      var it = addWidget(b.getAttribute('data-w'));
      closePanel();
      toast('已添加「'+it.name+'」（'+it.w+'×'+it.h+' 格）到桌面，按住即可拖动');
    });
  });
}
/* 自定义 HTML */
/* 快捷模板（例子）：和内置小组件同一个样子——底用 --cw-bg 同款毛玻璃、字用
   --cw-ink / --cw-sub，深浅外观都配好，不会再出现白底白字；尺寸按设计画布
   （每格 96px）排，放大缩小都由 #cwFit 整体等比缩放跟着小组件区域走 */
var TPLS = {
  hello:'<div style="height:100%;background:var(--cw-bg);color:var(--cw-ink);display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:18px">\n  <div style="width:64px;height:64px;border-radius:50%;background:radial-gradient(circle at 50% 36%,rgba(234,246,255,.9),rgba(125,211,252,.30) 62%,rgba(125,211,252,.04));box-shadow:inset 0 0 0 1px var(--cw-line);display:flex;align-items:center;justify-content:center;font-size:32px">👋</div>\n  <div style="font-size:19px;font-weight:800;margin-top:12px">你好，沧海</div>\n  <p style="font-size:13px;color:var(--cw-sub);margin:5px 0 0">今天也要元气满满。</p>\n  <svg width="118" height="12" viewBox="0 0 118 12" style="margin-top:12px"><path d="M2 8 Q 16 2 30 8 T 58 8 T 86 8 T 114 8" fill="none" stroke="var(--cw-accent)" stroke-width="2" stroke-linecap="round"/></svg>\n</div>',
  count:'<div style="height:100%;background:var(--cw-bg);color:var(--cw-ink);display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:16px">\n  <div style="font-size:12px;letter-spacing:.4em;color:var(--cw-sub)">计数器</div>\n  <div id="n" style="font-size:52px;font-weight:800;line-height:1.2;font-variant-numeric:tabular-nums">0</div>\n  <div style="display:flex;gap:8px;margin-top:6px">\n    <button onclick="document.getElementById(\'n\').textContent=+document.getElementById(\'n\').textContent+1" style="border:1px solid var(--cw-line);background:var(--cw-chip);color:var(--cw-ink);border-radius:99px;padding:7px 18px;font:inherit;font-size:14px;font-weight:700;cursor:pointer">点我 +1</button>\n    <button onclick="document.getElementById(\'n\').textContent=0" style="border:1px solid var(--cw-line);background:transparent;color:var(--cw-sub);border-radius:99px;padding:7px 14px;font:inherit;font-size:14px;cursor:pointer">清零</button>\n  </div>\n  <p style="font-size:12px;color:var(--cw-sub);margin:10px 0 0">脚本会在隔离框里运行</p>\n</div>',
  color:'<div id="cb" style="height:100%;background:linear-gradient(150deg,#062544,#0a4a7a 45%,#2f9bff 78%,#7dd3fc);display:flex;align-items:center;justify-content:center;cursor:pointer">\n  <span style="background:rgba(3,16,30,.40);color:#fff;font-size:16px;font-weight:800;padding:8px 18px;border-radius:99px;box-shadow:inset 0 0 0 1px rgba(255,255,255,.35)">沧海渐变块 · 点我换色</span>\n</div>\n<script>\nvar CBG=["linear-gradient(150deg,#062544,#0a4a7a 45%,#2f9bff 78%,#7dd3fc)","linear-gradient(150deg,#07293d,#14666e 48%,#2fbfae 80%,#a8ecdf)","linear-gradient(150deg,#050b14,#14335c 52%,#8a6d2f 84%,#c9a86a)"],CBI=0;\ndocument.getElementById("cb").addEventListener("click",function(){CBI=(CBI+1)%CBG.length;this.style.background=CBG[CBI];});\n<\/script>',
  poem:'<div style="height:100%;background:var(--cw-bg);color:var(--cw-ink);padding:20px 22px;display:flex;flex-direction:column;justify-content:center">\n  <div style="font-size:17px;font-weight:800">🍃 山间</div>\n  <div style="width:34px;height:3px;border-radius:2px;background:var(--cw-gold);margin:9px 0 11px"></div>\n  <div style="font-size:15px;line-height:2">云在青山外，<br>风从指缝来。</div>\n  <div style="font-size:12px;color:var(--cw-sub);margin-top:9px">—— 桌面小诗</div>\n</div>'
};
var cwHtml = document.getElementById('cwHtml'), cwPrev = document.getElementById('htmlPreview');
cwHtml.value = TPLS.hello;
function refreshPreview(){
  var f = document.getElementById('htmlPreviewFrame');
  var w = parseInt(document.getElementById('cwW').value,10)||2, h = parseInt(document.getElementById('cwH').value,10)||2;
  /* 预览框跟着所选格数变形状，里面的缩放也按这个尺寸算，所见就是上桌面的样子 */
  var pw = cwPrev.clientWidth || 350;
  cwPrev.style.height = Math.max(110, Math.min(260, Math.round(pw*h/w)))+'px';
  if(f) f.srcdoc = buildCustomSrcdoc(cwHtml.value, w, h);
}
cwHtml.addEventListener('input', refreshPreview);
document.getElementById('cwW').addEventListener('change', refreshPreview);
document.getElementById('cwH').addEventListener('change', refreshPreview);
refreshPreview();
document.querySelectorAll('.tplRow button').forEach(function(b){
  b.addEventListener('click', function(){ cwHtml.value = TPLS[b.getAttribute('data-tpl')]; refreshPreview(); });
});
document.getElementById('addCustomBtn').addEventListener('click', function(){
  var name = document.getElementById('cwName').value.trim() || '我的小组件';
  var w = parseInt(document.getElementById('cwW').value,10), h = parseInt(document.getElementById('cwH').value,10);
  if(editingWidget){
    var it = editingWidget;
    it.name = name; it.html = cwHtml.value;
    it.col = clamp(it.col, 0, COLS-w); it.row = clamp(it.row, 0, ROWS-h);
    if(rectHit(it.col, it.row, w, h, it.desk, [it])){
      var spot = findFree(w, h, it.col, it.row, it.desk, [it]);
      it.col = spot.col; it.row = spot.row;
    }
    it.w = w; it.h = h;
    it.dw = w; it.dh = h; /* 在面板里改尺寸 = 按新尺寸重新排版，设计锚点跟着更新 */
    posEl(it);
    renderWidget(it);
    closePanel();
    toast('小组件「'+name+'」已更新');
    return;
  }
  addCustomWidget(name, w, h, cwHtml.value);
  closePanel();
  toast('自定义小组件「'+name+'」（'+w+'×'+h+' 格）已放到桌面');
});


/* ================= 沧海小组件注册协议 v1 =================
   每个沧海软件（一个 HTML 文件）都可以给桌面注册小组件，两种注册方式：
   A. 在软件 HTML 里放注册块，宿主加载软件时扫描提取：
      script 标签带 data-canghai-widget 属性、type 为 application/json，
      内容是一个 JSON 对象（字段见 normalize 说明与开发提示词）。
   B. 软件运行时调用 JS API：Canghai.registerWidget(对象)。
   注册成功的小组件出现在「添加小组件」面板的「软件注册的小组件」里，
   用户点一下就放到桌面。小组件画面在沙盒 iframe 里运行（和自定义
   小组件同一套设计画布缩放与观海变量），软件窗口关着也照样运行，
   这就是「后台」；要让桌面做事只能走宿主桥 Canghai（postMessage）：
   openApp / openSelf / toast，或在元素上写 data-canghai-action。 */
var APP_WIDGETS = {};
var APP_WIDGET_ORDER = [];
function cwToken(s, fb){ var t = String(s == null ? '' : s).replace(/[^a-zA-Z0-9_-]/g, ''); return t || fb; }
function registerCanghaiWidget(def){
  if(!def || typeof def !== 'object') return false;
  var id = cwToken(def.id, '');
  if(!id || !def.name || !def.html) return false;
  if(APP_WIDGETS[id]) return false;
  APP_WIDGETS[id] = {
    id: id,
    appId: cwToken(def.appId, 'app'),
    appName: String(def.appName || def.name || '软件'),
    name: String(def.name),
    icon: String(def.icon || '🧩'),
    desc: String(def.desc || ''),
    w: clamp(Math.round(def.w) || 2, 1, 4),
    h: clamp(Math.round(def.h) || 2, 1, 4),
    html: String(def.html),
    script: def.script ? String(def.script) : '',
    refreshSeconds: clamp(Math.round(def.refreshSeconds) || 0, 0, 3600)
  };
  APP_WIDGET_ORDER.push(id);
  buildAppWidgetPresets();
  return true;
}
function parseCanghaiWidgets(html, sourceName){
  var out = [];
  if(!html) return out;
  var re = /<script\b[^>]*\bdata-canghai-widget\b[^>]*>([\s\S]*?)<\/script>/gi, m;
  while((m = re.exec(html))){
    try {
      var def = JSON.parse(m[1].trim());
      if(registerCanghaiWidget(def)) out.push(def);
    } catch(err){
      if(window.console) console.warn('沧海小组件注册块解析失败（' + (sourceName || '软件') + '）：', err);
    }
  }
  return out;
}
if(typeof window.Canghai !== 'undefined' && window.Canghai){
  window.Canghai.version = 1;
  window.Canghai.registerWidget = registerCanghaiWidget;
  window.Canghai.parseWidgets = parseCanghaiWidgets;
  window.Canghai.widgets = APP_WIDGETS;
  if(!window.Canghai.openApp) window.Canghai.openApp = function(id){ openApp(id); };
} else {
  window.Canghai = {
    version: 1,
    registerWidget: registerCanghaiWidget,
    parseWidgets: parseCanghaiWidgets,
    widgets: APP_WIDGETS,
    openApp: function(id){ openApp(id); }
  };
}
/* 小组件沙盒里注入的宿主桥：Canghai.openApp / openSelf / toast，
   以及 data-canghai-action 元素点击转发（openApp / toast） */
function buildAppWidgetSrcdoc(def){
  /* 动作在 pointerdown 阶段就发给宿主（click 只作兜底，600ms 内同一元素同一动作不重复）：
     若等 click，宿主页任何一层指针捕获 / 拖拽判定都可能把抬起与点击重定向走，
     按钮看起来就是「点不动」；pointerdown 是隔离框内最先发生的事件，谁也抢不走 */
  var bridge = '<script>window.Canghai={'
    + 'openApp:function(id){parent.postMessage({__canghai:1,type:"openApp",appId:id||"' + def.appId + '"},"*")},'
    + 'openSelf:function(){parent.postMessage({__canghai:1,type:"openApp",appId:"' + def.appId + '"},"*")},'
    + 'toast:function(m){parent.postMessage({__canghai:1,type:"toast",message:String(m)},"*")},'
    + 'setContextMenu:function(items){parent.postMessage({__canghai:1,type:"setContextMenu",items:items},"*")},'
    + 'openModal:function(opts){opts=opts||{};parent.postMessage({__canghai:1,type:"openModal",title:opts.title||"",html:opts.html||""},"*")},'
    + 'closeModal:function(){parent.postMessage({__canghai:1,type:"closeModal"},"*")}};'
    + 'var __lastA=0,__lastT=null;'
    + 'function __fire(e){var t=e.target&&e.target.closest?e.target.closest("[data-canghai-action]"):null;if(!t)return;'
    + 'var a=t.getAttribute("data-canghai-action");var now=Date.now();'
    + 'if(t===__lastT&&now-__lastA<600)return;__lastT=t;__lastA=now;'
    + 'if(a==="openApp")Canghai.openApp(t.getAttribute("data-app")||"' + def.appId + '");'
    + 'else if(a==="toast")Canghai.toast(t.getAttribute("data-text")||"")} '
    + 'document.addEventListener("pointerdown",__fire,true);'
    + 'document.addEventListener("click",__fire,true);'
    + '<\/script>';
  var html = bridge + (def.html || '') + (def.script ? '<script>' + def.script + '<\/script>' : '');
  return buildCustomSrcdoc(html, def.w, def.h);
}
function addAppWidget(id){
  var def = APP_WIDGETS[id];
  if(!def) return null;
  var spot = findFree(def.w, def.h, COLS - def.w, 0, curDesk);
  var it = makeItem({type:'widget', wtype:'app', widgetId:id, name:def.name, col:spot.col, row:spot.row, w:def.w, h:def.h});
  renderWidget(it);
  return it;
}
function buildAppWidgetPresets(){
  var g = document.getElementById('appWidgetGrid'), empty = document.getElementById('appWidgetEmpty');
  if(!g) return;
  if(empty) empty.style.display = APP_WIDGET_ORDER.length ? 'none' : '';
  g.innerHTML = APP_WIDGET_ORDER.map(function(id){
    var d = APP_WIDGETS[id];
    return '<button class="preset" type="button" data-aw="' + d.id + '"><span class="sz">' + d.w + '×' + d.h + '</span><span class="pi">' + escapeHtml(d.icon) + '</span><b>' + escapeHtml(d.name) + '</b><span>' + escapeHtml(d.appName) + (d.desc ? ' · ' + escapeHtml(d.desc) : '') + '</span></button>';
  }).join('');
  g.querySelectorAll('.preset').forEach(function(b){
    b.addEventListener('click', function(){
      var it = addAppWidget(b.getAttribute('data-aw'));
      if(it){ closePanel(); pulseItem(it); toast('已添加「' + it.name + '」（' + it.w + '×' + it.h + ' 格）到桌面，它来自软件「' + APP_WIDGETS[it.widgetId].appName + '」'); }
    });
  });
}
/* 宿主桥消息入口：只认本桌面小组件 iframe 发来的消息 */
window.addEventListener('message', function(e){
  var d = e.data;
  if(!d || d.__canghai !== 1) return;
  var owner = null;
  items.forEach(function(it){
    var f = it._frameEl || (it.el ? it.el.querySelector('iframe') : null);
    if(f && f.contentWindow === e.source) owner = it;
  });
  if(!owner) return;
  if(d.type === 'openApp'){
    var ok = ALL_APPS.some(function(a){ return a.id === d.appId; });
    if(ok) openApp(d.appId);
    else toast('小组件想打开的应用不存在：' + d.appId);
  } else if(d.type === 'toast'){
    toast(String(d.message || ''));
  } else if(d.type === 'setContextMenu'){
    owner.customMenu = Array.isArray(d.items) ? d.items : [];
  } else if(d.type === 'openModal'){
    if(typeof window.openCustomWidgetModal === 'function') {
      window.openCustomWidgetModal(d.title, d.html);
    }
  } else if(d.type === 'closeModal'){
    if(typeof window.closeCustomWidgetModal === 'function') {
      window.closeCustomWidgetModal();
    }
  }
});
buildAppWidgetPresets();

/* ================= 开发提示词（写给 AI 的规则） =================
   DEV_APP_PROMPT 是「开发整个软件」的提示词：软件是单个 HTML 文件，
   说明运行容器为 Android WebView、内置 Three.js 与 Canghai UI 库，并支持小组件。 */
function buildDevPrompt(){
  var ua = navigator.userAgent || '';
  var av = (ua.match(/Android\s+([0-9.]+)/i) || [])[1] || '14';
  var cv = (ua.match(/Chrome\/([0-9.]+)/i) || [])[1] || '128.0';
  return '【沧海系统 · HTML 软件与小组件开发指引】\n' +
    '你是一名资深前端工程师。请为「沧海系统」开发单文件 HTML 软件，并配套桌面小组件。严格按以下规范交付：\n\n' +
    '一、运行环境说明（重点）\n' +
    '1. 运行平台：真实手机驱动的 Android ' + av + ' 系统（基于 Chrome ' + cv + ' WebView 容器），在多屏/外接大屏桌面中运行。\n' +
    '2. 容器限制：运行于安卓原生 WebView 沙盒中，【非 Node.js 服务端环境】，严禁使用 require(\'fs\')、child_process 等服务端模块；完整支持标准 Web API、Canvas 2D/WebGL 硬件加速、localStorage、Web Audio 及 Pointer Lock 鼠标锁定。\n' +
    '3. 交付格式：【单文件完整 HTML】，CSS 全部内联于 <style>，JS 全部内联于 <script>，开箱即用，无需任何打包构建工具。\n' +
    '4. 交互体验：窗口自由缩放自适应、支持物理鼠标（包含滚轮、右键菜单、指针锁定）、键盘快捷键及手柄。\n\n' +
    '二、已内置的热门第三方库与 UI 库（离线直接可用，免联网加载）\n' +
    '1. 3D 三维引擎：已完整内置 Three.js (r186) 及其常用扩展（OrbitControls、GLTFLoader、FontLoader 等）。\n' +
    '   - 使用方式：直接使用全局变量 THREE，或写 import * as THREE from \'three\'。\n' +
    '2. 观海 UI 样式库（Canghai UI）：系统沙盒已为每个应用注入现代毛玻璃质感组件类名，开箱即用：\n' +
    '   - 卡片容器：.ch-card（自适应毛玻璃背景、精致描边与光晕阴影）\n' +
    '   - 按钮控件：.ch-btn（高质感半透明玻璃按钮）、.ch-btn-primary（海蓝强调主按钮）、.ch-btn:hover\n' +
    '   - 表单输入：.ch-input（暗黑质感输入框，自动适配高亮）\n' +
    '   - 弹性排版：.ch-row（横向对齐居中）、.ch-col（纵向排列）、.ch-badge（微型徽章标签）\n' +
    '   - 桌面主题变量：var(--cw-bg), var(--cw-ink), var(--cw-sub), var(--cw-line), var(--cw-accent)\n' +
    '3. 宿主系统桥（Canghai 对象自动注入）：\n' +
    '   - Canghai.toast("提示文字")：在桌面弹出平滑气泡通知；\n' +
    '   - Canghai.openApp("应用id")：呼起其他应用；\n' +
    '   - document.body.requestPointerLock()：物理鼠标锁定（适合 3D 第一人称漫游与游戏视角）。\n\n' +
    '三、配套桌面小组件开发（极简协议）\n' +
    '在 HTML 内任意位置插入以下 JSON 块，导入系统后桌面即可自动识别并上架小组件：\n' +
    '<script type="application/json" data-canghai-widget>\n' +
    '{\n' +
    '  "id": "my-app-widget",\n' +
    '  "appName": "软件名字",\n' +
    '  "name": "快捷小组件",\n' +
    '  "icon": "⚡",\n' +
    '  "desc": "桌面实时监控小组件",\n' +
    '  "w": 2, "h": 2,\n' +
    '  "html": "<div class=\'ch-card\' style=\'height:100%;box-sizing:border-box;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;\'><div style=\'font-size:15px;font-weight:700;color:var(--cw-ink);\'>实时状态</div><button data-canghai-action=\'openApp\' class=\'ch-btn ch-btn-primary\' style=\'padding:4px 14px;\'>进入应用</button></div>"\n' +
    '}\n' +
    '<\/script>\n' +
    '- 小组件设计尺寸按每格 96px 缩放（常用 2x2 或 3x1），根节点写 height:100%；\n' +
    '- 元素添加 data-canghai-action="openApp" 即可免代码实现点击打开所属应用。\n\n' +
    '四、交付要求\n' +
    '- 仅输出一个完整的单文件 HTML 代码（含 DOCTYPE、html、head、body），功能真实可用不留空占位符。\n\n' +
    '我的开发需求是：（在此处输入您想要开发的软件功能，例如：做一个带有 Three.js 宇宙星空的冥想白噪音工具，带有播放控制、时间记录，并配套一个 2x2 桌面小组件）';
}
var DEV_APP_PROMPT = buildDevPrompt();
var DEV_WIDGET_PROMPT =
'你是一名资深前端工程师。请为「沧海系统」开发桌面小组件，严格按下面的协议交付。这份提示词只讲小组件，不用开发整个软件。\n' +
'\n' +
'一、小组件是什么\n' +
'- 沧海系统是桌面式网页操作系统。小组件是放在桌面格子里的一个小画面，能显示信息、定时更新，还能让桌面打开软件或弹提示。\n' +
'- 小组件在沙盒 iframe 里运行：它属于哪个软件，软件窗口关着它也照样在桌面上运行；它不能直接访问宿主页面，只能通过宿主桥调用能力。\n' +
'\n' +
'二、注册方式（二选一）\n' +
'方式 A：把注册块写进所属软件的 HTML 里，宿主加载软件时自动扫描提取：\n' +
'<script type="application/json" data-canghai-widget>\n' +
'{ 这里放一个 JSON 对象，字段见第三节 }\n' +
'<\/script>\n' +
'方式 B：在软件运行时调用 Canghai.registerWidget({ 同样的对象 })。\n' +
'方式 C（独立小组件）：不挂软件时，只输出 html 画面片段（可含 style 和 script 标签），用户会把它粘贴到桌面「添加小组件 → 自定义 HTML 小组件」里，片段规则与第三节的 html 完全相同。\n' +
'\n' +
'三、注册对象字段\n' +
'- id：必填，全局唯一字符串，建议「软件 id-小组件名」，例如 "music-now"。\n' +
'- appId：必填，所属软件的 id；appName：必填，所属软件名。\n' +
'- name：必填，小组件名；icon：一个 emoji 或一小段内联 SVG；desc：一句话说明。\n' +
'- w、h：占桌面格数，宽高各 1 到 4 的整数，常用 2x2 或 3x1。\n' +
'- html：必填，画面 HTML 片段，只写片段，不要写 html、head、body 整页标签。\n' +
'- script：可选，小组件自己的 JS 代码（字符串）：可以用 setInterval 定时更新画面、做倒计时；可以用 document 操作小组件自己的画面；可以用宿主桥 Canghai（见第五节）。\n' +
'- refreshSeconds：可选，默认 0；大于 0 时宿主每隔这么多秒重跑一次小组件。\n' +
'\n' +
'四、画面规则\n' +
'- 每格设计尺寸 96px：内容按「宽 = w 乘 96、高 = h 乘 96」排版，再由宿主整体等比缩放贴进实际格子。按设计尺寸写 px，不要假设实际像素，根元素写 height:100%。\n' +
'- 用宿主 CSS 变量和系统小组件保持一个样子：var(--cw-bg) 毛玻璃底、var(--cw-ink) 主文字、var(--cw-sub) 次文字、var(--cw-line) 描边、var(--cw-chip) 衬底、var(--cw-accent) 海蓝、var(--cw-gold) 浅金；深浅外观自动换值。不要写死纯白底配纯白字。\n' +
'\n' +
'五、右键菜单与居中弹窗配置（重要交互规范）\n' +
'- 配置右键菜单：小组件可调用 Canghai.setContextMenu([{ id: "add", label: "添加待办…" }, { id: "edit", label: "编辑配置…" }]) 为自身挂载桌面右键专属菜单项。\n' +
'- 监听菜单点击：当用户右键点击该菜单项时，小组件通过监听宿主消息接收事件：window.addEventListener("message", function(e){ if(e.data.type === "contextMenuClick"){ console.log("点击了菜单项:", e.data.id); } });\n' +
'- 弹出居中窗口：当需要弹窗录入、添加或编辑时，弹窗内部由小组件自身绘制，小组件只需调用 Canghai.openModal({ title: "弹窗标题", html: "<div>你的表单HTML</div>" })，宿主桌面会自动将其在系统中央高保真毛玻璃弹出；小组件在完成操作后调用 Canghai.closeModal() 关闭。\n' +
'\n' +
'六、让小组件做事（宿主桥）\n' +
'- Canghai.openApp("软件id")：让桌面打开某个软件；Canghai.openSelf()：打开注册它的软件；Canghai.toast("文字")：让桌面弹一条提示。宿主已注入 Canghai，不用自己定义。\n' +
'- 不写 script 也能做事：元素加 data-canghai-action="openApp" data-app="软件id"，点击打开软件；加 data-canghai-action="toast" data-text="文字"，点击弹提示。\n' +
'\n' +
'七、最小例子\n' +
'<script type="application/json" data-canghai-widget>\n' +
'{"id":"demo-hello","appId":"demo","appName":"示例软件","name":"问候卡","icon":"👋","desc":"点一下打开示例软件","w":2,"h":2,"html":"<div style=\'height:100%;background:var(--cw-bg);color:var(--cw-ink);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:9px\'><div style=\'font-size:17px;font-weight:800\'>你好，沧海</div><button data-canghai-action=\'openApp\' data-app=\'demo\' style=\'border:1px solid var(--cw-line);background:var(--cw-chip);color:var(--cw-ink);border-radius:99px;padding:6px 14px;cursor:pointer\'>打开软件</button></div>"}\n' +
'<\/script>\n' +
'\n' +
'八、交付要求\n' +
'- 只输出代码：要挂到软件上就输出完整注册块（或 registerWidget 调用），独立小组件就输出 html 片段。不要拆成多个文件，不要留假按钮和占位符。\n' +
'\n' +
'我想要的小组件是：（在这里补一句，例如：做一个练琴倒计时小组件，25 分钟，时间到弹提示）';
var DEV_APP_DOC =
'开发软件提示词 · 使用说明\n' +
'1. 点这个窗口右上角的「复制提示词」，复制的就是下面「提示词开始」到「提示词结束」之间的全部文字。\n' +
'2. 把提示词粘贴给任意 AI，在提示词最后一行补上你想要的软件（那一行已经留好了位置）。\n' +
'3. AI 会按沧海系统的规则交回一个 HTML 文件：软件本体，加上按协议注册好的小组件，都在这一个文件里——提示词里已经写清了软件是 HTML、小组件怎么注册、画面怎么画、怎么让小组件在前台和后台做事。\n' +
'4. 把 AI 给的 HTML 文件发回来导入沧海系统后，软件里注册的小组件会出现在桌面「＋ 小组件」面板的「软件注册的小组件」区，点一下就能放到桌面。\n' +
'\n' +
'———— 提示词开始 ————\n' +
DEV_APP_PROMPT + '\n' +
'———— 提示词结束 ————';
var DEV_WIDGET_DOC =
'开发小组件提示词 · 使用说明\n' +
'1. 点这个窗口右上角的「复制提示词」，复制的就是下面「提示词开始」到「提示词结束」之间的全部文字。\n' +
'2. 粘贴给任意 AI，在提示词最后一行补上你想要的小组件。\n' +
'3. 这份提示词只讲小组件：怎么注册、画面怎么画、怎么用宿主桥让小组件打开软件或弹提示。AI 交回的代码，挂到软件里用注册块，独立使用就粘贴到「添加小组件 → 自定义 HTML 小组件」。\n' +
'\n' +
'———— 提示词开始 ————\n' +
DEV_WIDGET_PROMPT + '\n' +
'———— 提示词结束 ————';

/* ================= 提示词查看窗口（txt 文件 / 面板按钮共用） ================= */
function copyTextForAI(text, okMsg){
  function done(){ toast(okMsg); }
  function fallback(){
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;left:-9999px;top:0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); done(); }
    catch(e){ toast('自动复制没成，请在窗口里长按或全选文字手动复制'); }
    ta.remove();
  }
  if(navigator.clipboard && navigator.clipboard.writeText){
    navigator.clipboard.writeText(text).then(done, fallback);
  } else fallback();
}
function openDocWindow(key, title, grad, icon, docText, promptText){
  /* 展开/收回的原点：devdoc 对应桌面上的 txt 文件图标；小组件提示词窗口没有桌面图标，返回 null 走升起/沉下 */
  function docOriginEl(){
    if(key === 'devdoc'){
      var f = items.filter(function(x){ return x.type === 'file'; })[0];
      if(f && f.el) return f.el.querySelector('.appIcon') || f.el;
    }
    return null;
  }
  if(openWins[key]){
    var ex = openWins[key];
    ex._min = false; ex.style.display = 'flex';
    if(!winGrowFrom(ex, docOriginEl())){
      ex.classList.remove('rise'); void ex.offsetWidth; ex.classList.add('rise');
    }
    focusWin(ex);
    return;
  }
  var win = document.createElement('div');
  win.className = 'win focused';
  win._min = false;
  var w = Math.min(window.innerWidth - 20, 660), h = Math.min(window.innerHeight - 120, 560);
  win.style.width = w + 'px'; win.style.height = Math.max(320, h) + 'px';
  win.style.left = Math.max(8, (window.innerWidth - w) / 2) + 'px';
  win.style.top = Math.max(46, (window.innerHeight - h) / 2 - 16) + 'px';
  win.style.zIndex = ++zTop;
  win.innerHTML = '<div class="winbar"><button class="tl r" type="button" aria-label="关闭"></button><button class="tl y" type="button" aria-label="最小化"></button><button class="tl g" type="button" aria-label="缩放"></button><div class="ttl"><span style="display:inline-flex;width:18px;height:18px;border-radius:5px;background:' + grad + ';align-items:center;justify-content:center;overflow:hidden">' + icon + '</span>' + escapeHtml(title) + '</div><span style="width:97px"></span></div>'
    + '<div class="winbody"><div class="docView"><div class="docBar"><p>全文如下。点「复制提示词」复制的就是提示词部分（不含使用说明），粘贴给 AI 后，在最后一行补上你的需求即可。</p><button type="button" class="docCopy">复制提示词</button></div><pre class="docText"></pre></div></div>';
  win.querySelector('.docText').textContent = docText;
  winLayer.appendChild(win);
  openWins[key] = win;
  focusWin(win);
  if(!winGrowFrom(win, docOriginEl())) win.classList.add('rise');
  win.addEventListener('pointerdown', function(){ focusWin(win); });
  win.querySelector('.tl.r').addEventListener('click', function(){
    delete openWins[key];
    winShrinkTo(win, docOriginEl(), function(){ win.remove(); });
  });
  win.querySelector('.tl.y').addEventListener('click', function(){
    var tgt = null;
    if(key === 'devdoc'){ var f = items.filter(function(x){ return x.type === 'file'; })[0]; if(f) tgt = f.el.querySelector('.appIcon') || f.el; }
    minimizeWinTo(win, tgt, null);
    toast('「' + title + '」已最小化');
  });
  win.querySelector('.docCopy').addEventListener('click', function(){ copyTextForAI(promptText, '提示词已复制，粘贴给 AI 再补上你的需求'); });
  makeDraggable(win, win.querySelector('.winbar'));
}
function openDevDoc(){
  var ex = items.filter(function(x){ return x.type==='txt' && x.name==='开发软件提示词.txt'; })[0];
  if(ex){ openTxtWindow(ex); return; }
  var pr = (typeof buildDevPrompt === 'function') ? buildDevPrompt() : DEV_APP_PROMPT;
  openDocWindow('devdoc', '开发软件提示词.txt', 'linear-gradient(160deg,#fdfdff,#dbe7f3)', ICONS.devdoc, pr, pr);
}
function openDevWidgetDoc(){
  openDocWindow('devwidget', '开发小组件提示词', 'linear-gradient(160deg,#38bdf8,#0b4a7a)', ICONS.launchpad, DEV_WIDGET_DOC, DEV_WIDGET_PROMPT);
}
document.getElementById('devWidgetBtn').addEventListener('click', function(){ closePanel(); openDevWidgetDoc(); });

/* ================= 桌面文件：开发软件提示词.txt & 用户新建 txt 文件 ================= */
function renderFileCell(it){
  it.type = 'txt';
  if(!it.content) it.content = (typeof buildDevPrompt === 'function') ? buildDevPrompt() : DEV_APP_PROMPT;
  renderTxtCell(it);
}
function placeDevDoc(){
  var pr = (typeof buildDevPrompt === 'function') ? buildDevPrompt() : DEV_APP_PROMPT;
  var it = makeItem({type:'txt', name:'开发软件提示词.txt', content:pr, col:1, row:1, w:1, h:1, desk:0});
  renderTxtCell(it);
}
function renderTxtCell(it){
  it.el.innerHTML = '<div class="appCell fileCell"><div class="appIcon" style="background:linear-gradient(160deg,#fdfdff,#d7e6f5)">' + ICONS.devdoc + '</div><div class="appName">' + escapeHtml(it.name || '新建文本文档.txt') + '</div></div>';
  bindCell(it, it.el.firstChild, function(){ openTxtWindow(it); });
}
function newTxtFile(){
  var spot = findFree(1,1,1,1,curDesk);
  var names = items.filter(function(it){ return it.type==='txt'; }).length;
  var fname = '新建文本文档' + (names ? ' (' + (names + 1) + ')' : '') + '.txt';
  var it = makeItem({type:'txt', name:fname, content:'', col:spot.col, row:spot.row, w:1, h:1, desk:curDesk});
  renderTxtCell(it);
  pulseItem(it);
  deskSaveSoon();
  openTxtWindow(it);
  toast('已新建「' + fname + '」');
}
function openTxtWindow(it){
  var key = 'txt_' + it.id;
  function txtOriginEl(){
    if(it && it.el) return it.el.querySelector('.appIcon') || it.el;
    return null;
  }
  if(openWins[key]){
    var ex = openWins[key];
    ex._min = false; ex.style.display = 'flex';
    if(!winGrowFrom(ex, txtOriginEl())){
      ex.classList.remove('rise'); void ex.offsetWidth; ex.classList.add('rise');
    }
    focusWin(ex);
    return;
  }
  var win = document.createElement('div');
  win.className = 'win focused';
  win._min = false;
  var w = Math.min(window.innerWidth - 20, 620), h = Math.min(window.innerHeight - 120, 480);
  win.style.width = w + 'px'; win.style.height = Math.max(300, h) + 'px';
  win.style.left = Math.max(8, (window.innerWidth - w) / 2) + 'px';
  win.style.top = Math.max(46, (window.innerHeight - h) / 2 - 16) + 'px';
  win.style.zIndex = ++zTop;
  win.innerHTML = '<div class="winbar"><button class="tl r" type="button" aria-label="关闭"></button><button class="tl y" type="button" aria-label="最小化"></button><button class="tl g" type="button" aria-label="缩放"></button><div class="ttl"><span style="display:inline-flex;width:18px;height:18px;border-radius:5px;background:linear-gradient(160deg,#fdfdff,#dbe7f3);align-items:center;justify-content:center;overflow:hidden">' + ICONS.devdoc + '</span><span class="txtWinTtl">' + escapeHtml(it.name) + '</span></div><span style="width:97px"></span></div>'
    + '<div class="winbody"><div class="txtEditor"><div class="txtBar"><input type="text" class="txtNameInp" value="' + escapeHtml(it.name) + '" placeholder="文件名.txt"><span class="txtStat">自动保存</span><button type="button" class="txtCopyBtn">复制全文</button></div><textarea class="txtArea" placeholder="在此输入文本内容…"></textarea></div></div>';
  var ta = win.querySelector('.txtArea');
  var nameInp = win.querySelector('.txtNameInp');
  var statEl = win.querySelector('.txtStat');
  ta.value = it.content || '';
  ta.addEventListener('input', function(){
    it.content = ta.value;
    statEl.textContent = '已保存 · ' + ta.value.length + ' 字';
    deskSaveSoon();
  });
  nameInp.addEventListener('change', function(){
    var nv = (nameInp.value || '').trim() || '新建文本文档.txt';
    if(!/\.txt$/i.test(nv)) nv += '.txt';
    nameInp.value = nv;
    it.name = nv;
    var nm = it.el && it.el.querySelector('.appName');
    if(nm) nm.textContent = nv;
    var ttl = win.querySelector('.txtWinTtl');
    if(ttl) ttl.textContent = nv;
    deskSaveSoon();
  });
  win.querySelector('.txtCopyBtn').addEventListener('click', function(){
    copyTextForAI(ta.value, '文本内容已复制');
  });
  winLayer.appendChild(win);
  openWins[key] = win;
  focusWin(win);
  if(!winGrowFrom(win, txtOriginEl())) win.classList.add('rise');
  win.addEventListener('pointerdown', function(){ focusWin(win); });
  win.querySelector('.tl.r').addEventListener('click', function(){
    delete openWins[key];
    winShrinkTo(win, txtOriginEl(), function(){ win.remove(); });
  });
  win.querySelector('.tl.y').addEventListener('click', function(){
    minimizeWinTo(win, txtOriginEl(), null);
    toast('「' + it.name + '」已最小化');
  });
  win.querySelector('.tl.g').addEventListener('click', function(){ toggleMax(win); });
  makeDraggable(win, win.querySelector('.winbar'));
  setTimeout(function(){ ta.focus(); }, 60);
}
function openTxtEntryModal(folderIt, en){
  var fakeIt = {
    id: 'f_' + folderIt.id + '_' + (en.id || Date.now()),
    name: en.name || '新建文本文档.txt',
    content: en.content || '',
    el: folderIt.el
  };
  openTxtWindow(fakeIt);
  var key = 'txt_' + fakeIt.id;
  var win = openWins[key];
  if(win){
    var ta = win.querySelector('.txtArea');
    var nameInp = win.querySelector('.txtNameInp');
    if(ta) ta.addEventListener('input', function(){ en.content = ta.value; deskSaveSoon(); });
    if(nameInp) nameInp.addEventListener('change', function(){
      en.name = fakeIt.name;
      renderFolderCell(folderIt);
      var fwin = openWins['folder_' + folderIt.id];
      if(fwin && fwin._rerenderFolder) fwin._rerenderFolder();
      deskSaveSoon();
    });
  }
}

/* ================= 聚焦搜索 ================= */
var spotMask = document.getElementById('spotMask'), spotInput = document.getElementById('spotInput'), spotList = document.getElementById('spotList');
var spotResults = [], spotActive = 0;
function spotData(q){
  var res = [];
  res.push({icon:'<span class="sic" style="background:linear-gradient(160deg,#4a7aa8,#0a1f33)">'+ICONS.launchpad+'</span>', label:'启动台', sub:'全部应用 · 点一下打开', fn:function(){ openLaunchpad(); }});
  if(!q || '开发软件提示词'.indexOf(q)>=0) res.push({icon:'<span class="sic" style="background:linear-gradient(160deg,#fdfdff,#dbe7f3)">'+ICONS.devdoc+'</span>', label:'开发软件提示词.txt', sub:'桌面文件 · 复制提示词给 AI 开发沧海软件', fn:function(){ openDevDoc(); }});
  ALL_APPS.forEach(function(a){
    if(!q || a.name.indexOf(q)>=0) res.push({icon:'<span class="sic" style="background:'+a.grad+'">'+ICONS[a.id]+'</span>', label:a.name, sub:'应用', fn:function(){ openApp(a.id); }});
  });
  Object.keys(WIDGET_TYPES).forEach(function(k){
    var d = WIDGET_TYPES[k];
    if(!q || d.name.indexOf(q)>=0) res.push({icon:'<span class="sic" style="background:rgba(47,155,255,.28)">'+d.icon+'</span>', label:d.name+' 小组件', sub:'小组件 · 点一下添加到桌面', fn:function(){ var it = addWidget(k); pulseItem(it); }});
  });
  items.forEach(function(it){
    if(it.type!=='widget') return;
    var nm = it.name || '小组件';
    if(!q || nm.indexOf(q)>=0) res.push({icon:'<span class="sic" style="background:rgba(110,175,255,.25)">🧩</span>', label:nm, sub:'桌面上已有的小组件 · 点一下定位', fn:function(){ pulseItem(it); }});
  });
  items.forEach(function(it){
    if(it.type!=='folder') return;
    if(!q || it.name.indexOf(q)>=0) res.push({icon:'<span class="sic" style="background:rgba(255,201,60,.3)">📁</span>', label:it.name, sub:'文件夹', fn:function(){ openFolder(it); }});
  });
  return res;
}
function renderSpot(){
  var q = spotInput.value.trim();
  spotResults = spotData(q);
  spotActive = clamp(spotActive, 0, Math.max(0, spotResults.length-1));
  if(!spotResults.length){ spotList.innerHTML = '<div class="spotEmpty">没有找到「'+escapeHtml(q)+'」</div>'; return; }
  spotList.innerHTML = spotResults.map(function(r, i){
    return '<div class="spotItem'+(i===spotActive?' active':'')+'" data-i="'+i+'">'+r.icon+'<div><b>'+escapeHtml(r.label)+'</b><span>'+r.sub+'</span></div></div>';
  }).join('');
  spotList.querySelectorAll('.spotItem').forEach(function(n){
    n.addEventListener('click', function(){ activateSpot(parseInt(n.getAttribute('data-i'),10)); });
    n.addEventListener('pointermove', function(){ spotActive = parseInt(n.getAttribute('data-i'),10); renderSpotActive(); });
  });
}
function renderSpotActive(){
  spotList.querySelectorAll('.spotItem').forEach(function(n, i){ n.classList.toggle('active', i===spotActive); });
}
function activateSpot(i){
  var r = spotResults[i];
  if(!r) return;
  closeSpot();
  r.fn();
}
function openSpot(){
  spotMask.classList.add('open');
  spotInput.value = ''; spotActive = 0;
  renderSpot();
  setTimeout(function(){ spotInput.focus(); }, 30);
}
function closeSpot(){ spotMask.classList.remove('open'); }
spotInput.addEventListener('input', function(){ spotActive = 0; renderSpot(); });
spotInput.addEventListener('keydown', function(e){
  if(e.key==='ArrowDown'){ e.preventDefault(); spotActive = Math.min(spotResults.length-1, spotActive+1); renderSpotActive(); }
  else if(e.key==='ArrowUp'){ e.preventDefault(); spotActive = Math.max(0, spotActive-1); renderSpotActive(); }
  else if(e.key==='Enter'){ activateSpot(spotActive); }
  else if(e.key==='Escape'){ closeSpot(); }
});
spotMask.addEventListener('pointerdown', function(e){ if(e.target===spotMask) closeSpot(); });
var spotBtn = document.getElementById('spotBtn');
if(spotBtn) spotBtn.addEventListener('click', openSpot);

/* ================= 控制中心 / 日历面板 / 主题 / 锁屏 ================= */
var ccPanel = document.getElementById('ccPanel'), calPop = document.getElementById('calPop');
var ccBtn = document.getElementById('ccBtn');
if(ccBtn) ccBtn.addEventListener('click', function(){
  if(calPop) calPop.classList.remove('open');
  if(ccPanel) ccPanel.classList.toggle('open');
});
var clockBox = document.getElementById('clockBox');
if(clockBox) clockBox.addEventListener('click', function(){
  if(calPop.classList.contains('open')){ calPop.classList.remove('open'); return; }
  ccPanel.classList.remove('open');
  document.getElementById('calPopBody').innerHTML = calHTML();
  var now = new Date();
  document.getElementById('calPopFoot').textContent = '今天是 '+now.getFullYear()+' 年 '+(now.getMonth()+1)+' 月 '+now.getDate()+' 日，星期'+['日','一','二','三','四','五','六'][now.getDay()];
  calPop.classList.add('open');
});
var ccBright = document.getElementById('ccBright'), ccVol = document.getElementById('ccVol');
ccBright.addEventListener('input', function(){
  state.brightness = ccBright.value/100;
  document.getElementById('brightDiv').style.opacity = String(1 - state.brightness);
  document.getElementById('ccBrightVal').textContent = ccBright.value+'%';
});
ccVol.addEventListener('input', function(){
  state.volume = ccVol.value/100;
  document.getElementById('ccVolVal').textContent = ccVol.value+'%';
});
document.getElementById('ccDnd').addEventListener('click', function(){
  state.dnd = !state.dnd;
  this.classList.toggle('on', state.dnd);
  document.getElementById('dndBadge').style.display = state.dnd ? 'inline' : 'none';
  if(!state.dnd) toast('勿扰模式已关');
});
function setTheme(light){
  state.light = light;
  document.body.classList.toggle('light', light);
  var tb = document.getElementById('themeBtn');
  if(tb) tb.textContent = light ? '☀️' : '🌙';
  var cc = document.getElementById('ccDark');
  if(cc) cc.classList.toggle('on', !light);
  if(!window.__hasCustomWallpaper){
    setWallpaper(light ? 'wp-forest' : 'wp-dusk', false);
  }
  /* 自定义小组件的默认文字色跟着外观走，切外观时按新底色重绘一遍 */
  items.forEach(function(it){ if(it.type==='widget' && (it.wtype==='custom' || it.wtype==='app')) renderWidget(it); });
  refreshPreview();
}
document.getElementById('ccDark').addEventListener('click', function(){ setTheme(!state.light); toast(state.light?'已切换到浅色外观':'已切换到深色外观'); });
var tbEl = document.getElementById('themeBtn');
if(tbEl) tbEl.addEventListener('click', function(){ setTheme(!state.light); toast(state.light?'已切换到浅色外观':'已切换到深色外观'); });
document.getElementById('ccHide').addEventListener('click', function(){
  chSetDockAutoHide(!state.dockAutoHide);
  toast(state.dockAutoHide ? 'Dock 自动隐藏已开，鼠标贴边唤出' : 'Dock 自动隐藏已关');
});
document.getElementById('ccLock').addEventListener('click', function(){ ccPanel.classList.remove('open'); lockScreen(); });
var lockDeviceTimer = null, unlockAnimTimer = null;
function lockScreen(){
  closeMenu(); closePanel(); closeSpot();
  ccPanel.classList.remove('open'); calPop.classList.remove('open');
  applyUserProfile();
  var ls = document.getElementById('lockScreen');
  clearTimeout(unlockAnimTimer);
  ls.classList.remove('unlocking');
  ls.classList.add('show');
  tick();
  clearTimeout(lockDeviceTimer);
  lockDeviceTimer = setTimeout(function(){
    lockDeviceTimer = null;
    if(!ls.classList.contains('show') || ls.classList.contains('unlocking')) return;
    try{
      if(typeof Canghai !== 'undefined' && Canghai && Canghai.lockDevice){
        Canghai.lockDevice();
      }
    }catch(e){}
  }, 1000);
}
function unlockScreen(){
  clearTimeout(lockDeviceTimer);
  lockDeviceTimer = null;
  var ls = document.getElementById('lockScreen');
  if(!ls.classList.contains('show') || ls.classList.contains('unlocking')) return;
  ls.classList.add('unlocking');
  clearTimeout(unlockAnimTimer);
  unlockAnimTimer = setTimeout(function(){
    ls.classList.remove('show');
    ls.classList.remove('unlocking');
  }, 420);
}
window.onDeviceLockChanged = function(locked){
  if(locked === false){
    var ls = document.getElementById('lockScreen');
    if(ls && ls.classList.contains('show')) unlockScreen();
  }
};
var avBtn = document.getElementById('avatarBtn');
if(avBtn) avBtn.addEventListener('click', lockScreen);
document.getElementById('lockAvatar').addEventListener('click', function(e){ e.stopPropagation(); unlockScreen(); });
document.getElementById('lockScreen').addEventListener('click', unlockScreen);