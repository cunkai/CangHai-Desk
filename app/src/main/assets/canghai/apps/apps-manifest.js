/* ==========================================================================
 * 沧海 · 内置应用清单（自动生成，别手改）
 * 生成：python3 tools/gen-apps-manifest.py（扫描 canghai/apps/，每个子文件夹一个应用）
 * 桌面启动时经 <script> 加载，按这里注册进 CHApps.BUILTIN；
 * Dock / 启动台 / 桌面直接取用，不用再手写注册。
 * 加新应用：把应用文件夹（index.html + app.json + icon.svg）拖进 canghai/apps/
 *   → 重新跑一遍生成脚本（或由安卓层启动时扫描同一批文件夹生成同一份清单覆盖）。
 * 桥（Canghai.getState().htmlApps）里再报一遍这些 id 时，桌面去重，清单优先。
 * 生成日期：2026-11-13，共 13 个应用（仓海条目 2026-11-14 手加，格式同生成器）
 * ========================================================================== */
(function (w) {
  'use strict';
  if (!w.CHApps || !w.CHApps.registerBuiltinApp) return;
  var APPS = [
  {
    "id": "2048",
    "kind": "html",
    "name": "2048",
    "installed": true,
    "url": "../apps/2048/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\"><rect width=\"64\" height=\"64\" rx=\"14\" fill=\"#0a1628\"/><text x=\"32\" y=\"40\" font-family=\"sans-serif\" font-size=\"20\" font-weight=\"700\" fill=\"#ecd28f\" text-anchor=\"middle\">2048</text></svg>",
    "type": "builtin",
    "desc": "2048"
  },
  {
    "id": "jump",
    "kind": "html",
    "name": "跳一跳",
    "installed": true,
    "url": "../apps/jump/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 128 128\"><rect width=\"128\" height=\"128\" rx=\"24\" fill=\"#0b1e33\"/><rect x=\"28\" y=\"100\" width=\"72\" height=\"10\" rx=\"3\" fill=\"#1a5a8a\" stroke=\"#c9a85c\" stroke-width=\"1.5\"/><rect x=\"30\" y=\"24\" width=\"6\" height=\"78\" rx=\"3\" fill=\"#c9a85c\"/><circle cx=\"33\" cy=\"20\" r=\"6\" fill=\"#ecd28f\"/><path d=\"M36 28h60v32H36z\" fill=\"#262548\" stroke=\"#c9a85c\" stroke-width=\"2\"/><circle cx=\"66\" cy=\"44\" r=\"11\" fill=\"#ecd28f\"/><circle cx=\"71\" cy=\"42\" r=\"9\" fill=\"#262548\"/></svg>",
    "type": "builtin",
    "desc": "跳一跳"
  },
  {
    "id": "file-manager",
    "kind": "html",
    "name": "我的文件",
    "installed": true,
    "url": "../apps/file-manager/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\"><path d=\"M8 20a5 5 0 0 1 5-5h11.4c1.6 0 3.1.7 4 1.9L32 21h19a5 5 0 0 1 5 5v2H8z\" fill=\"#b18e46\"/><path d=\"M8 27h48v19a5 5 0 0 1-5 5H13a5 5 0 0 1-5-5z\" fill=\"#ecd28f\"/><path d=\"M8 27h48v4.5H8z\" fill=\"#f4e3b2\"/><path d=\"M8 27h48v19a5 5 0 0 1-5 5H13a5 5 0 0 1-5-5z\" fill=\"none\" stroke=\"#0a2a4a\" stroke-opacity=\".25\" stroke-width=\"1.5\"/></svg>",
    "type": "builtin",
    "desc": "我的文件"
  },
  {
    "id": "markdown",
    "kind": "html",
    "name": "Markdown",
    "installed": true,
    "url": "../apps/markdown/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 96 96\"><rect width=\"96\" height=\"96\" rx=\"20\" fill=\"#0a1628\"/><rect x=\"26\" y=\"16\" width=\"44\" height=\"64\" rx=\"5\" fill=\"none\" stroke=\"#c9a85c\" stroke-width=\"3\"/><text x=\"48\" y=\"60\" text-anchor=\"middle\" font-family=\"Georgia,serif\" font-size=\"34\" font-weight=\"700\" fill=\"#ecd28f\">M</text><path d=\"M35 70 L43 78 L53 70\" fill=\"none\" stroke=\"#ecd28f\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>",
    "type": "builtin",
    "desc": "Markdown",
    "handles": [
      "md",
      "markdown"
    ],
    "iface": "CanghaiMarkdown"
  },
  {
    "id": "calculator",
    "kind": "html",
    "name": "计算器",
    "installed": true,
    "url": "../apps/calculator/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 96 96\">\n  <rect x=\"14\" y=\"8\" width=\"68\" height=\"80\" rx=\"16\" fill=\"#143a5e\"/>\n  <rect x=\"24\" y=\"18\" width=\"48\" height=\"18\" rx=\"6\" fill=\"#0a1c2e\"/>\n  <rect x=\"30\" y=\"24\" width=\"28\" height=\"6\" rx=\"3\" fill=\"#c9a85c\"/>\n  <g fill=\"#f7f4ec\">\n    <rect x=\"24\" y=\"44\" width=\"12\" height=\"12\" rx=\"4\"/>\n    <rect x=\"42\" y=\"44\" width=\"12\" height=\"12\" rx=\"4\"/>\n    <rect x=\"60\" y=\"44\" width=\"12\" height=\"12\" rx=\"4\"/>\n    <rect x=\"24\" y=\"60\" width=\"12\" height=\"12\" rx=\"4\"/>\n    <rect x=\"42\" y=\"60\" width=\"12\" height=\"12\" rx=\"4\"/>\n  </g>\n  <rect x=\"60\" y=\"60\" width=\"12\" height=\"12\" rx=\"4\" fill=\"#c9a85c\"/>\n</svg>",
    "type": "builtin",
    "desc": "计算器"
  },
  {
    "id": "game-center",
    "kind": "html",
    "name": "游戏中心",
    "installed": true,
    "url": "../apps/game-center/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">\n  <defs>\n    <linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">\n      <stop offset=\"0\" stop-color=\"#12324a\"/><stop offset=\"1\" stop-color=\"#0a1a2a\"/>\n    </linearGradient>\n    <linearGradient id=\"gld\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">\n      <stop offset=\"0\" stop-color=\"#ecd28f\"/><stop offset=\"1\" stop-color=\"#c9a85c\"/>\n    </linearGradient>\n  </defs>\n  <rect width=\"64\" height=\"64\" rx=\"14\" fill=\"url(#bg)\"/>\n  <path d=\"M20 22h24a9 9 0 0 1 9 9v6a7 7 0 0 1-12.4 4.4L37 37H27l-3.6 4.4A7 7 0 0 1 11 37v-6a9 9 0 0 1 9-9z\" fill=\"none\" stroke=\"url(#gld)\" stroke-width=\"3\" stroke-linejoin=\"round\"/>\n  <path d=\"M22 30v6M19 33h6\" stroke=\"url(#gld)\" stroke-width=\"2.6\" stroke-linecap=\"round\"/>\n  <circle cx=\"41\" cy=\"31\" r=\"2.4\" fill=\"url(#gld)\"/><circle cx=\"46\" cy=\"35\" r=\"2.4\" fill=\"url(#gld)\"/>\n</svg>",
    "type": "builtin",
    "desc": "游戏中心"
  },
  {
    "id": "huaban",
    "kind": "html",
    "name": "画板",
    "installed": true,
    "url": "../apps/huaban/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\"><rect width=\"64\" height=\"64\" rx=\"14\" fill=\"#0a1628\"/><g transform=\"translate(33,3) scale(1.15)\"><path d=\"M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z\" fill=\"#ecd28f\"/></g><path d=\"M14 46c8 2 16-2 20-10\" fill=\"none\" stroke=\"#c9a85c\" stroke-width=\"4\" stroke-linecap=\"round\"/><circle cx=\"42\" cy=\"30\" r=\"5\" fill=\"#c9a85c\"/><path d=\"M44 26 54 12\" stroke=\"#e9eff6\" stroke-width=\"3.5\" stroke-linecap=\"round\"/></svg>",
    "type": "builtin",
    "desc": "画板"
  },
  {
    "id": "huiyin",
    "kind": "html",
    "name": "绘音",
    "installed": true,
    "url": "../apps/huiyin/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\"><rect width=\"64\" height=\"64\" rx=\"14\" fill=\"#0a1628\"/><path d=\"M12 42 C 22 22, 30 52, 42 28 S 54 34, 56 26\" fill=\"none\" stroke=\"#c9a85c\" stroke-width=\"3\" stroke-linecap=\"round\"/><circle cx=\"20\" cy=\"30\" r=\"4\" fill=\"#ecd28f\"/><circle cx=\"36\" cy=\"40\" r=\"4\" fill=\"#ecd28f\"/><circle cx=\"50\" cy=\"28\" r=\"4\" fill=\"#ecd28f\"/></svg>",
    "type": "builtin",
    "desc": "绘音"
  },
  {
    "id": "lan-hall",
    "kind": "html",
    "name": "局域网大厅",
    "installed": true,
    "url": "../apps/lan-hall/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">\n  <rect width=\"64\" height=\"64\" rx=\"14\" fill=\"#0d2740\"/>\n  <path d=\"M32 32 L16 18 M32 32 L48 18 M32 32 L16 46 M32 32 L48 46\" stroke=\"#4a93c8\" stroke-width=\"2.5\" stroke-linecap=\"round\"/>\n  <circle cx=\"32\" cy=\"32\" r=\"8\" fill=\"#6cc4ff\"/>\n  <circle cx=\"16\" cy=\"18\" r=\"4.5\" fill=\"#9ad9ff\"/>\n  <circle cx=\"48\" cy=\"18\" r=\"4.5\" fill=\"#9ad9ff\"/>\n  <circle cx=\"16\" cy=\"46\" r=\"4.5\" fill=\"#9ad9ff\"/>\n  <circle cx=\"48\" cy=\"46\" r=\"4.5\" fill=\"#9ad9ff\"/>\n</svg>",
    "type": "builtin",
    "desc": "局域网大厅"
  },
  {
    "id": "moyun",
    "kind": "html",
    "name": "打字练习",
    "installed": true,
    "url": "../apps/moyun/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\"><rect width=\"64\" height=\"64\" rx=\"14\" fill=\"#0a1628\"/><text x=\"32\" y=\"45\" font-family=\"STKaiti,KaiTi,Kaiti SC,serif\" font-size=\"38\" fill=\"#ecd28f\" text-anchor=\"middle\">打</text></svg>",
    "type": "builtin",
    "desc": "打字练习"
  },
  {
    "id": "office",
    "kind": "html",
    "name": "办公套件",
    "installed": true,
    "url": "../apps/office/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"><defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\"><stop offset=\"0\" stop-color=\"#123c61\"/><stop offset=\"1\" stop-color=\"#0a2a4a\"/></linearGradient></defs><rect width=\"100\" height=\"100\" rx=\"22\" fill=\"url(#g)\"/><path d=\"M30 20h28l12 12v48H30z\" fill=\"none\" stroke=\"#c9a85c\" stroke-width=\"3.5\" stroke-linejoin=\"round\"/><path d=\"M58 20v12h12\" fill=\"none\" stroke=\"#c9a85c\" stroke-width=\"3.5\" stroke-linejoin=\"round\"/><path d=\"M39 44h22M39 54h22M39 64h13\" stroke=\"#ecd28f\" stroke-width=\"3.5\" stroke-linecap=\"round\"/></svg>",
    "type": "builtin",
    "desc": "办公套件",
    "handles": [
      "docx",
      "txt",
      "md",
      "html",
      "htm",
      "xlsx",
      "xls",
      "csv",
      "pptx"
    ],
    "iface": "CanghaiOffice"
  },
  {
    "id": "photo-editor",
    "kind": "html",
    "name": "图片编辑器",
    "installed": true,
    "url": "../apps/photo-editor/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\"><rect width=\"64\" height=\"64\" rx=\"14\" fill=\"#0a1628\"/><g transform=\"translate(33,3) scale(1.15)\"><path d=\"M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z\" fill=\"#ecd28f\"/></g><path d=\"M8 50 22 34l10 10 6-7 18 13\" fill=\"none\" stroke=\"#c9a85c\" stroke-width=\"3.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>",
    "type": "builtin",
    "desc": "图片编辑器"
  },
  {
    "id": "canghai-git",
    "kind": "html",
    "name": "仓海",
    "installed": true,
    "url": "../apps/canghai-git/index.html",
    "grad": "linear-gradient(160deg,#1a5a8a,#0a2a4a)",
    "icon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\"><defs><linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"#12324a\"/><stop offset=\"1\" stop-color=\"#0a1a2a\"/></linearGradient></defs><rect width=\"64\" height=\"64\" rx=\"14\" fill=\"url(#bg)\"/><g fill=\"none\" stroke=\"#ecd28f\" stroke-width=\"3\" stroke-linecap=\"round\"><circle cx=\"20\" cy=\"16\" r=\"4.5\" fill=\"#0a1a2a\"/><circle cx=\"20\" cy=\"48\" r=\"4.5\" fill=\"#0a1a2a\"/><circle cx=\"45\" cy=\"24\" r=\"4.5\" fill=\"#0a1a2a\"/><path d=\"M20 20.5v23\"/><path d=\"M45 28.5c0 10-11 10-25 13.5\"/></g></svg>",
    "type": "builtin",
    "desc": "仓海"
  }
];
  APPS.forEach(function (a) { w.CHApps.registerBuiltinApp(a); });
})(window);
