const {chromium}=require('playwright'),assert=require('node:assert/strict');
const reports=require('node:path').resolve(__dirname,'../app/build/reports/browser');require('node:fs').mkdirSync(reports,{recursive:true});
const root=require('node:path').resolve(__dirname,'../app/src/main/assets/extensions/phantomshield')+'/';
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.NEXO_CHROMIUM||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',route=>route.fulfill({contentType:'text/html',body:''}));
  const protectedHtml = [
   '<div id="media" class="ad-banner"><video></video></div>',
   '<div id="player" class="ad-banner"><iframe src="https://cinema.other.example/embed/abc"></iframe></div>',
   '<div id="mixed" class="ad-banner"><iframe id="inline-ad" data-ad-slot="test" src="https://ads.other.example/frame"></iframe><iframe src="/player/abc"></iframe></div>',
   '<iframe id="captcha" class="ad-banner" src="https://js.hcaptcha.com/widget"></iframe>',
   '<iframe id="auth" class="ad-banner" src="https://tenant.auth0.com/widget"></iframe>',
   '<a id="track" class="ad-banner" href="https://cdn.other.example/subtitle.vtt?adurl=x">Download captions</a>'
  ].join('');
  await page.route('https://news.com/',route=>route.fulfill({contentType:'text/html',body:
   '<html><head><style>.ad-banner{width:300px;height:250px;display:block}</style></head><body>'+protectedHtml+
   '<div id="ad" class="ad-banner" style="display:block">Sample promotion</div></body></html>'}));
  await page.goto('https://news.com/');
  await page.evaluate(()=>{
   window.requests=0;window.reports=[];window.listeners=[];window.storage={aiConfig:{enabled:true,consent:true,rememberDecisions:true,endpoint:'https://api.example/v1',model:'test',scope:'public'}};
   window.browser={runtime:{id:'phantom',onMessage:{addListener:fn=>window.manualScan=fn},sendMessage:async m=>{
    if(m.type==='GET_SITE_STATE')return {enabled:true,aiEnabled:storage.aiConfig.enabled};
    if(m.type==='AI_CLASSIFY')return PhantomAI.classify('news.com',m.candidates,false);
    if(m.type==='AI_PAGE'){reports.push(m);return {ok:true};}
   }},storage:{local:{get:async defaults=>({...defaults,...structuredClone(storage)}),set:async values=>{
    Object.assign(storage,structuredClone(values));listeners.forEach(fn=>fn(Object.fromEntries(Object.entries(values).map(([k,v])=>[k,{newValue:v}])),'local'));
   }},onChanged:{addListener:fn=>listeners.push(fn)}}};
   window.fetch=async(url,options)=>{requests++;const payload=JSON.parse(JSON.parse(options.body).messages[1].content);
    if(payload.candidates.some(c=>c.localKey))throw Error('Local identity leaked');
    return new Response(JSON.stringify({usage:{prompt_tokens:10,completion_tokens:5},choices:[{message:{content:JSON.stringify({decisions:payload.candidates.map(c=>({id:c.id,isAd:true,confidence:.99}))})}}]}));
   };
   document.documentElement.setAttribute('data-nexo-pip-active','true');
  });
  for(const file of ['data/functional-safety.js','data/ai-config.js','background/ai-memory.js','background/ai-classifier.js','content/ai-detector.js'])await page.addScriptTag({path:root+file});
  await page.waitForTimeout(1400);assert.equal(await page.evaluate(()=>requests),0);
  await page.evaluate(()=>{document.documentElement.setAttribute('data-nexo-pip-active','false');document.dispatchEvent(new Event('nexo:pip-state'))});
  await page.waitForFunction(()=>document.getElementById('ad').style.display==='none');
  assert.equal(await page.evaluate(()=>requests),1);
  for(const id of ['media','player','mixed','captcha','auth','track']) {
   assert.equal(await page.locator('#'+id).evaluate(e=>getComputedStyle(e).display),'block',id+' remains visible');
  }
  assert.equal(await page.locator('#inline-ad').evaluate(e=>getComputedStyle(e).display),'none');
  assert.equal(await page.evaluate(()=>storage.aiMemory.length),1);
  await page.evaluate(async()=>{await browser.storage.local.set({aiConfig:{...storage.aiConfig,enabled:false}})});
  await page.waitForFunction(()=>document.getElementById('ad').style.display==='block');
  await page.evaluate(async()=>{await browser.storage.local.set({aiConfig:{...storage.aiConfig,enabled:true}})});
  await page.waitForFunction(()=>reports.some(r=>r.sources && r.sources.memory===1));assert.equal(await page.evaluate(()=>requests),1);
  await page.evaluate(async()=>{PhantomAI.invalidate();await PhantomAIMemory.forget('news.com');await browser.storage.local.set({aiMemoryRevision:{nonce:1,host:'news.com'}})});
  await page.waitForFunction(()=>document.getElementById('ad').style.display==='block');await page.waitForTimeout(1500);
  assert.equal(await page.evaluate(()=>requests),1,'forget must not immediately relearn');
  const manual=await page.evaluate(()=>manualScan({type:'AI_SCAN'},{id:'phantom'}));assert.equal(manual.ok,true);assert.equal(manual.hidden,2);
  assert.equal(await page.evaluate(()=>requests),2);assert.deepEqual(errors,[]);
  console.log('Real DOM + real classifier/memory: opt-in learns, memory reuses without network, forget restores and pauses, manual scan resumes; media, player sibling frames, CAPTCHA, OAuth, captions and PiP protected. Provider and iframe documents are mocked.');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
