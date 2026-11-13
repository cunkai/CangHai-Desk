/* CHSynth — shared synthesized timbres for Canghai apps.
   Zero dependencies, no audio files: every tone is generated live with
   oscillators + harmonic partials + an exponential-decay envelope.
   API: play(freq, opts), setTimbre(name), getTimbre(), timbres(),
        setMuted(bool), isMuted(). Safe to inline into a single file. */
(function(g){
'use strict';
var ctx=null,master=null,muted=false,current='piano';
/* partials: [frequency ratio, relative gain]; decay in seconds;
   filter: lowpass Hz (0 = none); type: oscillator type (default sine) */
var TIMBRES={
  piano:  {partials:[[1,1],[2,.5],[3,.25],[4,.12]],decay:1.4,attack:.004,filter:0},
  marimba:{partials:[[1,1],[4,.32]],decay:.45,attack:.003,filter:3200},
  pluck:  {partials:[[1,1],[2,.6],[3,.3]],decay:.7,attack:.002,filter:0,type:'triangle'},
  bell:   {partials:[[1,1],[2.76,.5],[5.4,.28]],decay:2.2,attack:.003,filter:0},
  ep:     {partials:[[1,1],[2,.35]],decay:1.1,attack:.008,filter:0}
};
function ensure(){
  if(!ctx){
    var AC=g.AudioContext||g.webkitAudioContext;
    if(!AC)return null;
    ctx=new AC();
    master=ctx.createGain();master.gain.value=.7;master.connect(ctx.destination);
  }
  if(ctx.state==='suspended')ctx.resume();
  return ctx;
}
function play(freq,opts){
  if(muted||!freq)return;
  var c=ensure();if(!c)return;
  opts=opts||{};
  var t=TIMBRES[current]||TIMBRES.piano;
  var gain=opts.gain||.14,dec=(opts.decay||1)*t.decay,when=c.currentTime+(opts.delay||0);
  var norm=0,i;
  for(i=0;i<t.partials.length;i++)norm+=t.partials[i][1];
  var out=master;
  if(t.filter){
    var f=c.createBiquadFilter();f.type='lowpass';f.frequency.value=t.filter;
    f.connect(master);out=f;
  }
  for(i=0;i<t.partials.length;i++){
    var p=t.partials[i];
    var o=c.createOscillator();o.type=t.type||'sine';o.frequency.value=freq*p[0];
    var og=c.createGain();
    og.gain.setValueAtTime(0,when);
    og.gain.linearRampToValueAtTime(gain*p[1]/norm,when+t.attack);
    og.gain.exponentialRampToValueAtTime(.0001,when+dec);
    o.connect(og);og.connect(out);
    o.start(when);o.stop(when+dec+.05);
  }
}
function setTimbre(name){if(TIMBRES[name])current=name;return current;}
function getTimbre(){return current;}
function timbres(){return Object.keys(TIMBRES);}
function setMuted(m){muted=!!m;}
function isMuted(){return muted;}
g.CHSynth={play:play,setTimbre:setTimbre,getTimbre:getTimbre,timbres:timbres,setMuted:setMuted,isMuted:isMuted};
})(typeof window!=='undefined'?window:this);
