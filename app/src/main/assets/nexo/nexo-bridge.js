(() => {
  "use strict";
  const port = globalThis.__nexoShield;
  if (!port || typeof port.postMessage !== "function") return;
  const send = payload => { try { port.postMessage(JSON.stringify({ id: 0, kind: "bridge", payload })); return true; } catch (_) { return false; } };
  let pipVideo = null, pip = false, captions = false, track = null, trackIds = new WeakMap(), nextId = 1;
  const captionSelectors = [".ytp-caption-segment", ".player-timedtext-text-container", ".vjs-text-track-display", ".jw-text-track-cue", ".shaka-text-container", ".plyr__caption"].join(",");
  const videos = () => Array.from(document.querySelectorAll("video"));
  const bestVideo = () => videos().sort((a,b) => (b.videoWidth*b.videoHeight || b.clientWidth*b.clientHeight) - (a.videoWidth*a.videoHeight || a.clientWidth*a.clientHeight))[0] || null;
  const rect = v => { try { const r=v.getBoundingClientRect(), d=devicePixelRatio||1; return {x:Math.round(r.left*d),y:Math.round(r.top*d),w:Math.round(r.width*d),h:Math.round(r.height*d)}; } catch (_) { return null; } };
  function report() { const v=pipVideo||bestVideo(); const r=v&&rect(v); send({type:"video_state",present:!!v,playing:!!v&&!v.paused&&!v.ended,width:v? v.videoWidth||v.clientWidth:0,height:v? v.videoHeight||v.clientHeight:0,rx:r?.x||0,ry:r?.y||0,rw:r?.w||0,rh:r?.h||0}); }
  function source() { return Array.from(document.querySelectorAll("video")); }
  function textCues(v) { const list=[]; try { for(const t of v.textTracks||[]) if(t.kind==="subtitles"||t.kind==="captions") { if(!track) {track=t; try{t.mode="hidden";}catch(_){}}; for(const c of t.cues||[]) { if(!trackIds.has(c)) trackIds.set(c,nextId++); list.push({i:trackIds.get(c),s:c.startTime,e:c.endTime,t:String(c.text||"").replace(/<[^>]*>/g,"").trim().slice(0,300)}); } } } catch (_) {} return list; }
  function captionsTick() { if(!captions) return; const v=pipVideo||bestVideo(); if(!v) return; const cues=textCues(v); if(cues.length) { send({type:"captions_track",source:"track",lang:track?.language||""}); send({type:"captions_cues",cues:cues.slice(0,3000)}); const now=v.currentTime, ids=cues.filter(c=>c.s<=now&&c.e>now).slice(0,4).map(c=>c.i); send({type:"captions_active",ids}); } const n=document.querySelector(captionSelectors); if(n) send({type:"captions_track",source:"dom",lang:""}),send({type:"captions_text",text:n.textContent.trim().slice(0,400)}); }
  function command(m) { if(m.type==="pip_mode") { pip=!!m.active; pipVideo=pip?(pipVideo||bestVideo()):null; document.documentElement.toggleAttribute("data-nexo-pip-active",pip); report(); } if(m.type==="pip_command") { const v=pipVideo||bestVideo(); if(!v)return; if(m.command==="pause")v.pause(); else if(m.command==="play")v.play().catch(()=>{}); else if(m.command==="toggle")(v.paused?v.play():v.pause()); else if(m.command==="seek_backward")v.currentTime=Math.max(0,v.currentTime-10); else if(m.command==="seek_forward")v.currentTime+=10; report(); } if(m.type==="captions") { captions=!!m.active; if(captions){pipVideo=bestVideo(); captionsTick();} } if(m.type==="lifecycle"&&m.state==="foreground")report(); }
  port.addEventListener("message", e => { try { const m=JSON.parse(e.data); if(m.event==="native"&&m.message)command(m.message); } catch (_) {} });
  ["play","pause","playing","ended","loadedmetadata","canplay","emptied"].forEach(e=>document.addEventListener(e,report,true));
  window.addEventListener("scroll",report,{passive:true}); setInterval(()=>{report();captionsTick();},1000); report();
})();
