'use strict';
/* 沧海 · CSS 降级器：把超出 Chrome 72（老机器 WebView）的写法降级
 *
 * 处理项：
 *   inset:0             -> top/right/bottom/left
 *   100dvh / svh / lvh  -> vh
 *   clamp()/min()/max() -> 先输出保守固定值，再输出原声明
 *   flex + gap          -> 生成 .no-flexgap 下的子元素 margin 规则
 *   backdrop-filter     -> 生成 .no-backdrop 下的实心背景规则
 *
 * 用法：
 *   const { downgradeCss } = require('./legacy_css.js');
 *   const { css, extra } = downgradeCss(cssText);
 */

const DVH_RE = /\b(\d*\.?\d+)(?:d|s|l)vh\b/g;
const RECURSE_AT = /^@(media|supports|document|layer|container)\b/i;

function findClose(s, open) {
  let d = 0;
  for (let k = open; k < s.length; k++) {
    const c = s[k];
    if (c === '(') d++;
    else if (c === ')') { d--; if (d === 0) return k; }
  }
  return -1;
}

function splitArgs(inner) {
  const out = [];
  let cur = '', d = 0;
  for (let i = 0; i < inner.length; i++) {
    const c = inner[i];
    if (c === '(') d++;
    else if (c === ')') d--;
    if (c === ',' && d === 0) { out.push(cur.trim()); cur = ''; continue; }
    cur += c;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

/* clamp/min/max -> 保守固定值 */
function downgradeValue(v) {
  let out = '', i = 0, hit = false;
  while (i < v.length) {
    const m = /^(clamp|min|max)\(/.exec(v.slice(i));
    if (m) {
      const open = i + m[0].length - 1;
      const close = findClose(v, open);
      if (close > 0) {
        const args = splitArgs(v.slice(open + 1, close));
        let pick;
        if (m[1] === 'clamp') pick = args.length >= 2 ? args[1] : args[0];
        else pick = args[0];
        if (pick !== undefined && pick !== '') { out += pick; hit = true; i = close + 1; continue; }
      }
    }
    out += v[i];
    i++;
  }
  return { value: out, hit };
}

function expandInset(v) {
  const p = v.trim().split(/\s+/);
  let t, r, b, l;
  if (p.length === 1) { t = r = b = l = p[0]; }
  else if (p.length === 2) { t = b = p[0]; r = l = p[1]; }
  else if (p.length === 3) { t = p[0]; r = l = p[1]; b = p[2]; }
  else { t = p[0]; r = p[1]; b = p[2]; l = p[3]; }
  return ['top:' + t, 'right:' + r, 'bottom:' + b, 'left:' + l];
}

function splitDecls(body) {
  const out = [];
  let cur = '', d = 0, q = null;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (q) {
      cur += c;
      if (c === q && body[i - 1] !== '\\') q = null;
      continue;
    }
    if (c === '"' || c === "'") { q = c; cur += c; continue; }
    if (c === '(') { d++; cur += c; continue; }
    if (c === ')') { d--; cur += c; continue; }
    if (c === ';' && d === 0) { out.push(cur); cur = ''; continue; }
    cur += c;
  }
  if (cur.trim()) out.push(cur);
  return out;
}

function propOf(decl) {
  const i = decl.indexOf(':');
  if (i < 0) return null;
  return { prop: decl.slice(0, i).trim().toLowerCase(), value: decl.slice(i + 1).trim() };
}

function solidify(color, alpha) {
  const m = /rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)/i.exec(color);
  if (!m) return null;
  return 'rgba(' + m[1] + ',' + m[2] + ',' + m[3] + ',' + alpha + ')';
}

function stripComment(s) {
  return s.replace(/\/\*[\s\S]*?\*\//g, '').trim();
}

/* 递归收集普通规则块，ctx 记录外层 at-rule（如 @media） */
function collect(css, start, end, ctx, result) {
  let i = start;
  while (i < end) {
    const brace = css.indexOf('{', i);
    if (brace < 0 || brace >= end) break;
    let s = brace;
    while (s > i && css[s - 1] !== ';' && css[s - 1] !== '}' && css[s - 1] !== '{') s--;
    const rawSel = css.slice(s, brace);
    const sel = stripComment(rawSel);
    let d = 0, j = brace;
    for (; j < end; j++) {
      if (css[j] === '{') d++;
      else if (css[j] === '}') { d--; if (d === 0) break; }
    }
    if (j >= end) break;
    if (sel.charAt(0) === '@') {
      if (RECURSE_AT.test(sel)) collect(css, brace + 1, j, ctx.concat([sel]), result);
      // @keyframes / @font-face 内部跳过
    } else if (sel) {
      result.push({ selStart: s, selEnd: brace, sel, bodyStart: brace + 1, bodyEnd: j, ctx });
    }
    i = j + 1;
  }
}

function downgradeCss(css, opts) {
  opts = opts || {};
  const extra = [];
  const blocks = [];
  collect(css, 0, css.length, [], blocks);

  const edits = [];
  for (const b of blocks) {
    const body = css.slice(b.bodyStart, b.bodyEnd);
    const decls = splitDecls(body);
    const outDecls = [];
    const seenFixed = new Set(); // 已经有固定值兜底的属性，避免重复降级
    let hasFlex = false, hasGrid = false, rowGap = null, colGap = null;
    let isColumn = false, bgColor = null, backdrop = null, changed = false;

    for (const d of decls) {
      if (!d.trim()) continue;
      const pv = propOf(d);
      if (!pv) { outDecls.push(d.trim()); continue; }
      let prop = pv.prop, value = pv.value;

      if (prop === 'display') {
        if (/flex/.test(value)) hasFlex = true;
        if (/grid/.test(value)) hasGrid = true;
      }
      if (prop === 'flex-direction' && /column/.test(value)) isColumn = true;

      if (prop === 'inset') { outDecls.push(...expandInset(value)); changed = true; continue; }
      if (prop === 'inset-inline') { outDecls.push('left:' + value, 'right:' + value); changed = true; continue; }
      if (prop === 'inset-block') { outDecls.push('top:' + value, 'bottom:' + value); changed = true; continue; }

      if (prop === 'gap') {
        const parts = value.trim().split(/\s+/);
        rowGap = parts[0];
        colGap = parts.length > 1 ? parts[1] : parts[0];
      }
      if (prop === 'row-gap') rowGap = value;
      if (prop === 'column-gap') colGap = value;

      if (prop === 'backdrop-filter' || prop === '-webkit-backdrop-filter') {
        /* 声明要保留（新 WebView 靠它做毛玻璃），只是额外给老 WebView
           生成 .no-backdrop 下的实心背景兜底，两者不冲突：
           .no-backdrop 只在探测失败时打上，新浏览器看不到那条规则。 */
        backdrop = value;
        outDecls.push(prop + ':' + value);
        continue;
      }
      if ((prop === 'background' || prop === 'background-color') && /^rgba?\(/i.test(value)) bgColor = value;

      const noDvh = value.replace(DVH_RE, (m, n) => n + 'vh');
      if (noDvh !== value) changed = true;
      value = noDvh;

      const dv = downgradeValue(value);
      if (dv.hit && !seenFixed.has(prop)) {
        outDecls.push(prop + ':' + dv.value);
        outDecls.push(prop + ':' + value);
        seenFixed.add(prop);
        changed = true;
      } else {
        outDecls.push(prop + ':' + value);
        if (!dv.hit) seenFixed.add(prop);
      }
    }

    if (hasFlex && !hasGrid) {
      const sel = scopeSel(b.sel, 'no-flexgap');
      const v = isColumn ? (rowGap || colGap) : (colGap || rowGap);
      if (v) {
        const axis = isColumn ? 'margin-top' : 'margin-left';
        const val = downgradeValue(v.replace(DVH_RE, (x, n) => n + 'vh')).value;
        extra.push(wrapCtx(b.ctx, sel + ' > * + *{' + axis + ':' + val + '}'));
      }
    }

    if (backdrop) {
      const sel = scopeSel(b.sel, 'no-backdrop');
      const solid = bgColor && solidify(bgColor, 0.94);
      extra.push(wrapCtx(b.ctx, sel + '{' + (solid ? 'background:' + solid : 'background:rgba(8,24,42,.94)') + '}'));
    }

    const newBody = outDecls.join(';');
    const norm = (x) => x.replace(/\s+/g, '').replace(/;+$/, '');
    if (changed || norm(newBody) !== norm(body)) {
      edits.push({ start: b.bodyStart, end: b.bodyEnd, text: newBody });
    }
  }

  edits.sort((a, b) => b.start - a.start);
  let out = css;
  for (const e of edits) out = out.slice(0, e.start) + e.text + out.slice(e.end);

  return { css: out, extra };
}

function wrapCtx(ctx, rule) {
  if (!ctx || !ctx.length) return rule;
  let r = rule;
  for (let i = ctx.length - 1; i >= 0; i--) r = ctx[i] + '{' + r + '}';
  return r;
}

/* 给选择器加降级作用域；以 html 开头的选择器要合并到同一个 html 上 */
function scopeSel(sel, cls) {
  let s = sel.replace(/\s+/g, ' ').trim();
  if (/^html\b/i.test(s)) {
    s = s.replace(/^html/i, 'html.' + cls);
  } else {
    s = '.' + cls + ' ' + s;
  }
  return s;
}

module.exports = { downgradeCss, downgradeValue, expandInset, downgradeHtmlStyles };

/* 处理整页 HTML 里所有内联 <style>，并把 fallback 规则汇总成一个独立 style 块 */
function downgradeHtmlStyles(html) {
  const allExtra = [];
  const out = html.replace(/<style\b([^>]*)>([\s\S]*?)<\/style>/gi, (m, attrs, css) => {
    const r = downgradeCss(css);
    for (const e of r.extra) allExtra.push(e);
    return '<style' + attrs + '>' + r.css + '</style>';
  });
  return { html: out, extra: allExtra };
}

if (require.main === module) {
  const fs = require('fs');
  const src = fs.readFileSync(process.argv[2], 'utf8');
  const r = downgradeCss(src);
  process.stdout.write(r.css);
  if (r.extra.length) process.stdout.write('\n\n/* ---- legacy fallback ---- */\n' + r.extra.join('\n') + '\n');
}
