/* 沧海 · 老 WebView 能力探测
 * 在 <head> 里同步执行，按能力给 <html> 打标记类，供 legacy.css 里的降级规则使用。
 * 目标基线：Chrome 72（Android 9 机型的 WebView）。
 */
(function () {
  var de = document.documentElement;
  function add(c) {
    try { de.classList.add(c); } catch (e) { de.className += ' ' + c; }
  }
  function has(c) {
    try { return de.classList.contains(c); } catch (e) { return (' ' + de.className + ' ').indexOf(' ' + c + ' ') >= 0; }
  }

  /* flex gap：Chrome 84 才支持。造成 100px 间距后量两个子元素的实际偏移 */
  try {
    var p = document.createElement('div');
    p.setAttribute('style', 'position:absolute;left:-9999px;top:0;width:10px;display:flex;flex-direction:column;gap:100px');
    var a = document.createElement('i');
    var b = document.createElement('i');
    a.style.cssText = b.style.cssText = 'height:1px;width:1px';
    p.appendChild(a);
    p.appendChild(b);
    de.appendChild(p);
    var gap = b.getBoundingClientRect().top - a.getBoundingClientRect().top;
    de.removeChild(p);
    if (!(gap > 50)) add('no-flexgap');
  } catch (e) {
    add('no-flexgap');
  }

  /* backdrop-filter：Chrome 76 才支持 */
  try {
    var C = window.CSS;
    var ok = C && C.supports && (
      C.supports('backdrop-filter', 'blur(1px)') ||
      C.supports('-webkit-backdrop-filter', 'blur(1px)')
    );
    if (!ok) add('no-backdrop');
  } catch (e) {
    add('no-backdrop');
  }

  /* 兼容标记本身也要能被后续脚本读到 */
  window.__canghaiLegacy = {
    noFlexGap: has('no-flexgap'),
    noBackdrop: has('no-backdrop')
  };
})();
