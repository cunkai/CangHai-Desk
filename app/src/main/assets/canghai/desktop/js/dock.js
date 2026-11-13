/* ========================================================
 * 沧海系统桌面 - Dock 交互桥接与启动台原点
 * ======================================================== */
function chPushDockRunState(){
  var map = {};
  if(typeof openWins !== 'undefined'){
    Object.keys(openWins).forEach(function(k){
      var w = openWins[k];
      map[k] = w._min ? 'minned' : 'running';
    });
  }

  try{
    if(typeof Canghai !== 'undefined' && Canghai && Canghai.setDockRunState){
      var payload = {
        runState: map,
        dockPins: (typeof dockPins !== 'undefined' ? dockPins.slice() : []),
        recentApps: (typeof recentApps !== 'undefined' ? recentApps.slice(0, 2) : [])
      };
      Canghai.setDockRunState(JSON.stringify(payload));
    }
  }catch(e){}
}
function pinToDock(appId){
  if(typeof dockPins === 'undefined') return;
  if(dockPins.indexOf(appId)>=0){ toast('「'+appById(appId).name+'」已经在 Dock 栏了'); return; }
  dockPins.push(appId);
  deskSaveSoon();
  try{
    if(typeof Canghai !== 'undefined' && Canghai && Canghai.setDockPins){
      Canghai.setDockPins(JSON.stringify(dockPins));
    }
  }catch(e){}
  chPushDockRunState();
  toast('「'+appById(appId).name+'」已固定到 Dock 栏');
}
function unpinFromDock(appId){
  if(typeof dockPins === 'undefined' || dockPins.indexOf(appId)<0) return;
  dockPins = dockPins.filter(function(x){ return x!==appId; });
  deskSaveSoon();
  try{
    if(typeof Canghai !== 'undefined' && Canghai && Canghai.setDockPins){
      Canghai.setDockPins(JSON.stringify(dockPins));
    }
  }catch(e){}
  chPushDockRunState();
  toast('已从 Dock 栏移除「'+appById(appId).name+'」');
}
function chSetDockAutoHide(v){
  if(typeof state !== 'undefined') state.dockAutoHide = !!v;
  try{ if(typeof Canghai !== 'undefined' && Canghai && Canghai.setDockAutoHide) Canghai.setDockAutoHide(!!v); }catch(e){}
  var sw = document.getElementById('ccHide');
  if(sw) sw.classList.toggle('on', !!v);
}
function chSetWinFullscreen(on){
  document.documentElement.classList.toggle('winFullscreen', !!on);
  try{ if(typeof Canghai !== 'undefined' && Canghai && Canghai.setDockFullscreen) Canghai.setDockFullscreen(!!on); }catch(e){}
}
function layoutDock(){
  if(typeof computeGrid === 'function') computeGrid();
}
function renderDock(){}
function refreshDockVis(){}
function autoScaleDock(){}
function pointInDock(){ return false; }
function openLaunchpad(){
  try{ if(typeof Canghai !== 'undefined' && Canghai && Canghai.openLaunchpad) Canghai.openLaunchpad(); }catch(e){}
}
function closeLaunchpad(){}
function toggleLaunchpad(){ openLaunchpad(); }
function lpIsOpen(){ return false; }
