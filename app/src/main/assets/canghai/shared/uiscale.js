/* ========================================================
 * 沧海 · 分辨率自适应缩放 (uiscale.js)
 * 桌面层与 Dock 层共用：按当前视口相对 1920×1080 的比例算 --rs，
 * 顶栏 / Dock / 弹窗 / 启动台 / 锁屏等 chrome 尺寸都乘 --rs。
 * 两端夹紧：低分辨率不低于 0.72（再小就看不清），
 * 高分辨率不高于 1.6（再大就占屏）；中间按比例走。
 * 网格格子本身已随分辨率重算（computeGrid），图标 / 小组件 / 便签
 * 跟格子走；--rs 只管 chrome，不动格子、不动壁纸、不动便签弹簧。
 * ======================================================== */
(function(){
  function scale(){
    var vw = window.innerWidth || 1920, vh = window.innerHeight || 1080;
    var s = Math.min(vw / 1920, vh / 1080);
    if(!isFinite(s) || s <= 0) s = 1;
    s = Math.max(0.72, Math.min(1.6, s));
    return Math.round(s * 100) / 100;
  }
  function apply(){
    var s = scale();
    document.documentElement.style.setProperty('--rs', s);
    window.__chUiScale = s;
  }
  window.chUiScale = function(){ return (typeof window.__chUiScale === 'number') ? window.__chUiScale : scale(); };
  window.chApplyUiScale = apply;
  apply();
  var t = null;
  function onResize(){ clearTimeout(t); t = setTimeout(apply, 120); }
  window.addEventListener('resize', onResize);
  window.addEventListener('orientationchange', onResize);
})();
