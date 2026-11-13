/* ==========================================================================
 * 沧海 · 成就系统桌面端接收器
 *
 * 作用：应用（iframe 里跑的游戏/软件）解锁成就时，成就库会往父页面广播
 *   {canghai:'ach-unlocked', app, ach, name, icon}
 * 这个文件只负责在桌面这一层接住广播、显示一条系统通知。游戏自己不弹
 * 系统通知，桌面是唯一显示成就通知的地方。
 *
 * 集成办法（只改桌面 index.html 一行，不动别的）：
 *   在 canghai/desktop/index.html 里，找到加载消息中心的那一行：
 *     <script src="js/msgcenter.js"></script>
 *   在它后面紧接着加一行：
 *     <script src="../shared/ach-shell.js"></script>
 *   放好后刷新桌面即可。不需要改任何桌面 JS，也不需要给每个游戏加代码。
 *
 * 行为：
 * - 同一个应用的同一个成就，5 秒内的重复广播只通知一次（去重）。
 * - 优先走桌面消息中心 CHMsgCenter.notify（右上角滑入 + 记历史）；
 *   如果消息中心没绑上（比如顶栏铃铛不在的桌面版本，通知不会显示），
 *   就退回桌面轻提示 toast()，保证通知一定看得见。
 * - 两个通知入口都不存在时静默不做事，绝不报错。
 * ========================================================================== */
(function(){
  'use strict';
  if(window.__chAchShell)return;
  window.__chAchShell=true;

  var DEDUP_MS=5000;
  var seen={};

  function prune(now){
    var keys=Object.keys(seen);
    if(keys.length<100)return;
    keys.forEach(function(k){ if(now-seen[k]>=DEDUP_MS)delete seen[k]; });
  }

  function appName(appId){
    try{
      if(window.CHAch){
        var st=CHAch.getState(appId);
        if(st&&st.name)return st.name;
      }
    }catch(e){}
    try{
      /* 桌面自己的应用清单（appById 找不到时会回退到第一个内置应用，
         所以必须核对 id 才用它的名字）。 */
      if(typeof appById==='function'){
        var a=appById(appId);
        if(a&&a.id===appId&&a.name)return a.name;
      }
    }catch(e){}
    return appId||'';
  }

  function show(p){
    var title=(p.icon?p.icon+' ':'')+(p.name||'');
    var body=appName(p.app)+' · 已解锁';
    var shown=false;
    try{
      if(window.CHMsgCenter&&typeof window.CHMsgCenter.notify==='function'){
        window.CHMsgCenter.notify({canghai:'notify',title:title,body:body,time:Date.now()});
        /* 消息中心没绑上时 notify 只记历史、不显示通知：看通知容器里
           有没有真的出现通知，没有就退回 toast，保证看得见。 */
        var wrap=document.getElementById('msgToastWrap');
        if(wrap&&wrap.querySelector('.ch-toast'))shown=true;
      }
    }catch(e){ shown=false; }
    if(!shown){
      try{
        if(typeof toast==='function')toast(title+' · '+body);
      }catch(e){}
    }
  }

  function onUnlock(p){
    if(!p||p.canghai!=='ach-unlocked'||!p.name)return;
    var key=(p.app||'')+'\n'+(p.ach||p.name);
    var now=Date.now();
    if(seen[key]&&now-seen[key]<DEDUP_MS)return;
    seen[key]=now;
    prune(now);
    show(p);
  }

  /* 应用 iframe 发来的 postMessage 广播（主通道）。 */
  window.addEventListener('message',function(e){
    var d=e&&e.data;
    if(!d||typeof d!=='object')return;
    try{ onUnlock(d); }catch(err){}
  });

  /* 同页直跑的游戏走 CustomEvent（备用通道，去重逻辑同一套）。 */
  window.addEventListener('canghai:ach',function(e){
    try{ onUnlock(e&&e.detail); }catch(err){}
  });
})();
