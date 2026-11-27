/* __CH_INPUT_SHIM__ — Canghai universal input layer.
 *
 * Injected at IMPORT time into HTML games that have no touch/gamepad support
 * of their own, so any game can be played with touch, a Bluetooth gamepad,
 * mouse or keyboard. Zero dependencies, no framework, runs at end of body.
 *
 * How it works (no game-specific knowledge needed): it SYNTHESIZES the same
 * DOM events a real keyboard/mouse would produce, so any game that listens
 * for keydown/keyup/mousedown/mousemove just works:
 *   - touch joystick  -> WASD key events (push to the edge = sprint)
 *   - touch drag      -> mousemove with movementX/Y (+ a virtual cursor
 *                        clientX/Y, so cursor-aim games work too)
 *   - touch tap       -> mouse click at that point (short tap, no drag)
 *   - touch buttons   -> Space / mouse left / mouse right / E
 *   - gamepad         -> the same synthesis (sticks, A/X/B/Y, triggers, d-pad)
 * Real keyboard/mouse are never touched: on a desktop with no touch and no
 * gamepad this file does nothing visible at all.
 *
 * Pointer lock: the first look-drag asks the game's canvas for pointer lock
 * (the way pointer-lock games expect). If the game never uses lock, the
 * request either succeeds harmlessly or fails silently.
 *
 * Config (optional, define BEFORE this script runs):
 *   window.CanghaiInput = {
 *     disabled: false,          // true: the game opts out entirely
 *     touch: true, gamepad: true,
 *     keys: { up:'KeyW', down:'KeyS', left:'KeyA', right:'KeyD',
 *             jump:'Space', sprint:'ShiftLeft', interact:'KeyE',
 *             drop:'KeyQ', pause:'Escape' },
 *     pad: { a:'jump', b:'use', x:'attack', y:'interact',
 *            lb:'drop', rb:'KeyR', back:'Tab', start:'pause',
 *            l3:'crouch', r3:'KeyF' },   // button index -> action or key code
 *     lookScale: 1.0,           // look sensitivity multiplier
 *     stickDeadzone: 0.18
 *   };
 * Actions the pad map can name: up/down/left/right/jump/sprint/interact/
 * drop/pause/crouch/attack/use. Anything else is treated as a key code
 * (e.g. 'KeyR', 'Tab') and sent as a key event.
 *
 * The installers inline THIS file into the game's HTML (see
 * tools/build_input_shim_src.py, which generates input-shim-src.js — a
 * string wrapper the installers load with a plain <script src> so it works
 * on file:// too, where fetch() is blocked).
 */
(function () {
  'use strict';
  if (window.__chInputShim) return;
  window.__chInputShim = true;
  var CFG = window.CanghaiInput || {};
  if (CFG.disabled) return;

  var KEYMAP = {
    up: 'KeyW', down: 'KeyS', left: 'KeyA', right: 'KeyD',
    jump: 'Space', sprint: 'ShiftLeft', interact: 'KeyE',
    drop: 'KeyQ', pause: 'Escape', crouch: 'ControlLeft'
  };
  var k;
  if (CFG.keys) for (k in CFG.keys) if (CFG.keys.hasOwnProperty(k)) KEYMAP[k] = CFG.keys[k];
  var PADMAP = {
    a: 'jump', b: 'use', x: 'attack', y: 'interact',
    lb: 'drop', rb: 'KeyR', back: 'Tab', start: 'pause',
    l3: 'crouch', r3: 'KeyF'
  };
  if (CFG.pad) for (k in CFG.pad) if (CFG.pad.hasOwnProperty(k)) PADMAP[k] = CFG.pad[k];
  var LOOK_SCALE = typeof CFG.lookScale === 'number' ? CFG.lookScale : 1;
  var DEADZONE = typeof CFG.stickDeadzone === 'number' ? CFG.stickDeadzone : 0.18;
  var TOUCH_ON = CFG.touch !== false, PAD_ON = CFG.gamepad !== false;

  /* ---- key code tables (synthetic events need keyCode/which set by hand) ---- */
  var KEY_CODES = {
    KeyA: 65, KeyB: 66, KeyC: 67, KeyD: 68, KeyE: 69, KeyF: 70, KeyG: 71,
    KeyH: 72, KeyI: 73, KeyJ: 74, KeyK: 75, KeyL: 76, KeyM: 77, KeyN: 78,
    KeyO: 79, KeyP: 80, KeyQ: 81, KeyR: 82, KeyS: 83, KeyT: 84, KeyU: 85,
    KeyV: 86, KeyW: 87, KeyX: 88, KeyY: 89, KeyZ: 90,
    Digit0: 48, Digit1: 49, Digit2: 50, Digit3: 51, Digit4: 52, Digit5: 53,
    Digit6: 54, Digit7: 55, Digit8: 56, Digit9: 57,
    Space: 32, ShiftLeft: 16, ShiftRight: 16, ControlLeft: 17, ControlRight: 17,
    AltLeft: 18, AltRight: 18, Escape: 27, Tab: 9, Enter: 13,
    ArrowUp: 38, ArrowDown: 40, ArrowLeft: 37, ArrowRight: 39
  };
  function keyName(code) {
    if (code === 'Space') return ' ';
    if (code === 'Escape') return 'Escape';
    if (code === 'Tab') return 'Tab';
    if (code === 'Enter') return 'Enter';
    if (/^Key[A-Z]$/.test(code)) return code.slice(3).toLowerCase();
    if (/^Digit[0-9]$/.test(code)) return code.slice(5);
    if (/^Arrow/.test(code)) return code.slice(5);
    if (/^Shift/.test(code)) return 'Shift';
    if (/^Control/.test(code)) return 'Control';
    if (/^Alt/.test(code)) return 'Alt';
    return code;
  }

  /* ---- synthetic event dispatch ---- */
  function keyEvent(type, code) {
    var ev = null;
    try {
      ev = new KeyboardEvent(type, { code: code, key: keyName(code), bubbles: true, cancelable: true });
    } catch (e) {
      try {
        ev = document.createEvent('KeyboardEvent');
        ev.initKeyboardEvent(type, true, true, window, keyName(code), 0, '', false, '');
      } catch (e2) { return; }
    }
    var kc = KEY_CODES[code] || 0;
    try { Object.defineProperty(ev, 'keyCode', { value: kc }); Object.defineProperty(ev, 'which', { value: kc }); } catch (e) {}
    (document.body || document.documentElement).dispatchEvent(ev);
  }
  function mouseEvent(type, opts) {
    var ev = null;
    try {
      ev = new MouseEvent(type, {
        bubbles: true, cancelable: true,
        clientX: opts.x || 0, clientY: opts.y || 0,
        button: opts.button || 0, buttons: opts.buttons || 0,
        movementX: opts.dx || 0, movementY: opts.dy || 0
      });
    } catch (e) {
      try { ev = document.createEvent('MouseEvent'); ev.initMouseEvent(type, true, true, window, 0, opts.x || 0, opts.y || 0, opts.x || 0, opts.y || 0, false, false, false, false, opts.button || 0, null); } catch (e2) { return; }
    }
    (findCanvas() || document).dispatchEvent(ev);
  }

  /* ---- game canvas (lazy: the game may create it after we run) ---- */
  var _canvas = null;
  function findCanvas() {
    if (_canvas && _canvas.isConnected) return _canvas;
    var best = null, bestArea = 0;
    var list = document.querySelectorAll('canvas');
    for (var i = 0; i < list.length; i++) {
      var r = list[i].getBoundingClientRect();
      var area = r.width * r.height;
      if (area > bestArea) { bestArea = area; best = list[i]; }
    }
    _canvas = best || document.querySelector('canvas');
    return _canvas;
  }
  function lockPointer() {
    try {
      var c = findCanvas();
      if (c && !document.pointerLockElement && c.requestPointerLock) {
        var p = c.requestPointerLock();
        if (p && p.catch) p.catch(function () { /* refused: harmless */ });
      }
    } catch (e) { /* ignore */ }
  }

  /* ---- owned-key tracking: an input only ever removes its own keys ---- */
  function syncKeys(owned, desired) {
    owned.forEach(function (code) { if (!desired.has(code)) { owned.delete(code); keyEvent('keyup', code); } });
    desired.forEach(function (code) { if (!owned.has(code)) { owned.add(code); keyEvent('keydown', code); } });
  }
  function keysFromVector(x, y, owned) {
    var desired = new Set();
    if (y < -0.25) desired.add(KEYMAP.up);
    if (y > 0.25) desired.add(KEYMAP.down);
    if (x < -0.25) desired.add(KEYMAP.left);
    if (x > 0.25) desired.add(KEYMAP.right);
    if (Math.hypot(x, y) > 0.92) desired.add(KEYMAP.sprint);
    syncKeys(owned, desired);
  }

  /* ---- virtual cursor (for cursor-aim games) ---- */
  var cursor = { x: window.innerWidth / 2, y: window.innerHeight / 2 };
  function moveCursor(dx, dy) {
    cursor.x = Math.max(0, Math.min(window.innerWidth, cursor.x + dx));
    cursor.y = Math.max(0, Math.min(window.innerHeight, cursor.y + dy));
  }
  function look(dx, dy) {
    moveCursor(dx, dy);
    mouseEvent('mousemove', { x: cursor.x, y: cursor.y, dx: dx, dy: dy });
  }

  /* ---- touch UI ---- */
  var ui = null, stickZone = null, baseEl = null, knobEl = null, downBtn = null;
  var seen = false, shown = false, hiddenByUser = false;
  var stickId = null, stickOX = 0, stickOY = 0, stickVec = { x: 0, y: 0 }, stickKeys = new Set();
  var lookId = null, lookLX = 0, lookLY = 0, lookSX = 0, lookSY = 0, lookT0 = 0, lookDragged = false, lookLocked = false;
  var breakHeld = false, placeHeld = false;

  function buildUI() {
    if (ui) return;
    var css = document.createElement('style');
    css.textContent = [
      '#ch-input-ui{position:fixed;inset:0;z-index:2147483000;display:none;pointer-events:none;touch-action:none}',
      '#ch-input-ui.on{display:block}',
      '#ch-stick-zone{position:absolute;left:0;bottom:132px;width:44%;height:38%;pointer-events:auto;touch-action:none}',
      '#ch-stick-base{position:absolute;width:116px;height:116px;margin:-58px 0 0 -58px;border-radius:50%;background:rgba(10,13,16,.35);border:1px solid rgba(255,255,255,.25);display:none}',
      '#ch-stick-knob{position:absolute;width:52px;height:52px;margin:-26px 0 0 -26px;border-radius:50%;background:rgba(255,255,255,.28);border:1px solid rgba(255,255,255,.45);display:none}',
      '.ch-btn{position:absolute;pointer-events:auto;touch-action:none;display:flex;align-items:center;justify-content:center;border-radius:50%;background:rgba(10,13,16,.46);border:1px solid rgba(255,255,255,.25);color:#fff;font-size:26px;user-select:none;-webkit-user-select:none;backdrop-filter:blur(9px)}',
      '.ch-btn.big{width:68px;height:68px}.ch-btn.med{width:58px;height:58px}.ch-btn.small{width:46px;height:46px;font-size:19px}',
      '.ch-btn.held{background:rgba(255,255,255,.30)}',
      '#ch-btn-jump{right:16px;bottom:200px}#ch-btn-attack{right:100px;bottom:176px}#ch-btn-use{right:100px;bottom:248px}',
      '#ch-btn-down{right:16px;bottom:288px;display:none}',
      '#ch-btn-interact{right:16px;bottom:118px;width:52px;height:52px;font-size:22px}',
      '#ch-btn-pause{right:14px;top:46px}#ch-btn-hide{right:70px;top:46px}',
      '#ch-input-dot{position:fixed;right:14px;top:46px;width:34px;height:34px;border-radius:50%;background:rgba(10,13,16,.5);border:1px solid rgba(255,255,255,.3);color:#fff;font-size:15px;display:none;align-items:center;justify-content:center;z-index:2147483000;pointer-events:auto}',
      '#ch-input-toast{position:fixed;left:50%;top:12%;transform:translateX(-50%);padding:8px 18px;border-radius:20px;background:rgba(10,13,16,.8);border:1px solid rgba(255,255,255,.25);color:#fff;font-size:13px;z-index:2147483001;opacity:0;transition:opacity .3s;pointer-events:none;white-space:nowrap}'
    ].join('\n');
    document.head.appendChild(css);
    ui = document.createElement('div');
    ui.id = 'ch-input-ui';
    ui.innerHTML = '<div id="ch-stick-zone"></div><div id="ch-stick-base"></div><div id="ch-stick-knob"></div>'
      + '<div class="ch-btn big" id="ch-btn-jump">\u25B2</div>'
      + '<div class="ch-btn med" id="ch-btn-attack">\u26CF</div>'
      + '<div class="ch-btn med" id="ch-btn-use">\u25A6</div>'
      + '<div class="ch-btn med" id="ch-btn-down">\u25BC</div>'
      + '<div class="ch-btn med" id="ch-btn-interact">\u25A4</div>'
      + '<div class="ch-btn small" id="ch-btn-pause">\u275A\u275A</div>'
      + '<div class="ch-btn small" id="ch-btn-hide">\u2014</div>';
    document.body.appendChild(ui);
    var dot = document.createElement('div');
    dot.id = 'ch-input-dot';
    dot.textContent = '\u25CB';
    document.body.appendChild(dot);
    dot.addEventListener('touchstart', function (e) { e.preventDefault(); e.stopPropagation(); setShown(true); }, { passive: false });
    dot.addEventListener('click', function () { setShown(true); });
    stickZone = ui.querySelector('#ch-stick-zone');
    baseEl = ui.querySelector('#ch-stick-base');
    knobEl = ui.querySelector('#ch-stick-knob');
    downBtn = ui.querySelector('#ch-btn-down');
    bindStick(stickZone);
    holdBtn(ui.querySelector('#ch-btn-jump'), KEYMAP.jump, null);
    holdBtn(ui.querySelector('#ch-btn-attack'), null, 'left');
    holdBtn(ui.querySelector('#ch-btn-use'), null, 'right');
    holdBtn(ui.querySelector('#ch-btn-interact'), KEYMAP.interact, null);
    holdBtn(ui.querySelector('#ch-btn-down'), KEYMAP.crouch || 'ControlLeft', null);
    tapBtn(ui.querySelector('#ch-btn-pause'), function () { keyEvent('keydown', KEYMAP.pause); keyEvent('keyup', KEYMAP.pause); });
    tapBtn(ui.querySelector('#ch-btn-hide'), function () { setShown(false); });
    bindLook();
  }
  function setShown(on) {
    if (!ui) return;
    hiddenByUser = !on;
    shown = on;
    ui.classList.toggle('on', on);
    var dot = document.getElementById('ch-input-dot');
    if (dot) dot.style.display = on ? 'none' : 'flex';
    if (!on) releaseAll();
  }
  function refresh() {
    if (!ui) return;
    var show = seen && !hiddenByUser;
    if (show !== shown) setShown(show);
  }
  function releaseAll() {
    stickId = null; lookId = null;
    stickVec.x = 0; stickVec.y = 0;
    if (baseEl) baseEl.style.display = 'none';
    if (knobEl) knobEl.style.display = 'none';
    syncKeys(stickKeys, new Set());
    if (breakHeld) { breakHeld = false; mouseEvent('mouseup', { x: cursor.x, y: cursor.y, button: 0 }); }
    if (placeHeld) { placeHeld = false; mouseEvent('mouseup', { x: cursor.x, y: cursor.y, button: 2 }); }
  }
  function holdBtn(el, code, mouse) {
    el.addEventListener('touchstart', function (e) {
      e.preventDefault(); e.stopPropagation(); noteTouch();
      el.classList.add('held');
      if (code) keyEvent('keydown', code);
      if (mouse === 'left') { breakHeld = true; mouseEvent('mousedown', { x: cursor.x, y: cursor.y, button: 0, buttons: 1 }); }
      if (mouse === 'right') { placeHeld = true; mouseEvent('mousedown', { x: cursor.x, y: cursor.y, button: 2, buttons: 2 }); }
    }, { passive: false });
    var end = function (e) {
      e.preventDefault(); el.classList.remove('held');
      if (code) keyEvent('keyup', code);
      if (mouse === 'left' && breakHeld) { breakHeld = false; mouseEvent('mouseup', { x: cursor.x, y: cursor.y, button: 0 }); mouseEvent('click', { x: cursor.x, y: cursor.y, button: 0 }); }
      if (mouse === 'right' && placeHeld) { placeHeld = false; mouseEvent('mouseup', { x: cursor.x, y: cursor.y, button: 2 }); mouseEvent('click', { x: cursor.x, y: cursor.y, button: 2 }); }
    };
    el.addEventListener('touchend', end, { passive: false });
    el.addEventListener('touchcancel', end, { passive: false });
  }
  function tapBtn(el, fn) {
    el.addEventListener('touchstart', function (e) { e.preventDefault(); e.stopPropagation(); noteTouch(); fn(); }, { passive: false });
  }
  function bindStick(zone) {
    zone.addEventListener('touchstart', function (e) {
      e.preventDefault(); noteTouch();
      var t = e.changedTouches[0];
      if (stickId !== null) return;
      stickId = t.identifier; stickOX = t.clientX; stickOY = t.clientY;
      baseEl.style.left = t.clientX + 'px'; baseEl.style.top = t.clientY + 'px';
      knobEl.style.left = t.clientX + 'px'; knobEl.style.top = t.clientY + 'px';
      knobEl.style.transform = 'translate(0px, 0px)';
      baseEl.style.display = 'block'; knobEl.style.display = 'block';
    }, { passive: false });
    zone.addEventListener('touchmove', function (e) {
      e.preventDefault();
      for (var i = 0; i < e.changedTouches.length; i++) {
        var t = e.changedTouches[i];
        if (t.identifier !== stickId) continue;
        var dx = t.clientX - stickOX, dy = t.clientY - stickOY;
        var len = Math.hypot(dx, dy), max = 52;
        if (len > max) { dx = dx / len * max; dy = dy / len * max; }
        stickVec.x = dx / max; stickVec.y = dy / max;
        knobEl.style.transform = 'translate(' + dx + 'px,' + dy + 'px)';
        keysFromVector(stickVec.x, stickVec.y, stickKeys);
      }
    }, { passive: false });
    var end = function (e) {
      for (var i = 0; i < e.changedTouches.length; i++) {
        if (e.changedTouches[i].identifier !== stickId) continue;
        stickId = null; stickVec.x = 0; stickVec.y = 0;
        baseEl.style.display = 'none'; knobEl.style.display = 'none';
        keysFromVector(0, 0, stickKeys);
      }
    };
    zone.addEventListener('touchend', end, { passive: false });
    zone.addEventListener('touchcancel', end, { passive: false });
  }
  function bindLook() {
    var canvas = findCanvas();
    if (!canvas) {
      /* the game may create its canvas later: retry for a few seconds */
      var tries = 0;
      var timer = setInterval(function () {
        canvas = findCanvas();
        if (canvas || ++tries > 20) { clearInterval(timer); if (canvas) bindLook(); }
      }, 250);
      return;
    }
    try { canvas.style.touchAction = 'none'; } catch (e) {}
    canvas.addEventListener('touchstart', function (e) {
      e.preventDefault(); noteTouch();
      var t = e.changedTouches[0];
      if (lookId !== null) return;
      lookId = t.identifier;
      lookLX = t.clientX; lookLY = t.clientY;
      lookSX = t.clientX; lookSY = t.clientY;
      lookT0 = Date.now(); lookDragged = false;
    }, { passive: false });
    canvas.addEventListener('touchmove', function (e) {
      e.preventDefault();
      for (var i = 0; i < e.changedTouches.length; i++) {
        var t = e.changedTouches[i];
        if (t.identifier !== lookId) continue;
        var dx = t.clientX - lookLX, dy = t.clientY - lookLY;
        lookLX = t.clientX; lookLY = t.clientY;
        if (!lookDragged && Math.hypot(t.clientX - lookSX, t.clientY - lookSY) > 12) {
          lookDragged = true;
          if (!lookLocked) { lockPointer(); lookLocked = true; }
        }
        if (lookDragged) look(dx * 1.6 * LOOK_SCALE, dy * 1.6 * LOOK_SCALE);
      }
    }, { passive: false });
    var end = function (e) {
      for (var i = 0; i < e.changedTouches.length; i++) {
        var t = e.changedTouches[i];
        if (t.identifier !== lookId) continue;
        lookId = null;
        /* short tap without drag = a mouse click at that point */
        if (!lookDragged && Date.now() - lookT0 < 350) {
          moveCursor(t.clientX - cursor.x, t.clientY - cursor.y);
          mouseEvent('mousedown', { x: t.clientX, y: t.clientY, button: 0, buttons: 1 });
          mouseEvent('mouseup', { x: t.clientX, y: t.clientY, button: 0 });
          mouseEvent('click', { x: t.clientX, y: t.clientY, button: 0 });
        }
      }
    };
    canvas.addEventListener('touchend', end, { passive: false });
    canvas.addEventListener('touchcancel', end, { passive: false });
  }
  function noteTouch() {
    if (!seen) { seen = true; buildUI(); }
    refresh();
  }
  function toast(msg) {
    var t = document.getElementById('ch-input-toast');
    if (!t) {
      t = document.createElement('div');
      t.id = 'ch-input-toast';
      document.body.appendChild(t);
    }
    t.textContent = msg;
    t.style.opacity = '1';
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.style.opacity = '0'; }, 2500);
  }

  /* ---- gamepad ---- */
  var padKeys = new Set(), padPrev = null, padMouseL = false, padMouseR = false;
  var padRaf = 0, padLast = 0, padLookUsed = false;
  function padAction(name) {
    if (name === 'attack') return 'mouse-left';
    if (name === 'use') return 'mouse-right';
    if (KEYMAP[name]) return KEYMAP[name];
    return name; /* a raw key code like 'KeyR' or 'Tab' */
  }
  function padButton(i, gp) { return !!(gp.buttons[i] && gp.buttons[i].pressed); }
  function padValue(i, gp) { return gp.buttons[i] ? (gp.buttons[i].value || 0) : 0; }
  var PAD_BUTTONS = { a: 0, b: 1, x: 2, y: 3, lb: 4, rb: 5, lt: 6, rt: 7, back: 8, start: 9, l3: 10, r3: 11 };
  function pollPad(now) {
    var dt = Math.min(0.05, Math.max(0.001, (now - padLast) / 1000));
    padLast = now;
    var gp = null;
    try {
      var pads = navigator.getGamepads ? navigator.getGamepads() : [];
      for (var i = 0; i < pads.length; i++) { if (pads[i] && pads[i].connected) { gp = pads[i]; break; } }
    } catch (e) {}
    if (!gp) {
      syncKeys(padKeys, new Set());
      if (padMouseL) { padMouseL = false; mouseEvent('mouseup', { x: cursor.x, y: cursor.y, button: 0 }); }
      if (padMouseR) { padMouseR = false; mouseEvent('mouseup', { x: cursor.x, y: cursor.y, button: 2 }); }
      padPrev = null; padRaf = 0; return;
    }
    var dz = function (v) { return Math.abs(v) < DEADZONE ? 0 : v; };
    var ax = dz(gp.axes[0] || 0), ay = dz(gp.axes[1] || 0);
    var rx = dz(gp.axes[2] || 0), ry = dz(gp.axes[3] || 0);
    var desired = new Set();
    var addAction = function (name) {
      var a = padAction(name);
      if (a === 'mouse-left' || a === 'mouse-right') return;
      desired.add(a);
    };
    if (ay < -0.25) desired.add(KEYMAP.up);
    if (ay > 0.25) desired.add(KEYMAP.down);
    if (ax < -0.25) desired.add(KEYMAP.left);
    if (ax > 0.25) desired.add(KEYMAP.right);
    if (Math.hypot(ax, ay) > 0.92) desired.add(KEYMAP.sprint);
    var btn = function (name) { return padButton(PAD_BUTTONS[name], gp); };
    var val = function (name) { return padValue(PAD_BUTTONS[name], gp); };
    ['a', 'b', 'x', 'y', 'lb', 'rb', 'back', 'l3', 'r3'].forEach(function (name) {
      if (btn(name)) addAction(PADMAP[name]);
    });
    if (btn('start')) { /* pause is sent once on the edge below, not held */ }
    syncKeys(padKeys, desired);
    if (rx || ry) {
      if (!padLookUsed) { padLookUsed = true; lockPointer(); }
      look(rx * 1150 * dt * LOOK_SCALE, ry * 1150 * dt * LOOK_SCALE);
    }
    var breaking = val('rt') > 0.3 || btn('x');
    var placing = val('lt') > 0.3 || btn('b');
    if (breaking && !padMouseL) { padMouseL = true; mouseEvent('mousedown', { x: cursor.x, y: cursor.y, button: 0, buttons: 1 }); }
    else if (!breaking && padMouseL) { padMouseL = false; mouseEvent('mouseup', { x: cursor.x, y: cursor.y, button: 0 }); mouseEvent('click', { x: cursor.x, y: cursor.y, button: 0 }); }
    if (placing && !padMouseR) { padMouseR = true; mouseEvent('mousedown', { x: cursor.x, y: cursor.y, button: 2, buttons: 2 }); }
    else if (!placing && padMouseR) { padMouseR = false; mouseEvent('mouseup', { x: cursor.x, y: cursor.y, button: 2 }); mouseEvent('click', { x: cursor.x, y: cursor.y, button: 2 }); }
    var prev = padPrev || [];
    var edge = function (name) { return btn(name) && !prev[PAD_BUTTONS[name]]; };
    if (edge('start')) { keyEvent('keydown', KEYMAP.pause); keyEvent('keyup', KEYMAP.pause); }
    padPrev = Array.prototype.map.call(gp.buttons, function (b) { return !!b.pressed; });
    padRaf = requestAnimationFrame(pollPad);
  }
  function onPadConnected(e) {
    try {
      var id = (e.gamepad && e.gamepad.id) || '';
      toast('\u624B\u67C4\u5DF2\u8FDE\u63A5' + (id ? ' \u00B7 ' + id.slice(0, 28) : ''));
    } catch (err) {}
    if (!padRaf) { padLast = performance.now(); padRaf = requestAnimationFrame(pollPad); }
  }
  function onPadDisconnected() {
    padPrev = null;
    if (!padRaf) return;
    /* the next poll notices the pad is gone and stops itself */
  }

  /* ---- boot ---- */
  function boot() {
    if (TOUCH_ON) {
      document.addEventListener('touchstart', function () { noteTouch(); }, { once: true, passive: true });
      document.addEventListener('visibilitychange', function () { if (document.hidden) releaseAll(); });
      window.addEventListener('blur', function () { releaseAll(); });
      if (('ontouchstart' in window) || (navigator.maxTouchPoints > 0)) {
        /* touch-capable: build the UI now so the first tap is instant */
        buildUI();
      }
    }
    if (PAD_ON && typeof navigator !== 'undefined' && navigator.getGamepads) {
      window.addEventListener('gamepadconnected', onPadConnected);
      window.addEventListener('gamepaddisconnected', onPadDisconnected);
      try {
        var pads = navigator.getGamepads();
        for (var i = 0; i < pads.length; i++) {
          if (pads[i] && pads[i].connected) { onPadConnected({ gamepad: pads[i] }); break; }
        }
      } catch (e) {}
    }
    /* expose a handle: games (or the runner) can hide/disable the layer */
    window.CanghaiInput = window.CanghaiInput || {};
    window.CanghaiInput.shim = {
      show: function () { buildUI(); setShown(true); },
      hide: function () { setShown(false); },
      disable: function () { setShown(false); releaseAll(); hiddenByUser = true; }
    };
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
