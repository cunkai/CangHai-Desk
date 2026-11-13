/* Canghai achievements — system-level engine, one store for every game/app.
 *
 * Contract (user-set, 2026-11-13):
 * - Games/apps REPORT events (stats, unlocks). They do NOT build their own
 *   achievement list UI and must NOT have an achievement entry (button/panel)
 *   inside the game. The Achievements Center app is the single place to
 *   browse achievements; the desktop shell owns system notifications.
 * - Games keep only the unlock moment: a notification toast + an effect and
 *   a sound that fit the game's own style (ink games stay gentle).
 * - One store, shared by every app on the same origin:
 *   localStorage['canghai.achievements.v1'] = {v:1, apps:{appId:{
 *     name, stats:{statId:number}, days:['YYYY-MM-DD',...],
 *     unlocked:{achId:{t:timestamp, legacy?:true}}, defs:[...]}}}
 *   Definitions are persisted with the store so the Center can render an app
 *   whose code never ran in that page. Stats never expire and are never
 *   reset by the engine; schema changes bump the key version with migration.
 * - Standalone (plain browser, no shell): the local store is authoritative.
 *   Unlocks are also broadcast via postMessage ({canghai:'ach-unlocked'})
 *   and a 'canghai:ach' CustomEvent so a shell can show system notices.
 * - If localStorage is unavailable, an in-memory store is used (achievements
 *   still work for the session, nothing throws).
 */
(function(){
'use strict';
if(window.CHAch)return;
var KEY='canghai.achievements.v1';
var storageOK=true, memFallback=null;
function load(){
  if(!storageOK)return memFallback;
  try{
    var raw=localStorage.getItem(KEY);
    var s=raw?JSON.parse(raw):null;
    if(!s||typeof s!=='object'||s.v!==1||!s.apps||typeof s.apps!=='object')s={v:1,apps:{}};
    return s;
  }catch(e){storageOK=false;if(!memFallback)memFallback={v:1,apps:{}};return memFallback;}
}
function save(s){
  if(!storageOK){memFallback=s;return;}
  try{localStorage.setItem(KEY,JSON.stringify(s));}
  catch(e){storageOK=false;memFallback=s;}
}
function ensureApp(s,appId,name){
  var a=s.apps[appId];
  if(!a||typeof a!=='object')a=s.apps[appId]={name:name||appId,stats:{},days:[],unlocked:{},defs:[]};
  if(name)a.name=name;
  if(!a.stats||typeof a.stats!=='object')a.stats={};
  if(!Array.isArray(a.days))a.days=[];
  if(!a.unlocked||typeof a.unlocked!=='object')a.unlocked={};
  if(!Array.isArray(a.defs))a.defs=[];
  return a;
}
var defs={};
var listeners=[];
function todayStr(){var d=new Date();return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');}
function notify(appId,def){
  var payload={canghai:'ach-unlocked',app:appId,ach:def.id,name:def.name,icon:def.icon||''};
  try{if(window.parent&&window.parent!==window)window.parent.postMessage(payload,'*');}catch(e){}
  try{if(typeof CustomEvent==='function')window.dispatchEvent(new CustomEvent('canghai:ach',{detail:payload}));}catch(e){}
  listeners.forEach(function(cb){try{cb(appId,def);}catch(e){}});
}
function checkThresholds(s,appId){
  var d=defs[appId];if(!d)return[];
  var a=s.apps[appId];if(!a)return[];
  var fresh=[];
  d.list.forEach(function(def){
    if(!def.stat||def.target==null||a.unlocked[def.id])return;
    if((a.stats[def.stat]||0)>=def.target){a.unlocked[def.id]={t:Date.now()};fresh.push(def);}
  });
  return fresh;
}
function commit(s,appId){
  var fresh=checkThresholds(s,appId);
  save(s);
  fresh.forEach(function(def){notify(appId,def);});
  return fresh;
}
window.CHAch={
  /* list: [{id, name, desc, icon, stat?, target?, hidden?}].
   * Achievements with {stat, target} unlock automatically when the lifetime
   * stat reaches target. Others unlock only via unlock(). Returns defs newly
   * unlocked by already-stored progress (e.g. right after import). */
  register:function(appId,name,list){
    var clean=(list||[]).map(function(d){return{id:d.id,name:d.name,desc:d.desc||'',icon:d.icon||'',stat:d.stat||null,target:(d.target==null?null:d.target),hidden:!!d.hidden};});
    defs[appId]={name:name,list:clean,byId:{}};
    clean.forEach(function(d){defs[appId].byId[d.id]=d;});
    var s=load();var a=ensureApp(s,appId,name);a.defs=clean;
    return commit(s,appId);
  },
  stat:function(appId,statId,delta){
    var s=load();var a=ensureApp(s,appId);
    a.stats[statId]=(a.stats[statId]||0)+(delta||0);
    return commit(s,appId);
  },
  setStat:function(appId,statId,value){
    var s=load();var a=ensureApp(s,appId);
    a.stats[statId]=value;
    return commit(s,appId);
  },
  maxStat:function(appId,statId,value){
    var s=load();var a=ensureApp(s,appId);
    if(value>(a.stats[statId]||0))a.stats[statId]=value;
    return commit(s,appId);
  },
  unlock:function(appId,achId){
    var d=defs[appId];var def=d&&d.byId[achId];
    if(!def)return null;
    var s=load();var a=ensureApp(s,appId);
    if(a.unlocked[achId])return null;
    a.unlocked[achId]={t:Date.now()};save(s);
    notify(appId,def);
    return def;
  },
  markDay:function(appId){
    var s=load();var a=ensureApp(s,appId);
    var t=todayStr();
    if(a.days.indexOf(t)===-1){a.days.push(t);if(a.days.length>4000)a.days.splice(0,a.days.length-4000);}
    a.stats.days=a.days.length;
    return commit(s,appId);
  },
  /* Merge legacy per-game saves: stats take the max, unlocked ids are kept
   * (marked legacy). Thresholds are re-checked so earned tiers unlock. */
  importData:function(appId,stats,unlockedIds){
    var s=load();var a=ensureApp(s,appId);
    Object.keys(stats||{}).forEach(function(k){var v=stats[k]||0;if(v>(a.stats[k]||0))a.stats[k]=v;});
    (unlockedIds||[]).forEach(function(id){if(!a.unlocked[id])a.unlocked[id]={t:Date.now(),legacy:true};});
    return commit(s,appId);
  },
  onUnlock:function(cb){listeners.push(cb);},
  getState:function(appId){var s=load();var a=ensureApp(s,appId);return JSON.parse(JSON.stringify(a));},
  getDefs:function(appId){var d=defs[appId];return d?JSON.parse(JSON.stringify(d.list)):[];},
  getAll:function(){
    var s=load();var out={};
    Object.keys(s.apps).forEach(function(id){out[id]=JSON.parse(JSON.stringify(s.apps[id]));});
    return out;
  },
  resetApp:function(appId){var s=load();delete s.apps[appId];save(s);}
};
})();
