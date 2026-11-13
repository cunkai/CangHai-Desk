/* 沧海动态壁纸（three.js 视觉层）
   只负责好看：渲染在 #wallpaper 里的一块 canvas 上，CSS 渐变打底、SVG 场景隐藏。
   性能规矩与 CSS 壁纸一致：拖图标 / 最大化窗口 / 前台有 3D 软件时停 render，
   页面隐藏时停 rAF，减动效时只画一帧，DPR 封顶 1.5；全用无光照材质，
   不用外部纹理（月光晕是程序生成的 CanvasTexture）。
   three 运行时（共享 three_runtime.js）首次选用动态壁纸时才懒加载，平时不占启动时间。 */
(function(){
'use strict';

var THREE_URL = '../shared/three_runtime.js';
var DPR_CAP = 1.5;

/* build(T, api) 返回 {update:function(t, dt, ptr, view)}；
   api={scene, camera}；view={hw, hh} 为 z=0 处半宽/半高，随尺寸变；
   ptr={x, y} 为平滑后的指针位置（-0.5 ~ 0.5）。 */
var DEFS = [
{ id:'wp3d-parallax', name:'星海视差', base:'wp-sea',
  css:'linear-gradient(160deg,#041f3a,#7a5c1e)',
  build: function(T, api){
    var scene = api.scene;
    var N = 1400, pos = new Float32Array(N*3), col = new Float32Array(N*3);
    var palette = [new T.Color(0x9db4ff), new T.Color(0xffffff), new T.Color(0xecd28f), new T.Color(0x6f86c9)];
    for(var i=0;i<N;i++){
      pos[i*3]=(Math.random()-.5)*20; pos[i*3+1]=(Math.random()-.5)*12; pos[i*3+2]=(Math.random()-.5)*8-1;
      var c = palette[(Math.random()*palette.length)|0];
      col[i*3]=c.r; col[i*3+1]=c.g; col[i*3+2]=c.b;
    }
    var g = new T.BufferGeometry();
    g.setAttribute('position', new T.BufferAttribute(pos,3));
    g.setAttribute('color', new T.BufferAttribute(col,3));
    var stars = new T.Points(g, new T.PointsMaterial({size:.055, map:dotTexture(T), vertexColors:true, transparent:true, opacity:.9, depthWrite:false}));
    var ico = new T.Mesh(new T.IcosahedronGeometry(2.2,1), new T.MeshBasicMaterial({color:0xc9a85c, wireframe:true, transparent:true, opacity:.3}));
    var grid = new T.GridHelper(40,40,0x2a4a7a,0x1a3358);
    grid.position.y=-3.2; grid.material.transparent=true; grid.material.opacity=.22;
    var ring = new T.Mesh(new T.RingGeometry(3.3,3.34,96), new T.MeshBasicMaterial({color:0xecd28f, transparent:true, opacity:.18, side:T.DoubleSide, depthWrite:false}));
    ring.rotation.x = Math.PI/2.6; ring.rotation.y = .3;
    scene.add(stars, ico, grid, ring);
    return { update: function(t, dt, ptr, view){
      stars.rotation.y = t*.02; ico.rotation.set(t*.1, t*.15, 0); ring.rotation.z = t*.05;
      var cam = api.camera;
      cam.position.x += (ptr.x*2 - cam.position.x)*.045;
      cam.position.y += (-ptr.y*1.5 - cam.position.y)*.045;
      cam.lookAt(0,0,0);
    }};
  }},

{ id:'wp3d-moon', name:'海上生明月', base:'wp-aurora',
  css:'linear-gradient(160deg,#010610,#8a6a25)',
  build: function(T, api){
    var scene = api.scene;
    /* 金色弯月：开口向右像 C，与品牌月亮同款 */
    var shape = new T.Shape();
    shape.absarc(0, 0, 1.1, Math.PI/2, Math.PI*3/2, false);
    shape.quadraticCurveTo(-0.9, 0, 0, 1.1);
    var moon = new T.Mesh(new T.ShapeGeometry(shape, 24), new T.MeshBasicMaterial({color:0xecd28f, transparent:true, opacity:.96, side:T.DoubleSide}));
    /* 月光晕：程序生成的径向渐变 */
    var cv = document.createElement('canvas'); cv.width = cv.height = 256;
    var cx = cv.getContext('2d');
    var grd = cx.createRadialGradient(128,128,10,128,128,128);
    grd.addColorStop(0,'rgba(236,210,143,.5)'); grd.addColorStop(.4,'rgba(236,210,143,.18)'); grd.addColorStop(1,'rgba(236,210,143,0)');
    cx.fillStyle = grd; cx.fillRect(0,0,256,256);
    var tex = new T.CanvasTexture(cv);
    if(T.SRGBColorSpace) tex.colorSpace = T.SRGBColorSpace;
    var glow = new T.Sprite(new T.SpriteMaterial({map:tex, transparent:true, blending:T.AdditiveBlending, depthWrite:false, opacity:.9}));
    glow.scale.set(5.2,5.2,1);
    scene.add(moon, glow);
    var s1 = makeStars(T, scene, 150, .05, 0xffffff, .8);
    var s2 = makeStars(T, scene, 90, .075, 0xffe9b0, .8);
    /* 海面波线 */
    var LINES = 4, SEG = 140, lines = [];
    var lineCols = [0x7dd3fc, 0x4aa8dd, 0xffffff, 0xc9a85c];
    var lineBase = [-1.7,-2.0,-2.3,-2.6], lineOp = [.32,.3,.2,.28];
    for(var l=0;l<LINES;l++){
      var lp = new Float32Array(SEG*3);
      for(var j=0;j<SEG;j++){ lp[j*3] = -12 + 24*j/(SEG-1); lp[j*3+1] = lineBase[l]; lp[j*3+2] = 0; }
      var lg = new T.BufferGeometry(); lg.setAttribute('position', new T.BufferAttribute(lp,3));
      var lm = new T.Line(lg, new T.LineBasicMaterial({color:lineCols[l], transparent:true, opacity:lineOp[l]}));
      scene.add(lm); lines.push({mesh:lm, base:lineBase[l], ph:l*1.7, sp:.5+l*.13, amp:.1+l*.03});
    }
    /* 月光碎银：月亮下几段短横线 */
    var shim = [];
    for(var k=0;k<5;k++){
      var sg = new T.BufferGeometry();
      sg.setAttribute('position', new T.BufferAttribute(new Float32Array([-0.5,0,0, 0.5,0,0]),3));
      var sm = new T.Line(sg, new T.LineBasicMaterial({color:0xffe9b0, transparent:true, opacity:.5}));
      scene.add(sm); shim.push(sm);
    }
    return { update: function(t, dt, ptr, view){
      var mx = view.hw*.58, my = view.hh*.52;
      var sc = Math.min(1, view.hw/3.2);
      moon.scale.set(sc, sc, 1); glow.scale.set(5.2*sc, 5.2*sc, 1);
      moon.position.set(mx, my, 0); glow.position.set(mx, my, -.2);
      s1.material.opacity = .55+.35*Math.sin(t*.9); s2.material.opacity = .55+.35*Math.sin(t*.9+Math.PI);
      for(var l=0;l<lines.length;l++){
        var L = lines[l], arr = L.mesh.geometry.attributes.position.array;
        for(var j=0;j<SEG;j++){
          var x = arr[j*3];
          arr[j*3+1] = L.base + Math.sin(x*.55 + t*L.sp + L.ph)*L.amp + Math.sin(x*1.4 - t*.3)*.04;
        }
        L.mesh.geometry.attributes.position.needsUpdate = true;
      }
      for(var k=0;k<shim.length;k++){
        var s = shim[k];
        s.position.set(mx-.6+k*.3, -1.55-k*.22, 0);
        s.material.opacity = .25+.35*(.5+.5*Math.sin(t*1.1+k*1.3));
        s.scale.x = .6+((k*37)%10)/10*.9;
      }
      var cam = api.camera;
      cam.position.x += (ptr.x*.7 - cam.position.x)*.04;
      cam.position.y += (-ptr.y*.5 - cam.position.y)*.04;
      cam.lookAt(0,0,0);
    }};
  }},

{ id:'wp3d-tide', name:'沧海潮汐', base:'wp-sea',
  css:'linear-gradient(160deg,#041f3a,#1a7ab8)',
  build: function(T, api){
    var scene = api.scene;
    var ribbons = [];
    var cols = [[0x0a4a7a,0x1479b8],[0x0d5a8f,0x2a9ad8],[0xc9a35c,0xffe9b0],[0x0a3a5e,0x1a7ab8],[0x062a44,0x0d5a8f]];
    var ys = [-2.5,-2.0,-1.5,-1.0,-0.55], ops=[.8,.7,.92,.6,.75];
    for(var r=0;r<5;r++){
      var geo = new T.PlaneGeometry(26, 1.1, 120, 1);
      var cnt = geo.attributes.position.count;
      var carr = new Float32Array(cnt*3);
      var cBot = new T.Color(cols[r][0]), cTop = new T.Color(cols[r][1]);
      var baseY = new Float32Array(cnt);
      for(var i=0;i<cnt;i++){
        var y = geo.attributes.position.getY(i);
        baseY[i] = y;
        var c = cBot.clone().lerp(cTop, (y + .55)/1.1);
        carr[i*3]=c.r; carr[i*3+1]=c.g; carr[i*3+2]=c.b;
      }
      geo.setAttribute('color', new T.BufferAttribute(carr,3));
      var mat = new T.MeshBasicMaterial({vertexColors:true, transparent:true, opacity:ops[r], side:T.DoubleSide, depthWrite:false});
      var mesh = new T.Mesh(geo, mat);
      mesh.position.y = ys[r]; mesh.position.z = -r*.15;
      scene.add(mesh);
      ribbons.push({mesh:mesh, baseY:baseY, ph:r*1.9, sp:.35+r*.07, amp:.22+r*.03, op:ops[r]});
    }
    makeStars(T, scene, 120, .05, 0xffffff, .6);
    return { update: function(t, dt, ptr, view){
      for(var r=0;r<ribbons.length;r++){
        var R = ribbons[r], arr = R.mesh.geometry.attributes.position.array;
        for(var i=0;i<arr.length;i+=3){
          var x = arr[i];
          arr[i+1] = R.baseY[i/3] + Math.sin(x*.45 + t*R.sp + R.ph)*R.amp + Math.sin(x*1.1 - t*.22 + R.ph)*.08;
        }
        R.mesh.geometry.attributes.position.needsUpdate = true;
        R.mesh.material.opacity = R.op*(0.85+0.15*Math.sin(t*.4+R.ph));
      }
      var cam = api.camera;
      cam.position.x += (ptr.x*.6 - cam.position.x)*.04;
      cam.position.y += (-ptr.y*.4 - cam.position.y)*.04;
      cam.lookAt(0,0,0);
    }};
  }},

{ id:'wp3d-plankton', name:'金色浮游', base:'wp-aurora',
  css:'linear-gradient(160deg,#010610,#6b5316)',
  build: function(T, api){
    var scene = api.scene;
    var N = 320, pos = new Float32Array(N*3), col = new Float32Array(N*3);
    var spd = new Float32Array(N), seed = new Float32Array(N);
    var palette = [new T.Color(0xecd28f), new T.Color(0xc9a85c), new T.Color(0xffffff), new T.Color(0x9db4ff)];
    for(var i=0;i<N;i++){
      pos[i*3]=(Math.random()-.5)*16; pos[i*3+1]=(Math.random()-.5)*9; pos[i*3+2]=(Math.random()-.5)*4-1;
      var c = palette[(Math.random()*palette.length)|0];
      col[i*3]=c.r; col[i*3+1]=c.g; col[i*3+2]=c.b;
      spd[i]=.05+Math.random()*.13; seed[i]=Math.random()*6.28;
    }
    var g = new T.BufferGeometry();
    g.setAttribute('position', new T.BufferAttribute(pos,3));
    g.setAttribute('color', new T.BufferAttribute(col,3));
    var pts = new T.Points(g, new T.PointsMaterial({size:.07, map:dotTexture(T), vertexColors:true, transparent:true, opacity:.85, depthWrite:false}));
    scene.add(pts);
    /* 顶部一道极淡的蓝光，慢呼吸，给深海一点纵深 */
    var gcv = document.createElement('canvas'); gcv.width = gcv.height = 256;
    var gcx = gcv.getContext('2d');
    var ggrd = gcx.createRadialGradient(128,128,10,128,128,128);
    ggrd.addColorStop(0,'rgba(122,168,255,.35)'); ggrd.addColorStop(.5,'rgba(122,168,255,.12)'); ggrd.addColorStop(1,'rgba(122,168,255,0)');
    gcx.fillStyle = ggrd; gcx.fillRect(0,0,256,256);
    var gtex = new T.CanvasTexture(gcv);
    if(T.SRGBColorSpace) gtex.colorSpace = T.SRGBColorSpace;
    var halo = new T.Sprite(new T.SpriteMaterial({map:gtex, transparent:true, blending:T.AdditiveBlending, depthWrite:false, opacity:.5}));
    halo.scale.set(10,10,1); halo.position.set(0, 2.6, -2);
    scene.add(halo);
    return { update: function(t, dt, ptr, view){
      var arr = pts.geometry.attributes.position.array;
      for(var i=0;i<N;i++){
        arr[i*3+1] += spd[i]*dt;
        arr[i*3] += Math.sin(t*.3+seed[i])*.0012;
        if(arr[i*3+1] > 4.6) arr[i*3+1] = -4.6;
      }
      pts.geometry.attributes.position.needsUpdate = true;
      halo.material.opacity = .45+.1*Math.sin(t*.25);
      var cam = api.camera;
      cam.position.x += (ptr.x*.5 - cam.position.x)*.04;
      cam.position.y += (-ptr.y*.35 - cam.position.y)*.04;
      cam.lookAt(0,0,0);
    }};
  }},
];

var _dotTex = null;
function dotTexture(T){
  if(_dotTex) return _dotTex;
  var cv = document.createElement('canvas'); cv.width = cv.height = 64;
  var cx = cv.getContext('2d');
  var grd = cx.createRadialGradient(32,32,2,32,32,32);
  grd.addColorStop(0,'rgba(255,255,255,1)');
  grd.addColorStop(.4,'rgba(255,255,255,.9)');
  grd.addColorStop(1,'rgba(255,255,255,0)');
  cx.fillStyle = grd; cx.fillRect(0,0,64,64);
  _dotTex = new T.CanvasTexture(cv);
  if(T.SRGBColorSpace) _dotTex.colorSpace = T.SRGBColorSpace;
  return _dotTex;
}

function makeStars(T, scene, n, size, color, opacity){
  var p = new Float32Array(n*3);
  for(var i=0;i<n;i++){
    p[i*3]=(Math.random()-.5)*22; p[i*3+1]=Math.random()*5.5-.5; p[i*3+2]=(Math.random()-.5)*4-1;
  }
  var g = new T.BufferGeometry();
  g.setAttribute('position', new T.BufferAttribute(p,3));
  var m = new T.Points(g, new T.PointsMaterial({size:size, map:dotTexture(T), color:color, transparent:true, opacity:opacity, depthWrite:false}));
  scene.add(m);
  return m;
}

function get(id){
  for(var i=0;i<DEFS.length;i++) if(DEFS[i].id===id) return DEFS[i];
  return null;
}

/* ---------- 运行时 ---------- */
var S = { loadPromise:null, renderer:null, canvas:null, camera:null, scene:null,
  active:null, built:null, raf:0, last:0, frames:0,
  ptr:{x:0, y:0, tx:0, ty:0}, view:{hw:6, hh:3.5} };

var reducedMotion = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

/* 共享运行时不挂 window.THREE，three 挂在 window.CH_MODULES['three'] 上
   （与 2048 / 跳一跳的垫片写法一致）；window.THREE 只作 r128 旧版兜底。 */
function getThree(){
  if(window.CH_MODULES && window.CH_MODULES['three']) return window.CH_MODULES['three'];
  if(window.THREE) return window.THREE;
  return null;
}

function ensureThree(){
  var T = getThree();
  if(T) return Promise.resolve(T);
  if(S.loadPromise) return S.loadPromise;
  S.loadPromise = new Promise(function(resolve, reject){
    var sc = document.createElement('script');
    sc.src = THREE_URL;
    sc.onload = function(){ var t = getThree(); if(t) resolve(t); else reject(new Error('THREE missing')); };
    sc.onerror = function(){ reject(new Error('three load failed')); };
    document.head.appendChild(sc);
  });
  S.loadPromise.catch(function(){ S.loadPromise = null; });
  return S.loadPromise;
}

function onPointer(e){
  S.ptr.tx = e.clientX/window.innerWidth - .5;
  S.ptr.ty = e.clientY/window.innerHeight - .5;
}

function resize(){
  if(!S.renderer) return;
  var wp = document.getElementById('wallpaper');
  var w = (wp && wp.clientWidth) || window.innerWidth;
  var h = (wp && wp.clientHeight) || window.innerHeight;
  S.renderer.setPixelRatio(Math.min(window.devicePixelRatio||1, DPR_CAP));
  S.renderer.setSize(w, h, false);
  S.camera.aspect = w/h; S.camera.updateProjectionMatrix();
  var hh = 6*Math.tan(S.camera.fov*Math.PI/360);
  S.view = {hw: hh*S.camera.aspect, hh: hh};
  if(S.active && reducedMotion) renderOnce();
}

function ensureRenderer(T){
  if(S.renderer) return;
  var wp = document.getElementById('wallpaper');
  var canvas = document.createElement('canvas');
  canvas.className = 'wp-gl';
  canvas.setAttribute('aria-hidden', 'true');
  wp.appendChild(canvas);
  try{
    S.renderer = new T.WebGLRenderer({canvas:canvas, antialias:false, alpha:true, powerPreference:'low-power'});
  }catch(e){
    canvas.remove();
    throw e;
  }
  S.canvas = canvas;
  S.renderer.setClearColor(0x000000, 0);
  S.renderer.setPixelRatio(Math.min(window.devicePixelRatio||1, DPR_CAP));
  S.camera = new T.PerspectiveCamera(60, 1, .1, 100);
  S.camera.position.set(0,0,6);
  resize();
  window.addEventListener('resize', resize);
  window.addEventListener('pointermove', onPointer, {passive:true});
  document.addEventListener('visibilitychange', onVisibility);
}

function disposeScene(){
  if(!S.scene) return;
  S.scene.traverse(function(o){
    if(o.geometry) o.geometry.dispose();
    if(o.material){
      var mats = Array.isArray(o.material) ? o.material : [o.material];
      mats.forEach(function(m){ if(m.map) m.map.dispose(); m.dispose(); });
    }
  });
  S.scene = null; S.built = null;
}

function paused(){
  if(document.hidden) return true;
  if(document.body.classList.contains('item-dragging')) return true;
  var h = document.documentElement;
  return h.classList.contains('hasMaxWin') || h.classList.contains('chBusy');
}

function renderOnce(){
  if(!S.renderer || !S.scene || !S.built) return;
  if(S.built.update) S.built.update(performance.now()/1000, 0, S.ptr, S.view);
  S.renderer.render(S.scene, S.camera);
  S.frames++;
}

function loop(t){
  S.raf = 0;
  if(!S.active || !S.renderer) return;
  if(!paused()){
    var dt = Math.min((t - S.last)/1000 || 0, .1); S.last = t;
    S.ptr.x += (S.ptr.tx - S.ptr.x)*.045;
    S.ptr.y += (S.ptr.ty - S.ptr.y)*.045;
    if(S.built && S.built.update) S.built.update(t/1000, dt, S.ptr, S.view);
    S.renderer.render(S.scene, S.camera);
    S.frames++;
  }
  S.raf = requestAnimationFrame(loop);
}

function startLoop(){
  if(S.raf) return;
  if(reducedMotion){ renderOnce(); return; }
  S.last = performance.now();
  S.raf = requestAnimationFrame(loop);
}

function stopLoop(){
  if(S.raf){ cancelAnimationFrame(S.raf); S.raf = 0; }
}

function onVisibility(){
  if(document.hidden) stopLoop();
  else if(S.active) startLoop();
}

function activate(id){
  var def = get(id);
  if(!def) return;
  S.active = id;
  ensureThree().then(function(T){
    if(S.active !== id) return;
    try{ ensureRenderer(T); }catch(e){ S.active = null; return; }
    disposeScene();
    S.scene = new T.Scene();
    S.camera.position.set(0,0,6);
    S.camera.lookAt(0,0,0);
    S.built = def.build(T, {scene:S.scene, camera:S.camera});
    if(S.canvas) S.canvas.style.display = '';
    startLoop();
  }).catch(function(){
    if(S.active === id){
      S.active = null;
      if(S.canvas) S.canvas.style.display = 'none';
    }
  });
}

function deactivate(){
  S.active = null;
  stopLoop();
  if(S.canvas) S.canvas.style.display = 'none';
}

window.CHWallpapers = {
  list: DEFS,
  get: get,
  is3D: function(cls){ return !!get(cls); },
  activate: activate,
  deactivate: deactivate,
  _debug: S
};

})();
