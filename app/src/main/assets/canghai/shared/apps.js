/* ==========================================================================
 * 沧海 · 内置软件与图标（唯一实现）
 *
 * 外屏桌面的 Dock、独立悬浮层的 Dock、启动台，全部从这里取，
 * 不许任何一处再手写一份内置软件表或图标 SVG，否则两边会长得不一样。
 *
 *   CHApps.BUILTIN  沧海自带软件（设置 / 输入监视 / 便签）
 *   CHApps.ICONS    Dock 与启动台要用的固定图标
 * ========================================================================== */
(function (w) {
  'use strict';

  var ICONS = {
    settings: '<svg viewBox="0 0 48 48"><path fill="#e8e8ee" stroke="#9a9aa5" stroke-width="1" d="M24 5l3.2 4.6 5.4-1.5 1 5.5 5.4 1.8-2.3 5.1 2.3 5.1-5.4 1.8-1 5.5-5.4-1.5L24 36l-3.2-4.6-5.4 1.5-1-5.5L9 25.6l2.3-5.1L9 15.4l5.4-1.8 1-5.5 5.4 1.5z" transform="translate(0 3) scale(.94)"/><circle cx="24" cy="24" r="6.4" fill="#b9b9c4" stroke="#8a8a96"/></svg>',
    sticky: '<svg viewBox="0 0 48 48"><path d="M11 7h26a1 1 0 0 1 1 1v25.5L29.5 42H11a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z" fill="#ffef9d"/><path d="M38 33.5 29.5 42v-6.8c0-.9.7-1.7 1.7-1.7z" fill="#e8cf6a"/><path d="M16 24h16M16 29.5h11" stroke="#c9ae4a" stroke-width="2.2" stroke-linecap="round"/><path d="M23.2 13.5h1.6l-.6 6.4h-.4z" fill="#9aa7b0"/><circle cx="24" cy="10.5" r="5.6" fill="#e2483d"/><ellipse cx="22.2" cy="8.6" rx="2" ry="1.4" fill="rgba(255,255,255,.55)"/></svg>',
    launchpad: '<svg viewBox="0 0 48 48"><g fill="#ffffff"><rect x="7" y="7" width="9.5" height="9.5" rx="2.6"/><rect x="19.2" y="7" width="9.5" height="9.5" rx="2.6" opacity=".82"/><rect x="31.5" y="7" width="9.5" height="9.5" rx="2.6" opacity=".64"/><rect x="7" y="19.2" width="9.5" height="9.5" rx="2.6" opacity=".82"/><rect x="19.2" y="19.2" width="9.5" height="9.5" rx="2.6"/><rect x="31.5" y="19.2" width="9.5" height="9.5" rx="2.6" opacity=".82"/><rect x="7" y="31.5" width="9.5" height="9.5" rx="2.6" opacity=".64"/><rect x="19.2" y="31.5" width="9.5" height="9.5" rx="2.6" opacity=".82"/><rect x="31.5" y="31.5" width="9.5" height="9.5" rx="2.6"/></g></svg>',
    /* 输入监视没有专门图标，走「名字哈希渐变 + 首字」，跟桌面页保持完全一致 */
    inputtrace: ''
  };

  var BUILTIN = [
    { id: 'settings', kind: 'system', name: '设置', grad: 'linear-gradient(160deg,#e8eef4,#5a6b7d)', icon: ICONS.settings, type: 'builtin', desc: '系统设置与多屏配置' },
    { id: 'inputtrace', kind: 'system', name: '调试外设', grad: 'linear-gradient(160deg,#7fe3ff,#1a6fd0)', icon: '', type: 'builtin', desc: '外设输入状态与按键监视' },
    { id: 'sticky', kind: 'system', name: '便签', grad: 'linear-gradient(160deg,#ffe97a,#f5b921)', icon: ICONS.sticky, type: 'builtin', desc: '随手记便签与备忘录' }
  ];

  /* 清单图标去底：icon.svg 第一层是一块满幅深色底（<rect> 盖满画布），
     直接渲然就成了「渐变底 + 深色方框 + 符号」三明沙；去掉这块底，
     只留符号画在渐变底上，跟设置 / 便签 / 调试外设一样单层。
     只去“首个、实心、盖满 ≥80% 画布”的 rect，符号本身的框线（fill="none"）不动。 */
  function stripIconBg(svg) {
    if (typeof svg !== 'string') return svg;
    var i = svg.search(/<rect/i);
    if (i < 0) return svg;
    var j = svg.indexOf('>', i);
    if (j < 0) return svg;
    var tag = svg.slice(i, j + 1);
    var attrs = {};
    tag.replace(/([a-zA-Z-]+)="([^"]*)"/g, function (_, k, v) { attrs[k.toLowerCase()] = v; return ''; });
    var w = parseFloat(attrs.width), h = parseFloat(attrs.height);
    if (!w || !h || !attrs.fill || attrs.fill.toLowerCase() === 'none') return svg;
    var vbi = svg.indexOf('viewBox="');
    if (vbi < 0) return svg;
    var vbe = svg.indexOf('"', vbi + 9);
    if (vbe < 0) return svg;
    var parts = svg.slice(vbi + 9, vbe).split(' ').filter(function (s) { return s !== ''; });
    if (parts.length < 4) return svg;
    if (w < parseFloat(parts[2]) * 0.8 || h < parseFloat(parts[3]) * 0.8) return svg;
    return svg.slice(0, i) + svg.slice(j + 1);
  }
  /* 动态注册内置 HTML 软件与游戏列表，后续扩展或加载新游戏直接调用 registerBuiltinApp */
  function registerBuiltinApp(app) {
    if (!app || !app.id) return;
    if (app.icon) app.icon = stripIconBg(app.icon);
    for (var i = 0; i < BUILTIN.length; i++) {
      if (BUILTIN[i].id === app.id) {
        BUILTIN[i] = Object.assign(BUILTIN[i], app);
        return;
      }
    }
    BUILTIN.push(app);
  }

  /* 输入监视的首字图标：和桌面页 instLetterIcon 同一套算法（渐变底 + 首字） */
  function letterIcon(name, grad) {
    var t = '';
    try {
      t = w.CHColor ? CHColor.escapeHtml(CHColor.initial(name)) : String(name || '').charAt(0);
    } catch (e) {
      t = String(name || '').charAt(0);
    }
    return '<span style="font-size:20px;font-weight:800;color:#fff;line-height:1;text-shadow:0 1px 2px rgba(0,0,0,.28)">' + t + '</span>';
  }

  w.CHApps = {
    ICONS: ICONS,
    BUILTIN: BUILTIN,
    letterIcon: letterIcon,
    registerBuiltinApp: registerBuiltinApp
  };
})(window);
