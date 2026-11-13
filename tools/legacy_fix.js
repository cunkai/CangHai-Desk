'use strict';
/* 沧海 · 对外屏桌面做老 WebView 降级（一次性改写 assets 里的源文件）
 * 用法：node tools/legacy_fix.js
 * 幂等：重复运行不会重复插入。
 */
const fs = require('fs');
const path = require('path');
const { downgradeHtmlStyles } = require('./legacy_css.js');

const ROOT = path.join(__dirname, '..');
const TARGETS = [
  { file: 'app/src/main/assets/canghai/desktop/index.html', js: '../shared/legacy.js' },
  { file: 'app/src/main/assets/canghai/dock/index.html', js: '../shared/legacy.js' },
];

const MARK = 'canghai-legacy';
const SCRIPT_MARK = 'shared/legacy.js';

for (const t of TARGETS) {
  const abs = path.join(ROOT, t.file);
  let html = fs.readFileSync(abs, 'utf8');
  const before = html;

  /* 1. 注入能力探测脚本（放在第一个 <style> 之前，抢在首屏绘制前打标记） */
  if (html.indexOf(SCRIPT_MARK) < 0) {
    const tag = '  <script src="' + t.js + '"></script>\n';
    const i = html.search(/<style\b/i);
    if (i > 0) html = html.slice(0, i) + tag + html.slice(i);
    else html = html.replace(/<\/head>/i, tag + '</head>');
  }

  /* 2. 所有内联 <style> 降级 */
  const dg = downgradeHtmlStyles(html);
  html = dg.html;

  /* 3. fallback 规则作为独立 style 块，插在 </head> 前 */
  if (dg.extra.length && html.indexOf(MARK) < 0) {
    const block = '  <!-- 沧海 · 老 WebView 降级规则（由 legacy_css.js 生成） -->\n'
      + '  <style id="' + MARK + '">\n' + dg.extra.join('\n') + '\n  </style>\n';
    html = html.replace(/<\/head>/i, block + '</head>');
  } else if (dg.extra.length && html.indexOf(MARK) >= 0) {
    /* 已有块则整体替换内容，保证重跑能更新 */
    html = html.replace(
      /<style id="canghai-legacy">[\s\S]*?<\/style>/,
      '<style id="' + MARK + '">\n' + dg.extra.join('\n') + '\n  </style>'
    );
  }

  if (html === before) {
    console.log('未变化  ' + t.file);
  } else {
    fs.writeFileSync(abs, html, 'utf8');
    const insets = (html.match(/(^|[\s;{])inset\s*:/g) || []).length;
    console.log('已降级  ' + t.file + '  fallback ' + dg.extra.length + ' 条，残留 inset ' + insets);
  }
}
