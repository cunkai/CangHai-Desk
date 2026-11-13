/* ==========================================================================
 * 沧海 · 应用外观算法（唯一实现）
 *
 * 外屏桌面（canghai/desktop/index.html）和手机端（phonehome/index.html）都引这一份，
 * 任何一端新增/改名应用，另一端的图标底色、首字、尺寸规则都不会跑偏。
 *
 * 约定：
 *   1. 有真机图标（Native 给的 <img> base64）就用真图标；
 *   2. 没有图标时用「名字哈希 → 固定渐变 + 首字」，哈希与调色板只在这里定义；
 *   3. 千万别在页面里再写死一份调色板。
 * ========================================================================== */
(function (w) {
  'use strict';

  /* 夜海调色板：同色系相邻，桌面上一排图标不会花 */
  var GRADS = [
    'linear-gradient(160deg,#6fd3ff,#2f7fe0)',
    'linear-gradient(160deg,#38bdf8,#0b4a7a)',
    'linear-gradient(160deg,#8be3d2,#0f8f78)',
    'linear-gradient(160deg,#ffd98a,#d99a2b)',
    'linear-gradient(160deg,#c3b4ff,#5b46b8)',
    'linear-gradient(160deg,#ffb3c0,#c94b63)',
    'linear-gradient(160deg,#a8d8ff,#3c7bbf)',
    'linear-gradient(160deg,#f4c98a,#a86a2b)'
  ];

  var FALLBACK_SEED = '沧海';

  function hash(seed) {
    var s = String(seed == null || seed === '' ? FALLBACK_SEED : seed);
    var h = 0;
    for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
    return h;
  }

  /** 名字 → 一直不变的渐变 */
  function gradFor(seed) {
    return GRADS[hash(seed) % GRADS.length];
  }

  /** 名字 → 首字（中文取首字，英文取首字母大写，emoji/数字原样） */
  function initial(name) {
    var s = String(name == null ? '' : name).trim();
    if (!s) return '沧';
    var first = Array.from ? Array.from(s)[0] : s.charAt(0);
    return /[a-z]/.test(first) ? first.toUpperCase() : first;
  }

  /** 是否已经是可直接 <img> 的图标标记 */
  function isImageIcon(icon) {
    return typeof icon === 'string' && /^\s*<(img|svg)/i.test(icon);
  }

  /**
   * 生成一个图标块的内部 HTML。
   * @param {{name?:string, icon?:string, grad?:string}} app
   */
  function tileInner(app) {
    app = app || {};
    if (isImageIcon(app.icon)) return app.icon;
    return '<span class="chInit">' + escapeHtml(initial(app.name)) + '</span>';
  }

  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  w.CHColor = {
    GRADS: GRADS,
    hash: hash,
    gradFor: gradFor,
    initial: initial,
    isImageIcon: isImageIcon,
    tileInner: tileInner,
    escapeHtml: escapeHtml
  };

  /* 兼容外屏旧代码里直接调用的全局函数 */
  if (!w.CH_GRADS) w.CH_GRADS = GRADS;
  if (typeof w.chGradFor !== 'function') w.chGradFor = gradFor;
})(window);
