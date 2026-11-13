/* ==========================================================================
 * 沧海 · 手机端数据适配层
 *
 * 拼在 沧海手机系统.html 的脚本末尾，跑在同一个作用域里（能直接用 state / APPS /
 * findDevice / renderDevices 这些）。原则：
 *   1. Native 的 CanghaiStore 是唯一数据源，页面不许自己存一份；
 *   2. 设计稿的函数一律不改写，行为靠「追加监听器」接管；
 *   3. 拿不到 Native（浏览器里直接打开预览）就退回空状态，不报错。
 * ========================================================================== */

/* ---------------- 小工具 ---------------- */
function chCall(fn) {
  var args = Array.prototype.slice.call(arguments, 1);
  try {
    if (!window.CanghaiPhone || typeof CanghaiPhone[fn] !== 'function') return null;
    return CanghaiPhone[fn].apply(CanghaiPhone, args);
  } catch (e) {
    return null;
  }
}
function chJson(s, dflt) {
  if (!s) return dflt;
  try { return JSON.parse(s) || dflt; } catch (e) { return dflt; }
}
/* 光标三件套的手机端镜像：唯一数据源在 CanghaiStore，这里只是显示用。
   大屏设置里改了 → 原生 push 到 onHostStatusSync → 这里更新 → 设备卡同步。 */
window.__chCursorSel = 'arrow';
window.__chCursorSize = 28;
window.__chCursorSpeed = 1.0;
window.chSyncCursorPref = function (key, v) {
  if (key === 'cursorSize') chCall('setCursorSize', Math.round(v));
  else if (key === 'speed') chCall('setCursorSpeed', v);
};
window.chSyncCursorSel = function (v) {
  var id = (v && v.indexOf('lib:') === 0) ? v.slice(4) : (v || 'arrow');
  chCall('selectCursor', id);
};
function chCursorSummary() {
  var el = document.getElementById('chCursorSummary');
  if (!el) return;
  var names = { arrow: '箭头', dot: '圆点', block: '方块' };
  var sel = window.__chCursorSel || 'arrow';
  var nm = names[sel] || (sel.indexOf('lib:') === 0 ? '自定义样式' : '箭头');
  el.textContent = '当前 ' + nm + ' · ' + window.__chCursorSize + ' px · '
    + (Math.round(window.__chCursorSpeed * 10) / 10).toFixed(1) + '×（与外屏同步）';
}
function chInitialOf(name) {
  return window.CHColor ? CHColor.initial(name) : String(name || '沧').charAt(0);
}
function chGradOf(name) {
  return window.CHColor ? CHColor.gradFor(name) : 'linear-gradient(160deg,#6fd3ff,#2f7fe0)';
}
/* 原生 homeType：0=原生 DeX 桌面，1=沧海桌面（字符串形式也兼容） */
function chDeskOf(v) {
  if (v === 1 || v === '1' || v === 'canghai' || v === true) return 'canghai';
  return 'native';
}
function chIsImgIcon(icon) {
  return window.CHColor ? CHColor.isImageIcon(icon) : /^\s*<(img|svg)/i.test(String(icon || ''));
}

/* ---------------- 数据映射 ---------------- */

/* 真机应用（LAUNCHER）→ 设计稿 app-row 要的结构 */
function chMapNativeApps(list) {
  return (list || []).map(function (a) {
    return {
      id: a.id,
      kind: 'native',
      name: a.name,
      comp: a.comp,
      pkg: a.pkg,
      sub: '已安装 · ' + (a.pkg || ''),
      color: chGradOf(a.name),
      ch: chInitialOf(a.name),
      icon: chIsImgIcon(a.icon) ? a.icon : ''
    };
  });
}

/* 沧海软件（HTML）→ 设计稿 installed 列表要的结构 */
function chMapHtmlApps(list) {
  return (list || []).map(function (a) {
    /* 原生存的 icon 是 SVG 文本、png 是渲染好的 data URL；
       tileHTML 要的是对象，这里归一化：优先 png（底色已经烤进去了）。 */
    var iconObj = '';
    if (a.png) iconObj = { type: 'img', src: a.png };
    else if (a.icon) iconObj = { type: 'img', src: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(a.icon) };
    return {
      id: a.id,
      name: a.name,
      icon: iconObj,
      iconSvg: a.icon || '',     /* 原始 SVG 文本，改名/换图标时往原生回写 */
      png: a.png || '',          /* 原生侧存的渲染好的图标 PNG（快捷方式用） */
      ori: a.ori || 'auto',      /* 横竖屏锁定：auto / portrait / landscape */
      html: '',                 /* 正文点「打开」时再向 Native 要，列表不背大文件 */
      source: '沧海软件',
      size: a.size || 0,
      fileCount: 0,
      entryKey: 'index.html',
      files: null,
      shortcut: false
    };
  });
}

/* 真机屏幕 → 设计稿 device 卡要的结构 */
function chMapDevices(list, mainId) {
  return (list || []).map(function (d, i) {
    var dev = makeDevice(d.kind === 'moon' ? 'moon' : 'wired');
    dev.id = 'disp' + d.id;          /* 设计稿拿 id 当 DOM 键，必须是字符串 */
    dev.displayId = d.id;            /* 投屏真正要用的 DisplayId */
    /* 名字要短且有意义：原来「Moonlight 串流设备 (Device)」在小屏上会被截断、撑出横向滚动 */
    dev.name = d.kind === 'moon' ? 'Moonlight' : '外接屏';
    dev.res = d.res || dev.res;
    dev.hz = d.fps || dev.hz;
    /* 原生会告诉这块屏实际跑的是哪套桌面，没说就按全局默认 */
    dev.desk = (d.desk === 'canghai') ? 'canghai'
      : (d.desk === 'native' ? 'native' : state.defaultDesk);
    /* 主屏以原生路由到的屏为准；没同步到就退回第一块 */
    dev.main = (mainId > 0) ? (d.id === mainId) : (i === 0);
    dev.active = !!d.active;
    dev.app = null;
    /* 光标只有一套：所有设备卡显示/修改的都是同一份全局设置 */
    dev.cursor = window.__chCursorSel || 'arrow';
    dev.cursorSize = window.__chCursorSize || 28;
    dev.speed = window.__chCursorSpeed || 1.0;
    return dev;
  });
}

/* ---------------- 状态灌入 ---------------- */
function chApplyPerms(p) {
  p = p || {};
  /* 沧海真正需要的只有两项：
       root / shizuku —— 建屏、切桌面。两者任一即可，UI 上都给，不再只逼用户开 Root
       overlay        —— 悬浮 Dock / 光标浮层，没它悬浮层起不来
     无障碍服务早已整体去掉；读应用列表、本地网络发现、电池优化都不必单独授权，
     一律视为已具备，老逻辑里 guard(["root","a11y"]) 这类地方也不会再拦人。 */
  var CH_KEEP = ['root', 'shizuku', 'overlay', 'shortcut'];
  for (var i = PERM_DEFS.length - 1; i >= 0; i--) {
    if (CH_KEEP.indexOf(PERM_DEFS[i].key) < 0) PERM_DEFS.splice(i, 1);
  }
  /* 特权通道：Root 和 Shizuku 是二选一，UI 上都要能看到、都能点。
     设计稿的 PERM_DEFS 只有 root，这里补一条 Shizuku 并把两者说清楚。 */
  if (PERM_MAP.root) PERM_MAP.root.desc = 'Root 或 Shizuku，任一即可投屏建屏';
  if (!PERM_MAP.shizuku) {
    var sk = {
      key: 'shizuku',
      name: 'Shizuku 授权',
      desc: '没有 Root 就用它 · 两者任一即可',
      req: false,
      soft: true,
      ico: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2.5 4 6v6c0 5 3.4 8 8 9.5 4.6-1.5 8-4.5 8-9.5V6l-8-3.5Z"/><path d="M12 8.5v4"/><circle cx="12" cy="15.5" r=".9" fill="currentColor"/></svg>'
    };
    var rootIdx = PERM_DEFS.indexOf(PERM_MAP.root);
    PERM_DEFS.splice(rootIdx < 0 ? 0 : rootIdx + 1, 0, sk);
    PERM_MAP.shizuku = sk;
  }
  /* 桌面快捷方式是「可选」：不给也能用沧海，只是加不了桌面入口。
     所以它不进必需项计数，只在权限卡片里以黄色「可选」呈现，
     真正拦人的是点「创建快捷方式」那一下。 */
  if (!PERM_MAP.shortcut) {
    var sc = {
      key: 'shortcut',
      name: '桌面快捷方式',
      desc: '把应用放到手机桌面直接点开 · 不给也能用',
      req: false,
      soft: true,
      ico: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="8" height="8" rx="2"/><rect x="13" y="13" width="8" height="8" rx="2"/><path d="M11 7h2a4 4 0 0 1 4 4v2"/></svg>'
    };
    PERM_DEFS.push(sc);
    PERM_MAP.shortcut = sc;
  }
  state.root = !!p.root;
  state.everRooted = state.everRooted || state.root;
  state.perms.a11y = true;
  state.perms.apps = true;
  state.perms.network = true;
  state.perms.battery = true;
  state.perms.overlay = !!p.overlay;
  state.perms.shizuku = !!p.shizuku;
  state.perms.shortcut = !!p.shortcut;
}

/* Root 与 Shizuku 二选一：任一授权后两条一起视为已满足，
   免得用户看到「Shizuku 已授权」旁边还挂着「Root 待授权」。 */
window.isGranted = function (key) {
  if (key === 'root' || key === 'shizuku') return state.root || !!state.perms.shizuku;
  return !!state.perms[key];
};

/* 覆盖设计稿的权限卡片。
   Root 和 Shizuku 是「或」的关系（任一即可），所以合成一张卡片：
   文字一起说明，给两个按钮分别直接调起 Shizuku / Root 的授权 —— 不用用户自己去软件里点。
   可选项不参与「还差 N 项」的计数，不然一个非必须的授权会一直挂着黄条拦人。 */
window.renderPermBanner = function () {
  var banner = $("#permBanner"), list = $("#permList");
  if (!banner || !list) return;
  /* 状态还没从原生同步完之前一律隐藏：开软件一瞬间不该闪出一排红色告警。
     只有检测确实完成、且真的缺授权时才显示。 */
  if (!window.__chStatusReady) { banner.classList.add("hidden"); return; }
  var missing = PERM_DEFS.filter(function (p) { return !window.isGranted(p.key); });
  /* root / shizuku 两个键只算「一项」，且由 merged 行专门渲染 */
  var privMissing = missing.some(function (p) { return p.key === 'root' || p.key === 'shizuku'; });
  var rest = missing.filter(function (p) { return p.key !== 'root' && p.key !== 'shizuku'; });
  var hard = rest.filter(function (p) { return !p.soft; });
  var soft = rest.filter(function (p) { return !!p.soft; });
  if (privMissing) hard.push(PERM_MAP.root || { key: 'root' });
  banner.classList.toggle("hidden", missing.length === 0);
  banner.classList.toggle("collapsed", permCollapsed && missing.length > 0);
  var head = $("#permBannerHead");
  if (head) head.setAttribute("aria-expanded", permCollapsed ? "false" : "true");
  var title = $("#permBannerTitle");
  if (title) {
    title.textContent = hard.length ? ("还差 " + hard.length + " 项授权，点一条去授权")
      : (soft.length ? ("还有 " + soft.length + " 项可选授权") : "");
  }
  /* Root + Shizuku 合并行：或的关系，点按钮直接调起对应授权弹窗 */
  var mergedRow = function () {
    return '<div class="perm-item perm-priv">' +
      '<span class="p-ico">' + (PERM_MAP.shizuku ? PERM_MAP.shizuku.ico : '') + '</span>' +
      '<span class="p-body"><span class="p-name">特权通道<em>必需</em></span>' +
      '<span class="p-desc">Root 或 Shizuku 二选一即可 · 点按钮直接调起授权</span></span>' +
      '<span class="p-go p-go-dual">' +
        '<button type="button" class="perm-grant-btn' + (grantingKeys.root ? " granting" : "") + '" data-perm-grant="root">Root 授权</button>' +
        '<button type="button" class="perm-grant-btn' + (grantingKeys.shizuku ? " granting" : "") + '" data-perm-grant="shizuku">Shizuku 授权</button>' +
      '</span>' +
      '</div>';
  };
  var row = function (p, granting) {
    return '<button type="button" class="perm-item' + (granting ? " granting" : "") + '" data-perm-grant="' + p.key + '">' +
      '<span class="p-ico">' + p.ico + '</span>' +
      '<span class="p-body"><span class="p-name">' + p.name + (p.soft ? '<em class="soft">可选</em>' : (p.req ? "<em>必需</em>" : "")) + '</span>' +
      '<span class="p-desc">' + p.desc + '</span></span>' +
      '<span class="p-go">' + (granting ? "授权中…" : '去授权 <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="m9 6 6 6-6 6"/></svg>') + '</span>' +
      '</button>';
  };
  list.innerHTML = (privMissing ? mergedRow() : "")
    + hard.filter(function (p) { return p.key !== 'root'; }).map(function (p) { return row(p, grantingKeys[p.key]); }).join("")
    + soft.map(function (p) { return row(p, grantingKeys[p.key]); }).join("");
  var wire = $("#btnSimWire");
  if (wire) wire.classList.toggle("is-locked", !state.root);
};

/* 「创建桌面快捷方式」：设计稿那版只改内存标记并谎报成功，
   真正放图标这件事在原生侧，没权限要回来说清楚。
   两个入口的 p 结构不一样：
     沧海软件（HTML）— p.app.id 是 CanghaiStore 里的应用 id
     手机应用（APK）— p.app 是 null，只能按名字回 APPS 里找回包名 */
function chDoShortcut() {
  if (!pendingShortcut) return;
  var p = pendingShortcut;
  var r = null;
  if (p.app && p.app.id) {
    r = chJson(chCall('createShortcut', p.app.id, p.name || ''), {});
  } else {
    var found = null;
    for (var i = 0; i < APPS.length; i++) {
      if (APPS[i].name === p.name && APPS[i].pkg) { found = APPS[i]; break; }
    }
    if (!found) {
      toast("这个应用已经在桌面上了，不用再加");
      return;
    }
    r = chJson(chCall('createAppShortcut', found.pkg, p.name || ''), {});
  }
  if (r && r.ok) {
    pendingShortcut = null;
    $("#shortcutModal").classList.remove("show");
    if (p.app) { p.app.shortcut = true; renderInstalled(); }
    toast("「" + p.name + "」正在添加到手机桌面，系统还会问一次");
    return;
  }
  if (r && r.reason === 'perm') {
    /* 可选权限：明确告知、给出路子，但不强行打断 —— 用户取消就停在原地 */
    if (window.confirm && confirm("没有「创建桌面快捷方式」的权限。\n去系统设置里开一下吗？\n（不给也能用沧海，只是加不了桌面入口）")) {
      chCall('openShortcutSettings');
      toast("在设置里找到「权限 → 桌面快捷方式」打开，回来再点一次");
    }
    return;
  }
  toast(r && r.msg ? r.msg : "创建失败，稍后再试");
}

/* 用捕获阶段拦下设计稿自己的 click 处理（它会谎报成功），换成真调用。 */
document.addEventListener('click', function (e) {
  var t = e.target;
  var btn = t && t.closest ? t.closest('#scConfirm') : null;
  if (!btn) return;
  e.stopImmediatePropagation();
  e.preventDefault();
  chDoShortcut();
}, true);

function chPhoneBoot() {
  /* 系统栏高度由原生经 URL 参数带过来（沉浸式下内容铺到状态栏/导航栏下面，
     页面按这两个值留白，否则标题被状态栏盖住、底部按钮被三大金刚键挤掉）。 */
  try {
    var q = location.search || '';
    var dpr = window.devicePixelRatio || 1;
    var mt = /[?&]sysTop=(\d+)/.exec(q), mb = /[?&]sysBottom=(\d+)/.exec(q);
    var ml = /[?&]sysLeft=(\d+)/.exec(q), mr = /[?&]sysRight=(\d+)/.exec(q);
    var root = document.documentElement;
    root.style.setProperty('--sys-top', (mt ? Math.round(Number(mt[1]) / dpr) : 0) + 'px');
    root.style.setProperty('--sys-bottom', (mb ? Math.round(Number(mb[1]) / dpr) : 0) + 'px');
    /* 打孔屏左右安全区：背景铺满，但标题和「黑屏触控」按钮不进挖孔区域。
       直接写 inline padding，不用 calc()+var()，老 WebView 72 也不会整条声明失效。 */
    var sl = ml ? Math.round(Number(ml[1]) / dpr) : 0;
    var sr = mr ? Math.round(Number(mr[1]) / dpr) : 0;
    root.style.setProperty('--sys-left', sl + 'px');
    root.style.setProperty('--sys-right', sr + 'px');
    var brand = document.querySelector('.app-brand');
    if (brand && (sl || sr)) {
      brand.style.paddingLeft = (22 + sl) + 'px';
      brand.style.paddingRight = (22 + sr) + 'px';
    }
  } catch (e) {}

  var st = chJson(chCall('getState'), {});
  var host = chJson(chCall('getHostStatus'), {});
  var perms = chJson(chCall('getPerms'), {});

  /* 1. 应用与软件 */
  APPS = chMapNativeApps(st.apps);
  state.installed = chMapHtmlApps(st.htmlApps);

  /* 2. 鼠标样式库：和外屏共用同一份，两端永远不会对不上 */
  state.cursorLib = (st.cursorLib || []).filter(function (x) { return !x.builtin; })
    .map(function (x) {
      return {
        id: x.id,
        name: x.name || '自定义指针',
        svg: x.svg || '',
        hotX: (x.hx == null ? 50 : x.hx),
        hotY: (x.hy == null ? 50 : x.hy)
      };
    });
  window.__chCurIds = state.cursorLib.map(function (x) { return x.id; });
  /* 开机就把光标三件套读回来，手机端设备卡与外屏设置显示同一份 */
  var cc = st.cursorCurrent || 'arrow';
  window.__chCursorSel = (cc === 'arrow' || cc === 'dot' || cc === 'block') ? cc : ('lib:' + cc);
  window.__chCursorSize = st.cursorSize || 28;
  window.__chCursorSpeed = st.cursorSpeed || 1.0;
  chCursorSummary();

  /* 3. 权限（只有 Root 是真的要） */
  state.defaultDesk = chDeskOf(host.homeType);
  chApplyPerms(perms);
  if (!perms.root && host.isRoot) state.root = true;
  /* 检测已落地：从这里开始才允许显示「发现问题」类横幅 */
  window.__chStatusReady = true;

  /* 4. 屏幕 */
  state.devices = chMapDevices(host.devices);
  document.querySelectorAll('#segDefaultDesk button').forEach(function (b) {
    b.classList.toggle('sel', b.getAttribute('data-v') === state.defaultDesk);
  });

  /* 5. 出首屏 */
  viewGuide.classList.add('hidden');
  viewMain.classList.remove('hidden');
  tabbar.classList.add('show');
  renderDevices();
  renderApps();
  updatePill();
  renderPermBanner();
  renderInstalled();
  if (state.devices.length) mascotSay('小星看到 ' + state.devices.length + ' 块屏幕啦', 2200);

  chRefreshIp();
  chBindBlackTouch();
}

/* ---------------- 顶部：真实 IP + 黑屏触控 ---------------- */
var __chLocalIp = '';

/* 顶部那行「IP地址：xxx」要用真机真实地址，设计稿里写死的是 192.168.1.23。 */
function chRefreshIp() {
  try {
    var host = chJson(chCall('getHostStatus'), {});
    var ip = host && host.ip ? String(host.ip) : '';
    if (!ip) return;
    __chLocalIp = ip;
    var btn = document.getElementById('btnCopyIp');
    if (btn) btn.textContent = 'IP地址：' + ip;
  } catch (e) { }
}

/* 复制按钮：设计稿里复制的是写死的假地址，这里接管成真地址。 */
document.addEventListener('click', function (e) {
  var btn = e.target && e.target.closest ? e.target.closest('#btnCopyIp') : null;
  if (!btn) return;
  e.stopImmediatePropagation();
  e.preventDefault();
  var t = __chLocalIp || chJson(chCall('getHostStatus'), {}).ip || '';
  if (!t) { toast('还没拿到本机 IP'); return; }
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(t).catch(function () { });
    } else {
      var ta = document.createElement('textarea');
      ta.value = t; ta.setAttribute('readonly', '');
      ta.style.cssText = 'position:fixed;left:-9999px;top:0';
      document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); } catch (x) { }
      document.body.removeChild(ta);
    }
    toast('已复制本机地址 ' + t);
  } catch (err) { toast('复制失败'); }
}, true);

/* 「黑屏触控」按钮：整屏变黑，手机变成当前激活窗口的触控板。
   目标可以是外接屏、沧海桌面虚拟屏，也可以是 Moonlight 串流虚拟屏；
   Native 返回 moon / wired / ok（已进入）或 off（一块可控制的屏都没有）。
   四指轻点退出；单指移动光标、单击左键、双指轻按右键。 */
function chBindBlackTouch() {
  var btn = document.getElementById('btnBlackTouch');
  if (!btn || btn.__chBound) return;
  btn.__chBound = true;
  btn.addEventListener('click', function () {
    var r = chCall('enterBlackTouchpad');
    /* 没连屏也照样进黑屏：先进来等人接屏，设备一上线原生会自动接管 */
    if (r === 'waiting') {
      toast('黑屏触控已开启 · 等待屏幕接入后自动接管 · 四指轻点退出');
      return;
    }
    if (r === 'off') { toast('黑屏触控暂时没能开启'); return; }
    var nm = (r === 'moon') ? 'Moonlight 串流画面' : '当前外接屏幕';
    toast('黑屏触控已开启 · 正在控制' + nm + ' · 四指轻点退出');
  });
}

/* ---------------- Native 主动推送（插拔屏 / Root 变化） ---------------- */
/* 设备列表的签名：内容没变就不重绘。Native 的 syncToPhoneWeb 在很多事件后都会推一次，
   全量重绘会把展开的设备面板卷回顶部，看起来就像页面自己刷新了。 */
var __chLastDevicesSig = '';
function chDevicesSig(list) {
  try { return JSON.stringify((list || []).map(function (d) {
    return [d.id, d.name, d.kind, d.res, d.fps, d.active, d.desk];
  })); } catch (e) { return String(list && list.length); }
}
window.onHostStatusSync = function (json) {
  try {
    if (!json) return;
    if (json.devices) {
      var sig = chDevicesSig(json.devices);
      if (sig !== __chLastDevicesSig) {
        __chLastDevicesSig = sig;
        state.devices = chMapDevices(json.devices, json.mainDisplayId);
        renderDevices();
        renderApps();
      }
    }
    if (json.isRoot !== undefined) {
      state.root = !!json.isRoot;
      state.everRooted = state.everRooted || state.root;
      renderPermBanner();
      updatePill();
    }
    if (json.homeType !== undefined) {
      state.defaultDesk = chDeskOf(json.homeType);
      document.querySelectorAll('#segDefaultDesk button').forEach(function (b) {
        b.classList.toggle('sel', b.getAttribute('data-v') === state.defaultDesk);
      });
    }
    /* 外屏设置里改了光标 → 原生推送过来，手机端设备卡立刻跟着换 */
    if (json.cursorCurrent !== undefined || json.cursorSize !== undefined || json.cursorSpeed !== undefined) {
      var sel = window.__chCursorSel, sz = window.__chCursorSize, sp = window.__chCursorSpeed;
      if (json.cursorCurrent !== undefined) {
        var c = json.cursorCurrent || 'arrow';
        sel = (c === 'arrow' || c === 'dot' || c === 'block') ? c : ('lib:' + c);
      }
      if (json.cursorSize !== undefined) sz = json.cursorSize || 28;
      if (json.cursorSpeed !== undefined) sp = json.cursorSpeed || 1.0;
      var changed = (sel !== window.__chCursorSel || sz !== window.__chCursorSize || sp !== window.__chCursorSpeed);
      window.__chCursorSel = sel; window.__chCursorSize = sz; window.__chCursorSpeed = sp;
      chCursorSummary();
      if (changed && window.__chStatusReady) {
        try {
          state.devices.forEach(function (d) {
            d.cursor = sel; d.cursorSize = sz; d.speed = sp;
          });
          renderDevices();
        } catch (e) { }
      }
    }
  } catch (e) { }
};

/* --------------------------------------------------------------------------
 * 双端联动：外屏装 / 卸 / 改名 / 换图标后，Native 主动调这个钩子。
 * 不刷新页面（会丢掉已展开的弹窗），只从唯一数据源重读并局部重绘。
 * -------------------------------------------------------------------------- */
window.__chReloadState = function () {
  try {
    var st = chJson(chCall('getState'), {});
    if (!st) return;
    APPS = chMapNativeApps(st.apps);
    state.installed = chMapHtmlApps(st.htmlApps);
    state.cursorLib = (st.cursorLib || []).filter(function (x) { return !x.builtin; })
      .map(function (x) {
        return { id: x.id, name: x.name || '自定义指针', svg: x.svg || '',
                 hotX: (x.hx == null ? 50 : x.hx), hotY: (x.hy == null ? 50 : x.hy) };
      });
    window.__chCurIds = state.cursorLib.map(function (x) { return x.id; });
    var cc = st.cursorCurrent || 'arrow';
    window.__chCursorSel = (cc === 'arrow' || cc === 'dot' || cc === 'block') ? cc : ('lib:' + cc);
    window.__chCursorSize = st.cursorSize || 28;
    window.__chCursorSpeed = st.cursorSpeed || 1.0;
    chCursorSummary();
    /* 展开的设备面板别被重绘卷回顶部：应用列表和已装软件列表才是要刷新的 */
    renderInstalled();
    renderApps();
    updatePill();
  } catch (e) { }
};

/* ==========================================================================
 * 行为接管：全部靠追加监听器，设计稿的原有逻辑照跑
 * ========================================================================== */

/* ---- 1. 原生应用 / 沧海软件的投屏、卸载、设置 ----
   应用页已改成「图标 + 名字」网格，点击统一弹应用操作单（#appSheet），
   投屏的 launchAppOnDisplay / launchHtmlOnDisplay、卸载的 removeHtml
   都在页面内联脚本里落到 Native；这里不再监听旧列表容器。 */

/* ---- 3. 打开沧海软件：正文点开时再向 Native 要 ---- */
(function () {
  var base = openRunner;
  openRunner = function (a) {
    if (a && !a.html) {
      var text = chCall('getHtmlApp', a.id);
      if (text) a.html = text;
    }
    return base(a);
  };
})();


/* ---- 源码工程包（AI 生成的 esbuild / three 工程）在手机端就地打包 ----
   这类 zip 里 src/index.html 只有 __CSS__ / __JS__ 两个占位符，
   真正的样式在 src/style.css，逻辑在 src 下的 ES modules，还会 import three。
   以前把占位符骨架直接存进原生，装完打开就是白屏 —— 能玩的那次用的是
   电脑上先构建好的单文件。现在全流程都在手机里完成：
     1) CSS 内联进 style 标签
     2) 入口 JS 改写成 module script，交给页面自带的打包器打成 IIFE
     3) 裸模块（three / three/addons/...）映射到 APK 内置的 CH_MODULES
     4) head 里留一个运行时标记，由 HtmlAppActivity 在 native 侧替换
   换任何同类 zip 都不再需要电脑 npm。 */
var CH_RUNTIME_TAG = '/*__CH_RUNTIME__*/';

function chBuildSourcePackage(pkg) {
  try {
    var files = pkg && pkg.files, html = pkg && pkg.html;
    if (!files || !html) return null;
    if (html.indexOf('/*__CSS__*/') < 0 && html.indexOf('/*__JS__*/') < 0) return null;
    var keys = Object.keys(files), dec = new TextDecoder('utf-8'), enc = new TextEncoder();
    var entryKey = pkg.entryKey || 'index.html';
    var entryDir = dirOf(entryKey);
    var i, k, m;

    var buildKey = null, jsEntry = null, cssKey = null;
    for (i = 0; i < keys.length; i++) {
      if (/(^|\/)build\.(mjs|js)$/i.test(keys[i])) { buildKey = keys[i]; break; }
    }
    if (buildKey) {
      var bs = dec.decode(files[buildKey]);
      m = /entryPoints\s*:\s*\[\s*['"]([^'"]+)['"]/.exec(bs);
      if (m) {
        var ec = [normPath(m[1]), normPath(dirOf(buildKey) + '/' + m[1]), normPath(entryDir + '/' + m[1])];
        for (i = 0; i < ec.length; i++) if (files[ec[i]]) { jsEntry = ec[i]; break; }
      }
      var cm = /readFileSync\(\s*['"]([^'"]+\.css)['"]/.exec(bs);
      if (cm) {
        var cc = [normPath(cm[1]), normPath(dirOf(buildKey) + '/' + cm[1]), normPath(entryDir + '/' + cm[1])];
        for (i = 0; i < cc.length; i++) if (files[cc[i]]) { cssKey = cc[i]; break; }
      }
    }
    if (!jsEntry) {
      var same = keys.filter(function (q) { return /\.m?js$/i.test(q) && dirOf(q) === entryDir; });
      var pref = same.filter(function (q) { return /^(main|index|app)\.m?js$/i.test(q.split('/').pop()); });
      jsEntry = (pref[0] || same[0] || null);
    }
    if (!jsEntry) {
      for (i = 0; i < keys.length; i++) {
        if (/\.m?js$/i.test(keys[i]) && /^(main|index|app)\.m?js$/i.test(keys[i].split('/').pop())) {
          jsEntry = keys[i]; break;
        }
      }
    }
    if (!jsEntry) return null;
    if (!cssKey) {
      var cssCands = [normPath(entryDir + '/style.css'), normPath(entryDir + '/main.css'), 'style.css', 'src/style.css'];
      for (i = 0; i < cssCands.length; i++) if (files[cssCands[i]]) { cssKey = cssCands[i]; break; }
      if (!cssKey) {
        var allCss = keys.filter(function (q) { return /\.css$/i.test(q); });
        if (allCss.length) cssKey = allCss[0];
      }
    }
    var css = cssKey ? dec.decode(files[cssKey]) : '';

    /* 裸模块 -> 内置运行时。为每个裸模块造一个包内虚拟文件，
       页面自带的小打包器就能像解析本地模块一样解析它。 */
    var specs = {}, jsKeys = keys.filter(function (q) { return /\.m?js$/i.test(q); });
    jsKeys.forEach(function (q) {
      var src = dec.decode(files[q]);
      var re = /\b(?:from|import)\s*\(?\s*['"]([^'"]+)['"]/g, mm;
      while ((mm = re.exec(src))) {
        var sp = mm[1];
        if (!sp || sp.charAt(0) === '.' || sp.charAt(0) === '/') continue;
        if (/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(sp)) continue;
        specs[sp] = 1;
      }
    });
    var extMap = {}, n = 0;
    Object.keys(specs).forEach(function (sp) {
      var vp = '__ch_ext__/m' + (n++) + '.js';
      extMap[sp] = vp;
      files[vp] = enc.encode('Object.assign(__exports, (window.CH_MODULES||{})[' + JSON.stringify(sp) + '] || {});');
    });
    jsKeys.forEach(function (q) {
      var src = dec.decode(files[q]);
      var out = src.replace(/(\b(?:from|import)\s*\(?\s*['"])([^'"]+)(['"])/g, function (all, a, sp, b) {
        return extMap[sp] ? (a + '/' + extMap[sp] + b) : all;
      });
      if (out !== src) files[q] = enc.encode(out);
    });

    var tpl = html
      .replace(/\/\*__CSS__\*\//g, function () { return css.replace(/<\/style/gi, '<\\/style'); })
      .replace(/<script([^>]*)>\s*\/\*__JS__\*\/\s*<\/script>/i,
        '<script type="module" src="./' + jsEntry.split('/').pop() + '"></script>');
    files[entryKey] = enc.encode(tpl);

    var doc = buildPackageDocument({ files: files, entryKey: entryKey });
    if (!doc) return null;
    if (/<head[^>]*>/i.test(doc)) {
      doc = doc.replace(/<head[^>]*>/i, function (h) { return h + '<script>' + CH_RUNTIME_TAG + '</script>'; });
    } else {
      doc = '<script>' + CH_RUNTIME_TAG + '</script>' + doc;
    }
    return doc;
  } catch (e) {
    try { console.log('[ch] 源码包打包失败: ' + e); } catch (e2) { }
    return null;
  }
}

/* ---- 4. 装完一个新软件，立刻进 Native 库（外屏马上就能看到） ---- */
(function () {
  var base = finishInstall;
  finishInstall = function (picked, pkg) {
    var iconSvg = '';
    try {
      if (picked && picked.icon) {
        var ic = picked.icon;
        if (ic.type === 'glyph') {
          iconSvg = ic.s || '';
        } else if (ic.type === 'img') {
          var m = /<svg[\s\S]*<\/svg>/i.exec(String(ic.src || ''));
          if (m) iconSvg = m[0];
          else if (/^data:image\/svg\+xml/i.test(String(ic.src || ''))) {
            try { iconSvg = decodeURIComponent(ic.src.split(',').slice(1).join(',')); } catch (e2) { }
          }
        } else {
          var m2 = /<svg[\s\S]*<\/svg>/i.exec(String(ic));
          if (m2) iconSvg = m2[0];
        }
      }
    } catch (e) { }

    /* 整包先就地打包成单文件 HTML：源码工程（占位符 + ESM）走手机端打包器，
       普通多文件包走页面自带的 buildPackageDocument，单文件直接用原文。
       以前只存入口 HTML，装完打开就是白屏 —— 这里是根因。 */
    var full = (pkg && pkg.html) || '';
    try {
      full = chBuildSourcePackage(pkg)
        || (pkg && pkg.files && pkg.entryKey
          ? buildPackageDocument({ files: pkg.files, entryKey: pkg.entryKey }) : null)
        || full;
      if (full && full.indexOf(CH_RUNTIME_TAG) >= 0) {
        toast('检测到源码工程包，已在手机里打包好，不用电脑构建');
      }
    } catch (e) {
      try { console.log('[ch] 整包打包失败: ' + e); } catch (e2) { }
    }

    var id = null;
    try {
      id = chCall('installHtml', picked && picked.name, full, iconSvg, '');
    } catch (e) { }

    var before = state.installed.length;
    base(picked, pkg);
    if (id && state.installed.length > before) {
      /* 用 Native 的 id 覆盖本地随机 id，卸载/投屏才对得上 */
      state.installed[0].id = id;
      if (full) state.installed[0].html = full;
      renderInstalled();
    }

    /* 渲染图标 PNG 存进原生：桌面快捷方式直接解码就用，
       不用原生去啃 SVG（WebView 72 也没有现成的光栅化）。 */
    try {
      var newApp = (id && state.installed[0] && state.installed[0].id === id) ? state.installed[0] : null;
      chIconToPng(picked && picked.icon, picked && picked.name, function (png) {
        if (png && id) {
          chCall('setHtmlIcon', id, iconSvg || '', png);
          if (newApp) { newApp.png = png; newApp.iconSvg = iconSvg; }
        }
      });
    } catch (e) { }
  };
})();

/* ---- 5. 鼠标样式库任何改动都回写 Native（两端共用一份） ---- */
function chSyncCursors() {
  var lib = state.cursorLib || [];
  var keep = {};
  lib.forEach(function (it) {
    if (!it || !it.id) return;
    keep[it.id] = 1;
    chCall('saveCursor', it.id, it.name || '', it.svg || '',
      Math.round(it.hotX == null ? 50 : it.hotX), Math.round(it.hotY == null ? 50 : it.hotY));
  });
  (window.__chCurIds || []).forEach(function (id) {
    if (!keep[id]) chCall('removeCursor', id);
  });
  window.__chCurIds = Object.keys(keep);
}
(function () {
  var base = renderCursorManage;
  renderCursorManage = function () {
    var r = base.apply(null, arguments);
    chSyncCursors();
    return r;
  };
})();

/* ---- 6. 授权横幅：现在只剩 Root，点了就去要 Root ---- */
(function () {
  var banner = document.getElementById('permBanner') || document.querySelector('.perm-banner');
  if (!banner) return;
  banner.addEventListener('click', function (e) {
    var b = e.target.closest('[data-perm-grant], [data-grant], [data-perm]');
    if (!b) return;
    var key = b.getAttribute('data-perm-grant') || b.getAttribute('data-grant') || b.getAttribute('data-perm');
    if (key === 'root' || key === 'a11y') chCall('requestRoot');
    if (key === 'shizuku') chCall('requestShizuku');
    if (key === 'overlay') chCall('openOverlay');
    if (key === 'shortcut') {
      chCall('openShortcutSettings');
      toast("在设置里打开「桌面快捷方式」权限，回来点一次创建就行");
    }
  }, true);
})();

/* ---- 7. 两处「桌面」切换各归各位 ----
   A. 设置页的「新屏幕默认桌面」：只是偏好，绝不能顺手把当前外屏桌面改掉
      （用户实测：点一下外屏桌面立刻变了）。只存下来，等新屏接入时用。
   B. 设备卡里的「原生桌面 / 沧海桌面」：以前只改本地假状态，啥也没发生，
      现在真正调原生 setDesktopType。用 capture 抢在页面自己的 guard 之前，
      免得没 Root（只用 Shizuku）时点不动。 */
(function () {
  var seg = document.getElementById('segDefaultDesk');
  if (seg) {
    seg.addEventListener('click', function () {
      try { localStorage.setItem('ch_default_desk', state.defaultDesk); } catch (e) { }
    });
  }
  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    var b = e.target.closest('.seg[data-seg="desk"] button');
    if (!b) return;
    var card = b.closest('.devcard'); if (!card) return;
    var d = findDevice(card.getAttribute('data-id')); if (!d) return;
    var v = b.getAttribute('data-v');
    e.stopPropagation(); e.preventDefault();
    var host = chJson(chCall('getHostStatus'), {});
    var id = d.id || host.activeDisplayId || 0;
    if (id) chCall('setDesktopType', id, v === 'canghai' ? 1 : 0);
    d.desk = v;
    renderDevices();
    toast('这块屏已切到' + (v === 'canghai' ? '沧海桌面' : '原生桌面'));
  }, true);
})();

/* ---- 7b. dock：定宽自适应 + 只要弹窗出现就向下隐藏 ----
   规则（用户钦定）：Dock 宽度不超过屏幕宽的 60%；放得下自然宽就按自然宽，
   放不下就固定到 60%，按钮在 Dock 里平分（.dock-fit）。
   不再走 transform scale 缩放——定宽后 Dock 永远放得下，缩放只会让字发虚。 */
(function () {
  var OVERLAY = '.dev-float.show, .modal.show, .runner.show, .blackout.show, [role="dialog"].show';
  var lastT = null, lastHidden = null, lastW = null, lastFit = null, lastNat = null, lastVw = null, queued = false;
  var raf = window.requestAnimationFrame || function (f) { setTimeout(f, 16); };
  function apply() {
    queued = false;
    var bar = document.querySelector('.tabbar');
    if (!bar) return;
    var vw = window.innerWidth || 360;
    var hidden = !!document.querySelector(OVERLAY);
    /* 宽屏左侧栏模式（≥1024px 媒体查询把 Dock 变成竖排侧栏）：
       不做胶囊化，把行内样式撤干净。 */
    if ((getComputedStyle(bar).flexDirection || 'row') === 'column') {
      if (lastT !== null || lastW !== null) {
        lastT = null; lastW = null; lastFit = null; lastNat = null; lastVw = null;
        bar.style.width = '';
        bar.style.transform = '';
      }
      if (lastHidden !== hidden) {
        lastHidden = hidden;
        bar.style.opacity = hidden ? '0' : '1';
        bar.style.pointerEvents = hidden ? 'none' : '';
        bar.classList.toggle('dock-hidden', hidden);
      }
      return;
    }
    /* 量自然宽：vw 没变就直接信上一轮的值，避免每次 apply 都改写样式
       触发 MutationObserver 再触发自己的死循环。 */
    var nat;
    if (lastNat != null && lastVw === vw) {
      nat = lastNat;
    } else {
      if (lastFit) bar.classList.remove('dock-fit');
      bar.style.width = 'max-content';
      nat = bar.offsetWidth || 260;
      lastVw = vw;
    }
    lastNat = nat;
    var target = Math.min(nat, Math.round(vw * 0.60));
    var fit = target < nat;
    /* 只在真的变了才写 DOM：无条件赋值会自己触发自己，形成无限循环。 */
    if (lastW !== target) { lastW = target; bar.style.width = target + 'px'; }
    if (lastFit !== fit) { lastFit = fit; bar.classList.toggle('dock-fit', fit); }
    var h = bar.offsetHeight || 60;
    var off = hidden ? Math.round(h + 36) : 0;
    var t = 'translateX(-50%) translateY(' + off + 'px)';
    if (lastT !== t) {
      lastT = t;
      bar.style.transformOrigin = 'center bottom';
      bar.style.transform = t;
    }
    if (lastHidden !== hidden) {
      lastHidden = hidden;
      bar.style.opacity = hidden ? '0' : '1';
      bar.style.pointerEvents = hidden ? 'none' : '';
      bar.classList.toggle('dock-hidden', hidden);
    }
  }
  function sync() {
    if (queued) return;
    queued = true;
    raf(apply);
  }
  window.addEventListener('resize', sync);
  window.addEventListener('orientationchange', sync);
  if (window.MutationObserver) {
    var mo = new MutationObserver(sync);
    mo.observe(document.body, { attributes: true, subtree: true, attributeFilter: ['class', 'style'] });
  } else {
    setInterval(sync, 250);
  }
  window.chSyncDock = sync;
  sync();
})();

/* ---- 8. renderDevices 包一层：全量重绘后把面板滚动位置还回去 ----
   设备设置面板是长列表，Native 推状态触发的重绘不能把人卷回顶部。 */
(function () {
  var base = renderDevices;
  renderDevices = function () {
    var sheet = document.querySelector('.float-sheet');
    var st = sheet ? sheet.scrollTop : 0;
    var r = base.apply(null, arguments);
    var s2 = document.querySelector('.float-sheet');
    if (s2 && st > 0) {
      if (s2.scrollTop !== st) s2.scrollTop = st;
    }
    return r;
  };
})();

/* ---- 9. 返回键：弹层优先吃掉 ----
   Native 在 onBackPressed 时先调这里；返回 true 表示这一下被页面消费了。
   优先级从高到低：应用运行器 > 设备设置面板 > 各类弹窗（取 z 最上面的可见层）。 */
window.chOnBack = function () {
  /* 页面里已经有一个统一入口（同一个 <script> 作用域），交给它一次关掉最上层弹层：
     屏幕配置页、运行器、黑屏、各 .modal 都走这里，返回键不会漏关。 */
  if (typeof window.chCloseTopLayer === 'function') {
    /* 只有真的关掉一层才返回 true；返回 false 时继续走下面的 DOM 兜底，
       不能因为入口里一次重绘异常就把这一下返回键直接判给「退出应用」。 */
    try { if (window.chCloseTopLayer()) return true; } catch (e) { }
  }
  /* 兜底：直接看 DOM 里还剩什么可见浮层，不依赖跨脚本的 state/renderDevices。
     配置页里切过桌面之后原生会回推状态重绘，这一步保证返回键依然能收起它。 */
  try {
    var cf = document.getElementById('caoFull');
    if (cf && cf.classList.contains('show')) {
      if (window.CaoChar && CaoChar.closeFull) CaoChar.closeFull();
      cf.classList.remove('show');
      return true;
    }
    var devFloat = document.querySelector('.dev-float.show');
    if (devFloat) {
      var back = document.getElementById('devFloatBackdrop');
      if (back) back.click();
      devFloat.classList.remove('show');
      return true;
    }
    var modal = document.querySelector('.modal.show');
    if (modal) { modal.classList.remove('show'); return true; }
    var runner = document.querySelector('.runner.show');
    if (runner) { runner.classList.remove('show'); return true; }
  } catch (e) { }
  return false;
};

/* ---- 10. 手机上打开沧海软件：开独立窗口（可多开），不再塞进页面内的运行器 ----
   窗口 = HtmlAppActivity 一个独立 Task：全屏沉浸、分辨率就是设备的、
   返回键传入、旋转会通知应用 —— 和桌面快捷方式点开的表现完全一致。 */
(function () {
  var base = openRunner;
  openRunner = function (a) {
    if (a && a.id && /^h\d+/.test(String(a.id))) {
      var r = chCall('openHtmlAppWindow', a.id);
      if (r === 'gone') { toast('这个应用已经被移除了'); return; }
      if (r === 'ok') {
        toast('已在独立窗口打开「' + a.name + '」· 再按一次返回键退出应用');
        return;
      }
      /* r === 'fail' 或浏览器预览：退回页面内运行器兜底 */
    }
    /* 兜底：完整文档已经装在 a.html 里了，别再用 files 重新拼一遍；
       有运行时标记就把内置 three 运行时拼进去。 */
    if (a && a.html) {
      var savedHtml = a.html, savedFiles = a.files, patched = false;
      try {
        if (savedHtml.indexOf(CH_RUNTIME_TAG) >= 0) {
          var rt = chCall('readSharedAsset', 'canghai/shared/three_runtime.js');
          /* 必须用函数形式替换：几 MB 的运行时里含 $& / $' 这类字符，
             字符串形式会被 String.replace 当成替换模式解释，把脚本改坏。 */
          var runtimeJs = String(rt || '').replace(/<\/script/gi, '<\\/script');
          a.html = savedHtml.replace(CH_RUNTIME_TAG, function () { return runtimeJs; });
          patched = true;
        }
        if (a.files) { a.files = null; patched = true; }
        if (patched) return base(a);
      } finally {
        if (patched) { a.html = savedHtml; a.files = savedFiles; }
      }
    }
    return base(a);
  };
})();

/* ---- 11. 图标 → 128×128 PNG data URL（原生建快捷方式直接解码就用） ----
   SVG / 图片 data URL / 渐变字母贴块都能画；画不出来就回 null，原生退回默认图标。 */
function chIconToPng(icon, name, cb) {
  try {
    var cv = document.createElement('canvas');
    cv.width = cv.height = 128;
    var ctx = cv.getContext('2d');
    var done = function () {
      try { cb(cv.toDataURL('image/png')); } catch (e2) { cb(null); }
    };
    var fail = function () {
      /* 兜底贴块：首字母 + 渐变 */
      try {
        var lg = ctx.createLinearGradient(0, 0, 128, 128);
        lg.addColorStop(0, '#6fd3ff'); lg.addColorStop(1, '#2f7fe0');
        var g = chGradOf(name);
        var m = /linear-gradient\((.+)\)/.exec(g);
        if (m) {
          var cols = m[1].match(/rgb\([^)]+\)|#[0-9a-f]{3,8}/ig) || [];
          if (cols.length >= 2) { lg = ctx.createLinearGradient(0, 0, 128, 128); lg.addColorStop(0, cols[0]); lg.addColorStop(1, cols[1]); }
        }
        ctx.fillStyle = lg; ctx.fillRect(0, 0, 128, 128);
        ctx.fillStyle = '#fff';
        ctx.font = 'bold 64px sans-serif';
        ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.fillText(chInitialOf(name), 64, 70);
      } catch (e3) { }
      done();
    };
    if (!icon) { fail(); return; }
    /* 设计稿的图标选择对象：{type:'glyph', c:底色, s:svg} / {type:'img', src:...} */
    var src = null, svg = null, bg = null;
    if (typeof icon === 'object' && icon.type) {
      if (icon.type === 'glyph') { bg = icon.c; svg = icon.s || ''; }
      else if (icon.type === 'img') src = icon.src || '';
    } else if (/^\s*<svg/i.test(String(icon))) {
      svg = String(icon);
    } else {
      var im0 = /src="([^"]+)"/.exec(String(icon));
      if (im0) src = im0[1];
    }
    if (bg) { ctx.fillStyle = bg; ctx.fillRect(0, 0, 128, 128); }
    if (src && /^data:image\/(png|jpeg|jpg|gif|webp)/i.test(src)) {
      var pic = new Image();
      pic.onload = function () {
        try { ctx.drawImage(pic, 0, 0, 128, 128); done(); } catch (e4) { fail(); }
      };
      pic.onerror = fail;
      pic.src = src;
      return;
    }
    /* SVG：svg 代码或 data:image/svg+xml */
    if (!svg && src && /^data:image\/svg\+xml/i.test(src)) {
      try { svg = decodeURIComponent(src.split(',').slice(1).join(',')); } catch (e5) { }
    }
    if (svg) {
      /* SVG 里的 currentColor 落不到独立图像上，补一个默认色再画 */
      if (/currentColor/i.test(svg) && !/<svg[^>]*\scolor=/i.test(svg)) {
        svg = svg.replace(/<svg/i, '<svg color="#ffffff"');
      }
      var simg = new Image();
      simg.onload = function () {
        try { ctx.drawImage(simg, 0, 0, 128, 128); done(); } catch (e6) { fail(); }
      };
      simg.onerror = fail;
      simg.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
      return;
    }
    fail();
  } catch (e) {
    cb(null);
  }
}

/* ---- 12. 沧海软件设置：改名 / 换图标 / 锁定横竖屏 ---- */
(function () {
  var modal = document.getElementById('appSetModal');
  if (!modal) return;
  var curApp = null;
  var choices = [], sel = 0, oriVal = 'auto';

  function renderGrid() {
    var g = document.getElementById('setAppIconGrid');
    if (!g) return;
    g.innerHTML = choices.map(function (ch, i) {
      return '<button type="button" class="icon-choice' + (i === sel ? " sel" : "") + '" data-setich="' + i + '"' +
        (ch.type === 'glyph' ? ' style="background:' + ch.c + '">' + ch.s + '</button>'
                             : '><img src="' + esc(ch.src) + '" alt="" /></button>');
    }).join('');
  }
  function renderOri() {
    document.querySelectorAll('#setAppOri button').forEach(function (b) {
      b.classList.toggle('sel', b.getAttribute('data-v') === oriVal);
    });
  }
  function iconToSvg(ch) {
    if (!ch) return '';
    if (ch.type === 'glyph') return ch.s || '';
    if (ch.type === 'img' && /^data:image\/svg\+xml/i.test(ch.src || '')) {
      try { return decodeURIComponent(ch.src.split(',').slice(1).join(',')); } catch (e) { return ''; }
    }
    return '';
  }

  window.chOpenAppSettings = function (id) {
    var a = null;
    (state.installed || []).forEach(function (x) { if (x.id === id) a = x; });
    if (!a) return;
    curApp = a;
    sel = 0;
    choices = [];
    /* 当前图标放第一个：优先 png（底色已烤进去），否则用原始 SVG 文本 */
    if (a.png) choices.push({ type: 'img', src: a.png });
    else if (a.iconSvg) choices.push({ type: 'img', src: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(a.iconSvg) });
    else if (a.icon) choices.push({ type: 'img', src: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(String(a.icon)) });
    GLYPHS.forEach(function (g) { choices.push({ type: 'glyph', c: g.c, s: g.s }); });
    sel = 0;
    document.getElementById('setAppName').value = a.name || '';
    document.getElementById('setAppName').classList.remove('err');
    var fi = document.getElementById('setAppFileInput'); if (fi) fi.value = '';
    oriVal = a.ori || 'auto';
    renderGrid();
    renderOri();
    modal.classList.add('show');
  };

  modal.addEventListener('click', function (e) {
    var b = e.target.closest('[data-setich]');
    if (b) { sel = Number(b.getAttribute('data-setich')); renderGrid(); return; }
    var ob = e.target.closest('#setAppOri button');
    if (ob) { oriVal = ob.getAttribute('data-v'); renderOri(); }
  });
  var fileInput = document.getElementById('setAppFileInput');
  var pickBtn = document.getElementById('setAppPickFile');
  if (pickBtn) pickBtn.addEventListener('click', function () { fileInput.value = ''; fileInput.click(); });
  if (fileInput) fileInput.addEventListener('change', function () {
    var f = fileInput.files && fileInput.files[0];
    if (!f) return;
    if (f.type && f.type.indexOf('image/') !== 0) { toast('选的不是图片文件，换一张图当图标'); return; }
    var fr = new FileReader();
    fr.onload = function () {
      choices.push({ type: 'img', src: String(fr.result) });
      sel = choices.length - 1;
      renderGrid();
      toast('已换成你选的图片');
    };
    fr.onerror = function () { toast('这张图片读不出来，换一张试试'); };
    fr.readAsDataURL(f);
  });
  $$('[data-close-appset]').forEach(function (el) {
    el.addEventListener('click', function () { modal.classList.remove('show'); });
  });
  document.getElementById('appSetCancel').addEventListener('click', function () { modal.classList.remove('show'); });
  document.getElementById('appSetSave').addEventListener('click', function () {
    if (!curApp) { modal.classList.remove('show'); return; }
    var id = curApp.id;
    var a = curApp; curApp = null;
    var changed = false;

    /* 1. 名字 */
    var nameInput = document.getElementById('setAppName');
    var name = nameInput.value.trim();
    if (!name) { nameInput.classList.add('err'); nameInput.focus(); toast('名字不能为空'); curApp = a; return; }
    if (name !== a.name) {
      var nn = chCall('renameHtml', id, name);
      if (nn) { a.name = nn; changed = true; }
      else toast('改名没成功，稍后再试');
    }

    /* 2. 图标（只在真的换过才写：第一个是当前图标，后面才是候选） */
    if (sel > 0) {
      var ch = choices[sel];
      var svg = iconToSvg(ch);
      if (ch && ch.type === 'glyph' && !svg) svg = ch.s;
      chIconToPng(ch, a.name, function (png) {
        if (chCall('setHtmlIcon', id, svg, png || '')) {
          a.iconSvg = svg;
          a.png = png || a.png;
          a.icon = a.png ? { type: 'img', src: a.png }
            : { type: 'img', src: 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg) };
          changed = true;
        }
        finishSet();
      });
      return;
    }
    finishSet();

    /* 3. 方向锁 + 收尾 */
    function finishSet() {
      if (oriVal !== (a.ori || 'auto')) {
        if (chCall('setHtmlOrientation', id, oriVal)) { a.ori = oriVal; changed = true; }
        else toast('方向锁定没保存成功');
      }
      renderInstalled();
      modal.classList.remove('show');
      if (changed) toast('「' + a.name + '」的设置已保存');
    }
  });
})();

/* ---------------- 启动 ----------------
   适配层现在是独立脚本文件（在页面主脚本之后加载），
   所以 chPhoneBoot 必须由这里调用：主脚本里的函数声明不会跨脚本提升。 */
(function () {
  function boot() {
    try {
      if (typeof chPhoneBoot === 'function') chPhoneBoot();
      else console.log('[ch] chPhoneBoot 未定义，适配层可能加载失败');
    } catch (e) {
      console.log('[ch] 启动失败: ' + (e && e.message));
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
