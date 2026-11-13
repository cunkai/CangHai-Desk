/* 沧海兼容性扫描：找出超出 WebView 72（Chrome 72）能力的语法 / API / CSS
 * 用法：node tools/scan_compat.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const TARGETS = [
  'app/src/main/assets/canghai/desktop/index.html',
  'app/src/main/assets/canghai/dock/index.html',
  'app/src/main/assets/canghai/shared/appcolor.js',
  'app/src/main/assets/canghai/shared/apps.js',
  'app/src/main/assets/phonehome/index.html',
];

/* level: fatal = 语法解析失败，整个脚本不执行；api = 运行期抛错；css = 样式失效；warn = 视觉降级 */
const JS_RULES = [
  { name: 'optional chaining ?.', re: /\?\.\s*[A-Za-z_$\[(]/g, since: 80, level: 'fatal' },
  { name: 'nullish ??', re: /\?\?/g, since: 80, level: 'fatal' },
  { name: 'logical assign ||= &&= ??=', re: /(\|\|=|&&=|\?\?=)/g, since: 85, level: 'fatal' },
  { name: 'numeric separator 1_000', re: /\b\d+_\d{3}\b/g, since: 75, level: 'fatal' },
  { name: 'private field #x', re: /\bthis\.#[A-Za-z_]/g, since: 84, level: 'fatal' },
  { name: 'static class block', re: /\bstatic\s*\{/g, since: 94, level: 'fatal' },
  { name: 'Blob.arrayBuffer()', re: /\.arrayBuffer\s*\(/g, since: 76, level: 'api' },
  { name: 'Blob.text()', re: /\.text\s*\(\s*\)/g, since: 76, level: 'api' },
  { name: 'DecompressionStream', re: /\bDecompressionStream\b/g, since: 80, level: 'api' },
  { name: 'structuredClone', re: /\bstructuredClone\s*\(/g, since: 98, level: 'api' },
  { name: 'Object.fromEntries', re: /\bObject\.fromEntries\s*\(/g, since: 73, level: 'api' },
  { name: 'Object.hasOwn', re: /\bObject\.hasOwn\s*\(/g, since: 93, level: 'api' },
  { name: 'String.replaceAll', re: /\.replaceAll\s*\(/g, since: 85, level: 'api' },
  { name: 'String/Array .at()', re: /\.at\s*\(\s*-?\d/g, since: 92, level: 'api' },
  { name: 'Array.findLast', re: /\.findLast(Index)?\s*\(/g, since: 97, level: 'api' },
  { name: 'Array.toSorted/toReversed', re: /\.to(Sorted|Reversed|Spliced)\s*\(/g, since: 110, level: 'api' },
  { name: 'Array.group/groupBy', re: /\.group(By)?\s*\(/g, since: 117, level: 'api' },
  { name: 'Promise.allSettled', re: /\bPromise\.allSettled\s*\(/g, since: 76, level: 'api' },
  { name: 'Promise.any', re: /\bPromise\.any\s*\(/g, since: 85, level: 'api' },
  { name: 'WeakRef', re: /\bWeakRef\b/g, since: 84, level: 'api' },
  { name: 'crypto.randomUUID', re: /\.randomUUID\s*\(/g, since: 92, level: 'api' },
  { name: 'Element.replaceChildren', re: /\.replaceChildren\s*\(/g, since: 86, level: 'api' },
  { name: 'reportError', re: /\breportError\s*\(/g, since: 95, level: 'api' },
  { name: 'navigator.clipboard.write', re: /navigator\.clipboard\.(write|writeText)/g, since: 76, level: 'api' },
  { name: 'ShowOpenFilePicker', re: /show(Open|Save)FilePicker/g, since: 86, level: 'api' },
  { name: 'Intl.Segmenter', re: /\bIntl\.Segmenter\b/g, since: 87, level: 'api' },
  { name: 'ResizeObserver loop()', re: /\.observe\s*\(\s*[A-Za-z_$]/g, since: 64, level: 'ok' },
];

const CSS_RULES = [
  { name: 'inset:', re: /(^|[\s;{])inset\s*:/g, since: 87 },
  { name: 'flex gap:', re: /\bgap\s*:/g, since: 84 },
  { name: 'aspect-ratio:', re: /aspect-ratio\s*:/g, since: 88 },
  { name: ':is / :where', re: /:(is|where)\s*\(/g, since: 88 },
  { name: 'clamp()', re: /\bclamp\s*\(/g, since: 79 },
  { name: 'min()/max() 函数', re: /(^|[\s:(,])m(in|ax)\s*\(/g, since: 79 },
  { name: 'dvh/svh/lvh', re: /\d(d|s|l)vh\b/g, since: 108 },
  { name: 'color-mix()', re: /color-mix\s*\(/g, since: 111 },
  { name: ':has()', re: /:has\s*\(/g, since: 105 },
  { name: 'backdrop-filter', re: /backdrop-filter\s*:/g, since: 76 },
  { name: 'overflow: clip', re: /overflow\s*:\s*clip/g, since: 90 },
  { name: 'content-visibility', re: /content-visibility\s*:/g, since: 85 },
  { name: 'text-wrap:', re: /text-wrap\s*:/g, since: 114 },
  { name: 'accent-color', re: /accent-color\s*:/g, since: 93 },
  { name: ':focus-visible', re: /:focus-visible/g, since: 86 },
  { name: 'prefers-color-scheme', re: /prefers-color-scheme/g, since: 76 },
  { name: '独立的 translate/rotate/scale 属性', re: /(^|[;{\s])(translate|rotate|scale)\s*:\s*[^;}]/g, since: 104 },
  { name: '@container', re: /@container\b/g, since: 105 },
  { name: '@layer', re: /@layer\b/g, since: 99 },
  { name: 'CSS 嵌套 &', re: /^\s*&[\s:.\[]/gm, since: 112 },
  { name: 'inset-inline / inset-block', re: /inset-(inline|block)/g, since: 87 },
  { name: 'object-fit', re: /object-fit\s*:/g, since: 32 },
];

function lines(text) {
  return text.split('\n');
}

function scan(file) {
  const abs = path.join(ROOT, file);
  if (!fs.existsSync(abs)) return null;
  const text = fs.readFileSync(abs, 'utf8');
  const ls = lines(text);
  const hits = [];

  const collect = (rules, kind) => {
    for (const r of rules) {
      r.re.lastIndex = 0;
      let m;
      let guard = 0;
      while ((m = r.re.exec(text)) !== null && guard++ < 5000) {
        const idx = m.index;
        const lineNo = text.slice(0, idx).split('\n').length;
        const src = (ls[lineNo - 1] || '').trim();
        hits.push({ kind, name: r.name, since: r.since, level: r.level || 'css', line: lineNo, src: src.slice(0, 150) });
        if (m[0].length === 0) r.re.lastIndex++;
      }
    }
  };

  collect(JS_RULES, 'js');
  collect(CSS_RULES, 'css');
  hits.sort((a, b) => a.line - b.line);
  return { file, text, hits };
}

const report = [];
for (const t of TARGETS) {
  const r = scan(t);
  if (r) report.push(r);
}

let total = 0;
for (const r of report) {
  const fatal = r.hits.filter((h) => h.level === 'fatal');
  const api = r.hits.filter((h) => h.level === 'api');
  const css = r.hits.filter((h) => h.level === 'css');
  console.log('\n===== ' + r.file + ' =====');
  console.log('致命语法 ' + fatal.length + ' | 运行期 API ' + api.length + ' | CSS ' + css.length);
  const groups = {};
  for (const h of r.hits) {
    const k = h.kind + '|' + h.name;
    (groups[k] = groups[k] || []).push(h);
  }
  for (const k of Object.keys(groups)) {
    const g = groups[k];
    console.log('  [' + g[0].level + '] ' + g[0].name + ' (需 Chrome ' + g[0].since + ') x' + g.length);
    for (const h of g.slice(0, 3)) console.log('      L' + h.line + ': ' + h.src);
    if (g.length > 3) console.log('      ... 还有 ' + (g.length - 3) + ' 处');
  }
  total += r.hits.length;
}
console.log('\n总命中 ' + total + ' 处');
