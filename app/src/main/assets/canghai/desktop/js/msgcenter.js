/* ==========================================================================
 * 沧海 · 桌面消息中心
 *
 * 应用（视频 / 相片等）想报信时走消息分发通道发一个带 canghai 标记的消息：
 *   { canghai: 'notify', title: '…', body: '…', time: 1234567890, thumb: 'data:image/…' }
 * 桌面收到后在这里统一处理：右上角滑入一条通知（停 4 秒再滑回去），
 * 同时记进消息中心的历史（最多 50 条），顶栏铃铛带未读角标，点开可看、可清空。
 *
 * 桌面自己的 toast() 是另一条更轻的提示（底部居中、纯文字），两者不冲突。
 * ========================================================================== */
(function (w) {
  'use strict';

  var MAX_KEEP = 50;
  var messages = [];   // [{title, body, time, thumb}]
  var unread = 0;

  var bellBtn, bellBadge, toastWrap, msgCenter, msgList, msgClear, msgClose;

  function $(id) { return document.getElementById(id); }
  function clockTime(ts) {
    var d = new Date(ts || Date.now());
    var p = function (n) { return String(n).padStart(2, '0'); };
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function bind() {
    bellBtn = $('msgBellBtn'); bellBadge = $('msgBellBadge');
    toastWrap = $('msgToastWrap'); msgCenter = $('msgCenterPanel');
    msgList = $('msgList'); msgClear = $('msgClear'); msgClose = $('msgClose');
    if (!bellBtn || !msgCenter) return false;
    bellBtn.addEventListener('click', function () { toggle(); });
    if (msgClear) msgClear.addEventListener('click', function () { clear(); });
    if (msgClose) msgClose.addEventListener('click', function () { close(); });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && msgCenter.classList.contains('open')) close();
    });
    return true;
  }

  function paintBadge() {
    if (!bellBadge) return;
    bellBadge.textContent = unread > 99 ? '99+' : String(unread);
    bellBadge.classList.toggle('show', unread > 0);
  }

  function showToast(msg) {
    if (!toastWrap) return;
    var el = document.createElement('div');
    el.className = 'ch-toast';
    var img = msg.thumb ? '<img src="' + esc(msg.thumb) + '" alt="">' : '';
    el.innerHTML = img +
      '<div class="ch-toast-body">' +
        '<div class="ch-toast-title">' + esc(msg.title || '') + '</div>' +
        '<div class="ch-toast-text">' + esc(msg.body || '') + '</div>' +
        '<div class="ch-toast-time">' + clockTime(msg.time) + '</div>' +
      '</div>';
    toastWrap.appendChild(el);
    requestAnimationFrame(function () { requestAnimationFrame(function () { el.classList.add('show'); }); });
    var hideTimer = setTimeout(function () { hideToast(el); }, 4000);
    el.addEventListener('click', function () {
      clearTimeout(hideTimer);
      hideToast(el);
      open();
    });
  }
  function hideToast(el) {
    el.classList.remove('show');
    setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 350);
  }

  function renderList() {
    if (!msgList) return;
    if (!messages.length) {
      msgList.innerHTML = '<div class="ch-msg-empty">暂无消息</div>';
      return;
    }
    msgList.innerHTML = messages.map(function (m) {
      var img = m.thumb ? '<img src="' + esc(m.thumb) + '" alt="">' : '<span class="ch-msg-noimg">✦</span>';
      return '<div class="ch-msg-item">' + img +
        '<div class="ch-msg-body">' +
          '<div class="ch-msg-title">' + esc(m.title || '') + '</div>' +
          '<div class="ch-msg-text">' + esc(m.body || '') + '</div>' +
          '<div class="ch-msg-time">' + clockTime(m.time) + '</div>' +
        '</div></div>';
    }).join('');
  }

  function notify(d) {
    var m = {
      title: d.title || '',
      body: d.body || d.text || '',
      time: d.time || Date.now(),
      thumb: d.thumb || ''
    };
    messages.unshift(m);
    if (messages.length > MAX_KEEP) messages.length = MAX_KEEP;
    unread++;
    paintBadge();
    showToast(m);
    renderList();
  }

  function open() {
    if (!msgCenter) return;
    msgCenter.classList.add('open');
    unread = 0;
    paintBadge();
  }
  function close() {
    if (!msgCenter) return;
    msgCenter.classList.remove('open');
  }
  function toggle() {
    if (!msgCenter) return;
    if (msgCenter.classList.contains('open')) close(); else open();
  }
  function clear() {
    messages.length = 0;
    unread = 0;
    paintBadge();
    renderList();
  }

  function init() {
    if (!bind()) {
      /* 桌面 HTML 还没 ready 时晚点再绑一次 */
      setTimeout(init, 300);
      return;
    }
    renderList();
  }

  w.CHMsgCenter = {
    notify: notify,
    open: open,
    close: close,
    toggle: toggle,
    clear: clear,
    get unread() { return unread; },
    get messages() { return messages.slice(); }
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})(window);
