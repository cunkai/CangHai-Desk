/**
 * 沧海 · 前端绘制探针（打开「性能监听」时由原生注入，平时不存在）
 *
 * 它只做一件事：每 2 秒把这些数字报给安卓层，让人能回答「这个页面画得动吗」。
 *   fps        这一页的 rAF 实际帧率（页面自己的调度频率，不是编码帧率）
 *   stall      两次 rAF 间隔超过 50ms 的次数和最长值 → 主线程被卡
 *   longtask   PerformanceObserver 抓到的 >50ms 任务
 *   mut        2 秒内 DOM 变更次数 / 每秒次数 → 布局抖动的源头
 *   nodes      节点总数（布局成本与它成正比）
 *   heapMB     JS 堆
 *
 * 注意：不依赖 CanghaiCore，直接用 __CanghaiNative，这样原生注入顺序无所谓。
 */
(function () {
  var REV = 3;
  if (window.__canghaiPerf && window.__canghaiPerf.rev >= REV) return;

  var stopped = false;
  var page = (function () {
    var p = location.pathname || "";
    var i = p.indexOf("/canghai/");
    if (i >= 0) return p.substring(i + 9) || "canghai";
    i = p.indexOf("/phonehome/");
    if (i >= 0) return "phonehome";
    return p.split("/").pop() || "unknown";
  })();

  var frames = 0, winStart = performance.now(), lastFrame = winStart;
  var stallCount = 0, stallMax = 0;
  var longCount = 0, longMax = 0;
  var muts = 0;

  try {
    if (window.PerformanceObserver) {
      new PerformanceObserver(function (list) {
        var es = list.getEntries();
        for (var i = 0; i < es.length; i++) {
          longCount++;
          if (es[i].duration > longMax) longMax = es[i].duration;
        }
      }).observe({ entryTypes: ["longtask"] });
    }
  } catch (e) {}

  try {
    if (window.MutationObserver && document.documentElement) {
      new MutationObserver(function (recs) { muts += recs.length; })
        .observe(document.documentElement, {
          subtree: true, childList: true, attributes: true, characterData: true
        });
    }
  } catch (e) {}

  function nodeCount() {
    try { return document.getElementsByTagName("*").length; } catch (e) { return 0; }
  }

  function flush(now) {
    var span = now - winStart;
    var heap = 0;
    try {
      if (performance.memory) heap = Math.round(performance.memory.usedJSHeapSize / 1048576 * 10) / 10;
    } catch (e) {}
    var payload = {
      page: page,
      fps: Math.round(frames * 10000 / span) / 10,
      stallCount: stallCount,
      stallMax: Math.round(stallMax),
      longCount: longCount,
      longMax: Math.round(longMax),
      mutPerSec: Math.round(muts * 1000 / span),
      nodes: nodeCount(),
      heapMB: heap,
      hidden: !!document.hidden
    };
    frames = 0; muts = 0; stallCount = 0; stallMax = 0; longCount = 0; longMax = 0;
    try {
      if (window.__CanghaiNative && window.__CanghaiNative.invoke) {
        window.__CanghaiNative.invoke("perf", "report", JSON.stringify(payload));
      }
    } catch (e) {}
  }

  function tick(now) {
    if (stopped) return;
    var gap = now - lastFrame;
    lastFrame = now;
    frames++;
    if (gap > 50) {
      stallCount++;
      if (gap > stallMax) stallMax = gap;
    }
    if (now - winStart >= 2000) {
      flush(now);
      winStart = now;
    }
    window.requestAnimationFrame(tick);
  }

  window.__canghaiPerf = { rev: REV, page: page };
  window.__canghaiPerfStop = function () {
    stopped = true;
    window.__canghaiPerf = null;
  };
  window.requestAnimationFrame(tick);
})();
